package com.runapp.ui.screens

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
    val context        = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Gesto de voltar sempre fecha a gravação, nunca o app
    BackHandler { onVoltar() }

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
    var tempoGravacao   by remember { mutableStateOf(0) }
    var arquivoSalvo    by remember { mutableStateOf<String?>(null) }
    var gravacaoService by remember { mutableStateOf<GravacaoService?>(null) }
    var servicoBound    by remember { mutableStateOf(false) }

    val serviceConnection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val svc = (binder as? GravacaoService.LocalBinder)?.getService() ?: run {
                    Log.e(TAG, "Binder nulo ao conectar serviço")
                    return
                }
                svc.onFinalizado = { nome ->
                    Log.d(TAG, "✅ onFinalizado: $nome")
                    gravando = false
                    if (nome != null) {
                        arquivoSalvo = nome
                        Toast.makeText(context, "Vídeo salvo: $nome.mp4", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Erro: vídeo não foi salvo", Toast.LENGTH_LONG).show()
                    }
                    gravacaoService = null
                }
                gravacaoService = svc
                servicoBound = true
                Log.d(TAG, "✅ Serviço conectado. gravando=${svc.gravando}")
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                Log.w(TAG, "Serviço desconectado")
                servicoBound = false
                gravacaoService = null
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (gravando) {
                Log.d(TAG, "DisposableEffect: parando gravação ao sair")
                gravacaoService?.pararGravacao()
            }
            if (servicoBound) runCatching { context.unbindService(serviceConnection) }
        }
    }

    LaunchedEffect(gravando) {
        tempoGravacao = 0
        while (gravando) { delay(1000L); tempoGravacao++ }
    }

    // ── Launcher MediaProjection ─────────────────────────────────────────────
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(TAG, "MediaProjection result: code=${result.resultCode} data=${result.data}")
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            Toast.makeText(context, "Iniciando gravação…", Toast.LENGTH_SHORT).show()
            val si = Intent(context, GravacaoService::class.java).apply {
                putExtra(GravacaoService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(GravacaoService.EXTRA_DATA, result.data)
                putExtra(GravacaoService.EXTRA_AUDIO_OK, audioOk)
            }
            ContextCompat.startForegroundService(context, si)
            // Bind com pequeno delay para o serviço já estar em foreground
            context.bindService(
                Intent(context, GravacaoService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
            gravando = true
        } else {
            Toast.makeText(context, "Gravação cancelada", Toast.LENGTH_SHORT).show()
            Log.w(TAG, "Usuário cancelou ou resultCode=${result.resultCode}")
        }
    }

    // ── Layout ────────────────────────────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // Câmera — COMPATIBLE evita tela preta
        if (cameraOk) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }.also { pv ->
                        ProcessCameraProvider.getInstance(ctx).addListener({
                            runCatching {
                                val prov = ProcessCameraProvider.getInstance(ctx).get()
                                prov.unbindAll()
                                prov.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                                )
                                Log.d(TAG, "✅ Câmera OK")
                            }.onFailure {
                                Log.e(TAG, "❌ Câmera: ${it.message}", it)
                                Toast.makeText(ctx, "Erro câmera: ${it.message}", Toast.LENGTH_SHORT).show()
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                    }
                }
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Aguardando permissão de câmera…", color = Color.White.copy(alpha = 0.5f), textAlign = TextAlign.Center)
            }
        }

        // Gradiente inferior
        Box(
            modifier = Modifier
                .fillMaxWidth().fillMaxHeight(0.65f).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))))
        )

        // HUD — sempre visível (aparece no vídeo)
        Column(
            modifier = Modifier
                .fillMaxWidth().align(Alignment.BottomCenter)
                .padding(start = 24.dp, end = 24.dp, bottom = 100.dp)
        ) {
            HudGrande("%.2f".format(state.distanciaMetros / 1000.0).replace(".", ","), "Km", "Distance")
            Spacer(Modifier.height(12.dp))
            HudGrande(
                state.paceAtual.let { p ->
                    if (p == "--:--") "--' --\""
                    else p.split(":").let { if (it.size == 2) "${it[0]}' ${it[1]}\"" else p }
                }, "", "Pace"
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BarrasVelocidade(velocidadeDesPace(state.paceAtual), Modifier.size(width = 80.dp, height = 100.dp))
                Spacer(Modifier.width(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudPequeno("Tempo",      state.tempoFormatado)
                    HudPequeno("Pace médio", state.paceMedia)
                    if (state.cadencia > 0) HudPequeno("Cadência", "${state.cadencia} spm")
                }
            }
        }

        // ── Botão gravar (centralizado) + botão voltar (inferior esquerdo) ────
        // Ocultos durante gravação para não aparecer no vídeo
        if (!gravando) {
            // Botão voltar — canto inferior esquerdo, mais acessível ao correr
            IconButton(
                onClick = onVoltar,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 36.dp)
                    .size(44.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = Color.White)
            }

            // Botão gravar — centralizado na parte inferior
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = {
                        Log.d(TAG, "Botão gravar clicado")
                        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                                as MediaProjectionManager
                        projectionLauncher.launch(mgr.createScreenCaptureIntent())
                    },
                    modifier = Modifier.size(68.dp)
                ) {
                    Icon(Icons.Default.FiberManualRecord, null,
                        tint = Color(0xFFEF5350), modifier = Modifier.size(34.dp))
                }
            }
        }

        // ── Botão STOP em Popup seguro (não aparece no vídeo) ─────────────────
        if (gravando) {
            Popup(
                alignment = Alignment.BottomEnd,
                properties = PopupProperties(
                    securePolicy = SecureFlagPolicy.SecureOn,
                    excludeFromSystemGesture = true
                )
            ) {
                Row(
                    modifier = Modifier
                        .padding(end = 16.dp, bottom = 36.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            Log.d(TAG, "Botão STOP clicado, serviço=$gravacaoService")
                            val svc = gravacaoService
                            if (svc != null) {
                                svc.pararGravacao()
                            } else {
                                Log.w(TAG, "Serviço null ao parar — enviando intent direta")
                                val stopIntent = Intent(context, GravacaoService::class.java)
                                context.stopService(stopIntent)
                                gravando = false
                            }
                            if (servicoBound) runCatching { context.unbindService(serviceConnection) }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val pulse = rememberInfiniteTransition(label = "p")
                    val a by pulse.animateFloat(1f, 0.3f,
                        infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "a")
                    Box(Modifier.size(7.dp).background(Color(0xFFEF5350).copy(alpha = a), CircleShape))
                    Text(
                        "%02d:%02d".format(tempoGravacao / 60, tempoGravacao % 60),
                        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                    )
                    Box(
                        modifier = Modifier.size(30.dp).clip(CircleShape)
                            .background(Color(0xFFEF5350).copy(alpha = 0.85f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Stop, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // Confirmação vídeo salvo
        arquivoSalvo?.let {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter).padding(top = 16.dp)
                    .background(Color(0xFF1B5E20).copy(alpha = 0.95f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text("✅ Salvo em Galeria → Filmes → RunApp",
                    color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            LaunchedEffect(it) { delay(3000L); onVoltar() }
        }
    }
}

// ── Barras de velocidade ─────────────────────────────────────────────────────

@Composable
private fun BarrasVelocidade(vel: Float, modifier: Modifier) {
    val maxVel  = 25f
    val nBarras = 5
    val ratio   = (vel / maxVel).coerceIn(0f, 1f)
    val ativas  = (ratio * nBarras).roundToInt().coerceIn(0, nBarras)
    val cores   = listOf(
        Color(0xFF546E7A), Color(0xFF1565C0), Color(0xFF29B6F6),
        Color(0xFF26C6DA), Color(0xFF66BB6A), Color(0xFFFFA726)
    )
    val corAtiva = cores[ativas]

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            for (i in 1..nBarras) {
                val frac  = 0.3f + 0.7f * (i.toFloat() / nBarras)
                val ativa = i <= ativas
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(frac)
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(if (ativa) corAtiva else Color.White.copy(alpha = 0.18f))
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${vel.roundToInt()} km/h",
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = if (ativas > 0) corAtiva else Color.White.copy(alpha = 0.5f)
        )
    }
}

// ── HUD helpers ───────────────────────────────────────────────────────────────

@Composable
private fun HudGrande(valor: String, unidade: String, label: String) {
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

@Composable
private fun HudPequeno(label: String, valor: String) {
    Column {
        Text(label, fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
        Text(valor, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

private fun velocidadeDesPace(p: String): Float {
    if (p == "--:--") return 0f
    return runCatching {
        val parts = p.split(":")
        if (parts.size < 2) return 0f
        val s = parts[0].toFloat() * 60 + parts[1].toFloat()
        if (s <= 0f) 0f else 3600f / s
    }.getOrDefault(0f)
}
