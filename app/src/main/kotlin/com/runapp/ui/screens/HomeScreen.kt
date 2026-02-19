package com.runapp.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onVerTreinos: () -> Unit,
    onVerHistorico: () -> Unit,
    onConfigurar: () -> Unit,
    corridaAtiva: Boolean = false,
    onVoltarParaCorrida: () -> Unit = {}
) {
    val hoje = LocalDate.now()
    val formatter = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", Locale("pt", "BR"))
    val dataFormatada = hoje.format(formatter).replaceFirstChar { it.uppercase() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RunApp 🏃", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onConfigurar) {
                        Icon(Icons.Default.Settings, contentDescription = "Configurações")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .animateContentSize(), // banner entra/sai suavemente sem pulo
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // Banner de corrida ativa — aparece quando o usuário saiu sem finalizar
            if (corridaAtiva) {
                Card(
                    onClick = onVoltarParaCorrida,
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF4CAF50)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "🏃 Corrida em andamento",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color.White
                            )
                            Text(
                                text = "Toque para voltar ao treino",
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.85f)
                            )
                        }
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Voltar à corrida",
                            modifier = Modifier.size(36.dp),
                            tint = Color.White
                        )
                    }
                }
            }
            Text(
                text = dataFormatada,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
            Text(
                text = "Pronto para treinar? 💪",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Card principal — ver treinos
            Card(
                onClick = onVerTreinos,
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Treinos da Semana",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Text(
                            text = "Veja e execute seus treinos do intervals.icu",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                        )
                    }
                    Icon(
                        Icons.Default.DirectionsRun,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            // Card secundário — histórico de corridas
            Card(
                onClick = onVerHistorico,
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Histórico de Corridas",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = "Veja, compartilhe e exporte seus GPX",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                        )
                    }
                    Icon(
                        Icons.Default.History,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            // Dica do dia
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "💡 Dica do Dia",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Mantenha o áudio do celular ligado durante a corrida " +
                               "para receber alertas de pace e troca de passo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
