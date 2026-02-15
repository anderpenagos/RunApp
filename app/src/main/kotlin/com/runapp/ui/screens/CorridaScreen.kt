package com.runapp.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import com.runapp.data.model.LatLngPonto
import com.runapp.ui.theme.corZona
import com.runapp.ui.viewmodel.CorridaViewModel
import com.runapp.ui.viewmodel.FaseCorrida
import com.runapp.util.PermissionHelper

@Composable
fun CorridaScreen(
    eventId: Long,
    onFinalizar: () -> Unit,
    viewModel: CorridaViewModel = viewModel(factory = CorridaViewModel.Factory)
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // ✨ NOVO: Gerenciamento de Permissões GPS
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    var permissaoGps by remember { 
        mutableStateOf(PermissionHelper.hasLocationPermissions(context)) 
    }

    var statusGps by remember { mutableStateOf("Buscando GPS...") }
    var pontosColetados by remember { mutableStateOf(0) }

    // Launcher para solicitar permissões
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissaoGps = permissions.values.all { it }
        if (!permissaoGps) {
            Toast.makeText(
                context,
                "⚠️ Permissões de GPS são necessárias para rastrear sua corrida",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(
                context,
                "✅ Permissões concedidas! Aguarde o sinal GPS...",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Solicitar permissões ao iniciar a tela
    LaunchedEffect(Unit) {
        if (!permissaoGps) {
            permissionLauncher.launch(PermissionHelper.LOCATION_PERMISSIONS)
        }
    }

    // Atualizar status do GPS baseado nos pontos coletados
    LaunchedEffect(state.rota.size) {
        pontosColetados = state.rota.size
        statusGps = when {
            !permissaoGps -> "⚠️ Sem permissão GPS"
            pontosColetados == 0 -> "🔍 Buscando sinal GPS..."
            pontosColetados < 10 -> "📡 Sinal GPS fraco ($pontosColetados pontos)"
            else -> "✅ GPS OK ($pontosColetados pontos)"
        }
    }
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    // Carregar treino ao entrar na tela
    LaunchedEffect(eventId) {
        viewModel.carregarTreino(eventId)
    }

    // Iniciar GPS quando permissão concedida e corrida iniciada
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val locationCallback = remember {
        object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { viewModel.onNovaLocalizacao(it) }
            }
        }
    }

    DisposableEffect(state.fase, permissaoGps) {  // ← Adicionado permissaoGps
        if (state.fase == FaseCorrida.CORRENDO && permissaoGps) {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateDistanceMeters(0f)
                .build()
            try {
                fusedLocationClient.requestLocationUpdates(
                    request, 
                    locationCallback, 
                    Looper.getMainLooper()
                )
                android.util.Log.d("CorridaScreen", "✅ GPS iniciado com sucesso")
            } catch (e: SecurityException) {
                android.util.Log.e("CorridaScreen", "❌ Erro GPS: ${e.message}")
                Toast.makeText(
                    context,
                    "Erro ao acessar GPS. Verifique as permissões.",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            if (!permissaoGps && state.fase == FaseCorrida.CORRENDO) {
                android.util.Log.w("CorridaScreen", "⚠️ GPS não iniciado - sem permissão")
            }
        }
        onDispose {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    // Navegar ao finalizar
    LaunchedEffect(state.fase) {
        if (state.fase == FaseCorrida.FINALIZADO) onFinalizar()
    }

    // Câmera do mapa
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(-23.55, -46.63), 15f)
    }
    LaunchedEffect(state.posicaoAtual) {
        state.posicaoAtual?.let { pos ->
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(LatLng(pos.lat, pos.lng), 16f)
            )
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // ✨ NOVO: Interface com Indicador de GPS
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    Column(modifier = Modifier.fillMaxSize()) {
        // Indicador de status GPS no topo
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = when {
                !permissaoGps -> Color(0xFFFF6B6B)  // Vermelho - sem permissão
                pontosColetados < 10 -> Color(0xFFFFBE0B)  // Amarelo - sinal fraco
                else -> Color(0xFF4ECDC4)  // Verde - GPS OK
            }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = statusGps,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                
                // Mostrar botão para reabrir permissões se negadas
                if (!permissaoGps) {
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = { 
                            permissionLauncher.launch(PermissionHelper.LOCATION_PERMISSIONS)
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = Color.White
                        )
                    ) {
                        Text("PERMITIR", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        
        // Resto da interface (mapa + controles)
        Box(modifier = Modifier.fillMaxSize()) {
            // Mapa
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(isMyLocationEnabled = permissaoGps),
                uiSettings = MapUiSettings(
                    compassEnabled = true,
                    myLocationButtonEnabled = false,
                    zoomControlsEnabled = false
                )
            ) {
                // Polilinha da rota
                if (state.rota.isNotEmpty()) {
                    Polyline(
                        points = state.rota.map { LatLng(it.lat, it.lng) },
                        color = Color(0xFF00BCD4),
                        width = 10f
                    )
                }
            }

            // Painel de informações
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
            ) {
                // Card de métricas
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.95f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Distância
                        MetricaRow(
                            label = "Distância",
                            value = "%.2f km".format(state.distanciaMetros / 1000),
                            icon = "🏃"
                        )
                        
                        // Tempo
                        MetricaRow(
                            label = "Tempo",
                            value = state.tempoFormatado,
                            icon = "⏱️"
                        )
                        
                        // Pace atual
                        MetricaRow(
                            label = "Pace Atual",
                            value = state.paceAtual,
                            icon = "📊"
                        )
                        
                        // Pace médio
                        MetricaRow(
                            label = "Pace Médio",
                            value = state.paceMedia,
                            icon = "📈"
                        )

                        // Indicador de auto-pause
                        if (state.autoPausado) {
                            Divider(modifier = Modifier.padding(vertical = 4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "⏸️ Pausado Automaticamente",
                                    color = Color(0xFFFFBE0B),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Passo atual
                state.passoAtual?.let { passo ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = corZona(passo.zona).copy(alpha = 0.9f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = passo.nome,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            if (!passo.isDescanso) {
                                Text(
                                    text = "Pace alvo: ${passo.paceAlvoMin} - ${passo.paceAlvoMax}",
                                    fontSize = 14.sp,
                                    color = Color.White.copy(alpha = 0.9f)
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Barra de progresso
                            LinearProgressIndicator(
                                progress = { state.progressoPasso },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.3f)
                            )
                            
                            Text(
                                text = "${state.tempoPassoRestante}s restantes",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }

            // Controles de corrida
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                AnimatedVisibility(
                    visible = state.fase == FaseCorrida.PREPARANDO,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    FloatingActionButton(
                        onClick = { 
                            if (permissaoGps) {
                                viewModel.iniciarCorrida()
                            } else {
                                Toast.makeText(
                                    context,
                                    "Conceda permissões de GPS primeiro",
                                    Toast.LENGTH_SHORT
                                ).show()
                                permissionLauncher.launch(PermissionHelper.LOCATION_PERMISSIONS)
                            }
                        },
                        containerColor = Color(0xFF4CAF50),
                        modifier = Modifier.size(72.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Iniciar",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                AnimatedVisibility(
                    visible = state.fase == FaseCorrida.CORRENDO,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        FloatingActionButton(
                            onClick = { viewModel.pausar() },
                            containerColor = Color(0xFFFF9800)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Pause,
                                contentDescription = "Pausar",
                                tint = Color.White
                            )
                        }
                        FloatingActionButton(
                            onClick = { viewModel.finalizarCorrida() },
                            containerColor = Color(0xFFF44336)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Finalizar",
                                tint = Color.White
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = state.fase == FaseCorrida.PAUSADO,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        FloatingActionButton(
                            onClick = { viewModel.retomar() },
                            containerColor = Color(0xFF4CAF50)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Retomar",
                                tint = Color.White
                            )
                        }
                        FloatingActionButton(
                            onClick = { viewModel.finalizarCorrida() },
                            containerColor = Color(0xFFF44336)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Finalizar",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricaRow(
    label: String,
    value: String,
    icon: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = icon, fontSize = 20.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                fontSize = 14.sp,
                color = Color.Gray
            )
        }
        Text(
            text = value,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
