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
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileWriter
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

    // ── Logger em arquivo (acessível sem Android Studio) ──────────────────────
    // O arquivo fica em: Armazenamento interno → Android/data/com.runapp/files/gravacao_log.txt
    // Abra com qualquer gerenciador de arquivos do celular
    private var logWriter: FileWriter? = null

    private fun log(msg: String) {
        Log.d(TAG, msg)
        try {
            if (logWriter == null) {
                val dir  = getExternalFilesDir(null) ?: filesDir
                val file = File(dir, "gravacao_log.txt")
                logWriter = FileWriter(file, true) // append
            }
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            logWriter?.write("[$ts] $msg\n")
            logWriter?.flush()
        } catch (_: Exception) {}
    }

    private fun fecharLog() {
        try { logWriter?.close() } catch (_: Exception) {}
        logWriter = null
    }
    // ──────────────────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instancia = this
        log("=== GravacaoService onCreate ===")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("onStartCommand")
        criarCanal()

        val notif = NotificationCompat.Builder(this, CANAL_ID)
            .setContentTitle("RunApp — gravando")
            .setContentText("Volte ao app para parar")
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
        @Suppress("DEPRECATION")
        val data       = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        val audioOk    = intent?.getBooleanExtra(EXTRA_AUDIO_OK, false) ?: false

        log("resultCode=$resultCode  data=${data != null}  audioOk=$audioOk")

        if (resultCode == -1 || data == null) {
            log("ERRO: dados inválidos — abortando")
            stopSelf(); return START_NOT_STICKY
        }

        try {
            val mgr  = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mgr.getMediaProjection(resultCode, data)

            if (proj == null) {
                log("ERRO: getMediaProjection retornou null")
                chamarFinalizado(null); stopSelf(); return START_NOT_STICKY
            }

            log("MediaProjection criado com sucesso")

            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    log("AVISO: MediaProjection encerrado pelo sistema")
                    if (gravandoInterno) pararGravacao()
                }
            }, mainHandler)

            mediaProjection = proj
            iniciarGravacao(proj, audioOk)

        } catch (e: Exception) {
            log("ERRO onStartCommand: ${e.message}")
            chamarFinalizado(null); stopSelf()
        }

        return START_NOT_STICKY
    }

    // Dimensão precisa ser múltiplo de 2 para o encoder H264
    private fun Int.toEven() = if (this % 2 == 0) this else this - 1

    private fun iniciarGravacao(proj: MediaProjection, audioOk: Boolean) {

        // ── Tela inteira, sem subtrair status bar ou navigation bar ──────────
        // Subtrair insets causava dimensões diferentes entre o VirtualDisplay
        // e o MediaRecorder, o que corrompe a gravação silenciosamente.
        val dm  = resources.displayMetrics
        val w   = dm.widthPixels.toEven()
        val h   = dm.heightPixels.toEven()
        val dpi = dm.densityDpi

        log("Dimensões: ${w}x${h}  dpi=$dpi")

        val ts   = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dir  = externalCacheDir ?: cacheDir
        val temp = File(dir, "corrida_$ts.mp4")
        arquivoTemp = temp

        log("Arquivo temp: ${temp.absolutePath}")

        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(this)
        else
            @Suppress("DEPRECATION") MediaRecorder()

        rec.setOnErrorListener { _, what, extra ->
            log("MediaRecorder.onError what=$what extra=$extra")
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
            log("MediaRecorder.prepare() OK")

            virtualDisplay = proj.createVirtualDisplay(
                "GravacaoCorrida", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                rec.surface, null, null
            )
            log("VirtualDisplay criado: $virtualDisplay")

            rec.start()
            recorder        = rec
            gravandoInterno = true
            log("▶ Gravação iniciada")

        } catch (e: Exception) {
            log("ERRO iniciarGravacao: ${e.message}")
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
        log("pararGravacao() chamado — gravandoInterno=$gravandoInterno")
        if (!gravandoInterno) return
        gravandoInterno = false

        Thread { executarParada() }.start()
    }

    private fun executarParada() {
        log("executarParada() — thread iniciada")

        // 1. Para o VirtualDisplay antes do recorder
        runCatching { virtualDisplay?.release() }.onFailure { log("vd.release erro: ${it.message}") }
        virtualDisplay = null
        log("VirtualDisplay liberado")

        // 2. Aguarda encoder finalizar os últimos frames
        Thread.sleep(500)

        // 3. Para o recorder (swallow exception — o arquivo ainda pode ser válido)
        runCatching { recorder?.stop() }
            .onSuccess { log("recorder.stop() OK") }
            .onFailure { log("recorder.stop() erro (pode ser normal): ${it.message}") }

        runCatching { recorder?.release() }
        recorder = null

        // 4. Para o MediaProjection
        runCatching { mediaProjection?.stop() }
            .onSuccess { log("mediaProjection.stop() OK") }
            .onFailure { log("mediaProjection.stop() erro: ${it.message}") }
        mediaProjection = null

        // 5. Verifica o arquivo
        val temp   = arquivoTemp
        val existe = temp?.exists() == true
        val bytes  = temp?.length() ?: 0L
        log("Arquivo: ${temp?.absolutePath}  existe=$existe  bytes=$bytes")

        val nome: String? = when {
            temp == null    -> { log("ERRO: arquivoTemp null");                    null }
            !existe         -> { log("ERRO: arquivo não existe");                  null }
            bytes == 0L     -> { log("ERRO: 0 bytes — encoder não gravou nada"); temp.delete(); null }
            bytes < 1000    -> { log("ERRO: $bytes bytes — MP4 corrompido");      temp.delete(); null }
            else            -> {
                log("Arquivo OK ($bytes bytes) — salvando na galeria...")
                salvarNaGaleria(temp)
            }
        }

        arquivoTemp = null
        log("Resultado final: nome=$nome")
        fecharLog()

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
                // API 29+ — MediaStore com IS_PENDING
                val cv = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "$nome.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE,    "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RunApp")
                    put(MediaStore.Video.Media.IS_PENDING,    1)
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
                    ?: run { log("ERRO: MediaStore.insert retornou null"); return null }

                log("URI criada: $uri")

                contentResolver.openOutputStream(uri)?.use { out ->
                    val copied = origem.inputStream().use { it.copyTo(out) }
                    log("Bytes copiados para MediaStore: $copied")
                } ?: run { log("ERRO: openOutputStream null"); return null }

                val rows = contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                    null, null
                )
                log("IS_PENDING=0: $rows linha(s) atualizada(s)")

                origem.delete()
                log("✅ Salvo em Movies/RunApp/$nome.mp4")
                nome

            } else {
                // API < 29
                @Suppress("DEPRECATION")
                val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                val destDir   = File(moviesDir, "RunApp").also { it.mkdirs() }
                val destino   = File(destDir, "$nome.mp4")
                origem.copyTo(destino, overwrite = true)
                origem.delete()
                android.media.MediaScannerConnection.scanFile(
                    this, arrayOf(destino.absolutePath), arrayOf("video/mp4"), null
                )
                log("✅ Salvo (legado): ${destino.absolutePath}")
                nome
            }
        } catch (e: Exception) {
            log("ERRO salvarNaGaleria: ${e.message}")
            null
        }
    }

    private fun chamarFinalizado(nome: String?) = mainHandler.post { onFinalizado?.invoke(nome) }

    override fun onDestroy() {
        super.onDestroy()
        instancia = null
        if (gravandoInterno) pararGravacao()
        log("onDestroy")
        fecharLog()
    }

    private fun criarCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val c = NotificationChannel(CANAL_ID, "Gravação", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(c)
        }
    }
}
