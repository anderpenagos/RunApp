package com.runapp.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.runapp.data.model.CorridaHistorico
import com.runapp.data.model.LatLngPonto
import com.runapp.data.model.SplitParcial
import kotlin.math.*

// ─────────────────────────────────────────────────────────────────────────────
// Paleta do Dashboard
// ─────────────────────────────────────────────────────────────────────────────
private val CorFundo       = Color(0xFF121212)
private val CorCard        = Color(0xFF1E1E1E)
private val CorPace        = Color(0xFF4FC3F7)
private val CorElevacao    = Color(0xFF546E7A)
private val CorCadencia    = Color(0xFFAB47BC)
private val CorCursor      = Color(0xFFFFFFFF)
private val CorBarra1      = Color(0xFF4CAF50)
private val CorBarra2      = Color(0xFFFF6B35)
private val CorGAP         = Color(0xFF81C784)

private val CoreZonas = listOf(
    Color(0xFF90CAF9), Color(0xFF66BB6A), Color(0xFFFFEE58), Color(0xFFFFA726), Color(0xFFEF5350)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetalheAtividadeScreen(
    corrida: CorridaHistorico,
    rota: List<LatLngPonto>,
    onVoltar: () -> Unit
) {
    val dadosGrafico = remember(rota) { prepararDadosGrafico(rota) }
    val zonasPace    = remember(dadosGrafico) { calcularZonasPace(dadosGrafico) }
    
    // Estado do Cursor compartilhado entre os gráficos
    var cursorFrac by remember { mutableFloatStateOf(-1f) }
    val indexCursor = if (cursorFrac >= 0)
        (cursorFrac * (dadosGrafico.paceNorm.size - 1)).roundToInt().coerceIn(dadosGrafico.paceNorm.indices)
    else -1

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
            item { ResumoMetricas(corrida) }

            // Gráfico Principal (Pace e Elevação)
            item {
                GraficoCard(titulo = "Pace e Elevação", subtitulo = "Azul = ritmo  •  Área = altitude") {
                    GraficoPaceElevacao(
                        dados = dadosGrafico, 
                        cursorFrac = cursorFrac, 
                        indexCursor = indexCursor,
                        onCursorChange = { cursorFrac = it }
                    )
                }
            }

            // GAP (Ritmo Ajustado)
            if (dadosGrafico.temGAP) {
                item {
                    GraficoCard(titulo = "Ritmo Ajustado (GAP)", subtitulo = "Verde = Esforço estimado em plano") {
                        GraficoGAP(dados = dadosGrafico, cursorFrac = cursorFrac, indexCursor = indexCursor, onCursorChange = { cursorFrac = it })
                    }
                }
            }

            // Cadência Interativa
            if (dadosGrafico.temCadencia) {
                item {
                    GraficoCard(titulo = "Cadência", subtitulo = "Passos por minuto") {
                        GraficoCadencia(dados = dadosGrafico, indexCursor = indexCursor)
                    }
                }
            }

            if (zonasPace.any { it.percentagem > 0f }) { item { ZonasCard(zonas = zonasPace) } }

            if (corrida.splitsParciais.isNotEmpty()) { item { SplitsCard(splits = corrida.splitsParciais) } }
        }
    }
}

// ── COMPONENTES DE GRÁFICO ───────────────────────────────────────────────────

@Composable
private fun GraficoPaceElevacao(
    dados: DadosGrafico, 
    cursorFrac: Float, 
    indexCursor: Int,
    onCursorChange: (Float) -> Unit
) {
    if (dados.paceNorm.isEmpty()) return

    Column {
        // Tooltip
        Box(modifier = Modifier.fillMaxWidth().height(24.dp)) {
            if (indexCursor >= 0) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("⏱️ ${dados.paceFormatado[indexCursor]}/km", color = CorPace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("⛰️ ${dados.altitudes[indexCursor].roundToInt()}m", color = Color.White, fontSize = 12.sp)
                    if (dados.temCadencia) Text("👟 ${dados.cadencias[indexCursor]} spm", color = CorCadencia, fontSize = 12.sp)
                }
            } else {
                Text("Arraste no gráfico para detalhes", color = Color.White.copy(alpha = 0.3f), fontSize = 11.sp, modifier = Modifier.align(Alignment.Center))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth().height(160.dp)) {
            // Eixo Y Pace
            Column(modifier = Modifier.fillMaxHeight().width(35.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(dados.paceFormatado.minOrNull() ?: "", fontSize = 9.sp, color = CorPace)
                Text(dados.paceFormatado.maxOrNull() ?: "", fontSize = 9.sp, color = CorPace.copy(alpha = 0.5f))
            }

            Canvas(modifier = Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.03f))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { onCursorChange(it.x / size.width) },
                        onDragEnd = { onCursorChange(-1f) },
                        onDragCancel = { onCursorChange(-1f) },
                        onDrag = { change, _ -> 
                            change.consume()
                            onCursorChange(change.position.x / size.width) 
                        }
                    )
                }
            ) {
                val w = size.width; val h = size.height
                val drawH = h - 12f

                // Grade
                repeat(3) { i ->
                    val y = h * (i + 1) / 4
                    drawLine(Color.White.copy(alpha = 0.05f), Offset(0f, y), Offset(w, y))
                }

                // Elevação (Fundo)
                val pathAlt = Path().apply {
                    moveTo(0f, h)
                    dados.altNorm.forEachIndexed { i, v -> lineTo(i.toFloat() / (dados.altNorm.size - 1) * w, 4f + drawH * (1f - v)) }
                    lineTo(w, h); close()
                }
                drawPath(pathAlt, Brush.verticalGradient(listOf(CorElevacao.copy(alpha = 0.4f), Color.Transparent), startY = 0f, endY = h))

                // Pace (Linha)
                val pathPace = Path().apply {
                    dados.paceNorm.forEachIndexed { i, v ->
                        val x = i.toFloat() / (dados.paceNorm.size - 1) * w
                        val y = 4f + drawH * v
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                }
                drawPath(pathPace, CorPace, style = Stroke(width = 3f, cap = StrokeCap.Round))

                // Cursor
                if (indexCursor >= 0) {
                    val cx = cursorFrac.coerceIn(0f, 1f) * w
                    drawLine(Color.White.copy(alpha = 0.6f), Offset(cx, 0f), Offset(cx, h), strokeWidth = 2f)
                    val py = 4f + drawH * dados.paceNorm[indexCursor]
                    drawCircle(CorPace, radius = 8f, center = Offset(cx, py))
                    drawCircle(CorCursor, radius = 4f, center = Offset(cx, py))
                }
            }

            // Eixo Y Altitude
            Column(modifier = Modifier.fillMaxHeight().width(35.dp).padding(start = 4.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text("${dados.altitudes.maxOrNull()?.roundToInt()}m", fontSize = 9.sp, color = CorElevacao)
                Text("${dados.altitudes.minOrNull()?.roundToInt()}m", fontSize = 9.sp, color = CorElevacao.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun GraficoCadencia(dados: DadosGrafico, indexCursor: Int) {
    val cadMin = dados.cadencias.filter { it > 0 }.minOrNull()?.toFloat() ?: 120f
    val cadMax = dados.cadencias.maxOrNull()?.toFloat() ?: 200f

    Column {
        Canvas(modifier = Modifier.fillMaxWidth().height(80.dp)) {
            val w = size.width; val h = size.height
            val barW = w / dados.cadencias.size

            dados.cadencias.forEachIndexed { i, spm ->
                if (spm > 0) {
                    val ratio = ((spm - cadMin) / (cadMax - cadMin).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    val isSelected = i == indexCursor
                    val barH = h * (0.2f + 0.8f * ratio)
                    
                    val corBase = lerpColor(CorBarra1, CorBarra2, ratio)
                    val corFinal = if (isSelected) Color.White else corBase.copy(alpha = 0.7f)
                    
                    drawRect(color = corFinal, topLeft = Offset(i * barW, h - barH), size = Size(barW.coerceAtLeast(1f), barH))
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${cadMin.roundToInt()} spm", fontSize = 9.sp, color = Color.White.copy(alpha = 0.3f))
            Text("Média: ${dados.cadencias.filter { it > 0 }.average().roundToInt()} spm", fontSize = 9.sp, color = CorCadencia)
            Text("${cadMax.roundToInt()} spm", fontSize = 9.sp, color = Color.White.copy(alpha = 0.3f))
        }
    }
}

// ── LÓGICA DE DADOS (GAP, ZONAS, ETC) ────────────────────────────────────────

private fun prepararDadosGrafico(rota: List<LatLngPonto>): DadosGrafico {
    if (rota.size < 2) return DadosGrafico(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 0.0, false, false, emptyList(), emptyList())

    // Suavização de altitude (11 pontos para o GAP)
    val altsSuav = rota.mapIndexed { i, _ ->
        val inicio = (i - 5).coerceAtLeast(0); val fim = (i + 5).coerceAtMost(rota.lastIndex)
        rota.subList(inicio, fim + 1).map { it.alt }.average()
    }

    val pacesBrutos = rota.map { it.paceNoPonto }
    val pacesValidos = pacesBrutos.filter { it in 60.0..1200.0 }
    val paceMin = pacesValidos.minOrNull() ?: 300.0
    val paceMax = pacesValidos.maxOrNull() ?: 600.0

    // Cálculo do GAP (Minetti)
    val gaps = rota.mapIndexed { i, pt ->
        val p = pt.paceNoPonto
        if (p !in 60.0..1200.0) return@mapIndexed 0.0
        val grad = if (i > 0) {
            val dAlt = altsSuav[i] - altsSuav[i-1]
            val dDist = haversineMetros(rota[i-1].lat, rota[i-1].lng, pt.lat, pt.lng).coerceAtLeast(0.5)
            (dAlt / dDist * 100).coerceIn(-30.0, 30.0)
        } else 0.0
        (p * (1.0 + 0.033 * grad + 0.00012 * grad.pow(2))).coerceIn(60.0, 1200.0)
    }

    val distTotal = rota.zipWithNext { a, b -> haversineMetros(a.lat, a.lng, b.lat, b.lng) }.sum()
    val step = maxOf(1, rota.size / 300)
    val idxs = rota.indices.filter { it % step == 0 }

    return DadosGrafico(
        paceNorm = idxs.map { ((pacesBrutos[it] - paceMin) / (paceMax - paceMin).coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f) },
        altNorm = idxs.map { ((altsSuav[it] - altsSuav.min()) / (altsSuav.max() - altsSuav.min()).coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f) },
        cadNorm = idxs.map { (rota[it].cadenciaNoPonto / 200f).coerceIn(0f, 1f) },
        gapNorm = idxs.map { ((gaps[it] - paceMin) / (paceMax - paceMin).coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f) },
        paceFormatado = idxs.map { formatarPace(pacesBrutos[it]) },
        gapFormatado = idxs.map { formatarPace(gaps[it]) },
        gapSegKm = idxs.map { gaps[it] },
        altitudes = idxs.map { altsSuav[it] },
        cadencias = idxs.map { rota[it].cadenciaNoPonto },
        distanciaTotal = distTotal,
        temCadencia = rota.any { it.cadenciaNoPonto > 0 },
        temGAP = (altsSuav.max() - altsSuav.min()) > 10.0,
        pacesRaw = idxs.map { pacesBrutos[it] },
        tempos = idxs.map { rota[it].tempo }
    )
}

// ── HELPERS RESTANTES (Resumo, Zonas, Splits, etc mantêm lógica anterior corrigida) ──

@Composable
private fun ResumoMetricas(corrida: CorridaHistorico) {
    Card(colors = CardDefaults.cardColors(containerColor = CorCard), shape = RoundedCornerShape(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            MetricaDetalhe("DISTÂNCIA", "%.2f".format(corrida.distanciaKm), "km")
            MetricaDetalhe("PACE MÉDIO", corrida.paceMedia, "/km")
            MetricaDetalhe("D+", "+${corrida.ganhoElevacaoM}", "m")
            MetricaDetalhe("CADÊNCIA", "${corrida.cadenciaMedia}", "spm")
        }
    }
}

@Composable
private fun GraficoCard(titulo: String, subtitulo: String, conteudo: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CorCard), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(titulo, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
            Text(subtitulo, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.4f), modifier = Modifier.padding(bottom = 12.dp))
            conteudo()
        }
    }
}

// (Mantenha ZonasCard, SplitsCard, GraficoGAP e as funções de suporte como haversine e formatarPace que você já tinha)
// ... as lógicas de zonas e splits permanecem iguais, apenas garantindo que usem os dados amostrados.

data class DadosGrafico(
    val paceNorm: List<Float>, val altNorm: List<Float>, val cadNorm: List<Float>, val gapNorm: List<Float>,
    val paceFormatado: List<String>, val gapFormatado: List<String>, val gapSegKm: List<Double>,
    val altitudes: List<Double>, val cadencias: List<Int>, val distanciaTotal: Double,
    val temCadencia: Boolean, val temGAP: Boolean, val pacesRaw: List<Double>, val tempos: List<Long>
)

data class InfoZona(val nome: String, val percentagem: Float, val tempo: String)

private fun formatarPace(segKm: Double): String {
    if (segKm !in 60.0..1200.0) return "--:--"
    return "%d:%02d".format((segKm / 60).toInt(), (segKm % 60).toInt())
}

private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t, green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t, alpha = 1f)

private fun haversineMetros(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val R = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1); val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat/2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng/2).pow(2)
    return R * 2 * atan2(sqrt(a), sqrt(1 - a))
}

private fun formatarDataDetalhe(iso: String): String = runCatching {
    val dt = java.time.LocalDateTime.parse(iso)
    dt.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy  HH:mm"))
}.getOrDefault(iso)

private fun calcularZonasPace(dados: DadosGrafico): List<InfoZona> {
    val validos = dados.pacesRaw.filter { it in 60.0..1200.0 }
    if (validos.isEmpty()) return emptyList()
    val threshold = validos.sorted()[(validos.size * 0.15).toInt()]
    val limites = listOf(threshold * 1.33, threshold * 1.14, threshold * 1.06, threshold * 1.00)
    val nomes = listOf("Recuperação", "Aeróbico", "Limiar", "Rápido", "Máximo")
    val conta = IntArray(5)
    validos.forEach { pace ->
        conta[when {
            pace >= limites[0] -> 0
            pace >= limites[1] -> 1
            pace >= limites[2] -> 2
            pace >= limites[3] -> 3
            else -> 4
        }]++
    }
    val tempoTotalSeg = if (dados.tempos.size >= 2) (dados.tempos.last() - dados.tempos.first()) / 1000L else 0L
    return nomes.mapIndexed { i, nome ->
        val perc = conta[i].toFloat() / validos.size * 100f
        InfoZona(nome, perc, "%d:%02d".format((tempoTotalSeg * perc / 6000).toInt(), ((tempoTotalSeg * perc / 100) % 60).toInt()))
    }
}

@Composable
private fun ZonasCard(zonas: List<InfoZona>) {
    Card(colors = CardDefaults.cardColors(containerColor = CorCard), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Zonas de ritmo", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
                zonas.forEachIndexed { idx, zona ->
                    if (zona.percentagem > 0f) Box(modifier = Modifier.weight(zona.percentagem).fillMaxHeight().background(CoreZonas.getOrElse(idx) { Color.Gray }))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            zonas.forEachIndexed { idx, zona ->
                val cor = CoreZonas.getOrElse(idx) { Color.Gray }
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(cor))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Z${idx + 1} ${zona.nome}", modifier = Modifier.width(110.dp), fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f))
                    Box(modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.07f))) {
                        val anim by animateFloatAsState(zona.percentagem / 100f)
                        Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(anim).background(cor.copy(alpha = 0.85f)))
                    }
                    Text("${"%.0f".format(zona.percentagem)}%", modifier = Modifier.padding(start = 8.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = cor)
                }
            }
        }
    }
}

@Composable
private fun SplitsCard(splits: List<SplitParcial>) {
    val paceMin = splits.minOf { it.paceSegKm }; val paceMax = splits.maxOf { it.paceSegKm }.coerceAtLeast(paceMin + 1.0)
    Card(colors = CardDefaults.cardColors(containerColor = CorCard), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Parciais por km", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(12.dp))
            splits.forEach { split ->
                val ratio = ((split.paceSegKm - paceMin) / (paceMax - paceMin)).toFloat().coerceIn(0f, 1f)
                val corBarra = lerpColor(CorBarra1, CorBarra2, ratio)
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${split.km}", modifier = Modifier.width(36.dp), fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                    Text(split.paceFormatado, modifier = Modifier.weight(1f), color = corBarra, fontSize = 13.sp)
                    val anim by animateFloatAsState(ratio.coerceAtLeast(0.1f))
                    Box(modifier = Modifier.weight(2f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.08f))) {
                        Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(anim).background(corBarra))
                    }
                }
            }
        }
    }
}

@Composable
private fun GraficoGAP(dados: DadosGrafico, cursorFrac: Float, indexCursor: Int, onCursorChange: (Float) -> Unit) {
    // Implementação similar ao GraficoPaceElevacao, mas usando dados.gapNorm e CorGAP
}