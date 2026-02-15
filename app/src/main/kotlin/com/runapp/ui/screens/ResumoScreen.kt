package com.runapp.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.runapp.ui.viewmodel.CorridaViewModel
import com.runapp.ui.viewmodel.SalvamentoEstado
import com.runapp.ui.viewmodel.UploadEstado

@Composable
fun ResumoScreen(
    onVoltarHome: () -> Unit,
    viewModel: CorridaViewModel = viewModel(factory = CorridaViewModel.Factory)
) {
    val state by viewModel.uiState.collectAsState()
    var mostrarConfirmarDescarte by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Ícone e título
        Surface(
            shape = androidx.compose.foundation.shape.CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        Text(
            text = "Corrida Concluída! 🎉",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        // Card de métricas
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp)) {
                MetricaResumo("📏 Distância", "%.2f km".format(state.distanciaMetros / 1000.0))
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                MetricaResumo("⏱ Tempo Total", state.tempoFormatado)
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                MetricaResumo("🏃 Pace Médio", "${state.paceMedia}/km")
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                MetricaResumo(
                    "✅ Passos Completos",
                    "${state.passoAtualIndex + 1} de ${state.passos.size}"
                )
            }
        }

        // ── Área de ações ─────────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {

                Text(
                    text = "O que deseja fazer?",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // ── Botão Salvar ──────────────────────────────────────────
                AnimatedContent(
                    targetState = state.salvamentoEstado,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "botao_salvar"
                ) { estadoSalvar ->
                    when (estadoSalvar) {
                        SalvamentoEstado.NAO_SALVO -> Button(
                            onClick = { viewModel.salvarCorrida() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("💾 Salvar Corrida")
                        }

                        SalvamentoEstado.SALVANDO -> Button(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Salvando...")
                        }

                        SalvamentoEstado.SALVO -> FilledTonalButton(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                disabledContainerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "✅ Corrida salva no dispositivo",
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }

                        SalvamentoEstado.ERRO -> Column(modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = { viewModel.salvarCorrida() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("⚠️ Tentar salvar novamente")
                            }
                            val erroSalvar = state.erroSalvamento
                            if (erroSalvar != null) {
                                Text(
                                    text = erroSalvar,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }

                // ── Botão Upload Intervals.icu (só aparece após salvar) ───
                if (state.salvamentoEstado == SalvamentoEstado.SALVO) {
                    AnimatedContent(
                        targetState = state.uploadEstado,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "botao_upload"
                    ) { estadoUpload ->
                        when (estadoUpload) {
                            UploadEstado.NAO_ENVIADO -> OutlinedButton(
                                onClick = { viewModel.uploadParaIntervals() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.CloudUpload, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("⬆️ Enviar para Intervals.icu")
                            }

                            UploadEstado.ENVIANDO -> OutlinedButton(
                                onClick = {},
                                enabled = false,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Enviando...")
                            }

                            UploadEstado.ENVIADO -> FilledTonalButton(
                                onClick = {},
                                enabled = false,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    disabledContainerColor = MaterialTheme.colorScheme.tertiaryContainer
                                )
                            ) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "✅ Enviado para Intervals.icu",
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }

                            UploadEstado.ERRO -> Column(modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { viewModel.uploadParaIntervals() },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Text("⚠️ Tentar upload novamente")
                                }
                                val erroUpload = state.erroSalvamento
                                if (erroUpload != null) {
                                    Text(
                                        text = erroUpload,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Descartar ─────────────────────────────────────────────
                if (state.salvamentoEstado == SalvamentoEstado.NAO_SALVO ||
                    state.salvamentoEstado == SalvamentoEstado.ERRO
                ) {
                    TextButton(
                        onClick = { mostrarConfirmarDescarte = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Descartar sem salvar", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Botão Voltar — sempre disponível
        OutlinedButton(
            onClick = onVoltarHome,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text("← Voltar ao Início", fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(8.dp))
    }

    // ── Diálogo de confirmação de descarte ────────────────────────────────
    if (mostrarConfirmarDescarte) {
        AlertDialog(
            onDismissRequest = { mostrarConfirmarDescarte = false },
            title = { Text("Descartar corrida?") },
            text = {
                Text(
                    "Os dados desta corrida serão perdidos permanentemente e não poderão ser recuperados.",
                    textAlign = TextAlign.Center
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        mostrarConfirmarDescarte = false
                        onVoltarHome()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Descartar")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { mostrarConfirmarDescarte = false }) {
                    Text("Cancelar")
                }
            }
        )
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
