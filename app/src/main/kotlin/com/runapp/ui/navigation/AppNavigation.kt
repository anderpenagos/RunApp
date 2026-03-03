package com.runapp.ui.navigation

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.runapp.service.RunningService
import kotlinx.coroutines.flow.first
import com.runapp.ui.screens.*
import com.runapp.ui.viewmodel.ConfigViewModel
import com.runapp.ui.viewmodel.CorridaViewModel
import com.runapp.ui.viewmodel.FaseCorrida

/** Estado do feedback do Coach na tela de detalhe de atividade. */
sealed class CoachUiState {
    object Inativo    : CoachUiState()
    object Carregando : CoachUiState()
    data class Pronto(val texto: String) : CoachUiState()
    data class Erro(val mensagem: String) : CoachUiState()
}

sealed class Screen(val route: String) {
    object Config      : Screen("config")
    object Home        : Screen("home")
    object Treinos     : Screen("treinos")
    object Historico   : Screen("historico")
    object DetalheAtividade : Screen("detalhe_atividade/{arquivoGpx}") {
        fun criarRota(arquivoGpx: String) = "detalhe_atividade/$arquivoGpx"
    }
    object DetalheTreino : Screen("detalhe/{eventId}") {
        fun criarRota(eventId: Long) = "detalhe/$eventId"
    }
    object Corrida : Screen("corrida/{eventId}") {
        fun criarRota(eventId: Long) = "corrida/$eventId"
    }
    object Resumo : Screen("resumo")
}

/**
 * Estado de carregamento para DetalheAtividade.
 * Distingue Carregando / Erro / Sucesso — sem isso um GPX corrompido
 * ou deletado deixa o spinner infinito na tela.
 */
private sealed class DetalheEstado {
    object Carregando : DetalheEstado()
    object Erro       : DetalheEstado()
    data class Sucesso(
        val corrida : com.runapp.data.model.CorridaHistorico,
        val rota    : List<com.runapp.data.model.LatLngPonto>
    ) : DetalheEstado()
}

@Composable
fun AppNavigation(notificationIntent: Intent? = null) {
    val navController = rememberNavController()
    val configViewModel: ConfigViewModel = viewModel(factory = ConfigViewModel.Factory)
    val configState by configViewModel.uiState.collectAsState()

    // CorridaViewModel vive no escopo da Activity — sobrevive à navegação
    val activity = LocalContext.current as androidx.activity.ComponentActivity
    val corridaViewModel: CorridaViewModel = viewModel(
        viewModelStoreOwner = activity,
        factory = CorridaViewModel.Factory
    )

    val corridaState by corridaViewModel.uiState.collectAsState()

    val corridaAtiva = corridaState.fase == FaseCorrida.CORRENDO || corridaState.fase == FaseCorrida.PAUSADO
    val eventoId = corridaState.treino?.id

    // Leitura SÍNCRONA do intent — não depende de collectAsState nem de remember.
    // O Intent já está na memória no frame 0, antes de qualquer Flow emitir.
    val idViaIntentExtra = notificationIntent?.let {
        if (it.action == RunningService.ACTION_SHOW_RUNNING)
            it.getLongExtra(RunningService.EXTRA_EVENT_ID, -1L)
        else -1L
    } ?: -1L

    // TRAVA DE INICIALIZAÇÃO: enquanto o Room não terminou de ler as credenciais,
    // não deixamos o NavHost tomar decisões. Sem isso, isConfigured==false no frame 0
    // manda o usuário para a Config mesmo que ele já tenha credenciais salvas.
    // A exceção é a notificação: o intent é síncrono e já sabemos o destino.
    if (configState.isLoading && idViaIntentExtra == -1L) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF121212)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = Color(0xFF4ECDC4))
        }
        return
    }

    // startDestination calculado UMA SÓ VEZ. Prioridade:
    // 1. Intent da notificação (síncrono, frame 0)
    // 2. Corrida restaurada pelo Service
    // 3. Configuração incompleta (só avaliado APÓS isLoading=false)
    // 4. Home
    val startDestination = remember {
        when {
            idViaIntentExtra != -1L -> Screen.Corrida.criarRota(idViaIntentExtra)
            corridaState.fase != FaseCorrida.PREPARANDO -> {
                val id = corridaState.treino?.id ?: -1L
                if (id != -1L) Screen.Corrida.criarRota(id) else Screen.Home.route
            }
            !configState.isConfigured -> Screen.Config.route
            else -> Screen.Home.route
        }
    }

    var processandoNavegacao by remember { mutableStateOf(false) }

    // rotaAtual como estado reativo: quando a rota muda, o LaunchedEffect re-executa
    // e a guarda "já está na corrida" tem o valor garantidamente atual.
    val backStackEntry by navController.currentBackStackEntryAsState()
    val rotaAtual = backStackEntry?.destination?.route

    LaunchedEffect(notificationIntent, corridaAtiva, eventoId, rotaAtual) {
        if (processandoNavegacao) return@LaunchedEffect

        // TRAVA DE SEGURANÇA: se já estamos na tela de corrida ou resumo,
        // ignoramos qualquer redirecionamento automático (da Home ou da notificação).
        // Isso evita que o NavController resete a pilha de telas e feche a corrida.
        val jaEstaNaCorrida = rotaAtual?.startsWith("corrida") == true
        val jaEstaNoResumo  = rotaAtual?.startsWith("resumo")  == true

        if (jaEstaNaCorrida || jaEstaNoResumo) {
            // Apenas limpa o intent de notificação para não reprocessar
            notificationIntent?.action = null
            return@LaunchedEffect
        }

        if (notificationIntent?.action == RunningService.ACTION_SHOW_RUNNING) {
            val idFromNotif = notificationIntent.getLongExtra(RunningService.EXTRA_EVENT_ID, -1L)
            val finalId = if (idFromNotif != -1L) idFromNotif else eventoId
            if (finalId != null && finalId != -1L) {
                processandoNavegacao = true
                android.util.Log.d("AppNav", "🎯 Navegando via notificação → corrida/$finalId (fromNotif=$idFromNotif)")
                navController.navigate(Screen.Corrida.criarRota(finalId)) {
                    popUpTo(Screen.Home.route) { inclusive = false }
                    launchSingleTop = true
                }
                notificationIntent.action = null
                notificationIntent.removeExtra(RunningService.EXTRA_EVENT_ID)
                return@LaunchedEffect
            }
        }

        // Auto-redirecionamento removido intencionalmente.
        // O banner verde na HomeScreen permite que o usuário volte quando quiser.
        // O startDestination já leva direto para a corrida ao abrir o app do zero.
    }

    // Reseta a trava assim que o backStack confirma a nova rota
    LaunchedEffect(backStackEntry) {
        processandoNavegacao = false
    }

    NavHost(navController = navController, startDestination = startDestination) {

        composable(Screen.Config.route) {
            ConfigScreen(
                viewModel = configViewModel,
                onConfigSalva = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Config.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Home.route) {
            HomeScreen(
                onVerTreinos   = { navController.navigate(Screen.Treinos.route) },
                onCorridaLivre = { navController.navigate(Screen.Corrida.criarRota(0L)) },
                onVerHistorico = { navController.navigate(Screen.Historico.route) },
                onConfigurar   = { navController.navigate(Screen.Config.route) },
                corridaAtiva   = corridaAtiva,
                onVoltarParaCorrida = {
                    eventoId?.let { navController.navigate(Screen.Corrida.criarRota(it)) }
                }
            )
        }

        composable(Screen.Treinos.route) {
            TreinosScreen(
                onTreinoSelecionado = { eventId ->
                    navController.navigate(Screen.DetalheTreino.criarRota(eventId))
                },
                onVoltar = { navController.popBackStack() }
            )
        }

        composable(Screen.Historico.route) {
            HistoricoScreen(
                onVoltar = { navController.popBackStack() },
                onVerDetalhe = { corrida ->
                    // FIX: O ponto (.) em "corrida_20240515.gpx" quebra o roteamento —
                    // o Navigation interpreta tudo após o ponto como extensão da URL.
                    // Uri.encode transforma "corrida.gpx" em "corrida%2Egpx" (opaco).
                    // O composable de destino desfaz com Uri.decode ao receber o argumento.
                    val arquivoSeguro = android.net.Uri.encode(corrida.arquivoGpx)
                    navController.navigate(Screen.DetalheAtividade.criarRota(arquivoSeguro))
                }
            )
        }

        composable(
            route = Screen.DetalheAtividade.route,
            arguments = listOf(navArgument("arquivoGpx") { type = NavType.StringType })
        ) { backStackEntry ->
            val arquivoGpx = backStackEntry.arguments?.getString("arquivoGpx")
                ?.let { android.net.Uri.decode(it) }
                ?: return@composable

            val detalheEstado = androidx.compose.runtime.produceState<DetalheEstado>(
                initialValue = DetalheEstado.Carregando
            ) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val app = navController.context.applicationContext as com.runapp.RunApp
                        val repo = com.runapp.data.repository.HistoricoRepository(app)
                        var corrida = repo.listarCorridas().find { it.arquivoGpx == arquivoGpx }
                            ?: return@runCatching DetalheEstado.Erro

                        val gpxFile = repo.obterArquivoGpx(corrida)
                        val rota = if (gpxFile != null)
                            com.runapp.util.GpxParser.parsear(gpxFile)
                        else emptyList()

                        // FALLBACK DE ZONAS: se o JSON foi salvo sem zonasFronteira
                        // (corridas antigas ou save com falha na busca), busca da API
                        // agora apenas para esta exibição — sem re-salvar o arquivo.
                        // Garante que o gráfico de zonas sempre aparece.
                        if (corrida.zonasFronteira.isEmpty()) {
                            runCatching {
                                // 1ª tentativa: cache local (DataStore) — sem rede, instantâneo
                                val cached = app.container.preferencesRepository.getZonasFronteiraCached()
                                if (cached.isNotEmpty()) {
                                    corrida = corrida.copy(zonasFronteira = cached)
                                    android.util.Log.d("AppNav", "✅ Zonas lidas do cache: ${cached.size}")
                                } else {
                                    // 2ª tentativa: API (requer rede)
                                    val apiKey    = app.container.preferencesRepository.apiKey.first()
                                    val athleteId = app.container.preferencesRepository.athleteId.first()
                                    if (athleteId != null) {
                                        val workoutRepo = app.container.createWorkoutRepository(apiKey ?: "")
                                        workoutRepo.getZonas(athleteId).getOrNull()?.let { zonesResponse ->
                                            val paceZones = workoutRepo.processarZonas(zonesResponse)
                                            if (paceZones.isNotEmpty()) {
                                                val zonasFronteira = paceZones.map { z ->
                                                    com.runapp.data.model.ZonaFronteira(
                                                        nome         = z.name,
                                                        cor          = z.color ?: "",
                                                        paceMinSegKm = (z.min ?: 0.0) * 1000.0,
                                                        paceMaxSegKm = z.max?.let { m -> m * 1000.0 }
                                                    )
                                                }
                                                corrida = corrida.copy(zonasFronteira = zonasFronteira)
                                                // Aproveita para popular o cache para as próximas vezes
                                                app.container.preferencesRepository.salvarZonasFronteira(zonasFronteira)
                                                android.util.Log.d("AppNav", "✅ Zonas buscadas da API: ${zonasFronteira.size}")
                                            }
                                        }
                                    }
                                }
                            }.onFailure {
                                android.util.Log.w("AppNav", "⚠️ Zonas indisponíveis: ${it.message}")
                            }
                        }

                        DetalheEstado.Sucesso(corrida, rota)
                    }.getOrElse { DetalheEstado.Erro }
                }
            }

            // ── Estado do Coach ── independente do carregamento da corrida ──────
            // A corrida aparece imediatamente; o card do Coach faz o loading separado.
            var coachEstado by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf<CoachUiState>(CoachUiState.Inativo)
            }

            val estadoAtual = detalheEstado.value
            androidx.compose.runtime.LaunchedEffect(estadoAtual) {
                if (estadoAtual !is DetalheEstado.Sucesso) return@LaunchedEffect
                val corrida = estadoAtual.corrida

                // Feedback já cacheado → usa direto, sem chamar a API
                if (corrida.feedbackCoach != null) {
                    coachEstado = CoachUiState.Pronto(corrida.feedbackCoach)
                    return@LaunchedEffect
                }

                // Gera em background enquanto a UI já está visível
                coachEstado = CoachUiState.Carregando
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val app       = navController.context.applicationContext as com.runapp.RunApp
                    val repo      = com.runapp.data.repository.HistoricoRepository(app)
                    val coachRepo = com.runapp.data.repository.CoachRepository()

                    // Tenta buscar CTL/ATL/TSB do dia da corrida no Intervals.icu.
                    // Falha silenciosa — o Coach funciona normalmente sem esses dados.
                    val wellness = runCatching {
                        val prefs     = app.container.preferencesRepository
                        val apiKey    = prefs.apiKey.first()
                        val athleteId = prefs.athleteId.first()
                        if (!apiKey.isNullOrBlank() && !athleteId.isNullOrBlank()) {
                            // Extrai "yyyy-MM-dd" do campo data da corrida (formato ISO)
                            val dataCorrida = corrida.data.take(10)
                            val intervalsApi = com.runapp.data.api.IntervalsClient.create(apiKey)
                            intervalsApi.getWellness(athleteId, dataCorrida)
                        } else null
                    }.getOrNull()

                    coachRepo.gerarFeedback(corrida, wellness).fold(
                        onSuccess = { feedback ->
                            repo.salvarFeedback(corrida.arquivoGpx, feedback)
                            coachEstado = CoachUiState.Pronto(feedback)
                        },
                        onFailure = { erro ->
                            android.util.Log.e("AppNav", "❌ Coach falhou: ${erro.message}")
                            coachEstado = CoachUiState.Erro(erro.message ?: "Erro desconhecido")
                        }
                    )
                }
            }

            when (val estado = estadoAtual) {
                is DetalheEstado.Carregando -> {
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                }
                is DetalheEstado.Erro -> {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        androidx.compose.foundation.layout.Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)
                        ) {
                            androidx.compose.material3.Text(
                                text = "Não foi possível carregar a atividade.",
                                color = Color(0xFFAAAAAA)
                            )
                            androidx.compose.material3.TextButton(
                                onClick = { navController.popBackStack() }
                            ) {
                                androidx.compose.material3.Text("Voltar", color = Color(0xFF4ECDC4))
                            }
                        }
                    }
                }
                is DetalheEstado.Sucesso -> {
                    DetalheAtividadeScreen(
                        corrida     = estado.corrida,
                        rota        = estado.rota,
                        coachEstado = coachEstado,
                        onVoltar    = { navController.popBackStack() }
                    )
                }
            }
        }

        composable(
            route = Screen.DetalheTreino.route,
            arguments = listOf(navArgument("eventId") { type = NavType.LongType })
        ) { backStackEntry ->
            val eventId = backStackEntry.arguments?.getLong("eventId") ?: return@composable
            DetalheTreinoScreen(
                eventId = eventId,
                corridaAtiva = corridaAtiva,
                onIniciarCorrida = { navController.navigate(Screen.Corrida.criarRota(eventId)) },
                onVoltar = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Corrida.route,
            arguments = listOf(navArgument("eventId") { type = NavType.LongType })
        ) { backStackEntry ->
            val eventIdDaUrl = backStackEntry.arguments?.getLong("eventId") ?: return@composable

            // GUARDA DE INTEGRIDADE: se já há uma corrida ativa com ID diferente,
            // redireciona para o treino correto em vez de criar estado "Frankenstein".
            if (corridaAtiva && eventoId != null && eventIdDaUrl != eventoId) {
                LaunchedEffect(Unit) {
                    navController.navigate(Screen.Corrida.criarRota(eventoId)) {
                        popUpTo(Screen.Corrida.route) { inclusive = true }
                    }
                }
                return@composable
            }

            CorridaScreen(
                eventId = eventIdDaUrl,
                viewModel = corridaViewModel,
                onSair = {
                    // popBackStack() falha quando a corrida é o startDestination (pilha vazia).
                    // Navegamos explicitamente para a Home, limpando a pilha inteira.
                    navController.navigate(Screen.Home.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onFinalizar = {
                    navController.navigate(Screen.Resumo.route) {
                        popUpTo(Screen.Corrida.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Resumo.route) {
            ResumoScreen(
                viewModel = corridaViewModel,
                onVoltarHome = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}
