package com.runapp.service

import android.app.*
import android.content.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat

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
        private const val CANAL_ID = "gravacao_canal_v2"
        private const val NOTIF_ID = 9001
        // Use uma string simples e única
        const val ACTION_PARAR = "STOP_RECORDING_NOW"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "projection_data"
        const val EXTRA_AUDIO_OK = "audio_ok"
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ESSENCIAL: Verifica se recebeu a ordem de parar
        if (intent?.action == ACTION_PARAR) {
            Log.d(TAG, "Recebido comando para PARAR")
            pararGravacao()
            return START_NOT_STICKY
        }

        iniciarForeground()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        val audioOk = intent?.getBooleanExtra(EXTRA_AUDIO_OK, false) ?: false

        if (resultCode != -1 && data != null && !gravando) {
            iniciarGravacao(resultCode, data, audioOk)
        }

        return START_STICKY
    }

    private fun iniciarForeground() {
        criarCanal()
        
        // Intent que volta para o Service para parar
        val stopIntent = Intent(this, GravacaoService::class.java).apply { 
            action = ACTION_PARAR 
        }
        
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, CANAL_ID)
            .setContentTitle("RunApp — Gravando")
            .setContentText("Clique no botão abaixo para encerrar")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setSilent(true) // Não faz barulho toda hora
            .addAction(android.R.drawable.ic_media_pause, "PARAR GRAVAÇÃO", stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun iniciarGravacao(resultCode: Int, data: Intent, audioOk: Boolean) {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, data)

        val metrics = resources.displayMetrics
        var w = metrics.widthPixels
        var h = metrics.heightPixels
        if (w % 2 != 0) w-- // Codecs odeiam números ímpares
        if (h % 2 != 0) h--
        
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
                setVideoEncodingBitRate(6_000_000)
                setOutputFile(fd)
                prepare()
            }

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "RunApp_Rec", w, h, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder?.surface, null, null
            )

            mediaRecorder?.start()
            gravando = true
        } catch (e: Exception) {
            pararGravacao()
        }
    }

    fun pararGravacao() {
        if (!gravando) return
        gravando = false

        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Vídeo muito curto")
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
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val canal = NotificationChannel(CANAL_ID, "Gravação de Tela", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(canal)
        }
    }
}