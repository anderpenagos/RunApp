package com.runapp.ui.navigation

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.runapp.service.RunningService
import com.runapp.ui.screens.*
import com.runapp.ui.viewmodel.ConfigViewModel
import com.runapp.ui.viewmodel.CorridaViewModel
import com.runapp.ui.viewmodel.FaseCorrida

sealed class Screen(val route: String) {
    object Config      : Screen("config")
    object Home        : Screen("home")
    object Treinos     : Screen("treinos")
    object Historico   : Screen("historico")
    object DetalheTreino : Screen("detalhe/{eventId}") {
        fun criarRota(eventId: Long) = "detalhe/$eventId"
    }
    object Corrida : Screen("corrida/{eventId}") {
        fun criarRota(eventId: Long) = "corrida/$eventId"
    }
    object Resumo : Screen("resumo")
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

    // Extrai o ID da notificação antes de definir o destino inicial.
    // Isso permite decidir a tela de abertura ANTES de renderizar qualquer coisa,
    // eliminando o flash Home → Corrida que causa o efeito "África".
    val idNotificacao = remember {
        if (notificationIntent?.action == RunningService.ACTION_SHOW_RUNNING)
            notificationIntent.getLongExtra(RunningService.EXTRA_EVENT_ID, -1L)
        else -1L
    }

    // Destino inicial calculado UMA SÓ VEZ no remember — o NavHost não pode receber
    // um startDestination diferente entre recomposições ou o back stack corrompe.
    // Usa corridaState.fase diretamente (não corridaAtiva) para detectar corrida
    // restaurada pelo Service antes mesmo do primeiro frame renderizar.
    val startDestination = remember {
        when {
            !configState.isConfigured -> Screen.Config.route
            idNotificacao != -1L     -> Screen.Corrida.criarRota(idNotificacao)
            corridaState.fase != FaseCorrida.PREPARANDO -> {
                val id = corridaState.treino?.id ?: -1L
                if (id != -1L) Screen.Corrida.criarRota(id) else Screen.Home.route
            }
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

        if (corridaAtiva && eventoId != null && rotaAtual == Screen.Home.route) {
            processandoNavegacao = true
            android.util.Log.d("AppNav", "🚀 Auto-redirecionando para corrida ativa")
            navController.navigate(Screen.Corrida.criarRota(eventoId)) {
                popUpTo(Screen.Home.route) { inclusive = false }
                launchSingleTop = true
            }
        }
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
                onVerTreinos  = { navController.navigate(Screen.Treinos.route) },
                onVerHistorico = { navController.navigate(Screen.Historico.route) },
                onConfigurar  = { navController.navigate(Screen.Config.route) }
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
                onVoltar = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.DetalheTreino.route,
            arguments = listOf(navArgument("eventId") { type = NavType.LongType })
        ) { backStackEntry ->
            val eventId = backStackEntry.arguments?.getLong("eventId") ?: return@composable
            DetalheTreinoScreen(
                eventId = eventId,
                onIniciarCorrida = { navController.navigate(Screen.Corrida.criarRota(eventId)) },
                onVoltar = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Corrida.route,
            arguments = listOf(navArgument("eventId") { type = NavType.LongType })
        ) { backStackEntry ->
            val eventId = backStackEntry.arguments?.getLong("eventId") ?: return@composable
            CorridaScreen(
                eventId = eventId,
                viewModel = corridaViewModel,
                onSair = { navController.popBackStack() },
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
