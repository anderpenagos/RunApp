package com.runapp.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
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
    
    var cursorFrac by remember { mutableFloatStateOf(-1f) }
    val indexCursor = if (cursorFrac >= 0 && dadosGrafico.paceNorm.isNotEmpty())
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

            item {
                GraficoCard(titulo = "Pace e Elevação", subtitulo = "Azul = ritmo  •  Área = altitude") {
                    GraficoPaceElevacao(dados = dadosGrafico, cursorFrac = cursorFrac, indexCursor = indexCursor, onCursorChange = { cursorFrac = it })
                }
            }

            if (dadosGrafico.temGAP) {
                item {
                    GraficoCard(titulo = "Ritmo Ajustado (GAP)", subtitulo = "Verde = Esforço estimado em plano") {
                        GraficoGAP(dados = dadosGrafico, cursorFrac = cursorFrac, indexCursor = indexCursor, onCursorChange = { cursorFrac = it })
                    }
                }
            }

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

@Composable
private fun ResumoMetricas(corrida: CorridaHistorico) {
    Card(colors = CardDefaults.cardColors(containerColor = CorCard), shape = RoundedCornerShape(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            MetricaDetalhe("DISTÂNCIA", "%.2f".format(corrida.distanciaKm), "km")
            VerticalDividerCustom()
            MetricaDetalhe("TEMPO", corrida.tempoFormatado, "")
            VerticalDividerCustom()
            MetricaDetalhe("PACE MÉDIO", corrida.paceMedia, "/km")
            if (corrida.ganhoElevacaoM > 0) {
                VerticalDividerCustom()
                MetricaDetalhe("D+", "+${corrida.ganhoElevacaoM}", "m")
            }
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
private fun VerticalDividerCustom() {
    Box(modifier = Modifier.width(1.dp).height(30.dp).background(Color.White.copy(alpha = 0.1f)))
}

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
private fun GraficoPaceElevacao(dados: DadosGrafico, cursorFrac: Float, indexCursor: Int, onCursorChange: (Float) -> Unit) {
    Column {
        Box(modifier = Modifier.fillMaxWidth().height(24.dp)) {
            if (indexCursor >= 0) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("⏱️ ${dados.paceFormatado[indexCursor]}/km", color = CorPace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("⛰️ ${dados.altitudes[indexCursor].roundToInt()}m", color = Color.White, fontSize = 12.sp)
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth().height(160.dp)) {
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
                        onDrag = { change, _ -> change.consume(); onCursorChange(change.position.x / size.width) }
                    )
                }
            ) {
                val w = size.width; val h = size.height; val drawH = h - 12f
                repeat(3) { i -> drawLine(Color.White.copy(alpha = 0.05f), Offset(0f, h * (i + 1) / 4), Offset(w, h * (i + 1) / 4)) }

                val pathAlt = Path().apply {
                    moveTo(0f, h)
                    dados.altNorm.forEachIndexed { i, v -> lineTo(i.toFloat() / (dados.altNorm.size - 1) * w, 4f + drawH * (1f - v)) }
                    lineTo(w, h); close()
                }
                drawPath(pathAlt, Brush.verticalGradient(listOf(CorElevacao.copy(alpha = 0.4f), Color.Transparent), startY = 0f, endY = h))

                val pathPace = Path().apply {
                    dados.paceNorm.forEachIndexed { i, v ->
                        val x = i.toFloat() / (dados.paceNorm.size - 1) * w
                        if (i == 0) moveTo(x, 4f + drawH * v) else lineTo(x, 4f + drawH * v)
                    }
                }
                drawPath(pathPace, CorPace, style = Stroke(width = 3f, cap = StrokeCap.Round))

                if (indexCursor >= 0) {
                    val cx = cursorFrac.coerceIn(0f, 1f) * w
                    drawLine(CorCursor.copy(alpha = 0.6f), Offset(cx, 0f), Offset(cx, h), strokeWidth = 2f)
                    drawCircle(CorPace, radius = 8f, center = Offset(cx, 4f + drawH * dados.paceNorm[indexCursor]))
                }
            }
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
    Canvas(modifier = Modifier.fillMaxWidth().height(80.dp)) {
        val w = size.width; val h = size.height
        val barW = w / dados.cadencias.size
        dados.cadencias.forEachIndexed { i, spm ->
            if (spm > 0) {
                val ratio = ((spm - cadMin) / (cadMax - cadMin).coerceAtLeast(1f)).coerceIn(0f, 1f)
                val isSelected = i == indexCursor
                val barH = h * (0.2f + 0.8f * ratio)
                val color = if (isSelected) Color.White else lerpColor(CorBarra1, CorBarra2, ratio).copy(alpha = 0.7f)
                drawRect(color, Offset(i * barW, h - barH), Size(barW.coerceAtLeast(1f), barH))
            }
        }
    }
}

@Composable
private fun GraficoGAP(dados: DadosGrafico, cursorFrac: Float, indexCursor: Int, onCursorChange: (Float) -> Unit) {
    Canvas(modifier = Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.03f))
        .pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { onCursorChange(it.x / size.width) },
                onDragEnd = { onCursorChange(-1f) },
                onDragCancel = { onCursorChange(-1f) },
                onDrag = { change, _ -> change.consume(); onCursorChange(change.position.x / size.width) }
            )
        }
    ) {
        val w = size.width; val h = size.height; val drawH = h - 12f
        val pathGap = Path().apply {
            dados.gapNorm.forEachIndexed { i, v ->
                val x = i.toFloat() / (dados.gapNorm.size - 1) * w
                if (i == 0) moveTo(x, 4f + drawH * v) else lineTo(x, 4f + drawH * v)
            }
        }
        drawPath(pathGap, CorGAP, style = Stroke(width = 2.5f))
        if (indexCursor >= 0) {
            val cx = cursorFrac.coerceIn(0f, 1f) * w
            drawLine(CorCursor.copy(alpha = 0.4f), Offset(cx, 0f), Offset(cx, h))
        }
    }
}

private fun prepararDadosGrafico(rota: List<LatLngPonto>): DadosGrafico {
    if (rota.size < 2) return DadosGrafico(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 0.0, false, false, emptyList(), emptyList())
    val altsSuav = rota.mapIndexed { i, _ ->
        val inicio = (i - 5).coerceAtLeast(0); val fim = (i + 5).coerceAtMost(rota.lastIndex)
        rota.subList(inicio, fim + 1).map { it.alt }.average()
    }
    val paces = rota.map { it.paceNoPonto }
    val pacesValidos = paces.filter { it in 60.0..1200.0 }
    val pMin = pacesValidos.minOrNull() ?: 300.0; val pMax = pacesValidos.maxOrNull() ?: 600.0
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
    val step = maxOf(1, rota.size / 300)
    val idxs = rota.indices.filter { it % step == 0 }
    return DadosGrafico(
        paceNorm = idxs.map { ((paces[it] - pMin) / (pMax - pMin).coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f) },
        altNorm = idxs.map { ((altsSuav[it] - altsSuav.min()) / (altsSuav.max() - altsSuav.min()).coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f) },
        cadNorm = idxs.map { (rota[it].cadenciaNoPonto / 200f).coerceIn(0f, 1f) },
        gapNorm = idxs.map { ((gaps[it] - pMin) / (pMax - pMin).coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f) },
        paceFormatado = idxs.map { formatarPace(paces[it]) },
        gapFormatado = idxs.map { formatarPace(gaps[it]) },
        gapSegKm = idxs.map { gaps[it] },
        altitudes = idxs.map { altsSuav[it] },
        cadencias = idxs.map { rota[it].cadenciaNoPonto },
        distanciaTotal = rota.zipWithNext { a, b -> haversineMetros(a.lat, a.lng, b.lat, b.lng) }.sum(),
        temCadencia = rota.any { it.cadenciaNoPonto > 0 },
        temGAP = (altsSuav.max() - altsSuav.min()) > 10.0,
        pacesRaw = idxs.map { paces[it] },
        tempos = idxs.map { rota[it].tempo }
    )
}

@Composable
private fun ZonasCard(zonas: List<InfoZona>) {
    Card(colors = CardDefaults.cardColors(containerColor = CorCard), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Zonas de ritmo", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
                zonas.forEachIndexed { idx, z -> if (z.percentagem > 0f) Box(modifier = Modifier.weight(z.percentagem).fillMaxHeight().background(CoreZonas[idx])) }
            }
            zonas.forEachIndexed { idx, z ->
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).background(CoreZonas[idx], CircleShape))
                    Text(" Z${idx+1} ${z.nome}", modifier = Modifier.weight(1f), fontSize = 12.sp, color = Color.White)
                    Text("${z.percentagem.roundToInt()}%", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CoreZonas[idx])
                }
            }
        }
    }
}

@Composable
private fun SplitsCard(splits: List<SplitParcial>) {
    Card(colors = CardDefaults.cardColors(containerColor = CorCard), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Parciais", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
            splits.forEach { s ->
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("${s.km} km", modifier = Modifier.width(50.dp), fontSize = 13.sp, color = Color.White)
                    Text(s.paceFormatado, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = CorPace)
                }
            }
        }
    }
}

private fun calcularZonasPace(dados: DadosGrafico): List<InfoZona> {
    val validos = dados.pacesRaw.filter { it in 60.0..1200.0 }
    if (validos.isEmpty()) return emptyList()
    val threshold = validos.sorted()[(validos.size * 0.15).toInt()]
    val limites = listOf(threshold * 1.33, threshold * 1.14, threshold * 1.06, threshold * 1.00)
    val nomes = listOf("Recuperação", "Aeróbico", "Limiar", "Rápido", "Máximo")
    val conta = IntArray(5)
    validos.forEach { p -> conta[when { p >= limites[0] -> 0; p >= limites[1] -> 1; p >= limites[2] -> 2; p >= limites[3] -> 3; else -> 4 }]++ }
    return nomes.mapIndexed { i, n -> InfoZona(n, conta[i].toFloat() / validos.size * 100f, "") }
}

private fun formatarPace(s: Double): String = if (s !in 60.0..1200.0) "--:--" else "%d:%02d".format((s/60).toInt(), (s%60).toInt())
private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(a.red + (b.red-a.red)*t, a.green + (b.green-a.green)*t, a.blue + (b.blue-a.blue)*t, 1f)
private fun haversineMetros(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6371000.0; val dLat = Math.toRadians(lat2-lat1); val dLon = Math.toRadians(lon2-lon1)
    val a = sin(dLat/2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon/2).pow(2)
    return R * 2 * atan2(sqrt(a), sqrt(1 - a))
}
private fun formatarDataDetalhe(iso: String): String = runCatching { java.time.LocalDateTime.parse(iso).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) }.getOrDefault(iso)

data class DadosGrafico(val paceNorm: List<Float>, val altNorm: List<Float>, val cadNorm: List<Float>, val gapNorm: List<Float>, val paceFormatado: List<String>, val gapFormatado: List<String>, val gapSegKm: List<Double>, val altitudes: List<Double>, val cadencias: List<Int>, val distanciaTotal: Double, val temCadencia: Boolean, val temGAP: Boolean, val pacesRaw: List<Double>, val tempos: List<Long>)
data class InfoZona(val nome: String, val percentagem: Float, val tempo: String)