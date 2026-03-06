package com.runapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.runapp.util.GpsDebugLogger

private val CorFundo    = Color(0xFF121212)
private val CorCard     = Color(0xFF1E1E1E)
private val CorTexto    = Color(0xFFCCCCCC)
private val CorScreen   = Color(0xFF4FC3F7)   // azul — SCREEN_ON/OFF
private val CorGps      = Color(0xFFFFB74D)   // laranja — GPS descartado
private val CorPace     = Color(0xFFEF5350)   // vermelho — spike de pace
private val CorNormal   = Color(0xFF888888)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogScreen(onVoltar: () -> Unit) {
    val context = LocalContext.current
    var conteudo by remember { mutableStateOf(GpsDebugLogger.ler(context)) }
    var mostrarConfirmacao by remember { mutableStateOf(false) }

    val linhas = remember(conteudo) { conteudo.lines() }
    val listState = rememberLazyListState()

    // Rola para o fim automaticamente ao abrir
    LaunchedEffect(linhas.size) {
        if (linhas.isNotEmpty()) listState.scrollToItem(linhas.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug GPS", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onVoltar) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { mostrarConfirmacao = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Limpar log", tint = Color(0xFFEF5350))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A1A))
            )
        },
        containerColor = CorFundo
    ) { padding ->

        if (linhas.isEmpty() || conteudo == "Nenhum log disponível.") {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Nenhum log disponível.\nFaça uma corrida com tela bloqueada para capturar os dados.",
                    color = CorNormal,
                    fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(32.dp)
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                item {
                    Text(
                        text = "${linhas.size} linhas  •  toque no lixo para limpar",
                        color = CorNormal,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                items(linhas) { linha ->
                    val cor = when {
                        "[SCREEN]" in linha -> CorScreen
                        "[GPS]"   in linha -> CorGps
                        "[PACE]"  in linha -> CorPace
                        else               -> CorTexto
                    }
                    val destaque = "[SCREEN_ON]" in linha || "[PACE]" in linha

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (destaque) Modifier.background(cor.copy(alpha = 0.08f), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                else Modifier
                            )
                            .padding(horizontal = 4.dp, vertical = if (destaque) 3.dp else 1.dp)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = linha,
                            color = cor,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }

    if (mostrarConfirmacao) {
        AlertDialog(
            onDismissRequest = { mostrarConfirmacao = false },
            title = { Text("Limpar log?", color = Color.White) },
            text  = { Text("O arquivo de debug será apagado permanentemente.", color = CorNormal) },
            confirmButton = {
                TextButton(onClick = {
                    GpsDebugLogger.limpar(context)
                    conteudo = GpsDebugLogger.ler(context)
                    mostrarConfirmacao = false
                }) { Text("Limpar", color = Color(0xFFEF5350)) }
            },
            dismissButton = {
                TextButton(onClick = { mostrarConfirmacao = false }) { Text("Cancelar", color = CorNormal) }
            },
            containerColor = Color(0xFF1E1E1E)
        )
    }
}
