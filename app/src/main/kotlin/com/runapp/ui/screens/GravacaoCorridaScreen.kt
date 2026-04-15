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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.SecureFlagPolicy
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
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // ── Permissões ───────────────────────────────────────────────────────────
    var cameraOk by remember { mutableStateOf(false) }
    var audioOk by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { r ->
        cameraOk = r[Manifest.permission.CAMERA] == true
        audioOk = r[Manifest.permission.RECORD_AUDIO] == true
    }

    LaunchedEffect(Unit) {
        val cc = PermissionChecker.checkSelfPermission(context, Manifest.permission.CAMERA) == PermissionChecker.PERMISSION_GRANTED
        val ca = PermissionChecker.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PermissionChecker.PERMISSION_GRANTED
        cameraOk = cc; audioOk = ca
        if (!cc || !ca) permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    // ── Estado do Service ────────────────────────────────────────────────────
    var gravando by remember { mutableStateOf(false) }
    var arquivoSalvo by remember { mutableStateOf<String?>(null) }
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

    DisposableEffect(Unit) {
        onDispose {
            runCatching { context.unbindService(serviceConnection) }
        }
    }

    // ── Launcher da Gravação (MediaProjection) ──────────────────────────────
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            val si = Intent(context, GravacaoService::class.java).apply {
                putExtra(GravacaoService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(GravacaoService.EXTRA_DATA, result.data)
                putExtra(GravacaoService.EXTRA_AUDIO_OK, audioOk)
            }
            ContextCompat.startForegroundService(context, si)
            context.bindService(si, serviceConnection, Context.BIND_AUTO_CREATE)
            gravando = true
        }
    }

    // ── Layout ────────────────────────────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // 1. Preview da Câmera (Será gravado no vídeo da tela)
        if (cameraOk) {
            AndroidView(
                factory = { ctx ->
                    val pv = PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                    ProcessCameraProvider.getInstance(ctx).addListener({
                        runCatching {
                            val prov = ProcessCameraProvider.getInstance(ctx).get()
                            val prev = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                            prov.unbindAll()
                            prov.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, prev)
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    pv
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Gradiente inferior para leitura dos dados
        Box(
            modifier = Modifier
                .fillMaxWidth().fillMaxHeight(0.65f).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
        )

        // 2. HUD - Informações da corrida (Aparecem no vídeo final)
        Column(
            modifier = Modifier
                .fillMaxWidth().align(Alignment.BottomCenter)
                .padding(start = 24.dp, end = 24.dp, bottom = 112.dp)
        ) {
            HudGrande("%.2f".format(state.distanciaMetros / 1000.0).replace(".", ","), "Km", "Distance")
            Spacer(Modifier.height(8.dp))
            val paceStr = if (state.paceAtual == "--:--") "--' --\"" else state.paceAtual
            HudGrande(paceStr, "", "Pace")
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Velocimetro(velocidadeDesPace(state.paceAtual), Modifier.size(130.dp))
                Spacer(Modifier.width(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudPequeno("Tempo", state.tempoFormatado)
                    HudPequeno("Pace médio", state.paceMedia)
                    if (state.cadencia > 0) HudPequeno("Cadência", "${state.cadencia} spm")
                }
            }
        }

        // 3. CONTROLES INICIAIS (Somem quando a gravação começa)
        if (!gravando) {
            IconButton(
                onClick = onVoltar,
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
                    .size(40.dp).background(Color.Black.copy(alpha = 0.4f), CircleShape)
            ) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }

            // Botão Iniciar (Redondo Vermelho)
            Box(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
                    .size(72.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f))
                    .clickable {
                        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        projectionLauncher.launch(mgr.createScreenCaptureIntent())
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.FiberManualRecord, null, tint = Color(0xFFEF5350), modifier = Modifier.size(40.dp))
            }
        }

        // 4. BOTÃO "PARAR" ERGONÔMICO (Invisível no vídeo final!)
        if (gravando) {
            Popup(
                alignment = Alignment.BottomEnd,
                properties = PopupProperties(
                    securePolicy = SecureFlagPolicy.SecureOn, // AQUI A MÁGICA: Invisível para o gravador
                    excludeFromSystemGesture = true
                )
            ) {
                Box(
                    modifier = Modifier
                        .padding(bottom = 40.dp, end = 20.dp)
                        .size(100.dp, 56.dp)
                        .background(Color(0xFFEF5350).copy(alpha = 0.9f), RoundedCornerShape(28.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            gravacaoService?.pararGravacao()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Stop, null, tint = Color.White, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("PARAR", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                    }
                }
            }
        }

        // Mensagem de Vídeo Salvo
        arquivoSalvo?.let {
            Box(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp)
                    .background(Color(0xFF1B5E20), RoundedCornerShape(8.dp)).padding(16.dp)
            ) {
                Text("✅ Vídeo salvo na galeria!", color = Color.White, fontWeight = FontWeight.Bold)
            }
            LaunchedEffect(it) { delay(3000L); onVoltar() }
        }
    }
}

// ── Componentes de UI ───────────────────────────────────────────────────────

@Composable private fun HudGrande(valor: String, unidade: String, label: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(valor, fontSize = 60.sp, fontWeight = FontWeight.Bold, color = Color.White, lineHeight = 60.sp)
        if (unidade.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(unidade, fontSize = 22.sp, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(bottom = 8.dp))
        }
    }
    Text(label, fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f), letterSpacing = 1.sp)
}

@Composable private fun HudPequeno(label: String, valor: String) {
    Column {
        Text(label.uppercase(), fontSize = 10.sp, color = Color.White.copy(alpha = 0.5f), fontWeight = FontWeight.Bold)
        Text(valor, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

@Composable private fun Velocimetro(vel: Float, modifier: Modifier) {
    val ratio = (vel / 30f).coerceIn(0f, 1f)
    Box(modifier, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val sw = 9.dp.toPx()
            val pad = sw / 2 + 6.dp.toPx()
            val arcSize = Size(size.width - 2 * pad, size.height - 2 * pad)
            val topLeft = Offset(pad, pad)
            val st = Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)

            drawArc(Color.White.copy(alpha = 0.15f), 150f, 240f, false, topLeft, arcSize, style = st)
            if (ratio > 0f) {
                drawArc(
                    color = androidx.compose.ui.graphics.lerp(Color(0xFF29B6F6), Color(0xFF66BB6A), ratio),
                    startAngle = 150f, sweepAngle = 240f * ratio, useCenter = false,
                    topLeft = topLeft, size = arcSize, style = st
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(vel.roundToInt().toString(), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("KM/H", fontSize = 9.sp, color = Color.White.copy(alpha = 0.5f))
        }
    }
}

private fun velocidadeDesPace(p: String): Float {
    if (p == "--:--") return 0f
    return runCatching {
        val partes = p.split(":")
        val s = partes[0].toFloat() * 60 + partes[1].toFloat()
        if (s <= 0f) 0f else 3600f / s
    }.getOrDefault(0f)
}