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
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.IOException

class GravacaoService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): GravacaoService = this@GravacaoService
    }

    private val binder = LocalBinder()
    private var mediaProjection: MediaProjection? = null
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var pfd: ParcelFileDescriptor? = null
    private var nomeArquivo: String? = null

    var gravando = false
        private set
    var onFinalizado: ((String?) -> Unit)? = null

    companion object {
        private const val TAG = "GravacaoService"
        private const val CANAL_ID = "gravacao_canal"
        private const val NOTIF_ID = 9001
        const val ACTION_PARAR = "com.runapp.PARAR_GRAVACAO"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "projection_data"
        const val EXTRA_AUDIO_OK = "audio_ok"
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. Verifica se o comando é para parar
        if (intent?.action == ACTION_PARAR) {
            pararGravacao()
            return START_NOT_STICKY
        }

        // 2. Cria o canal e a notificação de Foreground
        criarCanal()
        
        val stopIntent = Intent(this, GravacaoService::class.java).apply { action = ACTION_PARAR }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, CANAL_ID)
            .setContentTitle("RunApp — Gravando Corrida")
            .setContentText("A gravação está ativa. Toque para encerrar.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_stop, "PARAR AGORA", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        // 3. Inicia a gravação propriamente dita
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        val audioOk = intent?.getBooleanExtra(EXTRA_AUDIO_OK, false) ?: false

        if (resultCode != -1 && data != null) {
            iniciarGravacao(resultCode, data, audioOk)
        }

        return START_NOT_STICKY
    }

    private fun iniciarGravacao(resultCode: Int, data: Intent, audioOk: Boolean) {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, data)

        // Configura as dimensões da tela
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        
        // Garante que largura e altura sejam pares (necessário para alguns codecs)
        var w = metrics.widthPixels
        var h = metrics.heightPixels
        if (w % 2 != 0) w--
        if (h % 2 != 0) h--
        val dpi = metrics.densityDpi

        val nome = "corrida_${System.currentTimeMillis()}"
        nomeArquivo = nome

        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$nome.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RunApp")
        }

        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: return
        pfd = contentResolver.openFileDescriptor(uri, "w")
        val fd = pfd?.fileDescriptor ?: return

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
        
        try {
            mediaRecorder?.apply {
                if (audioOk) setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                if (audioOk) setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoSize(w, h)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(8_000_000) // 8Mbps para boa qualidade
                setOutputFile(fd)
                prepare()
            }

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "GravacaoRunApp", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder?.surface, null, null
            )

            mediaRecorder?.start()
            gravando = true
            Log.d(TAG, "Gravação iniciada")
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao iniciar recorder", e)
            pararGravacao()
        }
    }

    fun pararGravacao() {
        if (!gravando) return
        gravando = false

        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao parar (talvez vídeo muito curto)", e)
        } finally {
            mediaRecorder?.release()
            virtualDisplay?.release()
            mediaProjection?.stop()
            pfd?.close()
            
            mediaRecorder = null
            virtualDisplay = null
            mediaProjection = null
            pfd = null
        }

        onFinalizado?.invoke(nomeArquivo)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        pararGravacao()
        super.onDestroy()
    }

    private fun criarCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(CANAL_ID, "Gravação", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(canal)
        }
    }
}