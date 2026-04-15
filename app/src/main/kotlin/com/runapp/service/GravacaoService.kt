package com.runapp.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.IOException

/**
 * ForegroundService para gravação de tela.
 *
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
    var onFinalizado: ((String?) -> Unit)? = null

    companion object {
        private const val TAG           = "GravacaoService"
        private const val CANAL_ID      = "gravacao_canal"
        private const val NOTIF_ID      = 9001
        const val ACTION_PARAR          = "com.runapp.PARAR_GRAVACAO"
        const val EXTRA_RESULT_CODE     = "result_code"
        const val EXTRA_DATA            = "projection_data"
        const val EXTRA_AUDIO_OK        = "audio_ok"
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PARAR) {
            pararGravacao()
            return START_NOT_STICKY
        }

        criarCanal()

        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, GravacaoService::class.java).apply { action = ACTION_PARAR },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, CANAL_ID)
            .setContentTitle("RunApp — gravando")
            .setContentText("Toque para parar a gravação")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Parar", stopIntent)
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

        if (resultCode == -1 || data == null) {
            Log.e(TAG, "Dados de projeção inválidos")
            stopSelf()
            return START_NOT_STICKY
        }

        iniciarGravacao(resultCode, data, audioOk)
        return START_NOT_STICKY
    }

    private fun iniciarGravacao(resultCode: Int, data: Intent, audioOk: Boolean) {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, data)

        // Dimensões excluindo status bar e nav bar
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val w: Int; val h: Int; val dpi: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            val insets = wm.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(
                    android.view.WindowInsets.Type.systemBars()
                )
            w   = bounds.width()
            h   = bounds.height() - insets.top - insets.bottom
            dpi = resources.displayMetrics.densityDpi
        } else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(m)
            // Status bar height via recursos
            val sbRes = resources.getIdentifier("status_bar_height", "dimen", "android")
            val sbH   = if (sbRes > 0) resources.getDimensionPixelSize(sbRes) else 72
            w   = m.widthPixels
            h   = m.heightPixels - sbH
            dpi = m.densityDpi
        }

        Log.d(TAG, "Dimensões gravação: ${w}x${h} dpi=$dpi")

        val nome = "corrida_${System.currentTimeMillis()}"
        nomeArquivo = nome

        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$nome.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RunApp")
        }

        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
        if (uri == null) {
            Log.e(TAG, "Falha ao criar URI no MediaStore")
            stopSelf(); return
        }

        val pfd = contentResolver.openFileDescriptor(uri, "w")
        if (pfd == null) {
            Log.e(TAG, "Falha ao abrir FileDescriptor")
            stopSelf(); return
        }

        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(this)
        else
            @Suppress("DEPRECATION") MediaRecorder()

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
                setOutputFile(pfd.fileDescriptor)
                prepare()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Erro prepare MediaRecorder", e)
            rec.release()
            pfd.close()
            stopSelf(); return
        }

        mediaRecorder = rec

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "GravacaoCorrida", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            rec.surface, null, null
        )

        try {
            rec.start()
            gravando = true
            Log.d(TAG, "▶️ Gravação iniciada: $nome.mp4")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar MediaRecorder", e)
            rec.release()
            virtualDisplay?.release()
            mediaProjection?.stop()
            pfd.close()
            stopSelf()
        }
    }

    fun pararGravacao() {
        if (!gravando) return
        gravando = false

        runCatching { mediaRecorder?.stop() }.onFailure { Log.e(TAG, "Erro stop recorder", it) }
        runCatching { mediaRecorder?.release() }
        runCatching { virtualDisplay?.release() }
        runCatching { mediaProjection?.stop() }

        mediaRecorder  = null
        virtualDisplay = null
        mediaProjection = null

        Log.d(TAG, "⏹️ Gravação encerrada: ${nomeArquivo}.mp4")
        onFinalizado?.invoke(nomeArquivo)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (gravando) pararGravacao()
    }

    private fun criarCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CANAL_ID, "Gravação de vídeo", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Controle de gravação de corrida" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(canal)
        }
    }
}
