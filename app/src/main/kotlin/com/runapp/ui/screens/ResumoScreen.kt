package com.runapp.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.runapp.ui.viewmodel.CorridaViewModel

@Composable
fun ResumoScreen(
    onVoltarHome: () -> Unit,
    viewModel: CorridaViewModel = viewModel(factory = CorridaViewModel.Factory)
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        // Ícone de sucesso
        Surface(
            shape = androidx.compose.foundation.shape.CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(80.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        Text(
            text = "Corrida Concluída! 🎉",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        // Feedback de salvamento
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Atividade salva!",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Card de métricas
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(24.dp)) {
                MetricaResumo("📏 Distância", "%.2f km".format(state.distanciaMetros / 1000.0))
                Divider(modifier = Modifier.padding(vertical = 12.dp))
                MetricaResumo("⏱ Tempo Total", state.tempoFormatado)
                Divider(modifier = Modifier.padding(vertical = 12.dp))
                MetricaResumo("🏃 Pace Médio", "${state.paceMedia}/km")
                Divider(modifier = Modifier.padding(vertical = 12.dp))
                MetricaResumo(
                    "✅ Passos Completos",
                    "${state.passoAtualIndex + 1} de ${state.passos.size}"
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onVoltarHome,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("Voltar ao Início", fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun MetricaResumo(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Text(text = value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    }
}
