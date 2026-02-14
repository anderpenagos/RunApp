package com.runapp.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.runapp.data.model.CorridaHistorico
import com.runapp.ui.viewmodel.HistoricoViewModel
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoricoScreen(
    onVoltar: () -> Unit,
    viewModel: HistoricoViewModel = viewModel(factory = HistoricoViewModel.Factory)
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Launcher para abrir o seletor de compartilhamento do Android
    val shareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* resultado ignorado — não precisamos fazer nada após compartilhar */ }

    // Exibe snackbar quando há mensagem de feedback
    LaunchedEffect(state.mensagem) {
        state.mensagem?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.limparMensagem()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Histórico de Corridas 📋") },
                navigationIcon = {
                    IconButton(onClick = onVoltar) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Voltar")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                // ── Carregando ────────────────────────────────────────────
                state.carregando -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                // ── Lista vazia ───────────────────────────────────────────
                state.corridas.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("🏃", fontSize = 64.sp)
                        Text(
                            "Nenhuma corrida salva ainda",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Após concluir uma corrida, toque em \"Salvar Corrida\" " +
                            "para que ela apareça aqui.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }
                }

                // ── Lista de corridas ─────────────────────────────────────
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(
                            items = state.corridas,
                            key = { it.arquivoGpx }
                        ) { corrida ->
                            CorridaHistoricoCard(
                                corrida = corrida,
                                uploadEmAndamento = state.uploadEmAndamento == corrida.arquivoGpx,
                                onCompartilhar = {
                                    val intent = viewModel.compartilharGpx(corrida)
                                    if (intent != null) {
                                        shareLauncher.launch(
                                            Intent.createChooser(intent, "Compartilhar GPX")
                                        )
                                    }
                                },
                                onUpload  = { viewModel.uploadParaIntervals(corrida) },
                                onDeletar = { viewModel.deletarCorrida(corrida) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CorridaHistoricoCard(
    corrida: CorridaHistorico,
    uploadEmAndamento: Boolean,
    onCompartilhar: () -> Unit,
    onUpload: () -> Unit,
    onDeletar: () -> Unit
) {
    var confirmarDelete by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // ── Cabeçalho: data + nome ────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = formatarData(corrida.data),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = corrida.nome,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ── Métricas ──────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MetricaMini("📏", "%.2f km".format(corrida.distanciaKm))
                MetricaMini("⏱", corrida.tempoFormatado)
                MetricaMini("🏃", "${corrida.paceMedia}/km")
                MetricaMini("📍", "${corrida.pontosGps}pts")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

            // ── Ações ─────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Compartilhar GPX
                OutlinedButton(
                    onClick = onCompartilhar,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("GPX", style = MaterialTheme.typography.labelMedium)
                }

                // Upload Intervals.icu
                OutlinedButton(
                    onClick = onUpload,
                    enabled = !uploadEmAndamento,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    if (uploadEmAndamento) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Intervals", style = MaterialTheme.typography.labelMedium)
                }

                // Deletar
                OutlinedButton(
                    onClick = { confirmarDelete = true },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Apagar", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }

    // Diálogo de confirmação de exclusão
    if (confirmarDelete) {
        AlertDialog(
            onDismissRequest = { confirmarDelete = false },
            title = { Text("Apagar corrida?") },
            text = { Text("Os arquivos GPX e os dados desta corrida serão removidos permanentemente do seu dispositivo.") },
            confirmButton = {
                Button(
                    onClick = { confirmarDelete = false; onDeletar() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Apagar") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmarDelete = false }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun MetricaMini(emoji: String, valor: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, fontSize = 16.sp)
        Text(
            text = valor,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

/** Formata "2025-02-14T10:30:00" → "14/02/2025  10:30" */
private fun formatarData(iso: String): String {
    return runCatching {
        val dt = LocalDateTime.parse(iso)
        val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy  HH:mm", Locale("pt", "BR"))
        dt.format(fmt)
    }.getOrDefault(iso)
}
