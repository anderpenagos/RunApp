package com.runapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.runapp.RunApp
import com.runapp.data.model.PassoExecucao
import com.runapp.data.model.WorkoutEvent
import com.runapp.ui.theme.corZona
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// ViewModel inline para esta tela
data class DetalheTreinoState(
    val treino: WorkoutEvent? = null,
    val passos: List<PassoExecucao> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

class DetalheTreinoViewModel(
    private val app: android.app.Application,
    private val eventId: Long
) : ViewModel() {
    private val container = (app as RunApp).container
    private val _state = MutableStateFlow(DetalheTreinoState(isLoading = true))
    val state: StateFlow<DetalheTreinoState> = _state

    init { carregar() }

    private fun carregar() {
        viewModelScope.launch {
            try {
                val apiKey = container.preferencesRepository.apiKey.first() ?: run {
                    _state.value = DetalheTreinoState(error = "Configure a API Key")
                    return@launch
                }
                val athleteId = container.preferencesRepository.athleteId.first() ?: run {
                    _state.value = DetalheTreinoState(error = "Configure o Athlete ID")
                    return@launch
                }
                val repo = container.createWorkoutRepository(apiKey)
                val evento = repo.getTreinoDetalhe(athleteId, eventId).getOrThrow()
                val zonasResponse = repo.getZonas(athleteId).getOrDefault(null)
                val paceZones = if (zonasResponse != null) {
                    repo.processarZonas(zonasResponse)
                } else {
                    emptyList()
                }
                val passos = repo.converterParaPassos(evento, paceZones)
                _state.value = DetalheTreinoState(treino = evento, passos = passos)
            } catch (e: Exception) {
                _state.value = DetalheTreinoState(error = "Erro: ${e.message}")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetalheTreinoScreen(
    eventId: Long,
    onIniciarCorrida: () -> Unit,
    onVoltar: () -> Unit
) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as RunApp
    val viewModel: DetalheTreinoViewModel = viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DetalheTreinoViewModel(app, eventId) as T
    })
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.treino?.name ?: "Detalhes do Treino") },
                navigationIcon = {
                    IconButton(onClick = onVoltar) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar")
                    }
                }
            )
        },
        bottomBar = {
            if (state.treino != null) {
                Surface(tonalElevation = 4.dp) {
                    Button(
                        onClick = onIniciarCorrida,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .height(56.dp)
                    ) {
                        Icon(Icons.Default.DirectionsRun, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Iniciar Corrida", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                state.error != null -> Text(
                    text = state.error!!,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.error
                )
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Descrição
                        state.treino?.description?.let { desc ->
                            item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = desc,
                                        modifier = Modifier.padding(16.dp),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }

                        // Gráfico de estrutura
                        if (state.passos.isNotEmpty()) {
                            item {
                                Text("Estrutura do Treino", fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.height(8.dp))
                                GraficoTreino(passos = state.passos)
                            }
                        }

                        // Lista de passos
                        item {
                            Text("Passos", fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 8.dp))
                        }
                        itemsIndexed(state.passos) { index, passo ->
                            PassoCard(numero = index + 1, passo = passo)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GraficoTreino(passos: List<PassoExecucao>) {
    val totalSecs = passos.sumOf { it.duracao }.coerceAtLeast(1)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(60.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                passos.forEach { passo ->
                    val frac = passo.duracao.toFloat() / totalSecs
                    Box(
                        modifier = Modifier
                            .weight(frac.coerceAtLeast(0.02f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(corZona(passo.zona))
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            // Legenda
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(
                    "Z1" to com.runapp.ui.theme.ZonaColors[0],
                    "Z2" to com.runapp.ui.theme.ZonaColors[1],
                    "Z3" to com.runapp.ui.theme.ZonaColors[2],
                    "Z4" to com.runapp.ui.theme.ZonaColors[3],
                    "Z5" to com.runapp.ui.theme.ZonaColors[4]
                ).forEach { (label, cor) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(10.dp).background(cor, RoundedCornerShape(2.dp)))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
fun PassoCard(numero: Int, passo: PassoExecucao) {
    val duracaoTexto = formatarDuracaoPasso(passo.duracao)
    val paceTexto = when {
        passo.paceAlvoMin == "--:--" -> "Sem pace alvo"
        passo.paceAlvoMax == "--:--" -> "${passo.paceAlvoMin}/km"
        else -> "${passo.paceAlvoMin} – ${passo.paceAlvoMax}/km"
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Indicador de zona
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(corZona(passo.zona)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$numero",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(text = passo.nome, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall)
                Text(text = "⏱ $duracaoTexto  •  🏃 $paceTexto",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                if (passo.instrucao.isNotBlank()) {
                    Text(text = passo.instrucao,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }
    }
}

private fun formatarDuracaoPasso(segundos: Int): String {
    return if (segundos < 60) "${segundos}s"
    else if (segundos < 3600) "${segundos / 60}min ${segundos % 60}s"
    else "${segundos / 3600}h ${(segundos % 3600) / 60}min"
}
