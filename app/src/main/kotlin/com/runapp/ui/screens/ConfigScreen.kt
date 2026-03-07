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
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
