package com.runapp.ui.screens

import android.Manifest
import android.content.ContentValues
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.runapp.ui.viewmodel.CorridaUiState
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import kotlin.math.roundToInt

private const val TAG = "GravacaoCorridaScreen"

@Composable
fun GravacaoCorridaScreen(
    state: CorridaUiState,
    onVoltar: () -> Unit
) {
    val context        = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var cameraOk by remember { mutableStateOf(false) }
    var audioOk  by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { r ->
        cameraOk = r[Manifest.permission.CAMERA] == true
        audioOk  = r[Manifest.permission.RECORD_AUDIO] == true
    }

    LaunchedEffect(Unit) {
        val cc = PermissionChecker.checkSelfPermission(context, Manifest.permission.CAMERA) == PermissionChecker.PERMISSION_GRANTED
        val ca = PermissionChecker.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PermissionChecker.PERMISSION_GRANTED
        cameraOk = cc; audioOk = ca
        if (!cc || !ca) permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    var gravando      by remember { mutableStateOf(false) }
    var tempoGravacao by remember { mutableStateOf(0) }
    var arquivoSalvo  by remember { mutableStateOf<String?>(null) }
    var recording     by remember { mutableStateOf<Recording?>(null) }
    var videoCapture  by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { cameraExecutor.shutdown() } }

    LaunchedEffect(gravando) {
        if (gravando) {
            tempoGravacao = 0
            while (gravando) { delay(1000L); tempoGravacao++ }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        if (cameraOk) {
            AndroidView(
                factory = { ctx ->
                    val pv = PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                        scaleType          = PreviewView.ScaleType.FILL_CENTER
                    }
                    ProcessCameraProvider.getInstance(ctx).addListener({
                        val provider = ProcessCameraProvider.getInstance(ctx).get()
                        val preview  = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                        val recorder = Recorder.Builder()
                            .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                            .build()
                        val vc = VideoCapture.withOutput(recorder)
                        videoCapture = vc
                        try {
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, vc)
                            Log.d(TAG, "Camera OK")
                        } catch (e: Exception) { Log.e(TAG, "Erro camera", e) }
                    }, ContextCompat.getMainExecutor(ctx))
                    pv
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth().fillMaxHeight(0.65f).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))))
        )

        Column(
            modifier = Modifier
                .fillMaxWidth().align(Alignment.BottomCenter)
                .padding(start = 24.dp, end = 24.dp, bottom = 112.dp)
        ) {
            HudGrande("%.2f".format(state.distanciaMetros / 1000.0).replace(".", ","), "Km", "Distance")
            Spacer(Modifier.height(12.dp))
            val paceDisplay = state.paceAtual.let { p ->
                if (p == "--:--") "--' --\""
                else p.split(":").let { if (it.size == 2) "${it[0]}' ${it[1]}\"" else p }
            }
            HudGrande(paceDisplay, "", "Pace")
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Velocimetro(velocidadeDesPace(state.paceAtual), Modifier.size(130.dp))
                Spacer(Modifier.width(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudPequeno("Tempo",      state.tempoFormatado)
                    HudPequeno("Pace médio", state.paceMedia)
                    if (state.cadencia > 0) HudPequeno("Cadência", "${state.cadencia} spm")
                }
            }
        }

        arquivoSalvo?.let {
            Box(Modifier.align(Alignment.TopCenter).padding(top = 60.dp)
                .background(Color(0xFF1B5E20).copy(alpha = 0.9f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp)
            ) { Text("✅ Salvo em Movies/RunApp/$it.mp4", color = Color.White, fontSize = 13.sp) }
        }

        IconButton(
            onClick = { if (gravando) { recording?.stop(); recording = null }; onVoltar() },
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                .size(40.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape)
        ) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (gravando) IndicadorGravando(tempoGravacao)

            Box(
                modifier = Modifier.size(68.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = {
                        val vc = videoCapture ?: return@IconButton
                        if (gravando) {
                            recording?.stop(); recording = null; gravando = false
                        } else {
                            val nome = "corrida_${System.currentTimeMillis()}"
                            val cv = ContentValues().apply {
                                put(MediaStore.Video.Media.DISPLAY_NAME, "$nome.mp4")
                                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RunApp")
                            }
                            val out = MediaStoreOutputOptions.Builder(
                                context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            ).setContentValues(cv).build()

                            val prep = vc.output.prepareRecording(context, out)
                            val recFinal = if (audioOk) prep.withAudioEnabled() else prep
                            recording = recFinal.start(ContextCompat.getMainExecutor(context)) { ev ->
                                when (ev) {
                                    is VideoRecordEvent.Start    -> Log.d(TAG, "Gravando")
                                    is VideoRecordEvent.Finalize ->
                                        if (!ev.hasError()) arquivoSalvo = nome
                                        else Log.e(TAG, "Erro: ${ev.error}")
                                    else -> {}
                                }
                            }
                            gravando = true
                        }
                    },
                    modifier = Modifier.size(68.dp)
                ) {
                    Icon(
                        if (gravando) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                        null,
                        tint = if (gravando) Color.White else Color(0xFFEF5350),
                        modifier = Modifier.size(34.dp)
                    )
                }
            }
        }
    }
}

@Composable private fun HudGrande(valor: String, unidade: String, label: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(valor, fontSize = 60.sp, fontWeight = FontWeight.Bold, color = Color.White, lineHeight = 60.sp)
        if (unidade.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(unidade, fontSize = 22.sp, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(bottom = 8.dp))
        }
    }
    Text(label, fontSize = 13.sp, color = Color.White.copy(alpha = 0.55f))
}

@Composable private fun HudPequeno(label: String, valor: String) {
    Column {
        Text(label, fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
        Text(valor, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

@Composable private fun Velocimetro(vel: Float, modifier: Modifier) {
    val ratio = (vel / 30f).coerceIn(0f, 1f)
    Box(modifier, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val strokeWidth = 9.dp.toPx()
            val padding = strokeWidth / 2 + 6.dp.toPx()
            
            // Definindo o tamanho e a posição do arco manualmente (necessário para drawArc)
            val arcSize = Size(size.width - 2 * padding, size.height - 2 * padding)
            val topLeft = Offset(padding, padding)
            
            val arcStyle = Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round)

            // Fundo do velocímetro (Cinza transparente)
            drawArc(
                color = Color.White.copy(alpha = 0.15f),
                startAngle = 150f,
                sweepAngle = 240f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = arcStyle
            )

            // Progresso do velocímetro
            if (ratio > 0f) {
                val cor = androidx.compose.ui.graphics.lerp(Color(0xFF29B6F6), Color(0xFF66BB6A), ratio)
                drawArc(
                    color = cor,
                    startAngle = 150f,
                    sweepAngle = 240f * ratio,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = arcStyle
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(vel.roundToInt().toString(), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("KM/H", fontSize = 9.sp, color = Color.White.copy(alpha = 0.5f), letterSpacing = 2.sp)
        }
    }
}

@Composable private fun IndicadorGravando(t: Int) {
    val pulse = rememberInfiniteTransition(label = "p")
    val a by pulse.animateFloat(1f, 0.25f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Box(Modifier.size(8.dp).background(Color(0xFFEF5350).copy(alpha = a), CircleShape))
        Spacer(Modifier.width(6.dp))
        Text("%02d:%02d".format(t / 60, t % 60), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun velocidadeDesPace(p: String): Float {
    if (p == "--:--") return 0f
    return runCatching { 
        val partes = p.split(":")
        if (partes.size < 2) return 0f
        val minutos = partes[0].toFloat()
        val segundos = partes[1].toFloat()
        if (minutos == 0f && segundos == 0f) 0f else 3600f / (minutos * 60 + segundos)
    }.getOrDefault(0f)
}