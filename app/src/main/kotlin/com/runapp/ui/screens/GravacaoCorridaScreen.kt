package com.runapp.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
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
import androidx.compose.material3.*
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

    var cameraOk      by remember { mutableStateOf(false) }
    var audioOk       by remember { mutableStateOf(false) }
    var gravando      by remember { mutableStateOf(false) }
    var tempoGravacao by remember { mutableStateOf(0) }
    var arquivoSalvo  by remember { mutableStateOf<String?>(null) }
    var cameraFrontal by remember { mutableStateOf(false) }
    var zoomLevel     by remember { mutableStateOf(1.0f) }
    var cameraRef     by remember { mutableStateOf<Camera?>(null) }

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

    // Callback singleton
    DisposableEffect(Unit) {
        GravacaoService.onFinalizado = { nome ->
            gravando = false
            if (nome != null) arquivoSalvo = nome
        }
        onDispose { GravacaoService.onFinalizado = null }
    }

    LaunchedEffect(gravando) {
        tempoGravacao = 0
        while (gravando) { delay(1000L); tempoGravacao++ }
    }

    // Aplica zoom sempre que zoomLevel ou cameraRef mudar
    LaunchedEffect(zoomLevel, cameraRef) {
        cameraRef?.cameraControl?.setZoomRatio(zoomLevel)
    }

    // Launcher: passa resultCode + data para o serviço via Intent (abordagem padrão Android)
    // O serviço chama getMediaProjection() DEPOIS do startForeground() — requisito Android 14
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(TAG, "Resultado launcher: resultCode=${result.resultCode}")
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            val si = Intent(context, GravacaoService::class.java).apply {
                putExtra(GravacaoService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(GravacaoService.EXTRA_DATA, result.data)
                putExtra(GravacaoService.EXTRA_AUDIO_OK, audioOk)
            }
            ContextCompat.startForegroundService(context, si)
            gravando = true
        } else {
            Log.d(TAG, "Gravação cancelada pelo usuário")
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // Câmera
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
                                    val cam = prov.bindToLifecycle(lifecycleOwner, seletor,
                                        Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) })
                                    cameraRef = cam
                                    // Aplica zoom atual ao trocar câmera
                                    cam.cameraControl.setZoomRatio(zoomLevel)
                                    Log.d(TAG, "Câmera OK: ${if (cameraFrontal) "frontal" else "traseira"}")
                                }.onFailure { Log.e(TAG, "Câmera erro: ${it.message}") }
                            }, ContextCompat.getMainExecutor(ctx))
                        }
                    }
                )
            }
        }

        // Gradiente
        Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.65f).align(Alignment.BottomCenter)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f)))))

        // HUD — sempre visível no vídeo
        Column(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)
            .padding(start = 24.dp, end = 24.dp, bottom = 148.dp)) {
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

        // Controles
        if (!gravando) {
            IconButton(onClick = onVoltar,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 72.dp)
                    .size(44.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) { Icon(Icons.Default.ArrowBack, "Voltar", tint = Color.White) }

            IconButton(onClick = { cameraFrontal = !cameraFrontal; zoomLevel = 1.0f },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 72.dp)
                    .size(44.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) { Icon(Icons.Default.Cameraswitch, "Trocar câmera", tint = Color.White) }

            Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 68.dp)
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
            // Botão STOP — gravando=false imediatamente para feedback visual instantâneo
            Button(
                onClick = {
                    gravando = false
                    val svc = GravacaoService.instancia
                    if (svc != null) {
                        svc.pararGravacao()
                    } else {
                        context.stopService(Intent(context, GravacaoService::class.java))
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350)),
                shape  = RoundedCornerShape(28.dp),
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 72.dp).height(52.dp)
            ) {
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

        // Zoom — sempre visível na parte inferior, igual à câmera nativa
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(0.5f, 1.0f, 2.0f).forEach { nivel ->
                val selecionado = zoomLevel == nivel
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (selecionado) Color.White.copy(alpha = 0.2f) else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    TextButton(
                        onClick = { zoomLevel = nivel },
                        modifier = Modifier.size(38.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            text = if (nivel == 0.5f) ".5×" else "${nivel.toInt()}×",
                            fontSize = 13.sp,
                            fontWeight = if (selecionado) FontWeight.Bold else FontWeight.Normal,
                            color = if (selecionado) Color.Yellow else Color.White
                        )
                    }
                }
            }
        }

    }
}

@Composable private fun BarrasVelocidade(vel: Float, modifier: Modifier) {
    val nBarras = 5; val ativas = ((vel / 25f).coerceIn(0f,1f) * nBarras).roundToInt().coerceIn(0, nBarras)
    val cores = listOf(Color(0xFF546E7A), Color(0xFF1565C0), Color(0xFF29B6F6), Color(0xFF26C6DA), Color(0xFF66BB6A), Color(0xFFFFA726))
    Column(
        modifier = modifier, 
        verticalArrangement = Arrangement.Bottom, 
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f), 
            horizontalArrangement = Arrangement.spacedBy(5.dp), 
            verticalAlignment = Alignment.Bottom
        ) {
            for (i in 1..nBarras) Box(Modifier.weight(1f).fillMaxHeight(0.3f + 0.7f * i / nBarras)
                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                .background(if (i <= ativas) cores[ativas] else Color.White.copy(alpha = 0.18f)))
        }
        Spacer(Modifier.height(4.dp))
        Text("${vel.roundToInt()} km/h", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = if (ativas > 0) cores[ativas] else Color.White.copy(alpha = 0.5f))
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
        val pts = p.split(":"); if (pts.size < 2) return 0f
        val s = pts[0].toFloat() * 60 + pts[1].toFloat()
        if (s <= 0f) 0f else 3600f / s
    }.getOrDefault(0f)
}
