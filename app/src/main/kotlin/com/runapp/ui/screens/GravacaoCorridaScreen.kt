package com.runapp.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.runapp.ui.viewmodel.CorridaUiState
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

/**
 * Tela de gravação de vídeo durante a corrida.
 *
 * Fluxo:
 *  1. Abre câmera traseira em fullscreen via CameraX Preview.
 *  2. Sobrepõe o HUD com métricas em tempo real (pace, velocidade, distância, tempo).
 *  3. Usa MediaProjection para capturar tudo (câmera + overlay) e salvar em MP4.
 *
 * Permissões necessárias (declarar no AndroidManifest.xml):
 *  - android.permission.CAMERA
 *  - android.permission.RECORD_AUDIO
 *  - android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION  (API 34+)
 *  - Salvar vídeo: MediaStore API (sem permissão de armazenamento no Android 10+)
 */
@Composable
fun GravacaoCorridaScreen(
    state: CorridaUiState,
    onVoltar: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // ── Estado de gravação ──────────────────────────────────────────────
    var gravando by remember { mutableStateOf(false) }
    var tempoGravacao by remember { mutableStateOf(0) }
    var cameraPermitida by remember { mutableStateOf(false) }
    var audioPermitido by remember { mutableStateOf(false) }
    var mediaProjectionIntent by remember { mutableStateOf<Intent?>(null) }
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var mediaProjection by remember { mutableStateOf<MediaProjection?>(null) }

    // Cronômetro de gravação
    LaunchedEffect(gravando) {
        if (gravando) {
            while (gravando) {
                delay(1000)
                tempoGravacao++
            }
        } else {
            tempoGravacao = 0
        }
    }

    // ── Permissões ──────────────────────────────────────────────────────
    val permissaoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        cameraPermitida = perms[Manifest.permission.CAMERA] == true
        audioPermitido  = perms[Manifest.permission.RECORD_AUDIO] == true
    }

    // Solicitar permissão de captura de tela (MediaProjection)
    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            mediaProjectionIntent = result.data
            iniciarGravacao(
                context        = context,
                projectionIntent = result.data!!,
                resultCode     = result.resultCode,
                onRecorder     = { mediaRecorder = it },
                onProjection   = { mediaProjection = it }
            )
            gravando = true
        }
    }

    // Solicitar permissões ao entrar na tela
    LaunchedEffect(Unit) {
        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        permissaoLauncher.launch(perms.toTypedArray())
    }

    // ── Câmera ──────────────────────────────────────────────────────────
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // Preview da câmera — fullscreen
        if (cameraPermitida) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { previewView ->
                    cameraProviderFuture.addListener({
                        val provider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        try {
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("GravacaoScreen", "Erro câmera", e)
                        }
                    }, ContextCompat.getMainExecutor(context))
                }
            )
        }

        // ── Gradiente inferior para legibilidade ────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.6f)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
                    )
                )
        )

        // ── HUD — Métricas em tempo real ────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(start = 24.dp, end = 24.dp, bottom = 100.dp)
        ) {
            // Distância
            HudMetrica(
                valor  = "%.2f".format(state.distanciaMetros / 1000.0),
                unidade = "Km",
                label  = "Distance"
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Pace atual
            HudMetrica(
                valor  = state.paceAtual.replace(":", "' ").let {
                    if (it.contains("' ")) it + "\"" else it
                },
                unidade = "",
                label  = "Pace"
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Velocidade + velocímetro visual
            val velocidadeKmh = calcularVelocidadeKmh(state.paceAtual)
            Row(verticalAlignment = Alignment.CenterVertically) {
                VelocimetroCircular(
                    velocidade = velocidadeKmh,
                    modifier   = Modifier.size(140.dp)
                )
                Spacer(modifier = Modifier.width(20.dp))
                // Tempo de corrida
                Column {
                    HudMetricaPequena("Tempo", state.tempoFormatado)
                    Spacer(modifier = Modifier.height(8.dp))
                    HudMetricaPequena("Pace médio", state.paceMedia)
                    if (state.cadencia > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        HudMetricaPequena("Cadência", "${state.cadencia} spm")
                    }
                }
            }
        }

        // ── Botão voltar ────────────────────────────────────────────────
        IconButton(
            onClick = {
                if (gravando) pararGravacao(mediaRecorder, mediaProjection)
                onVoltar()
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.4f), CircleShape)
        ) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = Color.White)
        }

        // ── Botão gravar ─────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (gravando) {
                // Indicador de gravação + tempo
                GravandoIndicador(tempoGravacao = tempoGravacao)
                Spacer(modifier = Modifier.width(16.dp))
            }

            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(if (gravando) Color(0xFFEF5350) else Color.White.copy(alpha = 0.15f))
                    .then(
                        if (!gravando) Modifier.background(
                            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.2f), Color.Transparent))
                        ) else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = {
                        if (gravando) {
                            pararGravacao(mediaRecorder, mediaProjection)
                            gravando = false
                        } else {
                            // Solicita permissão de captura de tela
                            val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                                    as MediaProjectionManager
                            mediaProjectionLauncher.launch(mgr.createScreenCaptureIntent())
                        }
                    }
                ) {
                    Icon(
                        imageVector = if (gravando) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                        contentDescription = if (gravando) "Parar gravação" else "Gravar",
                        tint = if (gravando) Color.White else Color(0xFFEF5350),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

// ── Componentes do HUD ──────────────────────────────────────────────────────

@Composable
private fun HudMetrica(valor: String, unidade: String, label: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = valor,
            fontSize = 64.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            lineHeight = 64.sp
        )
        if (unidade.isNotEmpty()) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = unidade,
                fontSize = 22.sp,
                fontWeight = FontWeight.Normal,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.padding(bottom = 10.dp)
            )
        }
    }
    Text(
        text = label,
        fontSize = 14.sp,
        color = Color.White.copy(alpha = 0.6f),
        fontWeight = FontWeight.Normal
    )
}

@Composable
private fun HudMetricaPequena(label: String, valor: String) {
    Column {
        Text(label, fontSize = 11.sp, color = Color.White.copy(alpha = 0.55f))
        Text(valor, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

@Composable
private fun VelocimetroCircular(velocidade: Float, modifier: Modifier = Modifier) {
    val maxVel = 30f
    val ratio  = (velocidade / maxVel).coerceIn(0f, 1f)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // Fundo do arco
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            val pad    = stroke / 2 + 4.dp.toPx()
            val sweep  = 240f
            val start  = 150f

            // Trilho cinza
            drawArc(
                color      = Color.White.copy(alpha = 0.15f),
                startAngle = start,
                sweepAngle = sweep,
                useCenter  = false,
                style      = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = stroke,
                    cap   = androidx.compose.ui.graphics.StrokeCap.Round
                ),
                topLeft = androidx.compose.ui.geometry.Offset(pad, pad),
                size    = androidx.compose.ui.geometry.Size(size.width - pad * 2, size.height - pad * 2)
            )

            // Arco de velocidade — azul → verde
            val corArco = androidx.compose.ui.graphics.lerp(
                Color(0xFF29B6F6), Color(0xFF66BB6A), ratio
            )
            drawArc(
                color      = corArco,
                startAngle = start,
                sweepAngle = sweep * ratio,
                useCenter  = false,
                style      = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = stroke,
                    cap   = androidx.compose.ui.graphics.StrokeCap.Round
                ),
                topLeft = androidx.compose.ui.geometry.Offset(pad, pad),
                size    = androidx.compose.ui.geometry.Size(size.width - pad * 2, size.height - pad * 2)
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = velocidade.roundToInt().toString(),
                fontSize = 38.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "KM/H",
                fontSize = 10.sp,
                color = Color.White.copy(alpha = 0.55f),
                letterSpacing = 2.sp
            )
        }
    }
}

@Composable
private fun GravandoIndicador(tempoGravacao: Int) {
    val pulso = rememberInfiniteTransition(label = "pulso")
    val alpha by pulso.animateFloat(
        initialValue = 1f, targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    val min = tempoGravacao / 60
    val sec = tempoGravacao % 60

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(Color(0xFFEF5350).copy(alpha = alpha), CircleShape)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "%02d:%02d".format(min, sec),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────

private fun calcularVelocidadeKmh(paceStr: String): Float {
    if (paceStr == "--:--") return 0f
    return runCatching {
        val partes = paceStr.split(":")
        val segKm  = partes[0].toFloat() * 60 + partes[1].toFloat()
        if (segKm <= 0f) 0f else 3600f / segKm
    }.getOrDefault(0f)
}

private fun iniciarGravacao(
    context: Context,
    projectionIntent: Intent,
    resultCode: Int,
    onRecorder: (MediaRecorder) -> Unit,
    onProjection: (MediaProjection) -> Unit
) {
    val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    val projection = mgr.getMediaProjection(resultCode, projectionIntent)
    onProjection(projection)

    val metrics = DisplayMetrics()
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = wm.currentWindowMetrics.bounds
        metrics.widthPixels  = bounds.width()
        metrics.heightPixels = bounds.height()
        metrics.densityDpi   = context.resources.displayMetrics.densityDpi
    } else {
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)
    }

    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val arquivo = File(
        context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES),
        "corrida_$timestamp.mp4"
    )

    val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MediaRecorder(context)
    } else {
        @Suppress("DEPRECATION")
        MediaRecorder()
    }

    recorder.apply {
        setAudioSource(MediaRecorder.AudioSource.MIC)
        setVideoSource(MediaRecorder.VideoSource.SURFACE)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        setVideoSize(metrics.widthPixels, metrics.heightPixels)
        setVideoFrameRate(30)
        setVideoEncodingBitRate(6_000_000)
        setAudioEncodingBitRate(128_000)
        setAudioSamplingRate(44100)
        setOutputFile(arquivo.absolutePath)
        prepare()
    }

    val surface: Surface = recorder.surface
    projection.createVirtualDisplay(
        "GravacaoCorrida",
        metrics.widthPixels,
        metrics.heightPixels,
        metrics.densityDpi,
        android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        surface,
        null,
        null
    )

    recorder.start()
    onRecorder(recorder)

    android.util.Log.d("GravacaoScreen", "✅ Gravação iniciada: ${arquivo.absolutePath}")
}

private fun pararGravacao(mediaRecorder: MediaRecorder?, mediaProjection: MediaProjection?) {
    runCatching {
        mediaRecorder?.stop()
        mediaRecorder?.release()
    }
    runCatching {
        mediaProjection?.stop()
    }
    android.util.Log.d("GravacaoScreen", "⏹️ Gravação encerrada")
}
