package com.runapp.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    private var gravandoInterno = false
    private var recorder        : MediaRecorder? = null
    private var virtualDisplay  : android.hardware.display.VirtualDisplay? = null
    private var mediaProjection : MediaProjection? = null
    private var arquivoTemp     : File? = null
    private val mainHandler     = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instancia = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
            Log.e(TAG, "ERRO: dados inválidos resultCode=$resultCode data=$data")
            stopSelf(); return START_NOT_STICKY
        }

        try {
            val mgr  = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mgr.getMediaProjection(resultCode, data)
                ?: run { Log.e(TAG, "ERRO: getMediaProjection null"); chamarFinalizado(null); stopSelf(); return START_NOT_STICKY }

            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection encerrado pelo sistema")
                    if (gravandoInterno) pararGravacao()
                }
            }, mainHandler)

            mediaProjection = proj
            iniciarGravacao(proj, audioOk)

        } catch (e: Exception) {
            Log.e(TAG, "ERRO onStartCommand: ${e.message}", e)
            chamarFinalizado(null); stopSelf()
        }

        return START_NOT_STICKY
    }

    // Garante que dimensão seja múltiplo de 2 (exigência do encoder H264)
    private fun Int.toEven() = if (this % 2 == 0) this else this - 1

    private fun iniciarGravacao(proj: MediaProjection, audioOk: Boolean) {
        val wm  = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm  = resources.displayMetrics
        val dpi = dm.densityDpi

        // Calcular dimensões reais da tela
        val rawW: Int
        val rawH: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b   = wm.currentWindowMetrics.bounds
            val ins = wm.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars())
            rawW = b.width()
            rawH = b.height() - ins.top - ins.bottom
        } else {
            val sbRes = resources.getIdentifier("status_bar_height", "dimen", "android")
            val sbH   = if (sbRes > 0) resources.getDimensionPixelSize(sbRes) else 72
            rawW = dm.widthPixels
            rawH = dm.heightPixels - sbH
        }

        // H264 EXIGE dimensões pares — valores ímpares causam falha silenciosa no encoder
        val w = rawW.toEven().coerceAtLeast(2)
        val h = rawH.toEven().coerceAtLeast(2)
        Log.d(TAG, "Dimensões brutas: ${rawW}x${rawH} → ajustadas: ${w}x${h} dpi=$dpi audio=$audioOk")

        // Arquivo temporário no cache do app
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val cacheDir  = externalCacheDir ?: cacheDir
        val temp      = File(cacheDir, "corrida_$timestamp.mp4")
        arquivoTemp   = temp
        Log.d(TAG, "Arquivo temp: ${temp.absolutePath}")

        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()

        rec.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "MediaRecorder.onError what=$what extra=$extra")
        }
        rec.setOnInfoListener { _, what, extra ->
            Log.d(TAG, "MediaRecorder.onInfo what=$what extra=$extra")
        }

        try {
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
                setVideoEncodingBitRate(6_000_000)
                setOutputFile(temp.absolutePath)
                prepare()
            }
            Log.d(TAG, "MediaRecorder preparado com sucesso")

            virtualDisplay = proj.createVirtualDisplay(
                "GravacaoCorrida", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                rec.surface, null, null
            )
            Log.d(TAG, "VirtualDisplay criado: $virtualDisplay")

            rec.start()
            recorder        = rec
            gravandoInterno = true
            Log.d(TAG, "▶ Gravação iniciada → ${temp.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "ERRO iniciarGravacao: ${e.message}", e)
            runCatching { rec.release() }
            runCatching { proj.stop() }
            mediaProjection = null
            temp.delete()
            arquivoTemp = null
            chamarFinalizado(null)
            stopSelf()
        }
    }

    fun pararGravacao() {
        Log.d(TAG, "pararGravacao() chamado — gravandoInterno=$gravandoInterno")
        if (!gravandoInterno) return
        gravandoInterno = false

        // Executa em thread separada para não bloquear a UI
        Thread {
            executarParada()
        }.start()
    }

    private fun executarParada() {
        Log.d(TAG, "executarParada() iniciado")

        // 1. Para o VirtualDisplay (para de enviar frames ao encoder)
        runCatching { virtualDisplay?.release() }.onFailure { Log.e(TAG, "vd release: ${it.message}") }
        virtualDisplay = null

        // 2. Aguarda o encoder processar os últimos frames
        Thread.sleep(300)

        // 3. Para o recorder — mesmo que lance exceção, o arquivo pode ter dados válidos
        val stopException = runCatching { recorder?.stop() }.exceptionOrNull()
        if (stopException != null) {
            Log.w(TAG, "recorder.stop() lançou: ${stopException.message} (pode ser normal se gravação foi muito curta)")
        } else {
            Log.d(TAG, "recorder.stop() OK")
        }
        runCatching { recorder?.release() }
        recorder = null

        // 4. Para o MediaProjection
        runCatching { mediaProjection?.stop() }.onFailure { Log.e(TAG, "proj stop: ${it.message}") }
        mediaProjection = null

        // 5. Verifica e salva o arquivo
        val temp = arquivoTemp
        val tamanho = temp?.length() ?: 0L
        Log.d(TAG, "Arquivo temp: ${temp?.absolutePath} | existe=${temp?.exists()} | tamanho=$tamanho bytes")

        val nome: String? = when {
            temp == null -> {
                Log.e(TAG, "ERRO: arquivoTemp é null")
                null
            }
            !temp.exists() -> {
                Log.e(TAG, "ERRO: arquivo temp não existe")
                null
            }
            tamanho == 0L -> {
                Log.e(TAG, "ERRO: arquivo com 0 bytes — encoder nunca gravou nada. " +
                    "Possível causa: VirtualDisplay não recebeu frames ou setVideoSize com dimensões inválidas.")
                temp.delete()
                null
            }
            tamanho < 1000 -> {
                Log.e(TAG, "ERRO: arquivo muito pequeno ($tamanho bytes) — MP4 incompleto (falta MOOV atom). " +
                    "recorder.stop() provavelmente falhou.")
                temp.delete()
                null
            }
            else -> {
                Log.d(TAG, "Arquivo válido ($tamanho bytes) — salvando na galeria...")
                salvarNaGaleria(temp)
            }
        }

        arquivoTemp = null
        Log.d(TAG, "Resultado: nome=$nome")
        chamarFinalizado(nome)

        mainHandler.post {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun salvarNaGaleria(origem: File): String? {
        val nome = "corrida_${System.currentTimeMillis()}"

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29+: MediaStore com IS_PENDING
                val cv = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "$nome.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RunApp")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }

                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
                    ?: run { Log.e(TAG, "ERRO: MediaStore.insert retornou null"); return null }

                contentResolver.openOutputStream(uri)?.use { out ->
                    val bytesCopied = origem.inputStream().use { it.copyTo(out) }
                    Log.d(TAG, "Bytes copiados: $bytesCopied")
                } ?: run { Log.e(TAG, "ERRO: openOutputStream retornou null"); return null }

                val cvFinal = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                val rows = contentResolver.update(uri, cvFinal, null, null)
                Log.d(TAG, "IS_PENDING=0 aplicado: $rows linha(s) | uri=$uri")

                origem.delete()
                Log.d(TAG, "✅ Salvo na galeria: Movies/RunApp/$nome.mp4")
                nome

            } else {
                // API < 29: copia para pasta pública + MediaScanner
                @Suppress("DEPRECATION")
                val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                val runAppDir = File(moviesDir, "RunApp").also { it.mkdirs() }
                val destino   = File(runAppDir, "$nome.mp4")

                origem.copyTo(destino, overwrite = true)
                origem.delete()

                // Notifica a galeria
                MediaScannerConnection.scanFile(this, arrayOf(destino.absolutePath), arrayOf("video/mp4")) { path, _ ->
                    Log.d(TAG, "MediaScanner: escaneado $path")
                }

                Log.d(TAG, "✅ Salvo (legado): ${destino.absolutePath}")
                nome
            }
        } catch (e: Exception) {
            Log.e(TAG, "ERRO salvarNaGaleria: ${e.message}", e)
            null
        }
    }

    private fun chamarFinalizado(nome: String?) = mainHandler.post { onFinalizado?.invoke(nome) }

    override fun onDestroy() {
        super.onDestroy()
        instancia = null
        if (gravandoInterno) pararGravacao()
    }

    private fun criarCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val c = NotificationChannel(CANAL_ID, "Gravação", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(c)
        }
    }
}
