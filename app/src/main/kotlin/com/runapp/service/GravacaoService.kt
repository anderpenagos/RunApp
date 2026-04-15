package com.runapp.service

import android.app.*
import android.content.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File

class GravacaoService : Service() {

    inner class LocalBinder : Binder() { fun getService(): GravacaoService = this@GravacaoService }
    private val binder = LocalBinder()
    
    private var mediaProjection: MediaProjection? = null
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var nomeArquivo: String? = null
    var onFinalizado: ((String?) -> Unit)? = null
    var gravando = false

    companion object {
        const val ACTION_STOP = "STOP_ACTION"
        const val CANAL_ID = "gravacao_canal_v3"
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            encerrarTudo()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra("result_code", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("projection_data")
        
        if (resultCode != -1 && data != null) {
            iniciarNotificacao()
            iniciarGravacao(resultCode, data)
        }
        return START_STICKY
    }

    private fun iniciarNotificacao() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CANAL_ID, "Gravação", NotificationManager.IMPORTANCE_LOW))
        }
        val stopIntent = Intent(this, GravacaoService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        val notif = NotificationCompat.Builder(this, CANAL_ID)
            .setContentTitle("RunApp: Gravando Tela")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(android.R.drawable.ic_media_pause, "PARAR AGORA", stopPending)
            .setOngoing(true).build()
        startForeground(9001, notif)
    }

    private fun iniciarGravacao(resultCode: Int, data: Intent) {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, data)
        
        val metrics = resources.displayMetrics
        val w = if (metrics.widthPixels % 2 == 0) metrics.widthPixels else metrics.widthPixels - 1
        val h = if (metrics.heightPixels % 2 == 0) metrics.heightPixels else metrics.heightPixels - 1

        nomeArquivo = "run_${System.currentTimeMillis()}"
        
        // Criando o arquivo no MediaStore
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$nomeArquivo.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RunApp")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = contentResolver.insert(collection, cv) ?: return

        try {
            val pfd = contentResolver.openFileDescriptor(uri, "w") ?: return
            
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
            mediaRecorder?.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(w, h)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(6_000_000)
                setOutputFile(pfd.fileDescriptor)
                prepare()
                
                virtualDisplay = mediaProjection?.createVirtualDisplay("RunRec", w, h, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
                
                start()
                gravando = true
            }
            
            // Atualiza o estado para que o arquivo não seja mais "pendente" ao final
            Handler(Looper.getMainLooper()).postDelayed({
                val updateCv = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                contentResolver.update(uri, updateCv, null, null)
            }, 1000)

        } catch (e: Exception) {
            Log.e("Service", "Erro na gravação", e)
            encerrarTudo()
        }
    }

    fun encerrarTudo() {
        if (!gravando) return
        gravando = false
        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (e: Exception) { Log.e("Service", "Vídeo curto demais") }
        
        virtualDisplay?.release()
        mediaProjection?.stop()
        
        // Avisa a tela que acabou
        Handler(Looper.getMainLooper()).post {
            onFinalizado?.invoke(nomeArquivo)
        }
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}