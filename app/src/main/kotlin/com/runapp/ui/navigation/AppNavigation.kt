package com.runapp.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
fun AppNavigation() {
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

    // Se a corrida ainda está ativa e o usuário navegou para fora, redireciona de volta
    val corridaAtiva = corridaState.fase == FaseCorrida.CORRENDO || corridaState.fase == FaseCorrida.PAUSADO
    val eventoId = corridaState.treino?.id

    // Se corrida ativa e usuário está em outra tela, redireciona de volta
    LaunchedEffect(corridaAtiva) {
        if (corridaAtiva && eventoId != null) {
            val rotaAtual = navController.currentDestination?.route
            val naTelaCorreta = rotaAtual?.startsWith("corrida/") == true
            if (!naTelaCorreta) {
                navController.navigate(Screen.Corrida.criarRota(eventoId)) {
                    launchSingleTop = true
                }
            }
        }
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
