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
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Close
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
    // ACTIVITY_RECOGNITION: necessário no Android 10+ para TYPE_STEP_DETECTOR
    // e obrigatório para foregroundServiceType="health" no Android 14+.
    // Sem ele, startForeground() lança SecurityException → app fecha ao dar Play.
    var permissaoAtividade by remember {
        mutableStateOf(PermissionHelper.hasActivityRecognitionPermission(context))
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
                permissaoAtividade = PermissionHelper.hasActivityRecognitionPermission(context)
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

    // ACTIVITY_RECOGNITION — solicitado como parte do fluxo de permissões.
    // O usuário pode negar; nesse caso a cadência ficará desativada, mas o GPS funciona.
    val activityRecognitionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissaoAtividade = permissions.values.all { it }
        // Se negou, não bloqueia a corrida — apenas cadência desativada
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
            // Pede ACTIVITY_RECOGNITION em paralelo (Android 10+)
            if (!permissaoAtividade && PermissionHelper.ACTIVITY_RECOGNITION_PERMISSION.isNotEmpty()) {
                activityRecognitionLauncher.launch(PermissionHelper.ACTIVITY_RECOGNITION_PERMISSION)
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
        // Também pede ACTIVITY_RECOGNITION se ainda não foi concedida
        if (!permissaoAtividade && PermissionHelper.ACTIVITY_RECOGNITION_PERMISSION.isNotEmpty()) {
            activityRecognitionLauncher.launch(PermissionHelper.ACTIVITY_RECOGNITION_PERMISSION)
        }
    }

    // Solicitar exclusão de otimização de bateria ao entrar na tela.
    // Sem isso, fabricantes como Xiaomi/Samsung podem matar o service de GPS
    // em corridas longas mesmo com WakeLock e foreground service ativos.
    val batteryOptLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* apenas aguarda — não há retorno verificável aqui, o sistema decide */ }

    LaunchedEffect(Unit) {
        if (!PermissionHelper.isBatteryOptimizationIgnored(context)) {
            try {
                batteryOptLauncher.launch(PermissionHelper.batteryOptimizationIntent(context))
            } catch (e: Exception) {
                android.util.Log.w("CorridaScreen", "Não foi possível abrir diálogo de otimização de bateria: ${e.message}")
            }
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
        // 1. Sem permissão — prioridade crítica
        !permissaoGps -> "⚠️ Sem permissão GPS" to Color(0xFFFF6B6B)
        // 2. Instante exato do Play (tempo ainda zerado) — GPS estava OK, não deve "piscar"
        // Precisa vir antes do !gpsLocalizou para blindar a transição de estado
        state.fase == FaseCorrida.CORRENDO && state.tempoTotalSegundos == 0L ->
            "🚀 Iniciando..." to Color(0xFF4ECDC4)
        // 3. Perda total de sinal (posicaoAtual sumiu completamente)
        !gpsLocalizou -> "🔍 Buscando sinal GPS..." to Color(0xFFFFBE0B)
        // 4. Antes do Play — verde tranquilo
        state.fase == FaseCorrida.PREPARANDO -> "✅ GPS OK (Pronto para iniciar)" to Color(0xFF4ECDC4)
        // 5. Primeiros pontos após o Play — GPS OK, rota ainda acumulando
        state.fase == FaseCorrida.CORRENDO && pontosColetados < 5 -> "🚀 Iniciando gravação..." to Color(0xFF4ECDC4)
        // 6. Corrida normal
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
    // FIX 1 (evolução do fix de performance): Heatmap em background thread
    //
    // Versão anterior: remember(state.rota) — evitava recalcular a cada segundo
    //   (tick do timer), mas ainda rodava na Main Thread quando um novo ponto GPS
    //   chegava. Para uma maratona com 5000+ pontos, o cálculo de 5000 haversines
    //   + mesclagem de polylines na main thread causaria "engasgo" (jank) perceptível.
    //
    // FIX DEFINITIVO: LaunchedEffect(state.rota) + withContext(Dispatchers.Default).
    //   - O cálculo roda em uma thread de CPU do pool do Kotlin Coroutines.
    //   - A main thread fica 100% livre para animar, renderizar e responder a toques.
    //   - polylinesProcessadas é um State<> que dispara recomposição apenas quando
    //     o resultado fica pronto — nunca durante o processamento.
    //   - Em corridas muito longas (maratona: ~5000 pontos), o cálculo leva ~5-15ms
    //     em Dispatchers.Default, imperceptível ao usuário.
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    var polylinesProcessadas by remember { mutableStateOf<List<Pair<List<LatLng>, Color>>>(emptyList()) }
    // Quantos pontos da rota já foram incorporados nas polylines atuais.
    var ultimoIndexProcessado by remember { mutableStateOf(0) }

    // Reset completo quando a fase volta para PREPARANDO (nova corrida iniciada)
    LaunchedEffect(state.fase) {
        if (state.fase == FaseCorrida.PREPARANDO) {
            polylinesProcessadas = emptyList()
            ultimoIndexProcessado = 0
        }
    }

    // FIX 2 — POLYLINE INCREMENTAL:
    // Antes: recalculava TODOS os N segmentos a cada novo ponto GPS (O(N) por tick).
    // Depois: guarda `ultimoIndexProcessado` e calcula APENAS os pontos novos (O(1) amortizado).
    //
    // Chave = rota.size: dispara apenas quando um novo ponto chega — o tick do timer
    // muda tempoFormatado mas NÃO muda rota.size, então não há trabalho desnecessário.
    //
    // Casos especiais:
    //   • rota.size < ultimoIndexProcessado → rota substituída (recovery): recalculo total (1x)
    //   • fase mudou para PREPARANDO         → reset via LaunchedEffect acima
    LaunchedEffect(state.rota.size) {
        val rota = state.rota

        when {
            rota.size < 2 -> {
                polylinesProcessadas = emptyList()
                ultimoIndexProcessado = 0
            }
            rota.size < ultimoIndexProcessado -> {
                // RESET: rota foi substituída inteira (recovery de process death)
                // Recalcula tudo de uma vez — acontece no máximo uma vez por sessão
                val resultado = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    mesclarPolylinesPorCor(calcularSegmentosHeatmap(rota))
                }
                polylinesProcessadas = resultado
                ultimoIndexProcessado = rota.size
            }
            else -> {
                // CAMINHO NORMAL: processa apenas os pontos novos desde a última passagem.
                // Overlap de 1 ponto (novoInicio - 1) garante que o segmento de conexão
                // entre o último ponto antigo e o primeiro novo seja calculado.
                val novoInicio = (ultimoIndexProcessado - 1).coerceAtLeast(0)
                if (novoInicio >= rota.size - 1) return@LaunchedEffect

                val subRota = rota.subList(novoInicio, rota.size)
                val novosSegmentos = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    mesclarPolylinesPorCor(calcularSegmentosHeatmap(subRota))
                }
                ultimoIndexProcessado = rota.size

                if (novosSegmentos.isEmpty()) return@LaunchedEffect

                // Mescla os novos segmentos na lista existente.
                // Se a cor do primeiro novo segmento coincide com a do último existente,
                // estende essa polyline (equivalente ao addPoint). Caso contrário, appenda.
                val result = polylinesProcessadas.toMutableList()
                for ((pontos, cor) in novosSegmentos) {
                    if (result.isNotEmpty() && coresSimilares(result.last().second, cor)) {
                        val ultima = result.last()
                        // drop(1): o 1º ponto do novo segmento é idêntico ao último da polyline
                        // existente (overlap de 1) — evita duplicata de vértice
                        result[result.lastIndex] = Pair(ultima.first + pontos.drop(1), cor)
                    } else {
                        result.add(Pair(pontos, cor))
                    }
                }
                polylinesProcessadas = result
            }
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // FIX: Timeout no overlay "Restaurando sua corrida..."
    //
    // BUG ORIGINAL: o overlay ficava visível indefinidamente se:
    //   - O service morreu durante a corrida (OOM killer, crash de coroutine, etc.)
    //   - A posição GPS demorou mais de 10s para chegar após reconexão
    //   - qualquer outra falha no fluxo de restauração
    // O usuário ficava preso numa tela preta com spinner sem nenhuma saída.
    //
    // FIX: após 15 segundos sem conseguir restaurar, exibir botão de escape com
    // opção de encerrar a corrida e salvar os dados disponíveis (do backup) ou
    // iniciar uma nova corrida.
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    var overlayExpirado by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(15_000)
        overlayExpirado = true
    }
    // Reseta o timeout se a restauração concluir antes dos 15s
    LaunchedEffect(cameraSnapRealizado) {
        if (cameraSnapRealizado) overlayExpirado = false
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // CONTROLE DO MAPA — padrão Strava/Nike Run Club
    //
    // Pré-corrida (PREPARANDO): mapa sempre visível para confirmar localização GPS.
    // Ao dar Play: mapa destruído, transição para métricas fullscreen.
    // Durante corrida: usuário pode abrir mapa temporariamente via botão de mapa.
    // Ao fechar: mapa destruído de novo → zero GPU quando não visível.
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    var mapaVisivel by remember { mutableStateOf(false) }

    // Fecha mapa automaticamente ao iniciar a corrida (PREPARANDO → CORRENDO)
    var faseAnterior by remember { mutableStateOf(state.fase) }
    LaunchedEffect(state.fase) {
        if (faseAnterior == FaseCorrida.PREPARANDO && state.fase == FaseCorrida.CORRENDO) {
            mapaVisivel = false
        }
        faseAnterior = state.fase
    }

    // Mapa existe na composição apenas quando necessário:
    // • Antes do Play (confirmar GPS)   → sempre
    // • Durante corrida                 → só se usuário abriu explicitamente
    val mostrarMapa = state.fase == FaseCorrida.PREPARANDO || mapaVisivel
    Column(modifier = Modifier
        .fillMaxSize()
        // Fundo preto no container mais externo — elimina o flash da tela Home
        // que aparecia por 1-2 frames antes da máscara preta do Box interior carregar.
        .background(Color(0xFF121212))
    ) {
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

            // ── MAPA ────────────────────────────────────────────────────────────
            // Só existe na composição quando realmente necessário.
            // Quando mostrarMapa = false, o GoogleMap é destruído → zero GPU/memória.
            if (mostrarMapa) {
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
                        polylinesProcessadas.forEach { (pontos, cor) ->
                            Polyline(points = pontos, color = cor, width = 12f)
                        }
                        if (state.rota.size == 1) {
                            Circle(
                                center = LatLng(state.rota[0].lat, state.rota[0].lng),
                                radius = 5.0,
                                fillColor = Color(0xFF00BCD4),
                                strokeColor = Color(0xFF00BCD4)
                            )
                        }
                    }
                }

                // Botão "Fechar mapa" — só aparece durante corrida ativa (não em PREPARANDO)
                if (state.fase == FaseCorrida.CORRENDO || state.fase == FaseCorrida.PAUSADO) {
                    IconButton(
                        onClick = { mapaVisivel = false },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 8.dp, end = 8.dp)
                            .background(Color(0xFF1E1E1E).copy(alpha = 0.85f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Fechar mapa",
                            tint = Color.White
                        )
                    }

                    // Mini-barra de métricas no fundo do mapa (para não perder referência)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 90.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF1E1E1E).copy(alpha = 0.92f)
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            MetricaMiniMapa("TEMPO",    state.tempoFormatado)
                            VerticalDivider(modifier = Modifier.height(32.dp), color = Color.White.copy(alpha = 0.15f))
                            MetricaMiniMapa("DISTÂNCIA", "%.2f km".format(state.distanciaMetros / 1000))
                            VerticalDivider(modifier = Modifier.height(32.dp), color = Color.White.copy(alpha = 0.15f))
                            MetricaMiniMapa("PACE",     state.paceMedia + "/km")
                        }
                    }
                }
            }

            // ── MÉTRICAS FULLSCREEN ─────────────────────────────────────────────
            // Visível durante corrida ativa quando o mapa está fechado.
            // Animação: slide de baixo para cima ao abrir; fade out ao abrir mapa.
            androidx.compose.animation.AnimatedVisibility(
                visible = (state.fase == FaseCorrida.CORRENDO || state.fase == FaseCorrida.PAUSADO) && !mapaVisivel,
                enter = androidx.compose.animation.fadeIn(
                    animationSpec = androidx.compose.animation.core.tween(350)
                ) + androidx.compose.animation.slideInVertically(
                    animationSpec = androidx.compose.animation.core.tween(350)
                ) { it / 4 },
                exit = androidx.compose.animation.fadeOut(
                    animationSpec = androidx.compose.animation.core.tween(200)
                )
            ) {
                MetricasFullscreen(
                    state        = state,
                    onAbrirMapa  = { mapaVisivel = true },
                    onPausar     = { viewModel.pausar() },
                    onRetomar    = { viewModel.retomar() },
                    onFinalizar  = { viewModel.finalizarCorrida() },
                    onPularPasso = { viewModel.pularPasso() },
                    onVoltarPasso = { viewModel.voltarPasso() }
                )
            }

            // ── PASSO ATUAL (overlay no topo — visível com mapa aberto) ────────
            // Quando o mapa está aberto durante corrida, o card de passo ainda aparece
            // para o atleta não perder o contexto do treino estruturado.
            if (mostrarMapa && (state.fase == FaseCorrida.CORRENDO || state.fase == FaseCorrida.PAUSADO)) {
                state.passoAtual?.let { passo ->
                    val alphaUI by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = if (cameraSnapRealizado && state.treino != null) 1f else 0f,
                        animationSpec = androidx.compose.animation.core.tween(durationMillis = 400),
                        label = "alphaUIMap"
                    )
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .padding(start = 16.dp, end = 56.dp, top = 8.dp) // end = espaço pro botão fechar
                            .alpha(alphaUI),
                        colors = CardDefaults.cardColors(
                            containerColor = corZona(passo.zona).copy(alpha = 0.9f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { viewModel.voltarPasso() }) {
                                Icon(
                                    Icons.Default.SkipPrevious,
                                    contentDescription = "Voltar passo",
                                    tint = Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f).padding(horizontal = 2.dp)) {
                                Text(passo.nome, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                if (!passo.isDescanso) {
                                    Text(
                                        text = "🎯 ${passo.paceAlvoMin}—${passo.paceAlvoMax}/km",
                                        fontSize = 12.sp, color = Color.White.copy(alpha = 0.9f)
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { state.progressoPasso },
                                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp)).padding(top = 4.dp),
                                    color = Color.White,
                                    trackColor = Color.White.copy(alpha = 0.3f)
                                )
                                Text(
                                    text = "${state.tempoPassoRestante}s",
                                    fontSize = 11.sp, color = Color.White.copy(alpha = 0.9f),
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            IconButton(onClick = { viewModel.pularPasso() }) {
                                Icon(
                                    Icons.Default.SkipNext,
                                    contentDescription = "Pular passo",
                                    tint = Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ── OVERLAY DE TRANSIÇÃO (carregamento/restauração) ─────────────────
            val posicaoGpsValida = state.posicaoAtual?.let {
                Math.abs(it.lat) > 1.0 && Math.abs(it.lng) > 1.0
            } ?: false

            val mostrarOverlay = when (state.fase) {
                FaseCorrida.PREPARANDO -> state.treino == null
                else -> state.treino == null || !cameraSnapRealizado || !posicaoGpsValida
            } && mostrarMapa  // overlay só faz sentido quando mapa está visível

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
                        .pointerInput(Unit) {},
                    contentAlignment = Alignment.Center
                ) {
                    if (mostrarOverlay) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .alpha(overlayAlpha)
                                .padding(horizontal = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            if (!overlayExpirado) {
                                CircularProgressIndicator(color = Color(0xFF4ECDC4), modifier = Modifier.size(48.dp))
                                Text(
                                    text = when {
                                        state.treino == null -> "Carregando treino..."
                                        state.fase == FaseCorrida.PREPARANDO -> "Carregando treino..."
                                        else -> "Restaurando sua corrida..."
                                    },
                                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium
                                )
                            } else {
                                Text("⚠️", fontSize = 40.sp)
                                Text("Não foi possível restaurar a corrida", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                                Text("O sinal GPS pode estar fraco ou o serviço de rastreamento foi interrompido.", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, textAlign = TextAlign.Center)
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(onClick = { viewModel.finalizarCorrida() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4ECDC4)), modifier = Modifier.fillMaxWidth()) {
                                    Text("Encerrar e salvar dados", fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(onClick = { viewModel.carregarTreino(eventId) }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                                    Text("Tentar novamente")
                                }
                                TextButton(onClick = { viewModel.resetarCorrida(); onSair() }) {
                                    Text("Descartar corrida", color = Color(0xFFFF6B6B))
                                }
                            }
                        }
                    }
                }
            }

            // ── BOTÕES PRÉ-CORRIDA (Play) ──────────────────────────────────────
            // Só aparecem na fase PREPARANDO, no fundo da tela de mapa
            androidx.compose.animation.AnimatedVisibility(
                visible = state.fase == FaseCorrida.PREPARANDO,
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (state.erro != null && state.treino == null) {
                        Card(
                            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFB71C1C).copy(alpha = 0.92f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("⚠️ ${state.erro}", color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center)
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(onClick = { viewModel.carregarTreino(eventId) }) {
                                    Text("Tentar novamente", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    if (state.treino != null) {
                        FloatingActionButton(
                            onClick = {
                                when {
                                    !permissaoAtividade && PermissionHelper.ACTIVITY_RECOGNITION_PERMISSION.isNotEmpty() ->
                                        activityRecognitionLauncher.launch(PermissionHelper.ACTIVITY_RECOGNITION_PERMISSION)
                                    !permissaoGps -> {
                                        Toast.makeText(context, "Conceda permissões de GPS primeiro", Toast.LENGTH_SHORT).show()
                                        permissionLauncher.launch(PermissionHelper.LOCATION_PERMISSIONS)
                                    }
                                    else -> viewModel.iniciarCorrida()
                                }
                            },
                            containerColor = Color(0xFF4CAF50),
                            modifier = Modifier.size(72.dp)
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Iniciar", tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                    } else if (state.erro == null) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(48.dp))
                    }
                }
            }

            // ── BOTÕES DURANTE CORRIDA COM MAPA ABERTO ─────────────────────────
            if (mapaVisivel && (state.fase == FaseCorrida.CORRENDO || state.fase == FaseCorrida.PAUSADO)) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    if (state.fase == FaseCorrida.CORRENDO) {
                        FloatingActionButton(onClick = { viewModel.pausar() }, containerColor = Color(0xFFFF9800)) {
                            Icon(Icons.Default.Pause, contentDescription = "Pausar", tint = Color.White)
                        }
                    } else {
                        FloatingActionButton(onClick = { viewModel.retomar() }, containerColor = Color(0xFF4CAF50)) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Retomar", tint = Color.White)
                        }
                    }
                    FloatingActionButton(onClick = { viewModel.finalizarCorrida() }, containerColor = Color(0xFFF44336)) {
                        Icon(Icons.Default.Stop, contentDescription = "Finalizar", tint = Color.White)
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
 * FIX: Mescla segmentos consecutivos da mesma cor em Polylines únicas.
 *
 * Antes: ~2399 Polylines individuais de 2 pontos cada (um por par de GPS).
 * Depois: ~dezenas de Polylines, uma por transição de cor (mudança de pace).
 *
 * Reduz dramaticamente o número de composables dentro do GoogleMap,
 * acelerando a recomposição de O(N pontos) para O(N mudanças de pace).
 *
 * Retorna lista de pares (pontos da polyline, cor).
 */
private fun mesclarPolylinesPorCor(
    segmentos: List<SegmentoHeatmap>
): List<Pair<List<LatLng>, Color>> {
    if (segmentos.isEmpty()) return emptyList()

    val resultado = mutableListOf<Pair<List<LatLng>, Color>>()
    var corAtual = segmentos[0].cor
    var pontosAtual = mutableListOf(segmentos[0].inicio, segmentos[0].fim)

    for (i in 1 until segmentos.size) {
        val seg = segmentos[i]
        // Duas cores são "iguais" se a diferença perceptível é mínima
        // Usa tolerância para não criar uma Polyline por cada tiny mudança de pace
        if (coresSimilares(seg.cor, corAtual)) {
            // Mesma cor: estende a polyline atual adicionando apenas o ponto final
            // (o ponto inicial é idêntico ao ponto final anterior)
            pontosAtual.add(seg.fim)
        } else {
            // Mudou a cor: fecha a polyline atual e inicia nova
            resultado.add(Pair(pontosAtual.toList(), corAtual))
            corAtual = seg.cor
            pontosAtual = mutableListOf(seg.inicio, seg.fim)
        }
    }

    // Fecha a última polyline
    if (pontosAtual.size >= 2) {
        resultado.add(Pair(pontosAtual.toList(), corAtual))
    }

    return resultado
}

/**
 * Verifica se duas cores são perceptualmente similares (tolerância de ~10%).
 * Evita criar novas Polylines para variações mínimas de pace que não são
 * visíveis a olho nu no mapa.
 */
private fun coresSimilares(a: Color, b: Color, tolerancia: Float = 0.08f): Boolean {
    return Math.abs(a.red - b.red) < tolerancia &&
           Math.abs(a.green - b.green) < tolerancia &&
           Math.abs(a.blue - b.blue) < tolerancia
}

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
 *
 * NOTE: Esta função é SEMPRE chamada dentro de remember(state.rota) — nunca
 * diretamente no corpo do composable. Isso garante que roda apenas quando
 * a rota muda (novo ponto GPS), não a cada recomposição do timer/pace/cadência.
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

        // FIX 1 — GAP DE DISTÂNCIA ("Linha Reta"):
        // Se o intervalo entre dois pontos consecutivos for > 30s, o usuário provavelmente
        // correu uma curva enquanto o app estava morto (process death, GPS perdido, etc.).
        // Conectar esses dois pontos com uma linha reta produziria: (a) uma "linha fantasma"
        // sobre o mapa ignorando o percurso real, e (b) um pace instantâneo errado para
        // aquele segmento (muito lento, pois a distância em linha reta < distância real).
        // Solução: skip do segmento → a polyline simplesmente não fecha o gap visual.
        // O trecho reaparece normalmente quando os próximos pontos chegam em sequência normal.
        val GAP_MAXIMO_MS = 30_000L  // 30s: gap maior que isso = segmento interrompido
        if (dtMs > GAP_MAXIMO_MS) continue

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

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Tela de métricas fullscreen — exibida durante a corrida (sem mapa)
// Padrão Strava/Nike: números grandes, mínimo de distração, foco no ritmo.
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@Composable
private fun MetricasFullscreen(
    state: com.runapp.ui.viewmodel.CorridaUiState,
    onAbrirMapa:  () -> Unit,
    onPausar:     () -> Unit,
    onRetomar:    () -> Unit,
    onFinalizar:  () -> Unit,
    onPularPasso: () -> Unit,
    onVoltarPasso: () -> Unit
) {
    Box(
        modifier = androidx.compose.ui.Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
    ) {
        // ── Card do passo atual (topo) ─────────────────────────────────────
        state.passoAtual?.let { passo ->
            Card(
                modifier = androidx.compose.ui.Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = corZona(passo.zona).copy(alpha = 0.92f)
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.fase == FaseCorrida.CORRENDO || state.fase == FaseCorrida.PAUSADO) {
                        IconButton(onClick = onVoltarPasso) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Voltar passo",
                                tint = Color.White.copy(alpha = 0.85f), modifier = androidx.compose.ui.Modifier.size(26.dp))
                        }
                    }
                    Column(
                        modifier = androidx.compose.ui.Modifier.weight(1f).padding(horizontal = 4.dp)
                    ) {
                        Text(passo.nome, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        if (!passo.isDescanso) {
                            Text("🎯 ${passo.paceAlvoMin}—${passo.paceAlvoMax}/km", fontSize = 13.sp, color = Color.White.copy(alpha = 0.9f))
                        }
                        Spacer(androidx.compose.ui.Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { state.progressoPasso },
                            modifier = androidx.compose.ui.Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)),
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.3f)
                        )
                        Text("${state.tempoPassoRestante}s restantes", fontSize = 11.sp, color = Color.White.copy(alpha = 0.9f),
                            modifier = androidx.compose.ui.Modifier.padding(top = 3.dp))
                    }
                    if (state.fase == FaseCorrida.CORRENDO || state.fase == FaseCorrida.PAUSADO) {
                        IconButton(onClick = onPularPasso) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Próximo passo",
                                tint = Color.White.copy(alpha = 0.85f), modifier = androidx.compose.ui.Modifier.size(26.dp))
                        }
                    }
                }
            }
        }

        // ── Auto-pause banner ──────────────────────────────────────────────
        if (state.autoPausado) {
            Surface(
                modifier = androidx.compose.ui.Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(
                        horizontal = 16.dp,
                        vertical = if (state.passoAtual != null) 108.dp else 16.dp
                    ),
                color = Color(0xFFFFBE0B).copy(alpha = 0.95f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⏸️ Auto-pause • Aguardando movimento...", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }

        // ── Métricas centrais (números grandes) ────────────────────────────
        Column(
            modifier = androidx.compose.ui.Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            // TEMPO — métrica principal, fonte maior
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = state.tempoFormatado,
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = (-2).sp
                )
                Text("TEMPO", fontSize = 11.sp, color = Color.White.copy(alpha = 0.45f), letterSpacing = 2.sp)
            }

            // DISTÂNCIA + PACE MÉDIO
            Row(
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MetricaGrande(
                    label = "DISTÂNCIA",
                    value = "%.2f".format(state.distanciaMetros / 1000),
                    unit = "km"
                )
                VerticalDivider(
                    modifier = androidx.compose.ui.Modifier.height(64.dp),
                    color = Color.White.copy(alpha = 0.12f)
                )
                MetricaGrande(
                    label = "PACE MÉDIO",
                    value = state.paceMedia,
                    unit = "/km"
                )
            }

            // PACE ATUAL + CADÊNCIA (linha extra, só com dados)
            if (state.paceAtual != "--:--" || state.cadencia > 0) {
                Row(
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    MetricaGrande(
                        label = "PACE ATUAL",
                        value = state.paceAtual,
                        unit = "/km",
                        destaque = true
                    )
                    if (state.cadencia > 0) {
                        VerticalDivider(
                            modifier = androidx.compose.ui.Modifier.height(64.dp),
                            color = Color.White.copy(alpha = 0.12f)
                        )
                        MetricaGrande(
                            label = "CADÊNCIA",
                            value = state.cadencia.toString(),
                            unit = "spm"
                        )
                    }
                }
            }
        }

        // ── Botão mapa (canto inferior direito, acima dos controles) ───────
        IconButton(
            onClick = onAbrirMapa,
            modifier = androidx.compose.ui.Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 120.dp, end = 16.dp)
                .background(Color(0xFF2A2A2A), CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.Map,
                contentDescription = "Ver mapa",
                tint = Color.White.copy(alpha = 0.7f)
            )
        }

        // ── Botões de controle (fundo) ─────────────────────────────────────
        Row(
            modifier = androidx.compose.ui.Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp)
                .padding(horizontal = 48.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.fase == FaseCorrida.CORRENDO) {
                // PAUSAR — botão maior, destaque
                FloatingActionButton(
                    onClick = onPausar,
                    containerColor = Color(0xFFFF9800),
                    modifier = androidx.compose.ui.Modifier.size(64.dp)
                ) {
                    Icon(Icons.Default.Pause, contentDescription = "Pausar", tint = Color.White, modifier = androidx.compose.ui.Modifier.size(28.dp))
                }
                // PARAR — botão menor, secundário
                FloatingActionButton(
                    onClick = onFinalizar,
                    containerColor = Color(0xFF3A3A3A),
                    modifier = androidx.compose.ui.Modifier.size(52.dp)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Finalizar", tint = Color(0xFFFF6B6B), modifier = androidx.compose.ui.Modifier.size(22.dp))
                }
            } else {
                // RETOMAR — destaque
                FloatingActionButton(
                    onClick = onRetomar,
                    containerColor = Color(0xFF4CAF50),
                    modifier = androidx.compose.ui.Modifier.size(64.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Retomar", tint = Color.White, modifier = androidx.compose.ui.Modifier.size(28.dp))
                }
                // FINALIZAR — secundário
                FloatingActionButton(
                    onClick = onFinalizar,
                    containerColor = Color(0xFF3A3A3A),
                    modifier = androidx.compose.ui.Modifier.size(52.dp)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Finalizar", tint = Color(0xFFFF6B6B), modifier = androidx.compose.ui.Modifier.size(22.dp))
                }
            }
        }
    }
}

/** Métrica grande para a tela fullscreen */
@Composable
private fun MetricaGrande(
    label: String,
    value: String,
    unit: String,
    destaque: Boolean = false
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                fontSize = 38.sp,
                fontWeight = FontWeight.Bold,
                color = if (destaque) Color(0xFF4ECDC4) else Color.White
            )
            if (unit.isNotEmpty()) {
                Text(
                    text = " $unit",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.5f),
                    modifier = androidx.compose.ui.Modifier.padding(bottom = 6.dp)
                )
            }
        }
        Text(label, fontSize = 10.sp, color = Color.White.copy(alpha = 0.4f), letterSpacing = 1.5.sp)
    }
}

/** Mini métrica para a barra no fundo do mapa durante corrida */
@Composable
private fun MetricaMiniMapa(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(label, fontSize = 9.sp, color = Color.White.copy(alpha = 0.4f), letterSpacing = 1.sp)
    }
}
