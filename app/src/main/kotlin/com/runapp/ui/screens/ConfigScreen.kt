package com.runapp.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.runapp.ui.viewmodel.ConfigViewModel
import com.runapp.util.autofill

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ConfigScreen(
    viewModel: ConfigViewModel,
    onConfigSalva: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var mostrarApiKey by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Spacer(modifier = Modifier.height(40.dp))

        // Ícone e título
        Text(text = "🏃", fontSize = 64.sp)
        Text(
            text = "RunApp",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Configure sua conta intervals.icu para acessar seus treinos",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Campo Athlete ID
        OutlinedTextField(
            value = state.athleteId,
            onValueChange = viewModel::onAthleteIdChange,
            label = { Text("Athlete ID") },
            placeholder = { Text("Ex: i12345") },
            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
            supportingText = {
                Text("intervals.icu → Settings → Developer Settings → Athlete ID")
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .autofill(
                    autofillTypes = listOf(AutofillType.Username),
                    onFill = viewModel::onAthleteIdChange
                )
        )

        // Campo API Key
        OutlinedTextField(
            value = state.apiKey,
            onValueChange = viewModel::onApiKeyChange,
            label = { Text("API Key") },
            leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = { mostrarApiKey = !mostrarApiKey }) {
                    Icon(
                        if (mostrarApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = "Mostrar/esconder"
                    )
                }
            },
            visualTransformation = if (mostrarApiKey) VisualTransformation.None
                                   else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = {
                Text("intervals.icu → Settings → Developer Settings → API Key")
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .autofill(
                    autofillTypes = listOf(AutofillType.Password),
                    onFill = viewModel::onApiKeyChange
                )
        )

        // Configurações Gerais
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "⚙️ Configurações Gerais",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))

                // ── Auto-pause ────────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-pause",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Pausa automaticamente ao parar de se mover",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                    Switch(
                        checked = state.autoPauseEnabled,
                        onCheckedChange = { viewModel.onAutoPauseToggle(it) }
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
                )

                // ── Telemetria Reduzida ───────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "⛰️ GAP apenas em ladeiras",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Informa o esforço ajustado somente em subidas e descidas técnicas. Trechos planos recebem apenas o ritmo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                    Switch(
                        checked = state.gapTelemetriaReduzida,
                        onCheckedChange = { viewModel.onGapTelemetriaReduzidaToggle(it) }
                    )
                }
            }
        }

        // Feedback de Áudio
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "🔊 Feedback de Áudio",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))

                // ── Master switch ─────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Feedback de voz",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Ativa ou desativa todos os anúncios de áudio durante a corrida",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                    Switch(
                        checked = state.audioMasterEnabled,
                        onCheckedChange = { viewModel.onAudioMasterToggle(it) }
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
                )

                // ── Alertas de ritmo ──────────────────────────────────────────
                val alphaFilhos = if (state.audioMasterEnabled) 1f else 0.4f
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Alertas de ritmo",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alphaFilhos)
                        )
                        Text(
                            text = "Avisa quando o ritmo está fora do intervalo do treino",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alphaFilhos * 0.7f)
                        )
                    }
                    Switch(
                        checked = state.audioPaceAlerts,
                        onCheckedChange = { viewModel.onAudioPaceAlertsToggle(it) },
                        enabled = state.audioMasterEnabled
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
                )

                // ── Parciais ──────────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Parciais",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alphaFilhos)
                        )
                        Text(
                            text = "Anuncia dados ao atingir cada intervalo de distância",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alphaFilhos * 0.7f)
                        )
                    }
                    Switch(
                        checked = state.audioSplitsKm,
                        onCheckedChange = { viewModel.onAudioSplitsKmToggle(it) },
                        enabled = state.audioMasterEnabled
                    )
                }

                // ── Opções de parciais (só visíveis quando o switch está ON) ─
                val splitsAtivo = state.audioMasterEnabled && state.audioSplitsKm
                if (splitsAtivo) {
                    Spacer(modifier = Modifier.height(12.dp))

                    // Intervalo
                    Text(
                        text = "Intervalo de anúncio",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(500 to "500 m", 1000 to "1 km", 2000 to "2 km").forEach { (metros, label) ->
                            val selecionado = state.splitIntervaloMetros == metros
                            FilterChip(
                                selected = selecionado,
                                onClick = { viewModel.onSplitIntervaloChange(metros) },
                                label = { Text(label, fontSize = 12.sp) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Dados do anúncio
                    Text(
                        text = "Dados anunciados",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val opcoesDados = listOf(
                        "distancia"  to "Distância",
                        "tempo"      to "Tempo",
                        "pace_atual" to "Ritmo atual",
                        "pace_medio" to "Ritmo médio"
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        opcoesDados.forEach { (flag, label) ->
                            val selecionado = flag in state.splitDadosFlags
                            FilterChip(
                                selected = selecionado,
                                onClick = { viewModel.onSplitDadosFlagToggle(flag) },
                                label = { Text(label, fontSize = 11.sp) }
                            )
                        }
                    }
                }

            }
        }

        // Erro
        state.error?.let { erro ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = erro,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        // ── Diagnóstico de Zonas ──────────────────────────────────────────────
        if (state.isConfigured) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = androidx.compose.ui.graphics.Color(0xFF1A1A2E)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "🔬 Diagnóstico de Zonas",
                        fontWeight = FontWeight.Bold,
                        color = androidx.compose.ui.graphics.Color.White,
                        fontSize = 14.sp
                    )

                    val corStatus = when {
                        state.zonasCarregadas < 0  -> androidx.compose.ui.graphics.Color(0xFFAAAAAA)
                        state.zonasCarregadas == 0 -> androidx.compose.ui.graphics.Color(0xFFFF6B6B)
                        else                       -> androidx.compose.ui.graphics.Color(0xFF4ECDC4)
                    }
                    val textoStatus = when {
                        state.zonasCarregadas < 0  -> "⏳ Verificando cache..."
                        state.zonasCarregadas == 0 -> "❌ Nenhuma zona em cache"
                        else                       -> "✅ ${state.zonasCarregadas} zonas em cache"
                    }
                    Text(text = textoStatus, color = corStatus, fontSize = 13.sp, fontWeight = FontWeight.Medium)

                    if (state.zonasDiagTexto.isNotEmpty()) {
                        Text(
                            text = state.zonasDiagTexto,
                            color = androidx.compose.ui.graphics.Color(0xFFCCCCCC),
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }

                    // Resultado do teste ao vivo
                    if (state.zonasTeste.isNotEmpty()) {
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = state.zonasTeste,
                            color = if (state.zonasTeste.startsWith("✅"))
                                androidx.compose.ui.graphics.Color(0xFF4ECDC4)
                            else
                                androidx.compose.ui.graphics.Color(0xFFFF6B6B),
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }

                    Button(
                        onClick = { viewModel.testarZonas() },
                        enabled = !state.zonasTestandoApi,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = androidx.compose.ui.graphics.Color(0xFF2D2D44)
                        )
                    ) {
                        if (state.zonasTestandoApi) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = androidx.compose.ui.graphics.Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Buscando zonas...", color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp)
                        } else {
                            Text("🔄 Testar e atualizar zonas da API", color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // Botão salvar
        Button(
            onClick = { viewModel.salvarCredenciais(onConfigSalva) },
            enabled = !state.isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            if (state.isSaving) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text("Salvar e Entrar", fontSize = 16.sp)
            }
        }

        // Dica
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "💡 Como encontrar suas credenciais",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "1. Acesse intervals.icu no navegador\n" +
                           "2. Clique no ícone do seu perfil → Settings\n" +
                           "3. Role até \"Developer Settings\"\n" +
                           "4. Copie o Athlete ID e crie uma API Key",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
