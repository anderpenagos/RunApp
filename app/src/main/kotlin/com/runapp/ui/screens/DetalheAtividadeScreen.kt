package com.runapp.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.JointType // IMPORT ADICIONADO PARA CORRIGIR O ERRO
import com.google.maps.android.compose.*
import com.runapp.data.model.CorridaHistorico
import com.runapp.data.model.LatLngPonto
import com.runapp.data.model.SplitParcial
import com.runapp.data.model.VoltaAnalise
import com.runapp.data.model.ZonaFronteira
import kotlin.math.*

private val CorFundo    = Color(0xFF121212)
private val CorCard     = Color(0xFF1E1E1E)
private val CorPace     = Color(0xFF4FC3F7)
private val CorElevacao = Color(0xFF546E7A)
private val CorCadencia = Color(0xFFFF8A65)
private val CorGAP      = Color(0xFF81C784)
private val CorCursor   = Color(0xFFFFFFFF)
private val CorGrid     = Color(0xFFFFFFFF).copy(alpha = 0.05f)
private val CoreZonas   = listOf(
    Color(0xFF90CAF9), Color(0xFF66BB6A), Color(0xFFFFEE58), Color(0xFFFFA726), Color(0xFFEF5350)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetalheAtividadeScreen(
    corrida: CorridaHistorico,
    rota: List<LatLngPonto>,
    onVoltar: () -> Unit
) {
    val dados = remember(rota) { prepararDados(rota) }
    val zonas = remember(rota, corrida.zonasFronteira) {
        calcularZonasPorTempo(rota, corrida.zonasFronteira)
    }
    
    // Estado do cursor compartilhado entre gráficos e mapa
    var cursorFrac by remember { mutableFloatStateOf(-1f) }
    val pontoSelecionado = remember(cursorFrac, dados.indicesOriginais) {
        val idx = fracToIndex(cursorFrac, dados.indicesOriginais.size)
        if (idx >= 0) rota.getOrNull(dados.indicesOriginais[idx]) else null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(corrida.nome, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(formatarDataDetalhe(corrida.data), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onVoltar) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CorFundo)
            )
        },
        containerColor = CorFundo
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { CartaoResumo(corrida) }
            
            if (rota.size >= 2) { 
                item { CartaoMapa(rota, pontoSelecionado) } 
            }
            
            item {
                GraficoCard("Pace e Elevação", "Azul = ritmo  •  Área = altitude") {
                    GraficoPaceElevacao(dados, cursorFrac) { cursorFrac = it }
                }
            }
            
            if (dados.temGAP) {
                item {
                    GraficoCard("Ritmo Ajustado (GAP)", "Verde = esforço equivalente em plano") {
                        GraficoGAP(dados, cursorFrac) { cursorFrac = it }
                    }
                }
            }
            
            if (dados.temCadencia) {
                item {
                    GraficoCard("Cadência", "Passos por minuto ao longo do percurso") {
                        GraficoCadencia(dados, cursorFrac) { cursorFrac = it }
                    }
                }
            }
            
            if (zonas.any { it.percentagem > 0f }) { item { CartaoZonas(zonas) } }

            // ── Análise do Treino ─────────────────────────────────────────────
            item { CartaoAnaliseTreino(corrida) }

            if (corrida.splitsParciais.isNotEmpty()) { 
                item { CartaoParciais(corrida.splitsParciais) } 
            }
        }
    }
}

@Composable
private fun CartaoMapa(rota: List<LatLngPonto>, pontoSelecionado: LatLngPonto?) {
    // FIX 1 — HEATMAP ASSÍNCRONO:
    // O cálculo anterior rodava dentro de remember() na main thread — para 2400+ pontos
    // (~2400 haversines) isso bloqueava o compositor antes do mapa aparecer, causando
    // tela branca e congelamento. Movido para LaunchedEffect + Dispatchers.Default.
    var polylinesMescladas by remember { mutableStateOf<List<Pair<List<LatLng>, Color>>>(emptyList()) }
    LaunchedEffect(rota) {
        val resultado = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val segmentos = calcularSegmentosHeatmapDetalhe(rota)
            mesclarPolylinesDetalhe(segmentos)
        }
        polylinesMescladas = resultado
    }
    
    val bounds = remember(rota) {
        val builder = LatLngBounds.builder()
        rota.forEach { builder.include(LatLng(it.lat, it.lng)) }
        builder.build()
    }
    
    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(bounds.center, 14f)
    }

    LaunchedEffect(bounds) {
        try { cameraState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 80)) }
        catch (_: Exception) { }
    }

    Card(colors = CardDefaults.cardColors(containerColor = CorCard), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Percurso", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Cores por pace (verde lento → vermelho rápido)", fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.4f), modifier = Modifier.padding(bottom = 12.dp))
            
            Box(modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(8.dp))) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraState,
                    properties = MapProperties(mapType = MapType.NORMAL),
                    uiSettings = MapUiSettings(
                        zoomControlsEnabled = false,
                        // FIX 2 — SCROLL BLOQUEADO:
                        // GoogleMap com scrollGesturesEnabled=true dentro de LazyColumn
                        // consome todos os eventos de toque verticais, impedindo o scroll
                        // da tela. Como a câmera já auto-centra na rota via LaunchedEffect,
                        // o usuário não precisa arrastar o mapa — apenas fazer zoom.
                        scrollGesturesEnabled = false,
                        zoomGesturesEnabled = true
                    )
                ) {
                    // Desenha polylines otimizadas
                    polylinesMescladas.forEach { (pontos, cor) ->
                        Polyline(points = pontos, color = cor, width = 12f, jointType = JointType.ROUND)
                    }

                    // Marcador do cursor interativo
                    pontoSelecionado?.let {
                        Marker(
                            state = MarkerState(LatLng(it.lat, it.lng)),
                            icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE),
                            alpha = 0.9f,
                            zIndex = 10f
                        )
                    }

                    if (rota.isNotEmpty()) {
                        Circle(
                            center = LatLng(rota.first().lat, rota.first().lng),
                            radius = 12.0,
                            fillColor = Color.White,
                            strokeColor = CorFundo,
                            strokeWidth = 4f
                        )
                        Circle(
                            center = LatLng(rota.last().lat, rota.last().lng),
                            radius = 12.0,
                            fillColor = Color(0xFFEF5350),
                            strokeColor = CorFundo,
                            strokeWidth = 4f
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GraficoPaceElevacao(
    dados: DadosGrafico, 
    frac: Float, 
    onFracChange: (Float) -> Unit
) {
    val idx = fracToIndex(frac, dados.paceNorm.size)
    Column {
        Row(modifier = Modifier.fillMaxWidth().height(22.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (idx >= 0) {
                Text("⏱ ${dados.paceFormatado[idx]}/km", color = CorPace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("⛰ ${dados.altitudes[idx].roundToInt()} m", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            } else {
                Text("Topo: ${formatarPaceSegKm(dados.paceMin)}/km  •  Base: ${formatarPaceSegKm(dados.paceMax)}/km",
                    color = CorPace.copy(alpha = 0.45f), fontSize = 10.sp)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth().height(160.dp)) {
            EixoYVertical(topLabel = formatarPaceSegKm(dados.paceMin), botLabel = formatarPaceSegKm(dados.paceMax), cor = CorPace)
            Canvas(modifier = Modifier.weight(1f).fillMaxHeight()
                .clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.03f))
                .cursorInput(onFracChange)
            ) {
                val w = size.width; val h = size.height; val drawH = h - 12f
                for (i in 1..3) drawLine(CorGrid, Offset(0f, h * i / 4), Offset(w, h * i / 4))
                
                // Área altitude
                if (dados.altNorm.size >= 2) {
                    val pathAlt = Path().apply {
                        moveTo(0f, h)
                        dados.altNorm.forEachIndexed { i, v ->
                            lineTo(i.toFloat() / (dados.altNorm.size - 1) * w, 4f + drawH * (1f - v))
                        }
                        lineTo(w, h); close()
                    }
                    drawPath(pathAlt, Brush.verticalGradient(
                        listOf(CorElevacao.copy(alpha = 0.4f), Color.Transparent), startY = 0f, endY = h))
                }
                
                // Linha pace
                if (dados.paceNorm.size >= 2) {
                    val path = Path()
                    var first = true
                    dados.paceNorm.forEachIndexed { i, v ->
                        val x = i.toFloat() / (dados.paceNorm.size - 1) * w
                        if (v < 0f) { first = true; return@forEachIndexed }
                        if (first) { path.moveTo(x, 4f + drawH * v); first = false }
                        else path.lineTo(x, 4f + drawH * v)
                    }
                    drawPath(path, CorPace, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
                }
                
                // Cursor visual
                if (idx >= 0 && dados.paceNorm.isNotEmpty()) {
                    val cx = frac.coerceIn(0f, 1f) * w
                    drawLine(CorCursor.copy(alpha = 0.5f), Offset(cx, 0f), Offset(cx, h), strokeWidth = 1.5f)
                    val v = dados.paceNorm[idx]
                    if (v >= 0f) {
                        val cy = 4f + drawH * v
                        drawCircle(CorPace, radius = 7f, center = Offset(cx, cy))
                        drawCircle(Color.White, radius = 3f, center = Offset(cx, cy))
                    }
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
            EixoYVertical(
                topLabel = "${dados.altitudes.maxOrNull()?.roundToInt()}m",
                botLabel = "${dados.altitudes.minOrNull()?.roundToInt()}m",
                cor = CorElevacao,
                align = TextAlign.Start
            )
        }
    }
}

@Composable
private fun GraficoGAP(dados: DadosGrafico, frac: Float, onFracChange: (Float) -> Unit) {
    val idx = fracToIndex(frac, dados.gapNorm.size)
    Column {
        Row(modifier = Modifier.fillMaxWidth().height(22.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (idx >= 0) {
                Text("⚡ GAP: ${dados.gapFormatado[idx]}/km", color = CorGAP, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("Real: ${dados.paceFormatado[idx]}/km", color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
            } else {
                Text("Ritmo ajustado pela inclinação do terreno", color = CorGAP.copy(alpha = 0.5f), fontSize = 10.sp)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth().height(120.dp)) {
            EixoYVertical(topLabel = formatarPaceSegKm(dados.gapMin), botLabel = formatarPaceSegKm(dados.gapMax), cor = CorGAP)
            Canvas(modifier = Modifier.weight(1f).fillMaxHeight()
                .clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.03f))
                .cursorInput(onFracChange)
            ) {
                val w = size.width; val h = size.height; val drawH = h - 12f
                for (i in 1..3) drawLine(CorGrid, Offset(0f, h * i / 4), Offset(w, h * i / 4))
                if (dados.gapNorm.size >= 2) {
                    val pathArea = Path().apply {
                        moveTo(0f, h)
                        dados.gapNorm.forEachIndexed { i, v ->
                            val safeV = if (v < 0f) 1f else v
                            lineTo(i.toFloat() / (dados.gapNorm.size - 1) * w, 4f + drawH * safeV)
                        }
                        lineTo(w, h); close()
                    }
                    drawPath(pathArea, Brush.verticalGradient(listOf(CorGAP.copy(alpha = 0.2f), Color.Transparent), 0f, h))
                    val pathLine = Path()
                    var first = true
                    dados.gapNorm.forEachIndexed { i, v ->
                        val x = i.toFloat() / (dados.gapNorm.size - 1) * w
                        if (v < 0f) { first = true; return@forEachIndexed }
                        if (first) { pathLine.moveTo(x, 4f + drawH * v); first = false } else pathLine.lineTo(x, 4f + drawH * v)
                    }
                    drawPath(pathLine, CorGAP, style = Stroke(width = 2f, cap = StrokeCap.Round))
                }
                if (idx >= 0) {
                    val cx = frac.coerceIn(0f, 1f) * w
                    drawLine(CorCursor.copy(alpha = 0.5f), Offset(cx, 0f), Offset(cx, h), strokeWidth = 1.5f)
                }
            }
            Spacer(modifier = Modifier.width(35.dp))
        }
    }
}

@Composable
private fun GraficoCadencia(dados: DadosGrafico, frac: Float, onFracChange: (Float) -> Unit) {
    val idx = fracToIndex(frac, dados.cadencias.size)
    val cadValidas = dados.cadencias.filter { it > 0 }
    val cadMin = cadValidas.minOrNull()?.toFloat() ?: 140f
    val cadMax = cadValidas.maxOrNull()?.toFloat() ?: 200f
    val cadMedia = if (cadValidas.isNotEmpty()) cadValidas.average().roundToInt() else 0
    Column {
        Row(modifier = Modifier.fillMaxWidth().height(22.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (idx >= 0 && dados.cadencias[idx] > 0)
                Text("👟 ${dados.cadencias[idx]} spm", color = CorCadencia, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            else
                Text("Média: $cadMedia spm", color = CorCadencia.copy(alpha = 0.6f), fontSize = 11.sp)
            Text("${cadMin.toInt()} – ${cadMax.toInt()} spm", color = Color.White.copy(alpha = 0.3f), fontSize = 10.sp)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth().height(100.dp)) {
            EixoYVertical(topLabel = "${cadMax.toInt()}", botLabel = "${cadMin.toInt()}", cor = CorCadencia)
            Canvas(modifier = Modifier.weight(1f).fillMaxHeight()
                .clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.03f))
                .cursorInput(onFracChange)
            ) {
                val w = size.width; val h = size.height
                val barW = (w / dados.cadencias.size).coerceAtLeast(1f)
                for (i in 1..3) drawLine(CorGrid, Offset(0f, h * i / 4), Offset(w, h * i / 4))
                
                dados.cadencias.forEachIndexed { i, spm ->
                    if (spm > 0) {
                        val ratio = ((spm - cadMin) / (cadMax - cadMin).coerceAtLeast(1f)).coerceIn(0f, 1f)
                        val barH = h * (0.2f + 0.8f * ratio)
                        val isSelected = i == idx
                        val cor = if (isSelected) Color.White else lerpCorCadencia(ratio).copy(alpha = 0.8f)
                        drawRect(cor, Offset(i * barW, h - barH), Size(barW - 1f, barH))
                    }
                }
            }
            Spacer(modifier = Modifier.width(35.dp))
        }
    }
}

// ── UI Components ────────────────────────────────────────────────────────────

@Composable
private fun GraficoCard(titulo: String, subtitulo: String, conteudo: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CorCard), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(titulo, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(subtitulo, fontSize = 11.sp, color = Color.White.copy(alpha = 0.4f), modifier = Modifier.padding(bottom = 12.dp))
            conteudo()
        }
    }
}

@Composable
private fun EixoYVertical(topLabel: String, botLabel: String, cor: Color, align: TextAlign = TextAlign.End) {
    Column(modifier = Modifier.fillMaxHeight().width(35.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Text(topLabel, fontSize = 9.sp, color = cor, textAlign = align, modifier = Modifier.fillMaxWidth())
        Text(botLabel, fontSize = 9.sp, color = cor.copy(alpha = 0.5f), textAlign = align, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun CartaoResumo(corrida: CorridaHistorico) {
    Card(colors = CardDefaults.cardColors(containerColor = CorCard), shape = RoundedCornerShape(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            MetricaDetalhe("DISTÂNCIA", "%.2f".format(corrida.distanciaKm), "km")
            DivisorVertical()
            MetricaDetalhe("TEMPO", corrida.tempoFormatado, "")
            DivisorVertical()
            MetricaDetalhe("PACE MÉDIO", corrida.paceMedia, "/km")
            if (corrida.ganhoElevacaoM > 0) { DivisorVertical(); MetricaDetalhe("D+", "+${corrida.ganhoElevacaoM}", "m") }
        }
    }
}

@Composable
private fun MetricaDetalhe(label: String, valor: String, unidade: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 9.sp, color = Color.White.copy(alpha = 0.4f), fontWeight = FontWeight.Bold)
        Text(valor, fontSize = 17.sp, color = Color.White, fontWeight = FontWeight.Bold)
        if (unidade.isNotEmpty()) Text(unidade, fontSize = 10.sp, color = Color.White.copy(alpha = 0.4f))
    }
}

@Composable
private fun DivisorVertical() {
    Box(modifier = Modifier.width(1.dp).height(32.dp).background(Color.White.copy(alpha = 0.08f)))
}

@Composable
private fun CartaoZonas(zonas: List<InfoZona>) {
    Card(colors = CardDefaults.cardColors(containerColor = CorCard), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Zonas de ritmo", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(14.dp))
            
            // Barra horizontal de zonas
            Row(modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
                zonas.forEachIndexed { idx, z ->
                    if (z.percentagem > 0f) {
                        val cor = runCatching { Color(android.graphics.Color.parseColor(z.cor)) }
                            .getOrElse { CoreZonas.getOrElse(idx) { CoreZonas.last() } }
                        Box(modifier = Modifier.weight(z.percentagem).fillMaxHeight().background(cor))
                    }
                }
            }
            
            // Legenda detalhada
            zonas.forEachIndexed { idx, z ->
                val cor = runCatching { Color(android.graphics.Color.parseColor(z.cor)) }
                    .getOrElse { CoreZonas.getOrElse(idx) { CoreZonas.last() } }
                Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(10.dp).background(cor, CircleShape))
                    Text(" Z${idx + 1} ${z.nome}", modifier = Modifier.weight(1f).padding(start = 8.dp), fontSize = 12.sp, color = Color.White)
                    if (z.tempo.isNotEmpty()) Text("${z.tempo}  ", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
                    Text("${z.percentagem.roundToInt()}%", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = cor)
                }
            }
        }
    }
}

@Composable
private fun CartaoAnaliseTreino(corrida: CorridaHistorico) {
    // Decide qual fonte de dados usar:
    // 1. voltasAnalise preenchido → corrida com intervalos detectados → mostra por volta
    // 2. Só splitsParciais → corrida uniforme → mostra pace por km
    // 3. Sem nenhum dos dois → nada a mostrar
    val hasVoltas  = corrida.voltasAnalise.isNotEmpty()
    val hasSplits  = corrida.splitsParciais.isNotEmpty()
    if (!hasVoltas && !hasSplits) return

    // Unifica em uma lista de "barras" com a mesma estrutura visual
    data class Barra(
        val label: String,      // "V1", "V2" / "1km", "2km"
        val pace: Double,       // seg/km — para calcular altura
        val paceStr: String,    // "5:30"
        val tempo: String,      // "2:14"
        val dist: String,       // "0.4 km"
        val isDescanso: Boolean
    )

    val barras: List<Barra> = if (hasVoltas) {
        corrida.voltasAnalise.map { v ->
            val t = v.tempoSegundos
            Barra(
                label     = "V${v.numero}",
                pace      = v.paceSegKm,
                paceStr   = v.paceFormatado,
                tempo     = "%d:%02d".format(t / 60, t % 60),
                dist      = "%.2f km".format(v.distanciaKm),
                isDescanso = v.isDescanso
            )
        }
    } else {
        corrida.splitsParciais.map { s ->
            val t = (s.paceSegKm).toLong()  // pace ≈ tempo do km
            Barra(
                label     = "${s.km}km",
                pace      = s.paceSegKm,
                paceStr   = s.paceFormatado,
                tempo     = "%d:%02d".format(t / 60, t % 60),
                dist      = "1.00 km",
                isDescanso = false
            )
        }
    }

    if (barras.isEmpty()) return

    // Escala de altura: pace MENOR (mais rápido) → barra MAIS ALTA
    val paceMin = barras.minOf { it.pace }
    val paceMax = barras.maxOf { it.pace }
    val paceRange = (paceMax - paceMin).coerceAtLeast(1.0)

    // Conta rápidas/lentas para o resumo de topo
    val nRapidas  = if (hasVoltas) corrida.voltasAnalise.count { !it.isDescanso } else 0
    val nLentas   = if (hasVoltas) corrida.voltasAnalise.count {  it.isDescanso } else 0
    val melhorPace = barras.minByOrNull { it.pace }

    val CorRapida  = Color(0xFF4A90D9)
    val CorLenta   = Color(0xFF3D5A73)
    val CorSplit   = Color(0xFF4FC3F7)
    val CorDestaque = Color(0xFFFFD54F)  // amarelo — volta mais rápida

    Card(
        colors = CardDefaults.cardColors(containerColor = CorCard),
        shape  = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // ── Cabeçalho ──────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (hasVoltas) "Análise do Treino" else "Análise do Treino",
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White
                    )
                    Text(
                        text = if (hasVoltas)
                            "${corrida.voltasAnalise.size} intervalos detectados  •  $nRapidas rápidos  •  $nLentas recuperação"
                        else
                            "${barras.size} km  •  pace por parcial",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                // Pílula com o melhor pace
                melhorPace?.let { m ->
                    Column(horizontalAlignment = Alignment.End) {
                        Text("melhor", fontSize = 9.sp, color = Color.White.copy(alpha = 0.35f))
                        Text(
                            text = m.paceStr,
                            fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            color = CorDestaque
                        )
                        Text("/km", fontSize = 9.sp, color = Color.White.copy(alpha = 0.35f))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Gráfico de barras scrollável ────────────────────────────────
            val barW = if (barras.size <= 8) 0.dp else 48.dp  // fixo se couber, senão scroll
            val contentPadH = 4.dp
            val chartH = 120.dp

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = contentPadH)
            ) {
                items(barras) { barra ->
                    val isBest = barra.pace == paceMin
                    val heightFrac = (1.0 - (barra.pace - paceMin) / paceRange).toFloat()
                        .coerceIn(0.15f, 1f)
                    val corBarra = when {
                        isBest      -> CorDestaque
                        barra.isDescanso -> CorLenta
                        !hasVoltas  -> CorSplit
                        else        -> CorRapida
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(if (barras.size <= 8) IntrinsicSize.Min else barW)
                    ) {
                        // Pace em cima da barra (apenas nas rápidas ou na melhor)
                        if (!barra.isDescanso || isBest) {
                            Text(
                                text = barra.paceStr,
                                fontSize = 8.sp,
                                color = if (isBest) CorDestaque else Color.White.copy(alpha = 0.55f),
                                fontWeight = if (isBest) FontWeight.Bold else FontWeight.Normal
                            )
                        } else {
                            Spacer(Modifier.height(12.dp))
                        }
                        Spacer(Modifier.height(2.dp))

                        // Barra com altura proporcional ao pace
                        Box(
                            modifier = Modifier
                                .width(if (barras.size <= 8) 32.dp else 36.dp)
                                .height(chartH),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(heightFrac)
                                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                    .background(corBarra.copy(alpha = if (barra.isDescanso) 0.5f else 0.9f))
                            )
                            // Linha tracejada de referência no topo do container
                            // (equivalente ao pace médio — baseline visual)
                        }

                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = barra.label,
                            fontSize = 9.sp,
                            color = if (isBest) CorDestaque else Color.White.copy(alpha = 0.5f),
                            fontWeight = if (isBest) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            // ── Linha de pace médio (Canvas overlay) ───────────────────────
            // Desenhada separadamente como referência visual horizontal
            Spacer(Modifier.height(2.dp))
            if (barras.size > 1) {
                val paceMediaSegKm = barras.map { it.pace }.average()
                val fracMedia = (1.0 - (paceMediaSegKm - paceMin) / paceRange).toFloat()
                    .coerceIn(0f, 1f)
                Canvas(modifier = Modifier.fillMaxWidth().height(1.dp)) {
                    // apenas um ponto visual — linha real no chart area
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(Color.White.copy(alpha = 0.2f), CircleShape)
                    )
                    Spacer(Modifier.width(6.dp))
                    val min = (paceMediaSegKm / 60).toInt()
                    val sec = (paceMediaSegKm % 60).toInt()
                    Text(
                        text = "Pace médio: %d:%02d /km".format(min, sec),
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.35f)
                    )
                }
            }

            // ── Legenda ─────────────────────────────────────────────────────
            if (hasVoltas) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LegendaItem(CorRapida, "Rápido")
                    LegendaItem(CorLenta.copy(alpha = 0.5f), "Recuperação")
                    LegendaItem(CorDestaque, "Melhor volta")
                }
            }
        }
    }
}

@Composable
private fun LegendaItem(cor: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).background(cor, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 9.sp, color = Color.White.copy(alpha = 0.4f))
    }
}

@Composable
private fun CartaoParciais(splits: List<SplitParcial>) {
    Card(colors = CardDefaults.cardColors(containerColor = CorCard), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Parciais por km", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(8.dp))
            splits.forEach { s ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("${s.km} km", modifier = Modifier.width(60.dp), fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
                    Text(s.paceFormatado, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = CorPace)
                }
            }
        }
    }
}

// ── Inputs & Calculations ───────────────────────────────────────────────────

private fun Modifier.cursorInput(onFrac: (Float) -> Unit): Modifier =
    this
        .pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { o -> onFrac(o.x / size.width) },
                onDrag      = { c, _ -> c.consume(); onFrac(c.position.x / size.width) },
                onDragEnd   = { onFrac(-1f) },
                onDragCancel = { onFrac(-1f) }
            )
        }
        .pointerInput(Unit) {
            detectTapGestures(onPress = { o -> onFrac(o.x / size.width) })
        }

private fun fracToIndex(frac: Float, size: Int): Int {
    if (frac < 0f || size == 0) return -1
    return (frac * (size - 1)).roundToInt().coerceIn(0, size - 1)
}

data class DadosGrafico(
    val paceNorm:      List<Float>,
    val altNorm:       List<Float>,
    val gapNorm:       List<Float>,
    val paceFormatado: List<String>,
    val gapFormatado:  List<String>,
    val altitudes:     List<Double>,
    val cadencias:     List<Int>,
    val indicesOriginais: List<Int>, // Mapeamento para cursor no mapa
    val temCadencia:   Boolean,
    val temGAP:        Boolean,
    val paceMin: Double,
    val paceMax: Double,
    val gapMin:  Double,
    val gapMax:  Double
)

data class InfoZona(val nome: String, val percentagem: Float, val tempo: String, val cor: String = "")

private fun prepararDados(rota: List<LatLngPonto>): DadosGrafico {
    val vazio = DadosGrafico(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), emptyList(), false, false, 300.0, 600.0, 300.0, 600.0)
    if (rota.size < 2) return vazio

    // Suavização de altitude (média móvel)
    val altsSuav = rota.mapIndexed { i, _ ->
        val a = (i - 5).coerceAtLeast(0); val b = (i + 5).coerceAtMost(rota.lastIndex)
        rota.subList(a, b + 1).map { it.alt }.average()
    }
    
    val paces = rota.map { it.paceNoPonto }
    val pacesValidos = paces.filter { it in 60.0..1200.0 }
    val pMin = pacesValidos.minOrNull() ?: 300.0
    val pMax = pacesValidos.maxOrNull() ?: 600.0

    // GAP: Ritmo Ajustado pela Inclinação
    val gaps = rota.mapIndexed { i, pt ->
        val p = pt.paceNoPonto
        if (p !in 60.0..1200.0) return@mapIndexed 0.0
        val grad = if (i > 0) {
            val dAlt = altsSuav[i] - altsSuav[i - 1]
            val dDist = haversineM(rota[i - 1].lat, rota[i - 1].lng, pt.lat, pt.lng).coerceAtLeast(0.5)
            (dAlt / dDist * 100).coerceIn(-30.0, 30.0)
        } else 0.0
        (p * (1.0 + 0.033 * grad + 0.00012 * grad.pow(2))).coerceIn(60.0, 1200.0)
    }
    val gMin = gaps.filter { it > 0 }.minOrNull() ?: pMin
    val gMax = gaps.filter { it > 0 }.maxOrNull() ?: pMax

    // Downsampling para performance (max 300 pontos no gráfico)
    val step = maxOf(1, rota.size / 300)
    val idxs = rota.indices.filter { it % step == 0 }

    val paceRange = (pMax - pMin).coerceAtLeast(1.0)
    val gapRange  = (gMax - gMin).coerceAtLeast(1.0)
    val altMin = altsSuav.min(); val altMax = altsSuav.max()
    val altRange  = (altMax - altMin).coerceAtLeast(1.0)

    return DadosGrafico(
        paceNorm = idxs.map { i ->
            val p = paces[i]; if (p !in 60.0..1200.0) -1f
            else ((p - pMin) / paceRange).toFloat().coerceIn(0f, 1f)
        },
        altNorm = idxs.map { i ->
            ((altsSuav[i] - altMin) / altRange).toFloat().coerceIn(0f, 1f)
        },
        gapNorm = idxs.map { i ->
            val g = gaps[i]; if (g <= 0) -1f
            else ((g - gMin) / gapRange).toFloat().coerceIn(0f, 1f)
        },
        paceFormatado = idxs.map { formatarPaceSegKm(paces[it]) },
        gapFormatado  = idxs.map { formatarPaceSegKm(gaps[it]) },
        altitudes     = idxs.map { altsSuav[it] },
        cadencias     = idxs.map { rota[it].cadenciaNoPonto },
        indicesOriginais = idxs,
        temCadencia   = rota.any { it.cadenciaNoPonto > 0 },
        temGAP        = (altMax - altMin) > 10.0,
        paceMin = pMin, paceMax = pMax, gapMin = gMin, gapMax = gMax
    )
}

private fun calcularZonasPorTempo(
    rota: List<LatLngPonto>,
    zonasFronteira: List<ZonaFronteira>
): List<InfoZona> {
    if (zonasFronteira.isEmpty() || rota.size < 2) return emptyList()

    val tempoMs = LongArray(zonasFronteira.size)
    var totalMs = 0L

    for (i in 0 until rota.size - 1) {
        val pace = rota[i].paceNoPonto
        if (pace < 60.0 || pace > 1800.0) continue

        // FIX PRECISÃO: gap máximo de 60s para manter estatísticas reais
        val intervaloMs = (rota[i + 1].tempo - rota[i].tempo).coerceAtLeast(0L)
        if (intervaloMs <= 0L || intervaloMs > 60_000L) continue 

        var zonaIdx = zonasFronteira.lastIndex
        for (z in zonasFronteira.indices) {
            val zf = zonasFronteira[z]
            val dentroDoTeto = zf.paceMaxSegKm == null || pace <= zf.paceMaxSegKm
            if (pace >= zf.paceMinSegKm && dentroDoTeto) { zonaIdx = z; break }
        }

        tempoMs[zonaIdx] += intervaloMs
        totalMs += intervaloMs
    }

    if (totalMs == 0L) return emptyList()

    return zonasFronteira.mapIndexed { i, z ->
        val pct = tempoMs[i].toFloat() / totalMs * 100f
        val seg = tempoMs[i] / 1000L
        InfoZona(z.nome, pct, "%d:%02d".format(seg / 60, seg % 60), z.cor)
    }
}

// ── Heatmap & Otimização de Polylines ────────────────────────────────────────

private data class SegDetalhe(val inicio: LatLng, val fim: LatLng, val cor: Color)

private fun calcularSegmentosHeatmapDetalhe(rota: List<LatLngPonto>): List<SegDetalhe> {
    if (rota.size < 2) return emptyList()
    val LENTO = 7 * 60.0; val RAPIDO = 3 * 60.0 + 30.0
    return buildList {
        for (i in 0 until rota.size - 1) {
            val p1 = rota[i]; val p2 = rota[i + 1]
            val dtMs = p2.tempo - p1.tempo; if (dtMs <= 0) continue
            val distM = haversineM(p1.lat, p1.lng, p2.lat, p2.lng); if (distM < 1.0) continue
            val pace = (dtMs / 1000.0) / distM * 1000.0
            if (pace < 120.0 || pace > 1200.0) continue
            add(SegDetalhe(LatLng(p1.lat, p1.lng), LatLng(p2.lat, p2.lng), corPorPaceD(pace, RAPIDO, LENTO)))
        }
    }
}

/**
 * FIX PERFORMANCE: Agrupa segmentos de mesma cor em Polylines únicas.
 * Reduz de O(N_pontos) para O(N_mudanças_de_pace) Polylines no mapa.
 */
private fun mesclarPolylinesDetalhe(segmentos: List<SegDetalhe>): List<Pair<List<LatLng>, Color>> {
    if (segmentos.isEmpty()) return emptyList()
    val resultado = mutableListOf<Pair<List<LatLng>, Color>>()
    var corAtual = segmentos[0].cor
    var pontosAtual = mutableListOf(segmentos[0].inicio, segmentos[0].fim)

    for (i in 1 until segmentos.size) {
        val seg = segmentos[i]
        if (coresSimilaresD(seg.cor, corAtual)) {
            pontosAtual.add(seg.fim)
        } else {
            resultado.add(Pair(pontosAtual.toList(), corAtual))
            corAtual = seg.cor
            pontosAtual = mutableListOf(seg.inicio, seg.fim)
        }
    }
    resultado.add(Pair(pontosAtual.toList(), corAtual))
    return resultado
}

private fun coresSimilaresD(a: Color, b: Color) = 
    abs(a.red - b.red) < 0.05f && abs(a.green - b.green) < 0.05f && abs(a.blue - b.blue) < 0.05f

private fun corPorPaceD(pace: Double, rapido: Double, lento: Double): Color {
    val t = ((pace - rapido) / (lento - rapido)).coerceIn(0.0, 1.0).toFloat()
    return when {
        t < 0.33f -> lerpD(Color(0xFFF44336), Color(0xFFFF9800), t / 0.33f)
        t < 0.66f -> lerpD(Color(0xFFFF9800), Color(0xFFFFEB3B), (t - 0.33f) / 0.33f)
        else      -> lerpD(Color(0xFFFFEB3B), Color(0xFF4CAF50), (t - 0.66f) / 0.34f)
    }
}

private fun lerpD(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f)

private fun formatarPaceSegKm(s: Double): String {
    if (s !in 60.0..1200.0) return "--:--"
    return "%d:%02d".format((s / 60).toInt(), (s % 60).toInt())
}

private fun lerpCorCadencia(t: Float): Color =
    if (t < 0.5f) lerpD(Color(0xFF4CAF50), Color(0xFFFF9800), t / 0.5f)
    else          lerpD(Color(0xFFFF9800), Color(0xFFEF5350), (t - 0.5f) / 0.5f)

private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6_371_000.0; val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return R * 2 * atan2(sqrt(a), sqrt(1 - a))
}

private fun formatarDataDetalhe(iso: String): String = runCatching {
    java.time.LocalDateTime.parse(iso).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
}.getOrDefault(iso)