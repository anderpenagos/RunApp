package com.runapp.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat

/**
 * ForegroundService obrigatório para MediaProjection no Android 14+.
 *
 * Adicionar no AndroidManifest.xml dentro de <application>:
 *
 *   <service
 *       android:name=".service.GravacaoService"
 *       android:foregroundServiceType="mediaProjection"
 *       android:exported="false" />
 *
 * Permissões necessárias no AndroidManifest.xml:
 *   <uses-permission android:name="android.permission.RECORD_AUDIO" />
 *   <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
 *   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
 */
class GravacaoService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): GravacaoService = this@GravacaoService
    }

    private val binder = LocalBinder()
    private var mediaProjection: MediaProjection? = null
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var nomeArquivo: String? = null

    var gravando = false
        private set

    var onGravacaoFinalizada: ((String?) -> Unit)? = null

    companion object {
        private const val TAG = "GravacaoService"
        private const val NOTIF_CHANNEL = "gravacao_corrida"
        private const val NOTIF_ID = 9001

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA        = "data"
    }

    override fun onCreate() {
        super.onCreate()
        criarCanalNotificacao()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("RunApp")
            .setContentText("Gravando vídeo da corrida…")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun iniciarGravacao(resultCode: Int, data: Intent, audioOk: Boolean) {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, data)

        val metrics = obterMetricasTela()
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val dpi = metrics.densityDpi

        val timestamp = System.currentTimeMillis()
        nomeArquivo = "corrida_$timestamp"

        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "${nomeArquivo}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RunApp")
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
            ?: run { Log.e(TAG, "Falha ao criar URI MediaStore"); return }

        val pfd = contentResolver.openFileDescriptor(uri, "w")
            ?: run { Log.e(TAG, "Falha ao abrir FileDescriptor"); return }

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }

        recorder.apply {
            if (audioOk) setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (audioOk) setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoSize(w, h)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(5_000_000)
            if (audioOk) { setAudioEncodingBitRate(128_000); setAudioSamplingRate(44100) }
            setOutputFile(pfd.fileDescriptor)
            prepare()
        }
        mediaRecorder = recorder

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "GravacaoCorrida", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            recorder.surface, null, null
        )

        recorder.start()
        gravando = true
        Log.d(TAG, "▶️ Gravação iniciada: ${nomeArquivo}.mp4")
    }

    fun pararGravacao() {
        if (!gravando) return
        gravando = false
        runCatching { mediaRecorder?.stop() }
        runCatching { mediaRecorder?.release() }
        runCatching { virtualDisplay?.release() }
        runCatching { mediaProjection?.stop() }
        mediaRecorder = null
        virtualDisplay = null
        mediaProjection = null
        Log.d(TAG, "⏹️ Gravação encerrada: ${nomeArquivo}.mp4")
        onGravacaoFinalizada?.invoke(nomeArquivo)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (gravando) pararGravacao()
    }

    private fun criarCanalNotificacao() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                NOTIF_CHANNEL,
                "Gravação de vídeo",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Notificação durante gravação de corrida" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(canal)
        }
    }

    private fun obterMetricasTela(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            metrics.widthPixels  = b.width()
            metrics.heightPixels = b.height()
            metrics.densityDpi   = resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(metrics)
        }
        return metrics
    }
}
