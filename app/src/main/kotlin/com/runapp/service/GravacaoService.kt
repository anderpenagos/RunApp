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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File

class GravacaoService : Service() {

    companion object {
        private const val TAG      = "GravacaoService"
        private const val CANAL_ID = "gravacao_canal"
        private const val NOTIF_ID = 9001

        // MediaProjection criado no Composable (main thread, imediatamente após consentimento)
        // e passado aqui ANTES de iniciar o serviço — evita corrupção por serialização
        var projectionPronta: MediaProjection? = null
        var audioOkPendente: Boolean = false

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
        Log.d(TAG, "onCreate — instancia set")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        criarCanal()

        val notif = NotificationCompat.Builder(this, CANAL_ID)
            .setContentTitle("RunApp — gravando")
            .setContentText("Volte ao app e toque ■ para parar")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        val proj = projectionPronta
        if (proj == null) {
            Log.e(TAG, "projectionPronta é null — abortando")
            stopSelf(); return START_NOT_STICKY
        }

        iniciarGravacao(proj, audioOkPendente)
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

        // Arquivo temporário — sem FileDescriptor aberto externamente
        val cacheDir = externalCacheDir ?: cacheDir
        val temp = File(cacheDir, "gravacao_${System.currentTimeMillis()}.mp4")
        arquivoTemp = temp
        Log.d(TAG, "Temp: ${temp.absolutePath}")

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
                Log.d(TAG, "Recorder preparado")
            }

            virtualDisplay = proj.createVirtualDisplay(
                "GravacaoCorrida", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                rec.surface, null, null
            )
            Log.d(TAG, "VirtualDisplay: $virtualDisplay surface=${rec.surface}")

            rec.start()
            recorder        = rec
            gravandoInterno = true
            Log.d(TAG, "▶️ GRAVANDO: ${temp.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Erro iniciarGravacao: ${e.message}", e)
            runCatching { rec.release() }
            proj.stop()
            chamarFinalizado(null)
            stopSelf()
        }
    }

    fun pararGravacao() {
        Log.d(TAG, "pararGravacao gravandoInterno=$gravandoInterno")
        if (!gravandoInterno) return
        gravandoInterno = false

        runCatching { recorder?.stop()    }.onFailure { Log.e(TAG, "stop: ${it.message}") }
        runCatching { recorder?.release() }
        runCatching { virtualDisplay?.release() }
        runCatching { projectionPronta?.stop() }
        recorder = null; virtualDisplay = null; projectionPronta = null

        val temp = arquivoTemp
        Log.d(TAG, "Temp após parar: ${temp?.absolutePath} exists=${temp?.exists()} size=${temp?.length()}")

        if (temp != null && temp.exists() && temp.length() > 1000) {
            val nome = copiarParaGaleria(temp)
            temp.delete()
            Log.d(TAG, if (nome != null) "✅ Galeria: $nome" else "❌ Falha na cópia")
            chamarFinalizado(nome)
        } else {
            Log.e(TAG, "❌ Temp inválido ou vazio")
            chamarFinalizado(null)
        }

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
            Log.d(TAG, "Copiado para $uri")
            nome
        }.getOrElse { Log.e(TAG, "copiarParaGaleria: ${it.message}"); null }
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
