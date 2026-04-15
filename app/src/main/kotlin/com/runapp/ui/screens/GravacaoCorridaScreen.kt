package com.runapp.ui.screens

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
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
import com.runapp.service.GravacaoService
import com.runapp.ui.viewmodel.CorridaUiState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val TAG = "GravacaoCorridaScreen"

@Composable
fun GravacaoCorridaScreen(
    state: CorridaUiState,
    onVoltar: () -> Unit
) {
    val context        = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // ── Permissões ──────────────────────────────────────────────────────────
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
        cameraOk = cc
        audioOk  = ca
        if (!cc || !ca) permLauncher.launch(
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        )
    }

    // ── Estado ──────────────────────────────────────────────────────────────
    var gravando      by remember { mutableStateOf(false) }
    var tempoGravacao by remember { mutableStateOf(0) }
    var arquivoSalvo  by remember { mutableStateOf<String?>(null) }

    // Referência ao serviço — só existe enquanto gravando
    var gravacaoService by remember { mutableStateOf<GravacaoService?>(null) }
    val serviceConnection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val svc = (binder as? GravacaoService.LocalBinder)?.getService() ?: return
                svc.onFinalizado = { nome ->
                    gravando = false
                    arquivoSalvo = nome
                    gravacaoService = null
                }
                gravacaoService = svc
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                gravacaoService = null
            }
        }
    }

    // Cleanup ao sair da tela
    DisposableEffect(Unit) {
        onDispose {
            // Se ainda gravando ao sair, para tudo
            gravacaoService?.pararGravacao()
            runCatching { context.unbindService(serviceConnection) }
        }
    }

    // Cronômetro
    LaunchedEffect(gravando) {
        tempoGravacao = 0
        while (gravando) { delay(1000L); tempoGravacao++ }
    }

    // ── Launcher MediaProjection ────────────────────────────────────────────
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            // Inicia o serviço com os dados de projeção
            val serviceIntent = Intent(context, GravacaoService::class.java).apply {
                putExtra(GravacaoService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(GravacaoService.EXTRA_DATA, result.data)
                putExtra(GravacaoService.EXTRA_AUDIO_OK, audioOk)
            }
            // startForegroundService obrigatório para Android 8+
            ContextCompat.startForegroundService(context, serviceIntent)
            // Bind para obter referência e poder parar depois
            context.bindService(
                Intent(context, GravacaoService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
            gravando = true
        }
    }

    // ── Layout ───────────────────────────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // Preview câmera — sem VideoCapture, só Preview
        if (cameraOk) {
            AndroidView(
                factory = { ctx ->
                    val pv = PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                        scaleType          = PreviewView.ScaleType.FILL_CENTER
                    }
                    ProcessCameraProvider.getInstance(ctx).addListener({
                        runCatching {
                            val provider = ProcessCameraProvider.getInstance(ctx).get()
                            val preview  = Preview.Builder().build()
                                .also { it.setSurfaceProvider(pv.surfaceProvider) }
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview
                            )
                            Log.d(TAG, "Camera preview OK")
                        }.onFailure { Log.e(TAG, "Erro camera", it) }
                    }, ContextCompat.getMainExecutor(ctx))
                    pv
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Gradiente para legibilidade das métricas
        Box(
            modifier = Modifier
                .fillMaxWidth().fillMaxHeight(0.65f).align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f)))
                )
        )

        // ── HUD — sempre visível (aparece na gravação de tela) ──────────────
        Column(
            modifier = Modifier
                .fillMaxWidth().align(Alignment.BottomCenter)
                .padding(start = 24.dp, end = 24.dp, bottom = 112.dp)
        ) {
            HudGrande(
                "%.2f".format(state.distanciaMetros / 1000.0).replace(".", ","),
                "Km", "Distance"
            )
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

        // ── Controles ocultos durante gravação ──────────────────────────────
        if (!gravando) {
            IconButton(
                onClick = onVoltar,
                modifier = Modifier
                    .align(Alignment.TopStart).padding(12.dp)
                    .size(40.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape)
            ) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter).padding(bottom = 32.dp)
                    .size(68.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = {
                        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                                as MediaProjectionManager
                        projectionLauncher.launch(mgr.createScreenCaptureIntent())
                    },
                    modifier = Modifier.size(68.dp)
                ) {
                    Icon(
                        Icons.Default.FiberManualRecord, null,
                        tint = Color(0xFFEF5350),
                        modifier = Modifier.size(34.dp)
                    )
                }
            }
        } else {
            // Botão stop + timer — pequeno, canto inferior direito
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 36.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IndicadorGravando(tempoGravacao)
                Box(
                    modifier = Modifier
                        .size(44.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = {
                            gravacaoService?.pararGravacao()
                            runCatching { context.unbindService(serviceConnection) }
                            gravando = false
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            Icons.Default.Stop, null,
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        // Confirmação de vídeo salvo
        arquivoSalvo?.let {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter).padding(top = 16.dp)
                    .background(Color(0xFF1B5E20).copy(alpha = 0.92f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("✅ Vídeo salvo em Galeria > RunApp", color = Color.White, fontSize = 13.sp)
            }
            LaunchedEffect(it) { delay(3000L); onVoltar() }
        }
    }
}

// ── Componentes ──────────────────────────────────────────────────────────────

@Composable private fun HudGrande(valor: String, unidade: String, label: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(valor, fontSize = 60.sp, fontWeight = FontWeight.Bold, color = Color.White, lineHeight = 60.sp)
        if (unidade.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(unidade, fontSize = 22.sp, color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.padding(bottom = 8.dp))
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
            val sw  = 9.dp.toPx()
            val pad = sw / 2 + 6.dp.toPx()
            val tl  = Offset(pad, pad)
            val sz  = Size(size.width - 2 * pad, size.height - 2 * pad)
            val st  = Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawArc(Color.White.copy(alpha = 0.15f), 150f, 240f, false, tl, sz, style = st)
            if (ratio > 0f) {
                val cor = androidx.compose.ui.graphics.lerp(Color(0xFF29B6F6), Color(0xFF66BB6A), ratio)
                drawArc(cor, 150f, 240f * ratio, false, tl, sz, style = st)
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
    val a by pulse.animateFloat(1f, 0.3f,
        infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Box(Modifier.size(7.dp).background(Color(0xFFEF5350).copy(alpha = a), CircleShape))
        Spacer(Modifier.width(5.dp))
        Text("%02d:%02d".format(t / 60, t % 60), color = Color.White,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun velocidadeDesPace(p: String): Float {
    if (p == "--:--") return 0f
    return runCatching {
        val partes = p.split(":")
        if (partes.size < 2) return 0f
        val s = partes[0].toFloat() * 60 + partes[1].toFloat()
        if (s <= 0f) 0f else 3600f / s
    }.getOrDefault(0f)
}
