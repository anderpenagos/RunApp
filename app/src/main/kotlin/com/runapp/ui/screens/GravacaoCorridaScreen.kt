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

@Composable
fun GravacaoCorridaScreen(
    state: CorridaUiState,
    onVoltar: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var cameraOk by remember { mutableStateOf(false) }
    var gravando by remember { mutableStateOf(false) }
    var arquivoSalvo by remember { mutableStateOf<String?>(null) }
    var tempoSegundos by remember { mutableStateOf(0) }

    // Cronômetro (Não sai no vídeo)
    LaunchedEffect(gravando) {
        if (gravando) { tempoSegundos = 0; while (gravando) { delay(1000); tempoSegundos++ } }
    }

    val serviceConnection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val svc = (binder as? GravacaoService.LocalBinder)?.getService() ?: return
                svc.onFinalizado = { nome -> gravando = false; arquivoSalvo = nome }
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
    }

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            val intent = Intent(context, GravacaoService::class.java).apply {
                putExtra("result_code", result.resultCode)
                putExtra("projection_data", result.data)
            }
            ContextCompat.startForegroundService(context, intent)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            gravando = true
        }
    }

    LaunchedEffect(Unit) {
        cameraOk = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PermissionChecker.PERMISSION_GRANTED
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 1. CÂMERA
        if (cameraOk) {
            AndroidView(
                factory = { ctx ->
                    val pv = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        val provider = providerFuture.get()
                        val prev = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                        try {
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, prev)
                        } catch (e: Exception) { Log.e("Camera", "Erro", e) }
                    }, ContextCompat.getMainExecutor(ctx))
                    pv
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 2. INDICADOR DE GRAVAÇÃO (Invisível no vídeo)
        if (gravando) {
            Popup(alignment = Alignment.TopCenter, properties = PopupProperties(securePolicy = SecureFlagPolicy.SecureOn)) {
                Row(Modifier.padding(top = 40.dp).background(Color.Black.copy(0.5f), RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val alpha by rememberInfiniteTransition().animateFloat(1f, 0f, infiniteRepeatable(tween(500), RepeatMode.Reverse))
                    Box(Modifier.size(10.dp).background(Color.Red.copy(alpha = alpha), CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text("%02d:%02d".format(tempoSegundos / 60, tempoSegundos % 60), color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 3. HUD DE DADOS (Original)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.7f)))))
        
        Column(Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = 112.dp)) {
            HudGrande("%.2f".format(state.distanciaMetros / 1000.0).replace(".", ","), "Km", "Distance")
            Spacer(Modifier.height(12.dp))
            HudGrande(if (state.paceAtual == "--:--") "--' --\"" else state.paceAtual, "", "Pace")
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Velocimetro(velocidadeDesPace(state.paceAtual), Modifier.size(130.dp))
                Spacer(Modifier.width(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudPequeno("Tempo", state.tempoFormatado)
                    HudPequeno("Pace médio", state.paceMedia)
                }
            }
        }

        // 4. BOTÕES (Ocultos no vídeo)
        if (!gravando) {
            IconButton(onClick = onVoltar, modifier = Modifier.align(Alignment.TopStart).padding(16.dp).size(40.dp).background(Color.Black.copy(0.4f), CircleShape)) {
                Icon(Icons.Default.ArrowBack, null, tint = Color.White)
            }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp).size(72.dp).clip(CircleShape).background(Color.Red).clickable {
                val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(mgr.createScreenCaptureIntent())
            }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.FiberManualRecord, null, tint = Color.White, modifier = Modifier.size(40.dp))
            }
        } else {
            Popup(alignment = Alignment.BottomEnd, properties = PopupProperties(securePolicy = SecureFlagPolicy.SecureOn)) {
                Box(Modifier.padding(bottom = 40.dp, end = 20.dp).size(110.dp, 60.dp).background(Color.Red, RoundedCornerShape(30.dp)).clickable {
                    val intent = Intent(context, GravacaoService::class.java).apply { action = "com.runapp.STOP" }
                    context.startService(intent)
                }, contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Stop, null, tint = Color.White, modifier = Modifier.size(28.dp))
                        Text("PARAR", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        arquivoSalvo?.let {
            LaunchedEffect(it) { delay(3000L); onVoltar() }
            Box(Modifier.align(Alignment.TopCenter).padding(20.dp).background(Color(0xFF1B5E20), RoundedCornerShape(8.dp)).padding(16.dp)) {
                Text("✅ Vídeo salvo com sucesso!", color = Color.White)
            }
        }
    }
}

@Composable
private fun HudGrande(valor: String, unidade: String, label: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(valor, fontSize = 60.sp, fontWeight = FontWeight.Bold, color = Color.White)
        if (unidade.isNotEmpty()) Text(unidade, fontSize = 22.sp, color = Color.White.copy(0.7f), modifier = Modifier.padding(bottom = 8.dp, start = 4.dp))
    }
    Text(label.uppercase(), fontSize = 12.sp, color = Color.White.copy(0.5f))
}

@Composable
private fun HudPequeno(label: String, valor: String) {
    Column {
        Text(label.uppercase(), fontSize = 10.sp, color = Color.White.copy(0.5f))
        Text(valor, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

@Composable
private fun Velocimetro(vel: Float, modifier: Modifier) {
    val ratio = (vel / 30f).coerceIn(0f, 1f)
    Box(modifier, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val sw = 9.dp.toPx()
            val pad = sw / 2 + 6.dp.toPx()
            val arcSize = Size(size.width - 2 * pad, size.height - 2 * pad)
            val st = Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawArc(Color.White.copy(0.15f), 150f, 240f, false, Offset(pad, pad), arcSize, style = st)
            drawArc(androidx.compose.ui.graphics.lerp(Color(0xFF29B6F6), Color(0xFF66BB6A), ratio), 150f, 240f * ratio, false, Offset(pad, pad), arcSize, style = st)
        }
        Text(vel.roundToInt().toString(), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
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