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
import androidx.compose.ui.window.Dialog
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

    // ── Permissões ───────────────────────────────────────────────────────────
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

    // ── Estado ───────────────────────────────────────────────────────────────
    var gravando        by remember { mutableStateOf(false) }
    var arquivoSalvo    by remember { mutableStateOf<String?>(null) }
    var mostrarInstrucao by remember { mutableStateOf(false) }
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
                Log.d(TAG, "Service conectado")
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                gravacaoService = null
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            gravacaoService?.pararGravacao()
            runCatching { context.unbindService(serviceConnection) }
        }
    }

    // ── Launcher MediaProjection ─────────────────────────────────────────────
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
            context.bindService(
                Intent(context, GravacaoService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
            gravando = true
            Log.d(TAG, "Gravação iniciada via MediaProjection")
        } else {
            Log.w(TAG, "Usuário cancelou a permissão de tela")
        }
    }

    // ── Diálogo de instrução ─────────────────────────────────────────────────
    if (mostrarInstrucao) {
        Dialog(onDismissRequest = { mostrarInstrucao = false }) {
            Box(
                modifier = Modifier
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(16.dp))
                    .padding(24.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Como gravar",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        "Na janela que vai abrir:\n\n" +
                        "1. Toque no campo com seta ↓\n" +
                        "2. Selecione \"Tela inteira\"\n" +
                        "3. Toque em \"Próxima\"\n" +
                        "4. Confirme em \"Iniciar agora\"\n\n" +
                        "Para parar, toque em\n\"Parar\" na notificação.",
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFEF5350))
                            .then(Modifier.clip(RoundedCornerShape(10.dp))),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.clickable(
                            indication = null,
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        ) {
                            mostrarInstrucao = false
                            val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                                    as MediaProjectionManager
                            projectionLauncher.launch(mgr.createScreenCaptureIntent())
                        }.let { mod ->
                            Text(
                                "Entendido — Gravar",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                modifier = Modifier.padding(vertical = 14.dp)
                            )
                        }
                        // Botão funcional
                        Box(
                            Modifier.fillMaxWidth().height(50.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Transparent)
                        ) {
                            IconButton(
                                onClick = {
                                    mostrarInstrucao = false
                                    val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                                            as MediaProjectionManager
                                    projectionLauncher.launch(mgr.createScreenCaptureIntent())
                                },
                                modifier = Modifier.fillMaxSize()
                            ) { }
                        }
                    }
                }
            }
        }
    }

    // ── Layout ────────────────────────────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // Preview câmera
        if (cameraOk) {
            AndroidView(
                factory = { ctx ->
                    val pv = PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                        scaleType          = PreviewView.ScaleType.FILL_CENTER
                    }
                    ProcessCameraProvider.getInstance(ctx).addListener({
                        runCatching {
                            val prov   = ProcessCameraProvider.getInstance(ctx).get()
                            val prev   = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                            prov.unbindAll()
                            prov.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, prev)
                        }.onFailure { Log.e(TAG, "Erro câmera", it) }
                    }, ContextCompat.getMainExecutor(ctx))
                    pv
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Gradiente inferior
        Box(
            modifier = Modifier
                .fillMaxWidth().fillMaxHeight(0.65f).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))))
        )

        // ── HUD — sempre visível, aparece no vídeo ───────────────────────────
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
            val pace = state.paceAtual.let { p ->
                if (p == "--:--") "--' --\""
                else p.split(":").let { if (it.size == 2) "${it[0]}' ${it[1]}\"" else p }
            }
            HudGrande(pace, "", "Pace")
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

        // ── Controles — OCULTOS enquanto grava (não aparecem no vídeo) ────────
        if (!gravando) {
            // Botão voltar
            IconButton(
                onClick = onVoltar,
                modifier = Modifier
                    .align(Alignment.TopStart).padding(12.dp)
                    .size(40.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape)
            ) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }

            // Botão gravar
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter).padding(bottom = 32.dp)
                    .size(68.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = { mostrarInstrucao = true },
                    modifier = Modifier.size(68.dp)
                ) {
                    Icon(
                        Icons.Default.FiberManualRecord, null,
                        tint = Color(0xFFEF5350),
                        modifier = Modifier.size(34.dp)
                    )
                }
            }

            // Instrução rápida abaixo do botão
            Text(
                "Para parar: toque \"Parar\" na notificação",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.4f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp)
            )
        }

        // Quando gravando — só o indicador (pequeno, no canto, para stop via notificação)
        if (gravando) {
            // Aviso sobre como parar
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val pulse = rememberInfiniteTransition(label = "p")
                    val a by pulse.animateFloat(1f, 0.3f,
                        infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "a")
                    Box(Modifier.size(7.dp).background(Color(0xFFEF5350).copy(alpha = a), CircleShape))
                    Text("Gravando • pare pela notificação",
                        fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f))
                }
            }
        }

        // Confirmação de vídeo salvo
        arquivoSalvo?.let {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter).padding(top = 12.dp)
                    .background(Color(0xFF1B5E20).copy(alpha = 0.95f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text("✅ Vídeo salvo em Galeria → Filmes → RunApp",
                    color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            LaunchedEffect(it) { delay(3000L); onVoltar() }
        }
    }
}

// ── Componentes ───────────────────────────────────────────────────────────────

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

private fun velocidadeDesPace(p: String): Float {
    if (p == "--:--") return 0f
    return runCatching {
        val partes = p.split(":")
        if (partes.size < 2) return 0f
        val s = partes[0].toFloat() * 60 + partes[1].toFloat()
        if (s <= 0f) 0f else 3600f / s
    }.getOrDefault(0f)
}
