package com.runapp.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import android.os.Looper
import androidx.compose.runtime.DisposableEffect

@Composable
fun CorridaScreen(
    eventId: Long,
    onFinalizar: () -> Unit,
    viewModel: CorridaViewModel = viewModel(factory = CorridaViewModel.Factory)
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()

    // Permissões
    var permissaoGps by remember { mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    )}
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> permissaoGps = granted }

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

    DisposableEffect(state.fase) {
        if (state.fase == FaseCorrida.CORRENDO && permissaoGps) {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateDistanceMeters(2f)
                .build()
            try {
                fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            } catch (e: SecurityException) { /* permissão negada */ }
        } else {
            fusedLocationClient.removeLocationUpdates(locationCallback)
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

    // Tela de permissão
    if (!permissaoGps) {
        PermissaoGpsScreen { launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
        return
    }

    // Tela de preparação
    if (state.fase == FaseCorrida.PREPARANDO) {
        PreparaCorrida(
            nomeTreino = state.passos.firstOrNull()?.nome ?: "Treino",
            totalPassos = state.passos.size,
            duracao = state.passos.sumOf { it.duracao },
            onIniciar = { viewModel.iniciarCorrida() }
        )
        return
    }

    // Tela principal de corrida
    Column(modifier = Modifier.fillMaxSize()) {

        // MAPA (metade superior)
        GoogleMap(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            cameraPositionState = cameraPositionState,
            uiSettings = MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = false),
            properties = MapProperties(isMyLocationEnabled = permissaoGps)
        ) {
            // Rota percorrida
            if (state.rota.size > 1) {
                Polyline(
                    points = state.rota.map { LatLng(it.lat, it.lng) },
                    color = Color(0xFF2196F3),
                    width = 10f
                )
            }
        }

        // PAINEL DE CORRIDA (metade inferior)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // Passo atual com barra de progresso e cor de zona
            state.passoAtual?.let { passo ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = corZona(passo.zona).copy(alpha = 0.15f)),
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = passo.nome,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                val paceTexto = if (passo.paceAlvoMin != "--:--")
                                    "🎯 ${passo.paceAlvoMin}–${passo.paceAlvoMax}/km"
                                else "Sem pace alvo"
                                Text(text = paceTexto, style = MaterialTheme.typography.bodySmall)
                            }
                            // Contador regressivo do passo
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(corZona(passo.zona)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = formatarTempoCompacto(state.tempoPassoRestante),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { state.progressoPasso },
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                            color = corZona(passo.zona),
                            trackColor = corZona(passo.zona).copy(alpha = 0.2f)
                        )
                        Text(
                            text = "Passo ${state.passoAtualIndex + 1} de ${state.passos.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            // Métricas principais
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MetricaItem("PACE\nATUAL", state.paceAtual + "\n/km")
                VerticalDivider(modifier = Modifier.height(50.dp))
                MetricaItem("DISTÂNCIA", "%.2f\nkm".format(state.distanciaMetros / 1000.0))
                VerticalDivider(modifier = Modifier.height(50.dp))
                MetricaItem("TEMPO", state.tempoFormatado + "\n")
            }

            // Botões de controle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Pausar / Retomar
                OutlinedButton(
                    onClick = {
                        if (state.fase == FaseCorrida.CORRENDO) viewModel.pausar()
                        else viewModel.retomar()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        if (state.fase == FaseCorrida.CORRENDO) Icons.Default.Pause
                        else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (state.fase == FaseCorrida.CORRENDO) "Pausar" else "Retomar")
                }

                // Finalizar
                var confirmarFim by remember { mutableStateOf(false) }
                Button(
                    onClick = { confirmarFim = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Finalizar")
                }

                if (confirmarFim) {
                    AlertDialog(
                        onDismissRequest = { confirmarFim = false },
                        title = { Text("Finalizar Corrida?") },
                        text = { Text("Tem certeza que quer encerrar a corrida agora?") },
                        confirmButton = {
                            Button(onClick = { viewModel.finalizarCorrida() }) { Text("Finalizar") }
                        },
                        dismissButton = {
                            OutlinedButton(onClick = { confirmarFim = false }) { Text("Continuar") }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MetricaItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            lineHeight = 14.sp
        )
    }
}

@Composable
fun PermissaoGpsScreen(onSolicitar: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("📍", fontSize = 64.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Permissão de GPS necessária", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text("O RunApp precisa de acesso à sua localização para rastrear sua corrida.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onSolicitar, modifier = Modifier.fillMaxWidth()) {
            Text("Conceder Permissão")
        }
    }
}

@Composable
fun PreparaCorrida(nomeTreino: String, totalPassos: Int, duracao: Int, onIniciar: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🏃", fontSize = 80.sp)
        Spacer(modifier = Modifier.height(24.dp))
        Text("Pronto para correr?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text(nomeTreino, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(4.dp))
        Text("$totalPassos passos • ${duracao / 60} minutos", style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onIniciar, modifier = Modifier.fillMaxWidth().height(60.dp)) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Iniciar Corrida", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun formatarTempoCompacto(segundos: Int): String {
    return if (segundos >= 60) "${segundos / 60}:${"%02d".format(segundos % 60)}"
    else "${segundos}s"
}
