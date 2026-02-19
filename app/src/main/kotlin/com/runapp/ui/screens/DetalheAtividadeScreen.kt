package com.runapp.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.runapp.data.model.CorridaHistorico
import com.runapp.data.model.LatLngPonto
import com.runapp.data.model.SplitParcial
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────────────────
// Paleta do Dashboard
// ─────────────────────────────────────────────────────────────────────────────
private val CorFundo       = Color(0xFF121212)
private val CorCard        = Color(0xFF1E1E1E)
private val CorPace        = Color(0xFF4FC3F7)   // azul claro — linha principal
private val CorElevacao    = Color(0xFF546E7A)   // cinza azulado — área preenchida
private val CorCadencia    = Color(0xFFAB47BC)   // roxo — linha secundária
private val CorCursor      = Color(0xFFFFFFFF)
private val CorBarra1      = Color(0xFF4CAF50)   // verde — pace bom
private val CorBarra2      = Color(0xFFFF6B35)   // laranja — pace lento

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetalheAtividadeScreen(
    corrida: CorridaHistorico,
    rota: List<LatLngPonto>,          // pontos GPS com paceNoPonto e cadenciaNoPonto
    onVoltar: () -> Unit
) {
    // Pré-processa os dados uma vez, fora de qualquer recomposição cara
    val dadosGrafico = remember(rota) { prepararDadosGrafico(rota) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(corrida.nome, style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold)
                        Text(formatarDataDetalhe(corrida.data),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onVoltar) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CorFundo)
            )
        },
        containerColor = CorFundo
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── 1. Métricas de resumo ──────────────────────────────────────
            item {
                ResumoMetricas(corrida)
            }

            // ── 2. Gráfico Pace + Elevação (o "efeito Strava") ────────────
            item {
                GraficoCard(
                    titulo = "Pace e Elevação",
                    subtitulo = "Azul = pace  •  Área = altitude"
                ) {
                    GraficoPaceElevacao(dados = dadosGrafico)
                }
            }

            // ── 3. Gráfico de Cadência (se disponível) ────────────────────
            if (dadosGrafico.temCadencia) {
                item {
                    GraficoCard(
                        titulo = "Cadência",
                        subtitulo = "Passos por minuto ao longo do percurso"
                    ) {
                        GraficoCadencia(dados = dadosGrafico)
                    }
                }
            }

            // ── 4. Tabela de splits por km ────────────────────────────────
            if (corrida.splitsParciais.isNotEmpty()) {
                item {
                    SplitsCard(splits = corrida.splitsParciais)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Métricas de Resumo — linha de cards horizontais
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ResumoMetricas(corrida: CorridaHistorico) {
    Card(colors = CardDefaults.cardColors(containerColor = CorCard),
        shape = RoundedCornerShape(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            MetricaDetalhe("DISTÂNCIA", "%.2f".format(corrida.distanciaKm), "km")
            VerticalDivider()
            MetricaDetalhe("TEMPO", corrida.tempoFormatado, "")
            VerticalDivider()
            MetricaDetalhe("PACE MÉDIO", corrida.paceMedia, "/km")
            if (corrida.ganhoElevacaoM > 0) {
                VerticalDivider()
                MetricaDetalhe("D+", "+${corrida.ganhoElevacaoM}", "m")
            }
            if (corrida.cadenciaMedia > 0) {
                VerticalDivider()
                MetricaDetalhe("CADÊNCIA", "${corrida.cadenciaMedia}", "spm")
            }
        }
    }
}

@Composable
private fun MetricaDetalhe(label: String, valor: String, unidade: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            fontSize = 9.sp)
        Text(valor, style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, color = Color.White)
        if (unidade.isNotEmpty())
            Text(unidade, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
    }
}

@Composable
private fun VerticalDivider() {
    Box(modifier = Modifier
        .width(1.dp)
        .height(48.dp)
        .background(Color.White.copy(alpha = 0.1f)))
}

// ─────────────────────────────────────────────────────────────────────────────
// Wrapper de card para gráficos
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun GraficoCard(
    titulo: String,
    subtitulo: String,
    conteudo: @Composable () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = CorCard),
        shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(titulo, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold, color = Color.White)
            Text(subtitulo, style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.padding(bottom = 12.dp))
            conteudo()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Gráfico de Pace + Elevação sincronizados com cursor interativo
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun GraficoPaceElevacao(dados: DadosGrafico) {
    if (dados.paceNorm.isEmpty()) {
        Text("Dados de pace insuficientes", style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.4f))
        return
    }

    // Posição X do cursor (0..1), -1 = sem cursor
    var cursorFrac by remember { mutableFloatStateOf(-1f) }
    val indexCursor = if (cursorFrac >= 0 && dados.paceNorm.isNotEmpty())
        (cursorFrac * (dados.paceNorm.size - 1)).roundToInt().coerceIn(dados.paceNorm.indices)
    else -1

    Column {
        // Tooltip acima do gráfico quando cursor está ativo
        if (indexCursor >= 0) {
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Pace: ${dados.paceFormatado.getOrElse(indexCursor) { "--:--" }}",
                    style = MaterialTheme.typography.labelSmall, color = CorPace)
                Text("Alt: ${"%.0f".format(dados.altitudes.getOrElse(indexCursor) { 0.0 })}m",
                    style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
                if (dados.temCadencia)
                    Text("${dados.cadencias.getOrElse(indexCursor) { 0 }} spm",
                        style = MaterialTheme.typography.labelSmall, color = CorCadencia)
            }
            Spacer(modifier = Modifier.height(4.dp))
        } else {
            Spacer(modifier = Modifier.height(20.dp))
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(8.dp))
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> cursorFrac = (offset.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd   = { cursorFrac = -1f },
                        onHorizontalDrag = { _, delta ->
                            cursorFrac = (cursorFrac + delta / size.width).coerceIn(0f, 1f)
                        }
                    )
                }
        ) {
            val w = size.width; val h = size.height
            val padTop = 8f; val padBottom = 4f
            val drawH = h - padTop - padBottom

            // ── Área de elevação (fundo, preenchida) ──────────────────────
            if (dados.altNorm.isNotEmpty()) {
                val pathElevacao = Path()
                pathElevacao.moveTo(0f, h)
                dados.altNorm.forEachIndexed { i, v ->
                    val x = i.toFloat() / (dados.altNorm.size - 1) * w
                    val y = padTop + drawH * (1f - v)
                    if (i == 0) pathElevacao.lineTo(x, y) else pathElevacao.lineTo(x, y)
                }
                pathElevacao.lineTo(w, h)
                pathElevacao.close()
                drawPath(pathElevacao, Brush.verticalGradient(
                    colors = listOf(CorElevacao.copy(alpha = 0.5f), CorElevacao.copy(alpha = 0.1f)),
                    startY = padTop, endY = h
                ))
            }

            // ── Linha de pace (frente) ────────────────────────────────────
            if (dados.paceNorm.size >= 2) {
                val pathPace = Path()
                dados.paceNorm.forEachIndexed { i, v ->
                    val x = i.toFloat() / (dados.paceNorm.size - 1) * w
                    // Pace invertido: valor baixo (rápido) = Y baixo (topo do gráfico)
                    val y = padTop + drawH * v
                    if (i == 0) pathPace.moveTo(x, y) else pathPace.lineTo(x, y)
                }
                drawPath(pathPace, CorPace, style = Stroke(width = 2.5f, cap = StrokeCap.Round))

                // Linha de cadência sobreposta (se disponível)
                if (dados.temCadencia && dados.cadNorm.size >= 2) {
                    val pathCad = Path()
                    dados.cadNorm.forEachIndexed { i, v ->
                        val x = i.toFloat() / (dados.cadNorm.size - 1) * w
                        val y = padTop + drawH * (1f - v)
                        if (i == 0) pathCad.moveTo(x, y) else pathCad.lineTo(x, y)
                    }
                    drawPath(pathCad, CorCadencia.copy(alpha = 0.6f),
                        style = Stroke(width = 1.5f, cap = StrokeCap.Round))
                }
            }

            // ── Cursor vertical interativo ────────────────────────────────
            if (cursorFrac >= 0) {
                val cx = cursorFrac * w
                drawLine(CorCursor.copy(alpha = 0.5f), Offset(cx, 0f), Offset(cx, h), strokeWidth = 1.5f)

                // Ponto destacado na linha de pace
                val idx = (cursorFrac * (dados.paceNorm.size - 1)).roundToInt()
                    .coerceIn(dados.paceNorm.indices)
                val py = padTop + drawH * dados.paceNorm[idx]
                drawCircle(CorPace, radius = 6f, center = Offset(cx, py))
                drawCircle(CorFundo, radius = 3f, center = Offset(cx, py))
            }
        }

        // Labels do eixo X — distância aproximada
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0km", fontSize = 9.sp, color = Color.White.copy(alpha = 0.3f))
            if (dados.altNorm.size > 10)
                Text("${(dados.distanciaTotal / 2000).roundToInt()}km",
                    fontSize = 9.sp, color = Color.White.copy(alpha = 0.3f))
            Text("${"%.1f".format(dados.distanciaTotal / 1000)}km",
                fontSize = 9.sp, color = Color.White.copy(alpha = 0.3f))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Gráfico de barras de cadência
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun GraficoCadencia(dados: DadosGrafico) {
    if (dados.cadencias.isEmpty()) return
    val cadMin = dados.cadencias.filter { it > 0 }.minOrNull()?.toFloat() ?: 120f
    val cadMax = dados.cadencias.maxOrNull()?.toFloat() ?: 200f

    Canvas(modifier = Modifier.fillMaxWidth().height(80.dp)) {
        val w = size.width; val h = size.height
        val n = dados.cadencias.size
        val barW = w / n

        dados.cadencias.forEachIndexed { i, spm ->
            if (spm > 0) {
                val ratio = ((spm - cadMin) / (cadMax - cadMin).coerceAtLeast(1f)).coerceIn(0f, 1f)
                val barH = h * 0.1f + h * 0.9f * ratio
                val cor = lerpColor(CorBarra1, CorBarra2, ratio)
                drawRect(color = cor,
                    topLeft = Offset(i * barW, h - barH),
                    size = Size(barW - 1f, barH))
            }
        }
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${cadMin.roundToInt()} spm", fontSize = 9.sp, color = Color.White.copy(alpha = 0.3f))
        Text("Média: ${dados.cadencias.filter { it > 0 }.average().roundToInt()} spm",
            fontSize = 9.sp, color = CorCadencia)
        Text("${cadMax.roundToInt()} spm", fontSize = 9.sp, color = Color.White.copy(alpha = 0.3f))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tabela de splits por km
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun SplitsCard(splits: List<SplitParcial>) {
    val paceMin = splits.minOf { it.paceSegKm }
    val paceMax = splits.maxOf { it.paceSegKm }.coerceAtLeast(paceMin + 1.0)

    Card(colors = CardDefaults.cardColors(containerColor = CorCard),
        shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Parciais por km", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(12.dp))

            // Cabeçalho
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("KM", modifier = Modifier.width(36.dp), fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.4f))
                Text("PACE", modifier = Modifier.weight(1f), fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.4f))
                Text("BARRA", modifier = Modifier.weight(2f), fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.4f))
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f),
                modifier = Modifier.padding(vertical = 6.dp))

            splits.forEach { split ->
                val ratio = ((split.paceSegKm - paceMin) / (paceMax - paceMin)).toFloat()
                    .coerceIn(0f, 1f)
                val corBarra = lerpColor(CorBarra1, CorBarra2, ratio)
                // Animação da largura da barra ao entrar na tela
                val larguraAnimada by animateFloatAsState(
                    targetValue = ratio.coerceAtLeast(0.05f),
                    animationSpec = tween(600),
                    label = "split_bar_${split.km}"
                )

                Row(modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("${split.km}", modifier = Modifier.width(36.dp),
                        fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                    Text(split.paceFormatado + "/km", modifier = Modifier.weight(1f),
                        color = corBarra, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    Box(modifier = Modifier.weight(2f).height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White.copy(alpha = 0.08f))) {
                        Box(modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(larguraAnimada)
                            .background(Brush.horizontalGradient(listOf(corBarra, corBarra.copy(alpha = 0.6f))))
                            .clip(RoundedCornerShape(4.dp)))
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Preparação de dados — normalização para o gráfico (0..1)
// ─────────────────────────────────────────────────────────────────────────────
data class DadosGrafico(
    val paceNorm: List<Float>,       // pace normalizado 0..1 (0=rápido, 1=lento)
    val altNorm: List<Float>,        // altitude normalizada 0..1
    val cadNorm: List<Float>,        // cadência normalizada 0..1
    val paceFormatado: List<String>, // "5:30" por ponto
    val altitudes: List<Double>,     // metros reais
    val cadencias: List<Int>,        // SPM reais
    val distanciaTotal: Double,
    val temCadencia: Boolean
)

private fun prepararDadosGrafico(rota: List<LatLngPonto>): DadosGrafico {
    if (rota.size < 2) return DadosGrafico(emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), emptyList(), 0.0, false)

    // Suaviza altitude com média móvel de 5 pontos (elimina ruído GPS).
    // coerceAtLeast/coerceAtMost garantem que corridas curtas (< 5 pontos) não crasham.
    val altsSuav = rota.mapIndexed { i, _ ->
        val inicio = (i - 2).coerceAtLeast(0); val fim = (i + 2).coerceAtMost(rota.lastIndex)
        rota.subList(inicio, fim + 1).map { it.alt }.average()
    }

    // Suavização mais agressiva (11 pontos) para o gráfico de altitude sem serrilhado.
    // A mesma proteção de índices é essencial em corridas curtas de teste (< 11 pontos).
    val altsSuavGAP = rota.mapIndexed { i, _ ->
        val inicio = (i - 5).coerceAtLeast(0); val fim = (i + 5).coerceAtMost(rota.lastIndex)
        rota.subList(inicio, fim + 1).map { it.alt }.average()
    }

    // Filtra apenas pontos com pace válido (> 0) para escala
    val pacesBrutos = rota.map { it.paceNoPonto }
    val pacesValidos = pacesBrutos.filter { it > 60 && it < 1200 } // 1:00 a 20:00/km
    val paceMin = pacesValidos.minOrNull() ?: 0.0
    val paceMax = (pacesValidos.maxOrNull() ?: 1.0).coerceAtLeast(paceMin + 1.0)

    val altMin = altsSuav.min(); val altMax = altsSuav.max().coerceAtLeast(altMin + 1.0)

    val cadencias = rota.map { it.cadenciaNoPonto }
    val temCadencia = cadencias.any { it in 120..220 }
    val cadMin = cadencias.filter { it > 0 }.minOrNull()?.toDouble() ?: 0.0
    val cadMax = (cadencias.maxOrNull()?.toDouble() ?: 1.0).coerceAtLeast(cadMin + 1.0)

    // Distância total acumulada para labels do eixo X
    var dist = 0.0
    for (i in 1 until rota.size) dist += haversineMetros(
        rota[i-1].lat, rota[i-1].lng, rota[i].lat, rota[i].lng)

    // Para não ter 3000 pontos no gráfico (pesado), faz amostragem
    // Máx 300 pontos — suficiente para qualquer corrida
    val step = maxOf(1, rota.size / 300)
    val indices = rota.indices.filter { it % step == 0 }

    return DadosGrafico(
        paceNorm = indices.map { i ->
            val p = pacesBrutos[i]
            if (p <= 0) 0.5f  // pace inválido → meio do gráfico
            else ((p - paceMin) / (paceMax - paceMin)).toFloat().coerceIn(0f, 1f)
        },
        altNorm = indices.map { i ->
            ((altsSuav[i] - altMin) / (altMax - altMin)).toFloat().coerceIn(0f, 1f)
        },
        cadNorm = if (temCadencia) indices.map { i ->
            val c = cadencias[i].toDouble()
            if (c <= 0) 0f else ((c - cadMin) / (cadMax - cadMin)).toFloat().coerceIn(0f, 1f)
        } else emptyList(),
        paceFormatado = indices.map { i ->
            val p = pacesBrutos[i]
            if (p <= 0) "--:--"
            else { val min = (p / 60).toInt(); val seg = (p % 60).toInt(); "%d:%02d".format(min, seg) }
        },
        altitudes = indices.map { altsSuav[it] },
        cadencias = indices.map { cadencias[it] },
        distanciaTotal = dist,
        temCadencia = temCadencia
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────
private fun haversineMetros(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val R = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1); val dLng = Math.toRadians(lng2 - lng1)
    val a = Math.sin(dLat/2).let { it*it } +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLng/2).let { it*it }
    return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t, green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t, alpha = 1f)

private fun formatarDataDetalhe(iso: String): String = runCatching {
    val dt = java.time.LocalDateTime.parse(iso)
    dt.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy  HH:mm"))
}.getOrDefault(iso)
