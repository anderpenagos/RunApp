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
 * ForegroundService para gravação de tela via MediaProjection.
 *
 * NÃO inicia automaticamente — só é iniciado pelo botão gravar.
 *
 * AndroidManifest.xml — adicionar dentro de <application>:
 *   <service
 *       android:name=".service.GravacaoService"
 *       android:foregroundServiceType="mediaProjection"
 *       android:exported="false" />
 *
 * Permissões no AndroidManifest.xml:
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

    var gravando = false
        private set
    var onFinalizado: ((String?) -> Unit)? = null

    companion object {
        private const val TAG          = "GravacaoService"
        private const val CANAL_ID     = "gravacao_canal"
        private const val NOTIF_ID     = 9001
        const val EXTRA_RESULT_CODE    = "result_code"
        const val EXTRA_DATA           = "projection_data"
        const val EXTRA_AUDIO_OK       = "audio_ok"
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // Chamado via startForegroundService() com os extras da MediaProjection
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        criarCanal()
        val notif = NotificationCompat.Builder(this, CANAL_ID)
            .setContentTitle("RunApp — gravando")
            .setContentText("Gravação em andamento")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val data       = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        val audioOk    = intent?.getBooleanExtra(EXTRA_AUDIO_OK, false) ?: false

        if (resultCode != -1 && data != null) {
            iniciarGravacao(resultCode, data, audioOk)
        } else {
            Log.e(TAG, "Dados de projeção inválidos — encerrando serviço")
            stopSelf()
        }

        // NOT_STICKY: não reinicia automaticamente se morrer
        return START_NOT_STICKY
    }

    private fun iniciarGravacao(resultCode: Int, data: Intent, audioOk: Boolean) {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, data)

        val metrics = obterMetricas()
        val w   = metrics.widthPixels
        val h   = metrics.heightPixels
        val dpi = metrics.densityDpi

        val nome = "corrida_${System.currentTimeMillis()}"
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$nome.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RunApp")
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
            ?: run { Log.e(TAG, "Falha URI"); stopSelf(); return }
        val pfd = contentResolver.openFileDescriptor(uri, "w")
            ?: run { Log.e(TAG, "Falha FD"); stopSelf(); return }

        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()

        try {
            rec.apply {
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
            mediaRecorder = rec

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "GravacaoCorrida", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                rec.surface, null, null
            )
            rec.start()
            gravando = true
            Log.d(TAG, "▶️ Gravando: $nome.mp4")
            // guarda nome para callback
            _nomeAtual = nome
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar gravação", e)
            rec.release()
            stopSelf()
        }
    }

    private var _nomeAtual: String? = null

    fun pararGravacao() {
        if (!gravando) return
        gravando = false
        runCatching { mediaRecorder?.stop() }
        runCatching { mediaRecorder?.release() }
        runCatching { virtualDisplay?.release() }
        runCatching { mediaProjection?.stop() }
        mediaRecorder   = null
        virtualDisplay  = null
        mediaProjection = null
        Log.d(TAG, "⏹️ Gravação encerrada: ${_nomeAtual}.mp4")
        onFinalizado?.invoke(_nomeAtual)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (gravando) pararGravacao()
    }

    private fun criarCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val c = NotificationChannel(CANAL_ID, "Gravação de vídeo", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(c)
        }
    }

    private fun obterMetricas(): DisplayMetrics {
        val m  = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            m.widthPixels  = b.width()
            m.heightPixels = b.height()
            m.densityDpi   = resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(m)
        }
        return m
    }
}
