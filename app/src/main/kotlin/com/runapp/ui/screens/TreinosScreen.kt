package com.runapp.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.runapp.data.model.WorkoutEvent
import com.runapp.ui.viewmodel.TreinosViewModel
import com.google.gson.JsonElement
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TreinosScreen(
    onTreinoSelecionado: (Long) -> Unit,
    onVoltar: () -> Unit,
    viewModel: TreinosViewModel = viewModel(factory = TreinosViewModel.Factory)
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Treinos da Semana") },
                navigationIcon = {
                    IconButton(onClick = onVoltar) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.carregarTreinos() }) {
                        Icon(Icons.Default.Refresh, "Atualizar")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                state.error != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("❌", style = MaterialTheme.typography.displayMedium)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = state.error!!,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.carregarTreinos() }) {
                            Text("Tentar Novamente")
                        }
                    }
                }

                state.treinos.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("📅", style = MaterialTheme.typography.displayMedium)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Nenhum treino de corrida esta semana",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Verifique seu plano no intervals.icu",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(state.treinos) { treino ->
                            TreinoCard(
                                treino = treino,
                                onClick = { onTreinoSelecionado(treino.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TreinoCard(treino: WorkoutEvent, onClick: () -> Unit) {
    val data = parsearData(treino.startDateLocal)
    val duracaoTexto = treino.workoutDocRaw?.asJsonObject?.get("duration")?.asInt
        ?.let { formatarDuracao(it) } ?: ""

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = treino.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (data.isNotBlank()) {
                    Text(
                        text = data,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                if (duracaoTexto.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    AssistChip(
                        onClick = {},
                        label = { Text("⏱ $duracaoTexto") }
                    )
                }
                treino.description?.let { desc ->
                    if (desc.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = desc.take(80) + if (desc.length > 80) "..." else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            Icon(
                Icons.Default.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun parsearData(dateString: String): String {
    return try {
        val local = LocalDate.parse(dateString.take(10))
        val formatter = DateTimeFormatter.ofPattern("EEEE, dd/MM", Locale("pt", "BR"))
        local.format(formatter).replaceFirstChar { it.uppercase() }
    } catch (e: Exception) {
        ""
    }
}

private fun formatarDuracao(segundos: Int): String {
    return when {
        segundos <= 0 -> ""
        segundos < 3600 -> "${segundos / 60} min"
        else -> "${segundos / 3600}h ${(segundos % 3600) / 60}min"
    }
}
