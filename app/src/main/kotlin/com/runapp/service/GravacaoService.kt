package com.runapp.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File

/**
 * AndroidManifest.xml — dentro de <application>:
 *   <service
 *       android:name=".service.GravacaoService"
 *       android:foregroundServiceType="mediaProjection"
 *       android:exported="false" />
 *
 * Permissões:
 *   <uses-permission android:name="android.permission.RECORD_AUDIO" />
 *   <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
 *   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
 *   <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
 *       android:maxSdkVersion="28" />
 */
class GravacaoService : Service() {

    companion object {
        private const val TAG      = "GravacaoService"
        private const val CANAL_ID = "gravacao_canal"
        private const val NOTIF_ID = 9001

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA        = "projection_data"
        const val EXTRA_AUDIO_OK    = "audio_ok"

        @Volatile var instancia: GravacaoService? = null
            private set

        var onFinalizado: ((String?) -> Unit)? = null
        val gravando: Boolean get() = instancia?.gravandoInterno == true
    }

    private var gravandoInterno  = false
    private var recorder         : MediaRecorder? = null
    private var virtualDisplay   : android.hardware.display.VirtualDisplay? = null
    private var mediaProjection  : MediaProjection? = null

    // API 29+: gravação direto no MediaStore via FileDescriptor (sem cópia)
    private var mediaStoreUri    : Uri? = null
    private var mediaStorePfd    : ParcelFileDescriptor? = null

    // API < 29: arquivo temporário + cópia
    private var arquivoTemp      : File? = null
    private var nomeArquivo      : String? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instancia = this
        Log.d(TAG, "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        criarCanal()

        val notif = NotificationCompat.Builder(this, CANAL_ID)
            .setContentTitle("RunApp — gravando")
            .setContentText("Volte ao app para parar")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        val audioOk = intent?.getBooleanExtra(EXTRA_AUDIO_OK, false) ?: false

        if (resultCode == -1 || data == null) {
            Log.e(TAG, "Dados inválidos: resultCode=$resultCode data=$data")
            stopSelf(); return START_NOT_STICKY
        }

        try {
            val mgr  = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mgr.getMediaProjection(resultCode, data)
            if (proj == null) {
                Log.e(TAG, "getMediaProjection retornou null")
                chamarFinalizado(null); stopSelf(); return START_NOT_STICKY
            }

            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection encerrado pelo sistema")
                    if (gravandoInterno) pararGravacao()
                }
            }, mainHandler)

            mediaProjection = proj
            iniciarGravacao(proj, audioOk)

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao criar MediaProjection: ${e.message}", e)
            chamarFinalizado(null); stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun iniciarGravacao(proj: MediaProjection, audioOk: Boolean) {
        val wm  = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm  = resources.displayMetrics
        val dpi = dm.densityDpi
        val w: Int; val h: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b   = wm.currentWindowMetrics.bounds
            val ins = wm.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars())
            w = b.width(); h = b.height() - ins.top - ins.bottom
        } else {
            val sbRes = resources.getIdentifier("status_bar_height", "dimen", "android")
            val sbH   = if (sbRes > 0) resources.getDimensionPixelSize(sbRes) else 72
            w = dm.widthPixels; h = dm.heightPixels - sbH
        }
        Log.d(TAG, "Dimensões: ${w}x${h} dpi=$dpi audioOk=$audioOk")

        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()

        try {
            val nome = "corrida_${System.currentTimeMillis()}"
            nomeArquivo = nome

            rec.apply {
                if (audioOk) setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                if (audioOk) {
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(128_000)
                    setAudioSamplingRate(44100)
                }
                setVideoSize(w, h)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(5_000_000)

                // ── Destino da gravação ──────────────────────────────────────────
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // API 29+: grava diretamente no MediaStore via FileDescriptor.
                    // O vídeo já existe na galeria (oculto com IS_PENDING=1) desde o início.
                    // Não há cópia de arquivo — tudo vai direto para Movies/RunApp.
                    val cv = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, "$nome.mp4")
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RunApp")
                        put(MediaStore.Video.Media.IS_PENDING, 1) // oculto até terminar
                    }
                    val uri = contentResolver.insert(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv
                    ) ?: throw Exception("MediaStore.insert retornou null")

                    mediaStoreUri = uri

                    val pfd = contentResolver.openFileDescriptor(uri, "w")
                        ?: throw Exception("openFileDescriptor retornou null para $uri")
                    mediaStorePfd = pfd

                    setOutputFile(pfd.fileDescriptor)
                    Log.d(TAG, "✅ Destino: MediaStore direto — $uri")

                } else {
                    // API < 29 (Android 9 e abaixo): arquivo temporário
                    val temp = File(externalCacheDir ?: cacheDir, "$nome.mp4")
                    arquivoTemp = temp
                    setOutputFile(temp.absolutePath)
                    Log.d(TAG, "Destino: arquivo temp — ${temp.absolutePath}")
                }

                prepare()
                Log.d(TAG, "MediaRecorder preparado com sucesso")
            }

            virtualDisplay = proj.createVirtualDisplay(
                "GravacaoCorrida", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                rec.surface, null, null
            )
            Log.d(TAG, "VirtualDisplay criado: $virtualDisplay")

            rec.start()
            recorder        = rec
            gravandoInterno = true
            Log.d(TAG, "▶️ Gravação iniciada")

        } catch (e: Exception) {
            Log.e(TAG, "Erro iniciarGravacao: ${e.message}", e)
            runCatching { rec.release() }
            limparMediaStorePendente()
            proj.stop()
            mediaProjection = null
            chamarFinalizado(null)
            stopSelf()
        }
    }

    fun pararGravacao() {
        Log.d(TAG, "pararGravacao() gravandoInterno=$gravandoInterno")
        if (!gravandoInterno) return
        gravandoInterno = false

        // Ordem crítica:
        // 1. VirtualDisplay: para de entregar frames ao MediaRecorder
        runCatching { virtualDisplay?.release() }.onFailure { Log.e(TAG, "vd: ${it.message}") }
        virtualDisplay = null

        // 2. Aguarda encoder processar os últimos frames (evita "stop failed")
        Thread.sleep(200)

        // 3. Para o recorder — mesmo que lance exceção, o arquivo pode ser válido
        runCatching { recorder?.stop() }.onFailure { Log.w(TAG, "recorder.stop: ${it.message}") }
        runCatching { recorder?.release() }
        recorder = null

        // 4. Fecha o FileDescriptor DEPOIS do recorder liberar o arquivo
        runCatching { mediaStorePfd?.close() }.onFailure { Log.e(TAG, "pfd: ${it.message}") }
        mediaStorePfd = null

        // 5. Para o MediaProjection
        runCatching { mediaProjection?.stop() }.onFailure { Log.e(TAG, "proj: ${it.message}") }
        mediaProjection = null

        // 6. Publica o arquivo (remove IS_PENDING ou copia temp)
        val nome = finalizarArquivo()
        Log.d(TAG, "Resultado final: nome=$nome")

        chamarFinalizado(nome)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * API 29+: apenas muda IS_PENDING de 1 para 0 — o arquivo já está na galeria.
     * API <29: copia o arquivo temp para o MediaStore.
     */
    private fun finalizarArquivo(): String? {
        val nome = nomeArquivo ?: return null

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = mediaStoreUri ?: run { Log.e(TAG, "mediaStoreUri null"); return null }
            runCatching {
                val cv = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                val rows = contentResolver.update(uri, cv, null, null)
                Log.d(TAG, "IS_PENDING=0: $rows linha(s) atualizadas — $uri")
                if (rows > 0) nome else null
            }.getOrElse { Log.e(TAG, "finalizarArquivo: ${it.message}", it); null }

        } else {
            val temp = arquivoTemp
            Log.d(TAG, "Temp legado: ${temp?.absolutePath} existe=${temp?.exists()} tamanho=${temp?.length()}")
            if (temp != null && temp.exists() && temp.length() > 1000) {
                copiarParaGaleriaLegado(temp, nome).also { temp.delete() }
            } else {
                Log.e(TAG, "Arquivo temp inválido")
                temp?.delete()
                null
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun copiarParaGaleriaLegado(origem: File, nome: String): String? {
        return runCatching {
            val dir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_MOVIES)
            val runAppDir = File(dir, "RunApp").also { it.mkdirs() }
            val destino = File(runAppDir, "$nome.mp4")
            origem.copyTo(destino, overwrite = true)

            // Notifica a galeria sobre o novo arquivo
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "$nome.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATA, destino.absolutePath)
            }
            contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
            Log.d(TAG, "Legado: salvo em ${destino.absolutePath}")
            nome
        }.getOrElse { Log.e(TAG, "copiarParaGaleriaLegado: ${it.message}", it); null }
    }

    /** Remove a entrada pendente do MediaStore se a gravação falhou antes de iniciar */
    private fun limparMediaStorePendente() {
        mediaStoreUri?.let { uri ->
            runCatching { contentResolver.delete(uri, null, null) }
                .onSuccess { Log.d(TAG, "Entrada pendente removida do MediaStore") }
        }
        mediaStoreUri = null
        runCatching { mediaStorePfd?.close() }
        mediaStorePfd = null
    }

    private fun chamarFinalizado(nome: String?) = mainHandler.post { onFinalizado?.invoke(nome) }

    override fun onDestroy() {
        super.onDestroy()
        instancia = null
        if (gravandoInterno) pararGravacao()
        Log.d(TAG, "onDestroy")
    }

    private fun criarCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val c = NotificationChannel(CANAL_ID, "Gravação", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(c)
        }
    }
}
