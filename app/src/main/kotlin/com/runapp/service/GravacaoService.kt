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
    inner class LocalBinder : Binder() { fun getService(): GravacaoService = this@GravacaoService }
    private val binder = LocalBinder()
    private var mediaProjection: MediaProjection? = null
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var pfd: ParcelFileDescriptor? = null
    private var nomeArquivo: String? = null
    var onFinalizado: ((String?) -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "com.runapp.STOP") { encerrarTudo(); return START_NOT_STICKY }
        val rc = intent?.getIntExtra("result_code", -1) ?: -1
        val dt = intent?.getParcelableExtra<Intent>("projection_data")
        if (rc != -1 && dt != null) { iniciarNotif(); iniciarGravacao(rc, dt) }
        return START_STICKY
    }

    private fun iniciarNotif() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("rec", "Gravando", NotificationManager.IMPORTANCE_LOW))
        }
        val stopIn = Intent(this, GravacaoService::class.java).apply { action = "com.runapp.STOP" }
        val stopPe = PendingIntent.getService(this, 0, stopIn, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notif = NotificationCompat.Builder(this, "rec").setContentTitle("RunApp Gravando").setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(android.R.drawable.ic_media_pause, "PARAR", stopPe).setOngoing(true).build()
        startForeground(9001, notif)
    }

    private fun iniciarGravacao(rc: Int, dt: Intent) {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(rc, dt)
        val m = resources.displayMetrics
        val w = if (m.widthPixels % 2 == 0) m.widthPixels else m.widthPixels - 1
        val h = if (m.heightPixels % 2 == 0) m.heightPixels else m.heightPixels - 1
        
        nomeArquivo = "run_${System.currentTimeMillis()}"
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$nomeArquivo.mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RunApp")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        }
        
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: return
        pfd = contentResolver.openFileDescriptor(uri, "w")
        
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
        mediaRecorder?.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(w, h)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(8_000_000)
            setOutputFile(pfd?.fileDescriptor)
            prepare()
            virtualDisplay = mediaProjection?.createVirtualDisplay("Rec", w, h, m.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
            start()
        }
    }

    fun encerrarTudo() {
        try { mediaRecorder?.stop(); mediaRecorder?.release() } catch (e: Exception) { Log.e("Service", "Erro stop") }
        virtualDisplay?.release(); mediaProjection?.stop(); pfd?.close()
        
        // Callback para a UI avisar que salvou
        Handler(Looper.getMainLooper()).post { onFinalizado?.invoke(nomeArquivo) }
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}