package com.runapp.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    onSair: () -> Unit = {},
    viewModel: CorridaViewModel = viewModel(factory = CorridaViewModel.Factory)
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Gerenciamento de Permissões
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    var permissaoGps by remember {
        mutableStateOf(PermissionHelper.hasLocationPermissions(context))
    }
    var permissaoBackground by remember {
        mutableStateOf(PermissionHelper.hasBackgroundLocationPermission(context))
    }

    // Re-checa as permissões sempre que a tela volta ao foco (onResume).
    // O remember inicial executa antes da Activity estar completamente pronta,
    // então pode retornar false mesmo com permissão concedida — o observer corrige isso.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissaoGps = PermissionHelper.hasLocationPermissions(context)
                permissaoBackground = PermissionHelper.hasBackgroundLocationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Passo 3: pedir localização em background (após ter localização em primeiro plano)
    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissaoBackground = permissions.values.all { it }
        // Não bloqueia o app se negar — apenas GPS com tela bloqueada não vai funcionar
    }

    // Passo 2: pedir localização em primeiro plano
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
            // Só pede background location DEPOIS de ter a localização em primeiro plano
            // Android exige essa ordem
            if (!permissaoBackground && PermissionHelper.BACKGROUND_LOCATION_PERMISSION.isNotEmpty()) {
                backgroundLocationLauncher.launch(PermissionHelper.BACKGROUND_LOCATION_PERMISSION)
            }
        }
    }

    // Passo 1: pedir notificação (Android 13+), depois localização
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Independente da resposta, segue pedindo localização
        if (!permissaoGps) {
            permissionLauncher.launch(PermissionHelper.LOCATION_PERMISSIONS)
        }
    }

    // Solicitar permissões ao entrar na tela, na ordem correta
    LaunchedEffect(Unit) {
        when {
            // Primeiro notificação (Android 13+)
            !PermissionHelper.hasNotificationPermission(context) &&
            PermissionHelper.NOTIFICATION_PERMISSION.isNotEmpty() -> {
                notificationLauncher.launch(PermissionHelper.NOTIFICATION_PERMISSION)
            }
            // Depois localização em primeiro plano
            !permissaoGps -> {
                permissionLauncher.launch(PermissionHelper.LOCATION_PERMISSIONS)
            }
            // Por último, localização em background
            !permissaoBackground && PermissionHelper.BACKGROUND_LOCATION_PERMISSION.isNotEmpty() -> {
                backgroundLocationLauncher.launch(PermissionHelper.BACKGROUND_LOCATION_PERMISSION)
            }
        }
    }

    // Status GPS calculado em tempo real como derived state — sem LaunchedEffect.
    // Qualquer mudança em permissaoGps, posicaoAtual, fase ou rota.size
    // dispara recomposição automática e atualiza o banner instantaneamente.
    val pontosColetados = state.rota.size
    val gpsLocalizou = state.posicaoAtual != null
    val (statusGps, corBanner) = when {
        !permissaoGps -> "⚠️ Sem permissão GPS" to Color(0xFFFF6B6B)
        !gpsLocalizou -> "🔍 Buscando sinal GPS..." to Color(0xFFFFBE0B)
        state.fase == FaseCorrida.PREPARANDO -> "✅ GPS OK (Pronto para iniciar)" to Color(0xFF4ECDC4)
        pontosColetados < 10 -> "📡 Sinal GPS fraco ($pontosColetados pontos)" to Color(0xFFFFBE0B)
        else -> "✅ GPS OK ($pontosColetados pontos)" to Color(0xFF4ECDC4)
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

    // GPS liga assim que a tela abre (se houver permissão) — não espera o Play.
    // Isso garante que o mapa já mostra a posição real antes de iniciar a corrida,
    // e elimina o "mapa da África" quando o app é reaberto pela notificação.
    DisposableEffect(permissaoGps) {
        if (permissaoGps) {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateDistanceMeters(0f)
                .build()
            try {
                fusedLocationClient.requestLocationUpdates(
                    request,
                    locationCallback,
                    Looper.getMainLooper()
                )
                android.util.Log.d("CorridaScreen", "✅ GPS iniciado na abertura da tela")
            } catch (e: SecurityException) {
                android.util.Log.e("CorridaScreen", "❌ Erro GPS: ${e.message}")
                Toast.makeText(
                    context,
                    "Erro ao acessar GPS. Verifique as permissões.",
                    Toast.LENGTH_LONG
                ).show()
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

    // Diálogo de confirmação ao tentar sair durante a corrida
    var mostrarDialogoSair by remember { mutableStateOf(false) }

    // Interceptar botão voltar enquanto corrida ativa
    BackHandler(enabled = state.fase == FaseCorrida.CORRENDO || state.fase == FaseCorrida.PAUSADO) {
        mostrarDialogoSair = true
    }

    // Diálogo de confirmação
    if (mostrarDialogoSair) {
        AlertDialog(
            onDismissRequest = { mostrarDialogoSair = false },
            title = { Text("Corrida em andamento") },
            text = { Text("A corrida continua rodando em segundo plano. Deseja mesmo sair sem finalizar?") },
            confirmButton = {
                TextButton(onClick = { mostrarDialogoSair = false }) {
                    Text("Continuar correndo")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    mostrarDialogoSair = false
                    viewModel.pausar()
                    onSair()
                }) {
                    Text("Sair (pausar)", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }

    // Câmera do mapa
    // Usa o último ponto conhecido da rota como posição inicial — se existir.
    // Isso evita que o mapa apareça em (0,0) / "África" ao reabrir pelo notificação,
    // pois a posição já vem preenchida antes do LaunchedEffect assíncrono disparar.
    val posicaoInicial = remember(state.rota) {
        state.rota.lastOrNull()?.let { LatLng(it.lat, it.lng) }
            ?: state.posicaoAtual?.let { LatLng(it.lat, it.lng) }
            ?: LatLng(-23.55, -46.63) // São Paulo como fallback neutro
    }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(posicaoInicial, 16f)
    }
    // cameraSnapRealizado só vira true APÓS delay pós-snap.
    // O delay garante que o Google Maps terminou de renderizar o tile correto
    // antes de a máscara preta abrir — elimina qualquer frame de "África" visível.
    var cameraSnapRealizado by remember { mutableStateOf(false) }
    LaunchedEffect(state.posicaoAtual) {
        state.posicaoAtual?.let { pos ->
            if (Math.abs(pos.lat) > 1.0 && Math.abs(pos.lng) > 1.0) {
                val destino = LatLng(pos.lat, pos.lng)
                if (!cameraSnapRealizado) {
                    // SNAP instantâneo: sem animação, sem "viagem" pelo oceano
                    cameraPositionState.position = CameraPosition.fromLatLngZoom(destino, 17f)
                    // Aguarda o Google Maps renderizar os tiles corretos antes de abrir a cortina
                    kotlinx.coroutines.delay(600)
                    cameraSnapRealizado = true
                } else {
                    // Seguimentos suaves durante a corrida
                    cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(destino, 17f))
                }
            }
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // ✨ NOVO: Interface com Indicador de GPS
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    Column(modifier = Modifier.fillMaxSize()) {
        // Indicador de status GPS no topo
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = corBanner
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
            // Mapa invisível até o snap estar concluído — o alpha animado impede que
            // qualquer frame de África/oceano vaze por baixo da máscara preta.
            val alphaMap by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (cameraSnapRealizado) 1f else 0f,
                animationSpec = androidx.compose.animation.core.tween(durationMillis = 400),
                label = "alphaMap"
            )
            Box(modifier = Modifier.fillMaxSize().alpha(alphaMap)) {
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
                // Mapa de calor: segmentos coloridos por pace
                // Verde (lento/Z1) -> Amarelo (moderado/Z3) -> Vermelho (rapido/Z6+)
                if (state.rota.size >= 2) {
                    val segmentos = calcularSegmentosHeatmap(state.rota)
                    segmentos.forEach { seg ->
                        Polyline(
                            points = listOf(seg.inicio, seg.fim),
                            color = seg.cor,
                            width = 12f
                        )
                    }
                } else if (state.rota.size == 1) {
                    Circle(
                        center = LatLng(state.rota[0].lat, state.rota[0].lng),
                        radius = 5.0,
                        fillColor = Color(0xFF00BCD4),
                        strokeColor = Color(0xFF00BCD4)
                    )
                }
            }
            } // Fim do Box alpha do mapa

            // UI de métricas, máscara e botões — tudo ganha alpha junto com o mapa
            // para que nenhum elemento apareça "solto" sobre a África durante a transição.
            val alphaUI by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (cameraSnapRealizado) 1f else 0f,
                animationSpec = androidx.compose.animation.core.tween(durationMillis = 400),
                label = "alphaUI"
            )
            Box(modifier = Modifier.fillMaxSize().alpha(alphaUI)) {

            // Informações no topo (apenas passo atual)
            state.passoAtual?.let { passo ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(16.dp),
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
                                text = "🎯 ${passo.paceAlvoMin}—${passo.paceAlvoMax}/km",
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
            
            // Indicador de auto-pause no topo (se ativo)
            if (state.autoPausado) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 16.dp, vertical = if (state.passoAtual != null) 100.dp else 16.dp),
                    color = Color(0xFFFFBE0B).copy(alpha = 0.95f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "⏸️ Auto-pause • Aguardando movimento...",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // Painel de métricas EMBAIXO (card preto)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .padding(bottom = 80.dp) // Espaço para os botões
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF2C2C2C).copy(alpha = 0.95f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Grid 2x2 com métricas
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Distância
                            MetricaCompacta(
                                label = "DISTÂNCIA",
                                value = "%.2f".format(state.distanciaMetros / 1000),
                                unit = "km",
                                modifier = Modifier.weight(1f)
                            )
                            
                            // Tempo
                            MetricaCompacta(
                                label = "TEMPO",
                                value = state.tempoFormatado,
                                unit = "",
                                modifier = Modifier.weight(1f)
                            )
                        }
                        
                        Divider(color = Color.White.copy(alpha = 0.2f))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Pace atual
                            MetricaCompacta(
                                label = "PACE ATUAL",
                                value = state.paceAtual,
                                unit = "/km",
                                modifier = Modifier.weight(1f)
                            )
                            
                            // Pace médio
                            MetricaCompacta(
                                label = "PACE MÉDIO",
                                value = state.paceMedia,
                                unit = "/km",
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
            } // Fim do Box alphaUI (métricas)

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // MÁSCARA DE TRANSIÇÃO: esconde mapa até câmera estar no lugar certo.
            // Fica FORA do alphaUI para manter alpha 1 enquanto carrega.
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            val mostrarOverlay = state.treino == null || !cameraSnapRealizado

            val overlayAlpha by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (mostrarOverlay) 1f else 0f,
                animationSpec = androidx.compose.animation.core.tween(durationMillis = 800),
                label = "overlayAlpha"
            )

            if (overlayAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF121212).copy(alpha = overlayAlpha))
                        // Bloqueia toques na UI por baixo enquanto a máscara está visível
                        .pointerInput(Unit) {},
                    contentAlignment = Alignment.Center
                ) {
                    if (mostrarOverlay) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.alpha(overlayAlpha)
                        ) {
                            CircularProgressIndicator(
                                color = Color(0xFF4ECDC4),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (state.treino == null) "Carregando treino..." else "Restaurando sua corrida...",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
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
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // Erro de rede: só exibe se estivermos em PREPARANDO e sem treino.
                        // Se a fase já for CORRENDO/PAUSADO, o service tem os dados — ignora.
                        if (state.erro != null && state.treino == null && state.fase == FaseCorrida.PREPARANDO) {
                            Card(
                                modifier = Modifier
                                    .padding(horizontal = 24.dp)
                                    .padding(bottom = 16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFFB71C1C).copy(alpha = 0.92f)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "⚠️ ${state.erro}",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    TextButton(onClick = { viewModel.carregarTreino(eventId) }) {
                                        Text("Tentar novamente", color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        // Botão iniciar: só aparece quando o treino já está carregado
                        if (state.treino != null) {
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
                        } else if (state.erro == null) {
                            // Carregando: spinner enquanto aguarda treino ou service
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(48.dp)
                            )
                        }
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
private fun MetricaCompacta(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.6f),
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = value,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            if (unit.isNotEmpty()) {
                Text(
                    text = unit,
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(start = 2.dp, bottom = 2.dp)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Mapa de Calor — Segmentos coloridos por pace
// ─────────────────────────────────────────────────────────────────────────────

private data class SegmentoHeatmap(
    val inicio: LatLng,
    val fim: LatLng,
    val cor: Color
)

/**
 * Divide a lista de pontos GPS em segmentos ponto-a-ponto e atribui uma cor
 * baseada no pace instantâneo de cada segmento.
 *
 * Escala de cores (gradiente contínuo):
 *   pace >= 7:00/km  → Verde    (Z1 — muito lento)
 *   pace ~  5:30/km  → Amarelo  (Z2/Z3 — moderado)
 *   pace ~  4:30/km  → Laranja  (Z4 — limiar)
 *   pace <= 3:30/km  → Vermelho (Z6/Z7 — sprint)
 *
 * Pontos com timestamp idêntico ou distância < 1 m são ignorados para evitar
 * divisão por zero e picos de pace irreais.
 */
private fun calcularSegmentosHeatmap(
    rota: List<com.runapp.data.model.LatLngPonto>
): List<SegmentoHeatmap> {
    if (rota.size < 2) return emptyList()

    // Limites da escala em s/km (pace mais lento → mais rápido)
    val PACE_LENTO  = 7 * 60.0   // 7:00/km → cor verde
    val PACE_RAPIDO = 3 * 60.0 + 30.0  // 3:30/km → cor vermelha

    val resultado = mutableListOf<SegmentoHeatmap>()

    for (i in 0 until rota.size - 1) {
        val p1 = rota[i]
        val p2 = rota[i + 1]

        val dtMs = p2.tempo - p1.tempo
        if (dtMs <= 0) continue  // timestamp igual ou invertido → pula

        val distM = haversineMetros(p1.lat, p1.lng, p2.lat, p2.lng)
        if (distM < 1.0) continue  // pontos praticamente iguais → pula

        // Pace em s/km
        val paceSkm = (dtMs / 1000.0) / distM * 1000.0

        // Pace fora de faixa realista (< 2:00/km ou > 20:00/km) → ignora spike
        if (paceSkm < 120.0 || paceSkm > 1200.0) continue

        val cor = corPorPace(paceSkm, PACE_RAPIDO, PACE_LENTO)

        resultado.add(
            SegmentoHeatmap(
                inicio = LatLng(p1.lat, p1.lng),
                fim    = LatLng(p2.lat, p2.lng),
                cor    = cor
            )
        )
    }

    return resultado
}

/**
 * Interpola uma cor entre vermelho (pace rápido) e verde (pace lento).
 * t=0 → vermelho (#F44336), t=0.5 → laranja/amarelo, t=1 → verde (#4CAF50)
 */
private fun corPorPace(paceSkm: Double, paceRapido: Double, paceLento: Double): Color {
    // Normaliza: 0.0 = mais rápido (vermelho), 1.0 = mais lento (verde)
    val t = ((paceSkm - paceRapido) / (paceLento - paceRapido)).coerceIn(0.0, 1.0).toFloat()

    // Gradiente em 4 paradas: vermelho → laranja → amarelo → verde
    return when {
        t < 0.33f -> {
            // Vermelho → Laranja
            val local = t / 0.33f
            lerp(Color(0xFFF44336), Color(0xFFFF9800), local)
        }
        t < 0.66f -> {
            // Laranja → Amarelo
            val local = (t - 0.33f) / 0.33f
            lerp(Color(0xFFFF9800), Color(0xFFFFEB3B), local)
        }
        else -> {
            // Amarelo → Verde
            val local = (t - 0.66f) / 0.34f
            lerp(Color(0xFFFFEB3B), Color(0xFF4CAF50), local)
        }
    }
}

/** Interpolação linear entre duas cores Compose */
private fun lerp(a: Color, b: Color, t: Float): Color = Color(
    red   = a.red   + (b.red   - a.red)   * t,
    green = a.green + (b.green - a.green) * t,
    blue  = a.blue  + (b.blue  - a.blue)  * t,
    alpha = 1f
)

/** Distância entre dois pontos GPS em metros (fórmula de Haversine) */
private fun haversineMetros(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2).let { it * it }
    return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}
