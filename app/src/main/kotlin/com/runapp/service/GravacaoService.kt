package com.runapp.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.MediaRecorder
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

    private var gravandoInterno = false
    private var recorder        : MediaRecorder? = null
    private var virtualDisplay  : android.hardware.display.VirtualDisplay? = null
    private var arquivoTemp     : File? = null
    private val mainHandler     = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instancia = this
        Log.d(TAG, "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        criarCanal()

        // startForeground DEVE vir antes de getMediaProjection no Android 14+
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

        // Agora, dentro do ForegroundService com tipo mediaProjection, podemos chamar getMediaProjection
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
            Log.d(TAG, "MediaProjection criado com sucesso: $proj")
            iniciarGravacao(proj, audioOk)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao criar MediaProjection: ${e.message}", e)
            chamarFinalizado(null); stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun iniciarGravacao(proj: android.media.projection.MediaProjection, audioOk: Boolean) {
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

        // Arquivo temporário no cache do app — sem FileDescriptor aberto externamente
        val cacheDir = externalCacheDir ?: cacheDir
        val temp = File(cacheDir, "gravacao_${System.currentTimeMillis()}.mp4")
        arquivoTemp = temp
        Log.d(TAG, "Gravando em: ${temp.absolutePath}")

        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()

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
                setVideoEncodingBitRate(5_000_000)
                setOutputFile(temp.absolutePath)
                prepare()
                Log.d(TAG, "MediaRecorder preparado")
            }

            virtualDisplay = proj.createVirtualDisplay(
                "GravacaoCorrida", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                rec.surface, null, null
            )
            Log.d(TAG, "VirtualDisplay: $virtualDisplay")

            rec.start()
            recorder        = rec
            gravandoInterno = true
            Log.d(TAG, "▶️ Gravação iniciada: ${temp.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "Erro iniciarGravacao: ${e.message}", e)
            runCatching { rec.release() }
            proj.stop()
            chamarFinalizado(null)
            stopSelf()
        }
    }

    fun pararGravacao() {
        Log.d(TAG, "pararGravacao() gravandoInterno=$gravandoInterno")
        if (!gravandoInterno) return
        gravandoInterno = false

        runCatching { recorder?.stop()    }.onFailure { Log.e(TAG, "stop: ${it.message}") }
        runCatching { recorder?.release() }
        runCatching { virtualDisplay?.release() }
        recorder = null; virtualDisplay = null

        val temp = arquivoTemp
        Log.d(TAG, "Temp: ${temp?.absolutePath} exists=${temp?.exists()} size=${temp?.length()}")

        val nome = if (temp != null && temp.exists() && temp.length() > 1000) {
            copiarParaGaleria(temp).also { temp.delete() }
        } else {
            Log.e(TAG, "Arquivo temp inválido"); null
        }

        chamarFinalizado(nome)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun copiarParaGaleria(origem: File): String? {
        return runCatching {
            val nome = "corrida_${System.currentTimeMillis()}"
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "$nome.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RunApp")
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
                ?: return null
            contentResolver.openOutputStream(uri)?.use { out ->
                origem.inputStream().use { it.copyTo(out) }
            }
            Log.d(TAG, "Copiado para galeria: $uri ($nome.mp4)")
            nome
        }.getOrElse { Log.e(TAG, "copiarParaGaleria erro: ${it.message}"); null }
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
