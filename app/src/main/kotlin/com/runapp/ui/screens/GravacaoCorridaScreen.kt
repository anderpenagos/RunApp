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
        // 1. CÂMERA (CORRIGIDA)
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
                    } catch (e: Exception) { Log.e("Camera", "Erro bind", e) }
                }, ContextCompat.getMainExecutor(ctx))
                pv
            },
            modifier = Modifier.fillMaxSize()
        )

        // Gradiente sutil para leitura
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.4f)))))

        // --- HUD DE DADOS (LAYOUT FIEL À IMAGEM 2) ---
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 24.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.Start
        ) {
            // Distância
            HudGrande("%.2f".format(state.distanciaMetros / 1000.0).replace(".", ","), "Km", "Distance")
            
            Spacer(Modifier.height(24.dp))
            
            // Pace
            val paceDisplay = if (state.paceAtual == "--:--") "--' --\"" else {
                val p = state.paceAtual.split(":")
                if (p.size == 2) "${p[0]}' ${p[1]}\"" else state.paceAtual
            }
            HudGrande(paceDisplay, "", "Pace")
            
            Spacer(Modifier.height(40.dp))
            
            // Linha Inferior: Velocímetro + Dados Pequenos
            Row(verticalAlignment = Alignment.CenterVertically) {
                Velocimetro(velocidadeDesPace(state.paceAtual), Modifier.size(130.dp))
                
                Spacer(Modifier.width(24.dp))
                
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HudPequeno("Tempo", state.tempoFormatado)
                    HudPequeno("Pace médio", state.paceMedia)
                    if (state.cadencia > 0) HudPequeno("Cadência", "${state.cadencia} spm")
                }
            }
        }

        // 4. BOTÕES (Ocultos no vídeo via SecureOn)
        if (!gravando) {
            IconButton(onClick = onVoltar, modifier = Modifier.align(Alignment.TopStart).padding(16.dp).size(40.dp).background(Color.Black.copy(0.3f), CircleShape)) {
                Icon(Icons.Default.ArrowBack, null, tint = Color.White)
            }
            // Botão Iniciar (Centralizado embaixo)
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp).size(72.dp).clip(CircleShape).background(Color.Red).clickable {
                val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(mgr.createScreenCaptureIntent())
            }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.FiberManualRecord, null, tint = Color.White, modifier = Modifier.size(40.dp))
            }
        } else {
            // Botão Parar (Canto inferior direito, invisível no vídeo)
            Popup(alignment = Alignment.BottomEnd, properties = PopupProperties(securePolicy = SecureFlagPolicy.SecureOn)) {
                Box(Modifier.padding(bottom = 40.dp, end = 20.dp).size(110.dp, 60.dp).background(Color.Red, RoundedCornerShape(30.dp)).clickable {
                    val stopIntent = Intent(context, GravacaoService::class.java).apply { action = "com.runapp.STOP" }
                    context.startService(stopIntent)
                }, contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Stop, null, tint = Color.White, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("PARAR", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (arquivoSalvo != null) {
            Box(Modifier.align(Alignment.TopCenter).padding(20.dp).background(Color(0xFF1B5E20), RoundedCornerShape(8.dp)).padding(16.dp)) {
                Text("✅ Vídeo salvo na galeria!", color = Color.White)
            }
            LaunchedEffect(arquivoSalvo) { delay(3000L); arquivoSalvo = null }
        }
    }
}

@Composable
private fun HudGrande(valor: String, unidade: String, label: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(valor, fontSize = 72.sp, fontWeight = FontWeight.Bold, color = Color.White, lineHeight = 72.sp)
            if (unidade.isNotEmpty()) {
                Text(unidade, fontSize = 24.sp, color = Color.White, modifier = Modifier.padding(bottom = 12.dp, start = 6.dp))
            }
        }
        Text(label, fontSize = 14.sp, color = Color.White.copy(0.8f))
    }
}

@Composable
private fun HudPequeno(label: String, valor: String) {
    Column {
        Text(label, fontSize = 12.sp, color = Color.White.copy(0.7f))
        Text(valor, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
private fun Velocimetro(vel: Float, modifier: Modifier) {
    val ratio = (vel / 30f).coerceIn(0f, 1f)
    Box(modifier, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val sw = 9.dp.toPx()
            val pad = sw / 2 + 4.dp.toPx()
            val arcSize = Size(size.width - 2 * pad, size.height - 2 * pad)
            drawArc(Color.White.copy(0.2f), 150f, 240f, false, Offset(pad, pad), arcSize, style = Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            drawArc(Color(0xFF29B6F6), 150f, 240f * ratio, false, Offset(pad, pad), arcSize, style = Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(vel.roundToInt().toString(), fontSize = 38.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("KM/H", fontSize = 10.sp, color = Color.White.copy(0.6f))
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