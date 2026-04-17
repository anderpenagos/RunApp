package com.runapp.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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

    // ── Estado local ─────────────────────────────────────────────────────────
    var gravando      by remember { mutableStateOf(false) }
    var tempoGravacao by remember { mutableStateOf(0) }
    var arquivoSalvo  by remember { mutableStateOf<String?>(null) }
    var cameraFrontal by remember { mutableStateOf(false) }

    // Registra callback no singleton — não precisa de bind
    DisposableEffect(Unit) {
        GravacaoService.onFinalizado = { nome ->
            Log.d(TAG, "onFinalizado: $nome")
            gravando = false
            if (nome != null) {
                arquivoSalvo = nome
                Toast.makeText(context, "✅ Vídeo salvo: $nome.mp4", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "❌ Erro — vídeo não salvo", Toast.LENGTH_LONG).show()
            }
        }
        onDispose {
            GravacaoService.onFinalizado = null
        }
    }

    LaunchedEffect(gravando) {
        tempoGravacao = 0
        while (gravando) { delay(1000L); tempoGravacao++ }
    }

    // ── Lança dialog de captura de tela ──────────────────────────────────────
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(TAG, "Resultado: resultCode=${result.resultCode}")
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            val si = Intent(context, GravacaoService::class.java).apply {
                putExtra(GravacaoService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(GravacaoService.EXTRA_DATA, result.data)
                putExtra(GravacaoService.EXTRA_AUDIO_OK, audioOk)
            }
            ContextCompat.startForegroundService(context, si)
            gravando = true
            Toast.makeText(context, "⏺ Gravando…", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "startForegroundService chamado")
        } else {
            Toast.makeText(context, "Gravação cancelada", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Layout ────────────────────────────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // Câmera — key() recria quando troca frontal/traseira
        if (cameraOk) {
            val seletor = if (cameraFrontal) CameraSelector.DEFAULT_FRONT_CAMERA
                          else CameraSelector.DEFAULT_BACK_CAMERA
            key(cameraFrontal) {
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
                                    prov.bindToLifecycle(lifecycleOwner, seletor,
                                        Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) })
                                    Log.d(TAG, "✅ Câmera: ${if (cameraFrontal) "frontal" else "traseira"}")
                                }.onFailure { Log.e(TAG, "❌ Câmera: ${it.message}") }
                            }, ContextCompat.getMainExecutor(ctx))
                        }
                    }
                )
            }
        }

        // Gradiente inferior
        Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.65f).align(Alignment.BottomCenter)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))))
        )

        // HUD — visível no vídeo
        Column(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)
            .padding(start = 24.dp, end = 24.dp, bottom = 100.dp)
        ) {
            HudGrande("%.2f".format(state.distanciaMetros / 1000.0).replace(".", ","), "Km", "Distance")
            Spacer(Modifier.height(12.dp))
            HudGrande(state.paceAtual.let { p ->
                if (p == "--:--") "--' --\""
                else p.split(":").let { if (it.size == 2) "${it[0]}' ${it[1]}\"" else p }
            }, "", "Pace")
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BarrasVelocidade(velocidadeDesPace(state.paceAtual), Modifier.size(width = 80.dp, height = 100.dp))
                Spacer(Modifier.width(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudPequeno("Tempo", state.tempoFormatado)
                    HudPequeno("Pace médio", state.paceMedia)
                    if (state.cadencia > 0) HudPequeno("Cadência", "${state.cadencia} spm")
                }
            }
        }

        // ── Controles (visíveis sempre fora da gravação) ──────────────────────
        if (!gravando) {
            // Botão voltar — inferior esquerdo
            IconButton(onClick = onVoltar,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 36.dp)
                    .size(44.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) { Icon(Icons.Default.ArrowBack, "Voltar", tint = Color.White) }

            // Botão trocar câmera — inferior direito
            IconButton(onClick = { cameraFrontal = !cameraFrontal },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 36.dp)
                    .size(44.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) { Icon(Icons.Default.Cameraswitch, "Trocar câmera", tint = Color.White) }

            // Botão gravar — central
            Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
                .size(68.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = {
                    val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    projectionLauncher.launch(mgr.createScreenCaptureIntent())
                }, modifier = Modifier.size(68.dp)) {
                    Icon(Icons.Default.FiberManualRecord, "Gravar",
                        tint = Color(0xFFEF5350), modifier = Modifier.size(34.dp))
                }
            }
        } else {
            // ── BOTÃO STOP — grande, vermelho, sem bind ───────────────────────
            // Acessa GravacaoService.instancia diretamente (singleton estático)
            Button(
                onClick = {
                    Log.d(TAG, "STOP clicado. instancia=${GravacaoService.instancia}, gravando=${GravacaoService.gravando}")
                    val svc = GravacaoService.instancia
                    if (svc != null) {
                        svc.pararGravacao()
                    } else {
                        Log.e(TAG, "instancia null — forçando stop via stopService")
                        context.stopService(Intent(context, GravacaoService::class.java))
                        gravando = false
                        Toast.makeText(context, "Gravação parada", Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350)),
                shape  = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 36.dp)
                    .height(52.dp)
            ) {
                // Indicador pulsante
                val pulse = rememberInfiniteTransition(label = "p")
                val a by pulse.animateFloat(1f, 0.4f,
                    infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "a")
                Box(Modifier.size(8.dp).background(Color.White.copy(alpha = a), CircleShape))
                Spacer(Modifier.width(6.dp))
                Text("%02d:%02d".format(tempoGravacao / 60, tempoGravacao % 60),
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Default.Stop, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }

        // Confirmação vídeo salvo
        arquivoSalvo?.let {
            Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp)
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

// ── Barras de velocidade ──────────────────────────────────────────────────────

@Composable
private fun BarrasVelocidade(vel: Float, modifier: Modifier) {
    val maxVel = 25f; val nBarras = 5
    val ratio  = (vel / maxVel).coerceIn(0f, 1f)
    val ativas  = (ratio * nBarras).roundToInt().coerceIn(0, nBarras)
    val cores  = listOf(Color(0xFF546E7A), Color(0xFF1565C0), Color(0xFF29B6F6), Color(0xFF26C6DA), Color(0xFF66BB6A), Color(0xFFFFA726))
    val cor    = cores[ativas]
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for (i in 1..nBarras) {
                Box(Modifier.weight(1f).fillMaxHeight(0.3f + 0.7f * i / nBarras)
                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                    .background(if (i <= ativas) cor else Color.White.copy(alpha = 0.18f)))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("${vel.roundToInt()} km/h", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = if (ativas > 0) cor else Color.White.copy(alpha = 0.5f))
    }
}

@Composable private fun HudGrande(valor: String, unidade: String, label: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(valor, fontSize = 60.sp, fontWeight = FontWeight.Bold, color = Color.White, lineHeight = 60.sp)
        if (unidade.isNotEmpty()) { Spacer(Modifier.width(6.dp))
            Text(unidade, fontSize = 22.sp, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(bottom = 8.dp)) }
    }
    Text(label, fontSize = 13.sp, color = Color.White.copy(alpha = 0.55f))
}

@Composable private fun HudPequeno(label: String, valor: String) {
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
