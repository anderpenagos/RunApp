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

    val startDestination = if (configState.isConfigured) Screen.Home.route else Screen.Config.route

    val corridaAtiva = corridaState.fase == FaseCorrida.CORRENDO || corridaState.fase == FaseCorrida.PAUSADO
    val eventoId = corridaState.treino?.id

    // Trava de segurança: impede que dois navigate() disparem simultaneamente.
    // Sem isso, o clique na notificação e o LaunchedEffect do service podem colidir
    // durante a animação de abertura da tela (o "efeito bumerangue").
    var processandoNavegacao by remember { mutableStateOf(false) }

    // LaunchedEffect unificado com prioridade explícita:
    //   Prioridade 1 — clique na notificação (tem eventId no intent)
    //   Prioridade 2 — redirecionamento automático (service restaurou a corrida)
    LaunchedEffect(notificationIntent, corridaAtiva, eventoId) {
        if (processandoNavegacao) return@LaunchedEffect

        val rotaAtual = navController.currentBackStackEntry?.destination?.route
        val jaEstaNaCorrida = rotaAtual?.startsWith("corrida") == true
                           || rotaAtual?.startsWith("resumo")  == true
        if (jaEstaNaCorrida) return@LaunchedEffect

        if (notificationIntent?.action == RunningService.ACTION_SHOW_RUNNING) {
            val id = notificationIntent.getLongExtra(RunningService.EXTRA_EVENT_ID, -1L)
            if (id != -1L) {
                processandoNavegacao = true
                android.util.Log.d("AppNav", "🎯 Navegando via notificação → corrida/$id")
                navController.navigate(Screen.Corrida.criarRota(id)) {
                    popUpTo(Screen.Home.route) { inclusive = false }
                    launchSingleTop = true
                }
                notificationIntent.action = null
                notificationIntent.removeExtra(RunningService.EXTRA_EVENT_ID)
                return@LaunchedEffect
            }
        }

        if (corridaAtiva && eventoId != null) {
            val naHomeOuConfig = rotaAtual == Screen.Home.route || rotaAtual == Screen.Config.route
            if (naHomeOuConfig) {
                processandoNavegacao = true
                android.util.Log.d("AppNav", "🚀 Auto-redirecionando para corrida ativa")
                navController.navigate(Screen.Corrida.criarRota(eventoId)) {
                    popUpTo(Screen.Home.route) { inclusive = false }
                    launchSingleTop = true
                }
            }
        }
    }

    // Reseta a trava assim que a navegação completa (back stack confirma nova rota)
    val backStackEntry by navController.currentBackStackEntryAsState()
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
