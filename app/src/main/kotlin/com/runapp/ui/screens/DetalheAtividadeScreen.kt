package com.runapp.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import com.runapp.data.model.CorridaHistorico
import com.runapp.data.model.LatLngPonto
import com.runapp.data.model.SplitParcial
import kotlin.math.*
import kotlin.math.roundToInt

private val CorFundo    = Color(0xFF121212)
private val CorCard     = Color(0xFF1E1E1E)
private val CorPace     = Color(0xFF4FC3F7)
private val CorElevacao = Color(0xFF546E7A)
private val CorCadencia = Color(0xFFFF8A65)
private val CorGAP      = Color(0xFF81C784)
private val CorCursor   = Color(0xFFFFFFFF)
private val CorGrid     = Color(0xFFFFFFFF)
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
    val zonas = remember(dados) { calcularZonasPace(dados) }

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
            if (rota.size >= 2) { item { CartaoMapa(rota) } }
            item {
                GraficoCard("Pace e Elevação", "Azul = ritmo  •  Área = altitude") {
                    GraficoPaceElevacao(dados)
                }
            }
            if (dados.temGAP) {
                item {
                    GraficoCard("Ritmo Ajustado (GAP)", "Verde = esforço equivalente em plano") {
                        GraficoGAP(dados)
                    }
                }
            }
            if (dados.temCadencia) {
                item {
                    GraficoCard("Cadência", "Passos por minuto ao longo do percurso") {
                        GraficoCadencia(dados)
                    }
                }
            }
            if (zonas.any { it.percentagem > 0f }) { item { CartaoZonas(zonas) } }
            if (corrida.splitsParciais.isNotEmpty()) { item { CartaoParciais(corrida.splitsParciais) } }
        }
    }
}

@Composable
private fun CartaoMapa(rota: List<LatLngPonto>) {
    val segmentos = remember(rota) { calcularSegmentosHeatmapDetalhe(rota) }
    val bounds = remember(rota) {
        val builder = LatLngBounds.builder()
        rota.forEach { builder.include(LatLng(it.lat, it.lng)) }
        builder.build()
    }
    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(bounds.center, 14f)
    }
    LaunchedEffect(bounds) {
        try { cameraState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 60)) }
        catch (_: Exception) { }
    }
    Card(colors = CardDefaults.cardColors(containerColor = CorCard), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Percurso", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Cores = pace (verde lento → vermelho rápido)", fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.4f), modifier = Modifier.padding(bottom = 12.dp))
            Box(modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(8.dp))) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraState,
                    properties = MapProperties(mapType = MapType.NORMAL),
                    uiSettings = MapUiSettings(
                        zoomControlsEnabled = false,
                        scrollGesturesEnabled = false,
                        rotationGesturesEnabled = false,
                        tiltGesturesEnabled = false,
                        zoomGesturesEnabled = false
                    )
                ) {
                    segmentos.forEach { seg ->
                        Polyline(points = listOf(seg.inicio, seg.fim), color = seg.cor, width = 14f)
                    }
                    if (rota.isNotEmpty()) {
                        Marker(state = MarkerState(LatLng(rota.first().lat, rota.first().lng)), title = "Início")
                        if (rota.size > 1)
                            Marker(state = MarkerState(LatLng(rota.last().lat, rota.last().lng)), title = "Fim")
                    }
                }
            }
        }
    }
}

@Composable
private fun GraficoPaceElevacao(dados: DadosGrafico) {
    var frac by remember { mutableFloatStateOf(-1f) }
    val idx = fracToIndex(frac, dados.paceNorm.size)
    Column {
        // Tooltip
        Row(modifier = Modifier.fillMaxWidth().height(22.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (idx >= 0) {
                Text("⏱ ${dados.paceFormatado[idx]}/km", color = CorPace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("⛰ ${dados.altitudes[idx].roundToInt()} m", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            } else {
                Text("Mais rápido: ${formatarPaceSegKm(dados.paceMin)}/km  •  Mais lento: ${formatarPaceSegKm(dados.paceMax)}/km",
                    color = CorPace.copy(alpha = 0.45f), fontSize = 10.sp)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth().height(160.dp)) {
            // Eixo Y esquerdo: pace. TOPO = mais rápido (paceMin), BASE = mais lento (paceMax)
            EixoYVertical(topLabel = formatarPaceSegKm(dados.paceMin), botLabel = formatarPaceSegKm(dados.paceMax), cor = CorPace)
            Canvas(modifier = Modifier.weight(1f).fillMaxHeight()
                .clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.03f))
                .cursorInput { frac = it }
            ) {
                val w = size.width; val h = size.height; val drawH = h - 12f
                for (i in 1..3) drawLine(CorGrid.copy(alpha = 0.05f), Offset(0f, h * i / 4), Offset(w, h * i / 4))
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
                        listOf(CorElevacao.copy(alpha = 0.45f), Color.Transparent), startY = 0f, endY = h))
                }
                // Linha pace (salta pontos inválidos)
                if (dados.paceNorm.size >= 2) {
                    val path = Path()
                    var primeiroPontoValido = true
                    dados.paceNorm.forEachIndexed { i, v ->
                        val x = i.toFloat() / (dados.paceNorm.size - 1) * w
                        if (v < 0f) { primeiroPontoValido = true; return@forEachIndexed }
                        if (primeiroPontoValido) { path.moveTo(x, 4f + drawH * v); primeiroPontoValido = false }
                        else path.lineTo(x, 4f + drawH * v)
                    }
                    drawPath(path, CorPace, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
                }
                // Cursor
                if (idx >= 0 && dados.paceNorm.isNotEmpty() && dados.paceNorm[idx] >= 0f) {
                    val cx = frac.coerceIn(0f, 1f) * w
                    val cy = 4f + drawH * dados.paceNorm[idx]
                    drawLine(CorCursor.copy(alpha = 0.5f), Offset(cx, 0f), Offset(cx, h), strokeWidth = 1.5f)
                    drawCircle(CorPace, radius = 7f, center = Offset(cx, cy))
                    drawCircle(Color.White, radius = 3f, center = Offset(cx, cy))
                }
            }
            // Eixo Y direito: altitude. TOPO = max altitude, BASE = min altitude
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
private fun GraficoGAP(dados: DadosGrafico) {
    var frac by remember { mutableFloatStateOf(-1f) }
    val idx = fracToIndex(frac, dados.gapNorm.size)
    Column {
        Row(modifier = Modifier.fillMaxWidth().height(22.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (idx >= 0) {
                Text("⚡ GAP: ${dados.gapFormatado[idx]}/km", color = CorGAP, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("Real: ${dados.paceFormatado[idx]}/km", color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
            } else {
                Text("Mais rápido: ${formatarPaceSegKm(dados.gapMin)}/km  •  Mais lento: ${formatarPaceSegKm(dados.gapMax)}/km",
                    color = CorGAP.copy(alpha = 0.45f), fontSize = 10.sp)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth().height(120.dp)) {
            EixoYVertical(topLabel = formatarPaceSegKm(dados.gapMin), botLabel = formatarPaceSegKm(dados.gapMax), cor = CorGAP)
            Canvas(modifier = Modifier.weight(1f).fillMaxHeight()
                .clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.03f))
                .cursorInput { frac = it }
            ) {
                val w = size.width; val h = size.height; val drawH = h - 12f
                for (i in 1..3) drawLine(CorGrid.copy(alpha = 0.05f), Offset(0f, h * i / 4), Offset(w, h * i / 4))
                if (dados.gapNorm.size >= 2) {
                    val pathArea = Path().apply {
                        moveTo(0f, h)
                        dados.gapNorm.forEachIndexed { i, v ->
                            val safeV = if (v < 0f) 1f else v
                            lineTo(i.toFloat() / (dados.gapNorm.size - 1) * w, 4f + drawH * safeV)
                        }
                        lineTo(w, h); close()
                    }
                    drawPath(pathArea, Brush.verticalGradient(listOf(CorGAP.copy(alpha = 0.25f), Color.Transparent), 0f, h))
                    val pathLine = Path()
                    var first = true
                    dados.gapNorm.forEachIndexed { i, v ->
                        val x = i.toFloat() / (dados.gapNorm.size - 1) * w
                        if (v < 0f) { first = true; return@forEachIndexed }
                        if (first) { pathLine.moveTo(x, 4f + drawH * v); first = false } else pathLine.lineTo(x, 4f + drawH * v)
                    }
                    drawPath(pathLine, CorGAP, style = Stroke(width = 2f, cap = StrokeCap.Round))
                }
                if (idx >= 0 && dados.gapNorm.isNotEmpty() && dados.gapNorm[idx] >= 0f) {
                    val cx = frac.coerceIn(0f, 1f) * w
                    val cy = 4f + drawH * dados.gapNorm[idx]
                    drawLine(CorCursor.copy(alpha = 0.5f), Offset(cx, 0f), Offset(cx, h), strokeWidth = 1.5f)
                    drawCircle(CorGAP, radius = 7f, center = Offset(cx, cy))
                    drawCircle(Color.White, radius = 3f, center = Offset(cx, cy))
                }
            }
            Spacer(modifier = Modifier.width(39.dp))
        }
    }
}

@Composable
private fun GraficoCadencia(dados: DadosGrafico) {
    var frac by remember { mutableFloatStateOf(-1f) }
    val idx = fracToIndex(frac, dados.cadencias.size)
    val cadValidas = dados.cadencias.filter { it > 0 }
    val cadMin = cadValidas.minOrNull()?.toFloat() ?: 100f
    val cadMax = cadValidas.maxOrNull()?.toFloat() ?: 220f
    val cadMedia = if (cadValidas.isNotEmpty()) cadValidas.average().roundToInt() else 0
    Column {
        Row(modifier = Modifier.fillMaxWidth().height(22.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (idx >= 0 && dados.cadencias[idx] > 0)
                Text("👟 ${dados.cadencias[idx]} spm", color = CorCadencia, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            else
                Text("Média: $cadMedia spm", color = CorCadencia.copy(alpha = 0.5f), fontSize = 11.sp)
            Text("${cadMin.roundToInt()} – ${cadMax.roundToInt()} spm", color = Color.White.copy(alpha = 0.3f), fontSize = 10.sp)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth().height(100.dp)) {
            EixoYVertical(topLabel = "${cadMax.roundToInt()}", botLabel = "${cadMin.roundToInt()}", cor = CorCadencia)
            Spacer(modifier = Modifier.width(4.dp))
            Canvas(modifier = Modifier.weight(1f).fillMaxHeight()
                .clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.03f))
                .cursorInput { frac = it }
            ) {
                val w = size.width; val h = size.height
                val barW = (w / dados.cadencias.size).coerceAtLeast(1f)
                for (i in 1..3) drawLine(CorGrid.copy(alpha = 0.05f), Offset(0f, h * i / 4), Offset(w, h * i / 4))
                dados.cadencias.forEachIndexed { i, spm ->
                    if (spm > 0) {
                        val ratio = ((spm - cadMin) / (cadMax - cadMin).coerceAtLeast(1f)).coerceIn(0f, 1f)
                        val barH = h * (0.15f + 0.85f * ratio)
                        val isSelected = i == idx
                        val cor = if (isSelected) Color.White else lerpCorCadencia(ratio).copy(alpha = 0.8f)
                        drawRect(cor, Offset(i * barW, h - barH), Size(barW - 1f, barH))
                    }
                }
                if (idx >= 0) {
                    val cx = frac.coerceIn(0f, 1f) * w
                    drawLine(CorCursor.copy(alpha = 0.4f), Offset(cx, 0f), Offset(cx, h), strokeWidth = 1.5f)
                }
            }
            Spacer(modifier = Modifier.width(39.dp))
        }
    }
}

// ── UI helpers ───────────────────────────────────────────────────────────────

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
        Text(label, fontSize = 9.sp, color = Color.White.copy(alpha = 0.5f), fontWeight = FontWeight.Bold)
        Text(valor, fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Bold)
        if (unidade.isNotEmpty()) Text(unidade, fontSize = 9.sp, color = Color.White.copy(alpha = 0.5f))
    }
}

@Composable
private fun DivisorVertical() {
    Box(modifier = Modifier.width(1.dp).height(30.dp).background(Color.White.copy(alpha = 0.1f)))
}

@Composable
private fun CartaoZonas(zonas: List<InfoZona>) {
    Card(colors = CardDefaults.cardColors(containerColor = CorCard), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Zonas de ritmo", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
                zonas.forEachIndexed { idx, z ->
                    if (z.percentagem > 0f) Box(modifier = Modifier.weight(z.percentagem).fillMaxHeight().background(CoreZonas[idx]))
                }
            }
            zonas.forEachIndexed { idx, z ->
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).background(CoreZonas[idx], CircleShape))
                    Text(" Z${idx + 1} ${z.nome}", modifier = Modifier.weight(1f), fontSize = 12.sp, color = Color.White)
                    Text("${z.percentagem.roundToInt()}%", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CoreZonas[idx])
                }
            }
        }
    }
}

@Composable
private fun CartaoParciais(splits: List<SplitParcial>) {
    Card(colors = CardDefaults.cardColors(containerColor = CorCard), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Parciais por km", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
            splits.forEach { s ->
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("${s.km} km", modifier = Modifier.width(50.dp), fontSize = 13.sp, color = Color.White)
                    Text(s.paceFormatado, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = CorPace)
                }
            }
        }
    }
}

// ── Modifier helper para cursor interativo ───────────────────────────────────

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

// ── Dados ────────────────────────────────────────────────────────────────────

data class DadosGrafico(
    val paceNorm:      List<Float>,
    val altNorm:       List<Float>,
    val gapNorm:       List<Float>,
    val paceFormatado: List<String>,
    val gapFormatado:  List<String>,
    val altitudes:     List<Double>,
    val cadencias:     List<Int>,
    val distanciaTotal: Double,
    val temCadencia:   Boolean,
    val temGAP:        Boolean,
    val pacesRaw:      List<Double>,
    val tempos:        List<Long>,
    // Limites reais dos eixos Y em seg/km
    val paceMin: Double,
    val paceMax: Double,
    val gapMin:  Double,
    val gapMax:  Double
)

data class InfoZona(val nome: String, val percentagem: Float, val tempo: String)

private fun prepararDados(rota: List<LatLngPonto>): DadosGrafico {
    val vazio = DadosGrafico(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), 0.0, false, false, emptyList(), emptyList(), 300.0, 600.0, 300.0, 600.0)
    if (rota.size < 2) return vazio

    val altsSuav = rota.mapIndexed { i, _ ->
        val a = (i - 5).coerceAtLeast(0); val b = (i + 5).coerceAtMost(rota.lastIndex)
        rota.subList(a, b + 1).map { it.alt }.average()
    }
    val paces = rota.map { it.paceNoPonto }
    val pacesValidos = paces.filter { it in 60.0..1200.0 }
    val paceMin = pacesValidos.minOrNull() ?: 300.0
    val paceMax = pacesValidos.maxOrNull() ?: 600.0

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
    val gapsValidos = gaps.filter { it in 60.0..1200.0 }
    val gapMin = gapsValidos.minOrNull() ?: paceMin
    val gapMax = gapsValidos.maxOrNull() ?: paceMax

    val step = maxOf(1, rota.size / 300)
    val idxs = rota.indices.filter { it % step == 0 }

    // paceNorm: 0 = mais rápido (paceMin) → TOPO do gráfico | 1 = mais lento (paceMax) → BASE
    val paceRange = (paceMax - paceMin).coerceAtLeast(1.0)
    val gapRange  = (gapMax  - gapMin ).coerceAtLeast(1.0)
    val altRange  = (altsSuav.max() - altsSuav.min()).coerceAtLeast(1.0)

    return DadosGrafico(
        paceNorm = idxs.map { i ->
            val p = paces[i]; if (p !in 60.0..1200.0) -1f
            else ((p - paceMin) / paceRange).toFloat().coerceIn(0f, 1f)
        },
        altNorm = idxs.map { i ->
            ((altsSuav[i] - altsSuav.min()) / altRange).toFloat().coerceIn(0f, 1f)
        },
        gapNorm = idxs.map { i ->
            val g = gaps[i]; if (g !in 60.0..1200.0) -1f
            else ((g - gapMin) / gapRange).toFloat().coerceIn(0f, 1f)
        },
        paceFormatado = idxs.map { formatarPaceSegKm(paces[it]) },
        gapFormatado  = idxs.map { formatarPaceSegKm(gaps[it]) },
        altitudes     = idxs.map { altsSuav[it] },
        cadencias     = idxs.map { rota[it].cadenciaNoPonto },
        distanciaTotal = rota.zipWithNext { a, b -> haversineM(a.lat, a.lng, b.lat, b.lng) }.sum(),
        temCadencia   = rota.any { it.cadenciaNoPonto > 0 },
        temGAP        = (altsSuav.max() - altsSuav.min()) > 10.0,
        pacesRaw      = idxs.map { paces[it] },
        tempos        = idxs.map { rota[it].tempo },
        paceMin = paceMin, paceMax = paceMax,
        gapMin  = gapMin,  gapMax  = gapMax
    )
}

private fun calcularZonasPace(dados: DadosGrafico): List<InfoZona> {
    val validos = dados.pacesRaw.filter { it in 60.0..1200.0 }
    if (validos.isEmpty()) return emptyList()
    val threshold = validos.sorted()[(validos.size * 0.15).toInt()]
    val limites = listOf(threshold * 1.33, threshold * 1.14, threshold * 1.06, threshold * 1.00)
    val nomes = listOf("Recuperação", "Aeróbico", "Limiar", "Rápido", "Máximo")
    val conta = IntArray(5)
    validos.forEach { p ->
        conta[when { p >= limites[0] -> 0; p >= limites[1] -> 1; p >= limites[2] -> 2; p >= limites[3] -> 3; else -> 4 }]++
    }
    return nomes.mapIndexed { i, n -> InfoZona(n, conta[i].toFloat() / validos.size * 100f, "") }
}

// ── Heatmap para o mapa ──────────────────────────────────────────────────────

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

// ── Utils ────────────────────────────────────────────────────────────────────

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
