package com.runapp.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Psychology
import com.runapp.ui.navigation.CoachUiState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
private val CoreZonas = listOf(
    Color(0xFF90CAF9), // Z1 — azul claro
    Color(0xFF66BB6A), // Z2 — verde
    Color(0xFFFFEE58), // Z3 — amarelo
    Color(0xFFFFB74D), // Z4 — laranja claro
    Color(0xFFFF7043), // Z5 — laranja escuro
    Color(0xFFE53935), // Z6 — vermelho
    Color(0xFFB71C1C)  // Z7 — vermelho escuro
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetalheAtividadeScreen(
    corrida: CorridaHistorico,
    rota: List<LatLngPonto>,
    coachEstado: CoachUiState = CoachUiState.Inativo,
    onRegenerar: () -> Unit = {},
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
            
            // ── Análise de Voltas / Intervalos ────────────────────────────────
            if (corrida.voltasAnalise.isNotEmpty()) {
                item { CartaoVoltas(corrida.voltasAnalise) }
            }

            if (zonas.any { it.percentagem > 0f }) { item { CartaoZonas(zonas) } }
            
            if (corrida.splitsParciais.isNotEmpty() && corrida.voltasAnalise.isEmpty()) { 
                item { CartaoParciais(corrida.splitsParciais) } 
            }

            // ── Análise do Coach (Gemini 2.5 Flash) ──────────────────────────
            item { CartaoCoach(coachEstado, onRegenerar) }
        }
    }
}

@Composable
private fun CartaoMapa(rota: List<LatLngPonto>, pontoSelecionado: LatLngPonto?) {

    // ── PERFORMANCE FIX — visão geral dos problemas resolvidos ───────────────
    //
    // PROBLEMA RAIZ: Uma corrida de 5km@1Hz = ~5000 pontos GPS.
    // Cada mudança de pace gerava um novo `Polyline` composable →
    // centenas de objetos gerenciados pelo Maps SDK → OOM / jank / ANR.
    //
    // FIX A — DOUGLAS-PEUCKER antes do heatmap:
    //   5000 pontos → ~150 pontos (tolerância 8m).
    //   Imperceptível visualmente num mapa de 240dp mas elimina 97% do trabalho.
    //
    // FIX B — 8 BUCKETS DE COR (quantização):
    //   Em vez de calcular uma cor contínua por segmento, mapeia cada pace
    //   a um de 8 buckets pré-definidos. Isso garante que a mesclagem
    //   de polylines adjacentes funciona muito melhor (pace similar = mesma cor)
    //   → resultado final: 8-15 Polylines no mapa, nunca centenas.
    //
    // FIX C — REMOVE JointType.ROUND:
    //   JointType.ROUND força o GPU a calcular junções arredondadas em cada
    //   vértice das polylines. Removido → JointType.DEFAULT (miter joints, muito mais leve).
    //
    // FIX D — BOUNDS ASSÍNCRONO:
    //   O `remember` anterior criava N objetos LatLng na main thread durante
    //   a composição. Movido para withContext(Default) junto com o heatmap.
    //
    // FIX E — CURSOR DESACOPLADO DO MAPA:
    //   `pontoSelecionado` mudando re-renderizava TODO o GoogleMap a cada toque.
    //   Agora o mapa só re-renderiza quando `polylinesMescladas` muda (uma vez).
    //   O marcador do cursor é exibido por cima via overlay Canvas simples.

    data class MapData(
        val polylines: List<Pair<List<LatLng>, Color>>,
        val bounds:    LatLngBounds,
        val inicio:    LatLng,
        val fim:       LatLng
    )

    var mapData by remember { mutableStateOf<MapData?>(null) }

    LaunchedEffect(rota) {
        val resultado = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            // A — Simplifica rota: 5000 → ~150 pontos para o mapa
            val rotaSimples = com.runapp.util.DouglasPeucker.simplify(rota, toleranceMeters = 4.0)

            // B — Heatmap com buckets quantizados → mínimo de Polylines
            val segmentos = calcularSegmentosHeatmapDetalhe(rotaSimples)
            val polylines = mesclarPolylinesDetalhe(segmentos)

            // D — Bounds assíncrono: sem criar LatLng na main thread
            val builder = LatLngBounds.builder()
            rota.forEach { builder.include(LatLng(it.lat, it.lng)) }

            MapData(
                polylines = polylines,
                bounds    = builder.build(),
                inicio    = LatLng(rota.first().lat, rota.first().lng),
                fim       = LatLng(rota.last().lat,  rota.last().lng)
            )
        }
        mapData = resultado
    }

    val cameraState = rememberCameraPositionState()

    // Anima câmera só quando os bounds ficam prontos (uma única vez)
    val bounds = mapData?.bounds
    LaunchedEffect(bounds) {
        bounds ?: return@LaunchedEffect
        try { cameraState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 80)) }
        catch (_: Exception) { }
    }

    Card(colors = CardDefaults.cardColors(containerColor = CorCard), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Percurso", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Cores por pace (verde lento → vermelho rápido)", fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.4f), modifier = Modifier.padding(bottom = 12.dp))

            Box(modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(8.dp))) {

                if (mapData == null) {
                    // Placeholder enquanto o heatmap calcula — evita mapa vazio piscando
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A2E)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = CorPace,
                            strokeWidth = 2.dp
                        )
                    }
                } else {
                    // E — Mapa estático: só re-renderiza quando polylines mudam
                    val data = mapData!!
                    GoogleMap(
                        modifier = Modifier.fillMaxSize(),
                        cameraPositionState = cameraState,
                        properties = MapProperties(mapType = MapType.NORMAL),
                        uiSettings = MapUiSettings(
                            zoomControlsEnabled  = false,
                            scrollGesturesEnabled = false,  // evita conflito com LazyColumn
                            zoomGesturesEnabled  = true,
                            rotationGesturesEnabled = false,
                            tiltGesturesEnabled  = false
                        )
                    ) {
                        // B+C — Polylines quantizadas, sem JointType.ROUND
                        data.polylines.forEach { (pontos, cor) ->
                            Polyline(points = pontos, color = cor, width = 10f)
                        }

                        // Marcadores de início e fim (estáticos, não dependem do cursor)
                        Circle(center = data.inicio, radius = 10.0,
                            fillColor = Color.White, strokeColor = CorFundo, strokeWidth = 3f)
                        Circle(center = data.fim,    radius = 10.0,
                            fillColor = Color(0xFFEF5350), strokeColor = CorFundo, strokeWidth = 3f)
                    }

                    // E — Overlay leve para o cursor: Canvas simples, não re-renderiza o mapa
                    pontoSelecionado?.let { ponto ->
                        val proj = cameraState.projection
                        if (proj != null) {
                            val screenPt = proj.toScreenLocation(LatLng(ponto.lat, ponto.lng))
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                drawCircle(
                                    color  = CorPace,
                                    radius = 14f,
                                    center = Offset(screenPt.x.toFloat(), screenPt.y.toFloat())
                                )
                                drawCircle(
                                    color  = Color.White,
                                    radius = 6f,
                                    center = Offset(screenPt.x.toFloat(), screenPt.y.toFloat())
                                )
                            }
                        }
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
                val distLabel = dados.distanciasKm.getOrNull(idx)?.let { "%.2fkm".format(it) } ?: ""
                Text("⏱ ${dados.paceFormatado[idx]}/km", color = CorPace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (distLabel.isNotEmpty()) Text(distLabel, color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp)
                    Text("⛰ ${dados.altitudes[idx].roundToInt()} m", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                }
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

                // Linhas verticais dos marcadores de km (dentro do canvas)
                dados.kmMarcadores.forEach { frac ->
                    val x = frac * w
                    drawLine(Color.White.copy(alpha = 0.08f), Offset(x, 0f), Offset(x, h), strokeWidth = 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
                }
                
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
                
                // Linha pace — pontos inválidos (GPS spike) interpolados linearmente
                if (dados.paceNorm.size >= 2) {
                    val paceInterp = interpolarInvalidos(dados.paceNorm)
                    val path = Path()
                    paceInterp.forEachIndexed { i, v ->
                        val x = i.toFloat() / (paceInterp.size - 1) * w
                        if (i == 0) path.moveTo(x, 4f + drawH * v)
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
        EixoKm(dados.kmMarcadores, dados.kmLabels, larguraEixoY = 35.dp, larguraEixoYDir = 39.dp)
    }
}

@Composable
private fun GraficoGAP(dados: DadosGrafico, frac: Float, onFracChange: (Float) -> Unit) {
    val idx = fracToIndex(frac, dados.gapNorm.size)
    Column {
        Row(modifier = Modifier.fillMaxWidth().height(22.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (idx >= 0) {
                val distLabel = dados.distanciasKm.getOrNull(idx)?.let { "%.2fkm".format(it) } ?: ""
                Text("⚡ GAP: ${dados.gapFormatado[idx]}/km", color = CorGAP, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (distLabel.isNotEmpty()) Text(distLabel, color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp)
                    Text("Real: ${dados.paceFormatado[idx]}/km", color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
                }
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
                // Linhas verticais de km
                dados.kmMarcadores.forEach { frac ->
                    drawLine(Color.White.copy(alpha = 0.08f), Offset(frac * w, 0f), Offset(frac * w, h),
                        strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
                }
                if (dados.gapNorm.size >= 2) {
                    val gapInterp = interpolarInvalidos(dados.gapNorm)
                    val pathArea = Path().apply {
                        moveTo(0f, h)
                        gapInterp.forEachIndexed { i, v ->
                            lineTo(i.toFloat() / (gapInterp.size - 1) * w, 4f + drawH * v)
                        }
                        lineTo(w, h); close()
                    }
                    drawPath(pathArea, Brush.verticalGradient(listOf(CorGAP.copy(alpha = 0.2f), Color.Transparent), 0f, h))
                    val pathLine = Path()
                    gapInterp.forEachIndexed { i, v ->
                        val x = i.toFloat() / (gapInterp.size - 1) * w
                        if (i == 0) pathLine.moveTo(x, 4f + drawH * v)
                        else pathLine.lineTo(x, 4f + drawH * v)
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
        EixoKm(dados.kmMarcadores, dados.kmLabels, larguraEixoY = 35.dp, larguraEixoYDir = 35.dp)
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
            if (idx >= 0 && dados.cadencias[idx] > 0) {
                val distLabel = dados.distanciasKm.getOrNull(idx)?.let { "%.2fkm".format(it) } ?: ""
                Text("👟 ${dados.cadencias[idx]} spm", color = CorCadencia, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                if (distLabel.isNotEmpty()) Text(distLabel, color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp)
            } else
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
                // Linhas verticais de km
                dados.kmMarcadores.forEach { frac ->
                    drawLine(Color.White.copy(alpha = 0.08f), Offset(frac * w, 0f), Offset(frac * w, h),
                        strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
                }
                
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
        EixoKm(dados.kmMarcadores, dados.kmLabels, larguraEixoY = 35.dp, larguraEixoYDir = 35.dp)
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

/**
 * Eixo X com marcadores de km alinhados à largura real do gráfico.
 * [marcadores] lista de frações 0..1 onde cada km fechado cai.
 * [larguraEixoY] largura reservada pelo EixoYVertical esquerdo (para alinhamento).
 * [larguraEixoYDir] largura reservada pelo EixoYVertical direito (opcional).
 */
@Composable
private fun EixoKm(
    marcadores: List<Float>,
    labels: List<String> = emptyList(),
    larguraEixoY: Dp = 35.dp,
    larguraEixoYDir: Dp = 35.dp
) {
    if (marcadores.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 3.dp)
    ) {
        Spacer(modifier = Modifier.width(larguraEixoY))

        // height(18.dp): texto 8sp tem ~13-14dp de altura.
        // A altura anterior (12dp) clipava a metade superior dos caracteres.
        // fillMaxWidth(frac) + wrapContentWidth(End) posiciona cada label
        // na fração correta sem depender de constraints em tempo de composição.
        Box(modifier = Modifier.weight(1f).height(18.dp)) {
            marcadores.forEachIndexed { i, frac ->
                val label = labels.getOrElse(i) { "${i + 1}km" }
                Text(
                    text = label,
                    fontSize = 8.sp,
                    color = Color.White.copy(alpha = 0.35f),
                    modifier = Modifier
                        .fillMaxWidth(frac)
                        .wrapContentWidth(Alignment.End, unbounded = true)
                )
            }
        }

        Spacer(modifier = Modifier.width(larguraEixoYDir))
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
                    Text(" Z${idx + 1}", modifier = Modifier.weight(1f).padding(start = 8.dp), fontSize = 12.sp, color = Color.White)
                    if (z.tempo.isNotEmpty()) Text("${z.tempo}  ", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
                    Text("${z.percentagem.roundToInt()}%", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = cor)
                }
            }
        }
    }
}

@Composable
private fun CartaoVoltas(voltas: List<com.runapp.data.model.VoltaAnalise>) {
    val corTiro     = Color(0xFF4FC3F7)   // azul vivo = esforço
    val corDescanso = Color(0xFF546E7A)   // cinza azulado = recuperação

    val temIntervalos = voltas.any { !it.isDescanso } && voltas.any { it.isDescanso }
    val titulo   = if (temIntervalos) "Análise do Treino" else "Voltas"
    val subtitulo = if (temIntervalos) "Azul = esforço  •  Cinza = recuperação" else "Voltas detectadas automaticamente"

    Card(colors = CardDefaults.cardColors(containerColor = CorCard), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(titulo, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(subtitulo, fontSize = 11.sp, color = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.padding(bottom = 12.dp))

            // ── Gráfico de barras ────────────────────────────────────────────
            val pacesValidos = voltas.map { it.paceSegKm }.filter { it in 60.0..1200.0 }
            val paceMin = pacesValidos.minOrNull() ?: 240.0
            val paceMax = pacesValidos.maxOrNull() ?: 720.0
            val paceRange = (paceMax - paceMin).coerceAtLeast(1.0)

            // Dashed reference line = pace médio das voltas de esforço
            val paceMediaTiros = voltas.filter { !it.isDescanso }
                .map { it.paceSegKm }.filter { it in 60.0..1200.0 }
                .let { if (it.isEmpty()) null else it.average() }

            // Labels eixo Y (pace mais rápido no topo, mais lento na base)
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.width(40.dp).height(120.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatarPaceSegKm(paceMin),
                        fontSize = 8.sp,
                        color = Color.White.copy(alpha = 0.4f)
                    )
                    Text(
                        text = formatarPaceSegKm((paceMin + paceMax) / 2),
                        fontSize = 8.sp,
                        color = Color.White.copy(alpha = 0.4f)
                    )
                    Text(
                        text = formatarPaceSegKm(paceMax),
                        fontSize = 8.sp,
                        color = Color.White.copy(alpha = 0.4f)
                    )
                }
                Canvas(modifier = Modifier
                    .weight(1f)
                    .height(120.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.03f))
                ) {
                    val w = size.width; val h = size.height
                    val n = voltas.size
                    if (n == 0) return@Canvas

                    val barW = w / n
                    val padH = 4f

                    // Grid lines
                    for (i in 1..3) drawLine(CorGrid, Offset(0f, h * i / 4), Offset(w, h * i / 4))

                    // Linha de referência (pace médio dos tiros)
                    // Invertido: pace rápido (baixo s/km) = topo do gráfico
                    paceMediaTiros?.let { refPace ->
                        val refNorm = ((paceMax - refPace) / paceRange).toFloat().coerceIn(0f, 1f)
                        val refY = padH + (h - padH * 2) * refNorm
                        drawLine(
                            color = corTiro.copy(alpha = 0.35f),
                            start = Offset(0f, refY), end = Offset(w, refY),
                            strokeWidth = 1.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                        )
                    }

                    // Barras — invertidas: pace rápido = barra alta (esforço visualmente maior)
                    voltas.forEachIndexed { i, volta ->
                        val norm = ((paceMax - volta.paceSegKm) / paceRange).toFloat().coerceIn(0.05f, 1f)
                        val barH = (h - padH) * norm
                        val barX = i * barW
                        val cor  = if (volta.isDescanso) corDescanso.copy(alpha = 0.65f)
                                   else corTiro.copy(alpha = 0.85f)
                        drawRect(
                            color   = cor,
                            topLeft = Offset(barX + 2f, h - barH),
                            size    = Size(barW - 4f, barH)
                        )
                    }
                }
            }

            // ── Rótulos do eixo X (números das voltas) ───────────────────────
            val mostrarLabels = voltas.size <= 20
            if (mostrarLabels) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    voltas.forEachIndexed { i, _ ->
                        Text(
                            text = "${i + 1}",
                            modifier = Modifier.weight(1f),
                            fontSize = 8.sp,
                            color = Color.White.copy(alpha = 0.3f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // ── Lista de voltas ──────────────────────────────────────────────
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Volta", fontSize = 10.sp, color = Color.White.copy(alpha = 0.35f), modifier = Modifier.width(44.dp))
                Text("Dist.", fontSize = 10.sp, color = Color.White.copy(alpha = 0.35f), modifier = Modifier.width(64.dp))
                Text("Tempo", fontSize = 10.sp, color = Color.White.copy(alpha = 0.35f), modifier = Modifier.width(52.dp))
                Text("Pace", fontSize = 10.sp, color = Color.White.copy(alpha = 0.35f), textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            }
            Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.08f)))
            Spacer(modifier = Modifier.height(4.dp))

            voltas.forEach { volta ->
                val corPaceVolta = if (volta.isDescanso) corDescanso else corTiro
                val labelTipo    = if (volta.isDescanso) "Rec." else "Tiro"
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Número + indicador de tipo
                    Row(modifier = Modifier.width(44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(6.dp).background(corPaceVolta, CircleShape))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("${volta.numero}", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    // Distância
                    Text(
                        "%.2f km".format(volta.distanciaKm),
                        fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.width(64.dp)
                    )
                    // Tempo
                    val min = volta.tempoSegundos / 60
                    val sec = volta.tempoSegundos % 60
                    Text(
                        "%d:%02d".format(min, sec),
                        fontSize = 13.sp, color = Color.White.copy(alpha = 0.55f),
                        modifier = Modifier.width(52.dp)
                    )
                    // Pace
                    Text(
                        "${volta.paceFormatado}/km",
                        fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        color = corPaceVolta,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ── Resumo: total de tiros e descanso ────────────────────────────
            if (temIntervalos) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.08f)))
                Spacer(modifier = Modifier.height(8.dp))
                val nTiros     = voltas.count { !it.isDescanso }
                val nDescansos = voltas.count { it.isDescanso }
                val distTiros  = voltas.filter { !it.isDescanso }.sumOf { it.distanciaKm }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$nTiros", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = corTiro)
                        Text("tiros", fontSize = 10.sp, color = Color.White.copy(alpha = 0.4f))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("%.2f km".format(distTiros), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = corTiro)
                        Text("vol. esforço", fontSize = 10.sp, color = Color.White.copy(alpha = 0.4f))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$nDescansos", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = corDescanso)
                        Text("recuperações", fontSize = 10.sp, color = Color.White.copy(alpha = 0.4f))
                    }
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

/**
 * Preenche os valores -1f (pontos inválidos de GPS spike) com interpolação linear
 * entre o último ponto válido anterior e o próximo ponto válido posterior.
 * Se não existir ponto válido antes, usa o próximo. Se não existir depois, usa o anterior.
 * Isso evita buracos visuais no gráfico sem distorcer os valores reais nos pontos bons.
 */
private fun interpolarInvalidos(lista: List<Float>): List<Float> {
    if (lista.none { it < 0f }) return lista  // nada a fazer
    val resultado = lista.toMutableList()
    for (i in lista.indices) {
        if (lista[i] >= 0f) continue
        // Busca vizinho válido mais próximo antes e depois
        val antes = (i - 1 downTo 0).firstOrNull { lista[it] >= 0f }
        val depois = (i + 1..lista.lastIndex).firstOrNull { lista[it] >= 0f }
        resultado[i] = when {
            antes != null && depois != null -> {
                val t = (i - antes).toFloat() / (depois - antes).toFloat()
                lista[antes] + t * (lista[depois] - lista[antes])
            }
            antes != null  -> lista[antes]
            depois != null -> lista[depois]
            else           -> 0f
        }
    }
    return resultado
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
    val gapMax:  Double,
    // Fração 0..1 no eixo X onde cada marcador de distância ocorre (intervalo adaptativo)
    val kmMarcadores:  List<Float> = emptyList(),
    // Labels dos marcadores (ex: "1km", "2km" ou "100m", "200m" para corridas curtas)
    val kmLabels:      List<String> = emptyList(),
    // Distância acumulada em km para cada ponto downsampled (para label do cursor)
    val distanciasKm:  List<Float> = emptyList()
)

data class InfoZona(val nome: String, val percentagem: Float, val tempo: String, val cor: String = "")

private fun prepararDados(rota: List<LatLngPonto>): DadosGrafico {
    val vazio = DadosGrafico(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), emptyList(), false, false, 300.0, 600.0, 300.0, 600.0)
    if (rota.size < 2) return vazio

    // Detecção prévia de spikes para excluir da SMA de altitude
    val isAltSpike = BooleanArray(rota.size) { false }
    for (i in 1 until rota.size) {
        val dtS = ((rota[i].tempo - rota[i-1].tempo) / 1000.0).coerceAtLeast(0.1)
        val distM = haversineM(rota[i-1].lat, rota[i-1].lng, rota[i].lat, rota[i].lng)
        if (distM / dtS > 12.0) { isAltSpike[i] = true; isAltSpike[i-1] = true }
    }

    // Suavização de altitude (média móvel ±5) excluindo pontos de spike GPS.
    // Pontos spike ficam como -1f e são interpolados logo após para linha contínua.
    val altsRawF = rota.mapIndexed { i, pt ->
        if (isAltSpike[i]) -1f
        else {
            val a = (i - 5).coerceAtLeast(0); val b = (i + 5).coerceAtMost(rota.lastIndex)
            val vizinhos = (a..b).mapNotNull { j -> if (!isAltSpike[j]) rota[j].alt else null }
            (if (vizinhos.isEmpty()) pt.alt else vizinhos.average()).toFloat()
        }
    }
    val altsSuav = interpolarInvalidos(altsRawF).map { it.toDouble() }
    
    // ── Detecção de saltos GPS ─────────────────────────────────────────────────
    // Quando o GPS perde sinal e volta numa posição diferente, haversine/Δt
    // indica velocidade impossível (>12 m/s = 43km/h). Esses pontos são forçados
    // para INVÁLIDO (-1.0) no gráfico → linha quebrada, sem spike visual falso.
    // Marca também 1 ponto antes/depois como zona de contaminação da EMA.
    val MAX_SPEED_GPS_MS = 12.0
    val isGpsSpike = BooleanArray(rota.size) { false }
    for (i in 1 until rota.size) {
        val dtS = ((rota[i].tempo - rota[i-1].tempo) / 1000.0).coerceAtLeast(0.1)
        val distM = haversineM(rota[i-1].lat, rota[i-1].lng, rota[i].lat, rota[i].lng)
        if (distM / dtS > MAX_SPEED_GPS_MS) {
            isGpsSpike[i] = true
            isGpsSpike[i - 1] = true
            if (i + 1 <= rota.lastIndex) isGpsSpike[i + 1] = true
        }
    }

    val paces = rota.mapIndexed { i, pt ->
        if (isGpsSpike[i]) -1.0 else pt.paceNoPonto
    }
    val pacesValidos = paces.filter { it in 60.0..1200.0 }
    val pMin = pacesValidos.minOrNull() ?: 300.0
    val pMax = pacesValidos.maxOrNull() ?: 600.0

    // SMA centrada de ±12 pontos (~24s) aplicada APENAS para o gráfico.
    // O paceNoPonto foi gravado com responsividade de display em mente — correto para
    // o número na tela em tempo real, mas "nervoso" demais para visualização histórica.
    // A altitude já usa SMA ±5; o pace precisa de janela maior pois é mais ruidoso.
    // Importante: usamos apenas pontos válidos (60-1200 s/km) na média — pontos inválidos
    // (paradas, GPS perdido) são ignorados no cálculo mas marcados como -1 no resultado
    // para que o gráfico quebre a linha nesses trechos (comportamento correto).
    val JANELA_SUAV = 12  // ±12 pontos = janela de 25 pontos centrada = ~24s de dados
    val pacesSuav = paces.mapIndexed { i, p ->
        if (p !in 60.0..1200.0) return@mapIndexed -1.0  // inválido — não suaviza
        val a = (i - JANELA_SUAV).coerceAtLeast(0)
        val b = (i + JANELA_SUAV).coerceAtMost(paces.lastIndex)
        val vizinhos = (a..b).mapNotNull { j ->
            if (!isGpsSpike[j] && paces[j] in 60.0..1200.0) paces[j] else null
        }
        if (vizinhos.isEmpty()) -1.0 else vizinhos.average()
    }

    // Recalcula pMin/pMax com os valores suavizados para aproveitar melhor o range do gráfico
    val pacesSuavValidos = pacesSuav.filter { it in 60.0..1200.0 }
    val pMinSuav = pacesSuavValidos.minOrNull() ?: pMin
    val pMaxSuav = pacesSuavValidos.maxOrNull() ?: pMax
    val paceRangeSuav = (pMaxSuav - pMinSuav).coerceAtLeast(1.0)

    // GAP: usa paces ORIGINAIS (não suavizados) — cálculo biomecânico, não visual.
    // Fórmula de Minetti (2002) idêntica à do RunningService:
    //   C(g) = 155.4g⁵ - 30.4g⁴ - 43.3g³ + 46.3g² + 19.5g + 3.6
    //   fator = C(g) / C(0) = C(g) / 3.6
    //   GAP   = pace / fator  (subida → fator > 1 → GAP < pace = mais rápido no plano)
    // g é fração adimensional (0.08 = 8%), não percentagem.
    fun fatorMinettiLocal(gradFrac: Double): Double {
        val g = gradFrac.coerceIn(-0.45, 0.45)
        val custo = 155.4 * Math.pow(g, 5.0) -
                     30.4 * Math.pow(g, 4.0) -
                     43.3 * Math.pow(g, 3.0) +
                     46.3 * Math.pow(g, 2.0) +
                     19.5 * g + 3.6
        return (custo / 3.6).coerceAtLeast(0.1)
    }

    val gaps = rota.mapIndexed { i, pt ->
        val p = pt.paceNoPonto
        if (p !in 60.0..1200.0) return@mapIndexed 0.0
        val gradFrac = if (i > 0) {
            val dAlt  = altsSuav[i] - altsSuav[i - 1]
            val dDist = haversineM(rota[i - 1].lat, rota[i - 1].lng, pt.lat, pt.lng).coerceAtLeast(0.5)
            (dAlt / dDist).coerceIn(-0.45, 0.45)
        } else 0.0
        (p / fatorMinettiLocal(gradFrac)).coerceIn(60.0, 1200.0)
    }
    val gMin = gaps.filter { it > 0 }.minOrNull() ?: pMin
    val gMax = gaps.filter { it > 0 }.maxOrNull() ?: pMax

    // Downsampling para performance (max 300 pontos no gráfico)
    val step = maxOf(1, rota.size / 300)
    val idxs = rota.indices.filter { it % step == 0 }

    val paceRange = paceRangeSuav
    val gapRange  = (gMax - gMin).coerceAtLeast(1.0)
    val altMin = altsSuav.min(); val altMax = altsSuav.max()
    val altRange  = (altMax - altMin).coerceAtLeast(1.0)

    // ── Distância acumulada por ponto original ───────────────────────────────
    // Usada para marcar km no eixo X e para o label de distância do cursor.
    val distAcumM = DoubleArray(rota.size)
    for (i in 1 until rota.size) {
        distAcumM[i] = distAcumM[i - 1] + haversineM(
            rota[i - 1].lat, rota[i - 1].lng, rota[i].lat, rota[i].lng
        )
    }
    val distTotalM = distAcumM.last().coerceAtLeast(1.0)

    // Fração 0..1 onde cada marcador de distância cai.
    // O intervalo é adaptativo: para corridas curtas usa 100m/200m/500m
    // para que sempre apareçam marcadores mesmo em testes de <1km.
    val intervaloM = when {
        distTotalM < 300.0  ->  50.0   // < 300m: a cada 50m
        distTotalM < 800.0  -> 100.0   // < 800m: a cada 100m
        distTotalM < 2000.0 -> 200.0   // < 2km:  a cada 200m
        distTotalM < 5000.0 -> 500.0   // < 5km:  a cada 500m
        else                -> 1000.0  // >= 5km: a cada 1km
    }
    val labelSuffix = when (intervaloM) {
        50.0, 100.0, 200.0, 500.0 -> "m"
        else -> "km"
    }
    val kmMarcadores = mutableListOf<Float>()
    val kmLabels     = mutableListOf<String>()
    var marcadorProximo = intervaloM
    for (idx in idxs) {
        if (distAcumM[idx] >= marcadorProximo) {
            kmMarcadores.add((distAcumM[idx] / distTotalM).toFloat().coerceIn(0f, 1f))
            kmLabels.add(if (labelSuffix == "km")
                "${(marcadorProximo / 1000.0).toInt()}km"
            else
                "${marcadorProximo.toInt()}m"
            )
            marcadorProximo += intervaloM
        }
    }

    // Min/max reais dos pontos subamostrados — alinha normalização com labels do eixo Y
    val pMinReal = idxs.mapNotNull { i -> pacesSuav[i].takeIf { it in 60.0..1200.0 } }.minOrNull() ?: pMinSuav
    val pMaxReal = idxs.mapNotNull { i -> pacesSuav[i].takeIf { it in 60.0..1200.0 } }.maxOrNull() ?: pMaxSuav
    val pRangeReal = (pMaxReal - pMinReal).coerceAtLeast(1.0)

    return DadosGrafico(
        paceNorm = idxs.map { i ->
            val p = pacesSuav[i]; if (p < 0.0) -1f
            else ((p - pMinReal) / pRangeReal).toFloat().coerceIn(0f, 1f)
        },
        altNorm = idxs.map { i ->
            ((altsSuav[i] - altMin) / altRange).toFloat().coerceIn(0f, 1f)
        },
        gapNorm = idxs.map { i ->
            val g = gaps[i]; if (g <= 0) -1f
            else ((g - gMin) / gapRange).toFloat().coerceIn(0f, 1f)
        },
        paceFormatado = idxs.map { formatarPaceSegKm(pacesSuav[it].coerceAtLeast(0.0).let { v -> if (v < 60.0) paces[it] else v }) },
        gapFormatado  = idxs.map { formatarPaceSegKm(gaps[it]) },
        altitudes     = idxs.map { altsSuav[it] },
        cadencias     = run {
            // Interpola zeros entre leituras válidas de cadência (igual ao pace).
            // Zeros ocorrem em pontos-âncora e nos primeiros pontos de cada segmento.
            val raw = idxs.map { rota[it].cadenciaNoPonto.toFloat().let { v -> if (v <= 0f) -1f else v } }
            interpolarInvalidos(raw).map { it.toInt().coerceAtLeast(0) }
        },
        indicesOriginais = idxs,
        temCadencia   = rota.any { it.cadenciaNoPonto > 0 },
        temGAP        = (altMax - altMin) > 5.0,
        paceMin = pMinReal, paceMax = pMaxReal, gapMin = gMin, gapMax = gMax,
        kmMarcadores  = kmMarcadores,
        kmLabels      = kmLabels,
        distanciasKm  = idxs.map { (distAcumM[it] / 1000.0).toFloat() }
    )
}

private fun calcularZonasPorTempo(
    rota: List<LatLngPonto>,
    zonasFronteira: List<ZonaFronteira>
): List<InfoZona> {
    if (zonasFronteira.isEmpty() || rota.size < 2) return emptyList()

    // DIAGNÓSTICO: verifica se os timestamps dos pontos são válidos e crescentes.
    // Pode falhar em GPX legados ou com queda de relógio. Nesse caso,
    // assume 1 ponto GPS = 1 segundo (frequência padrão do service).
    val temTimestampsValidos = run {
        var positivos = 0
        for (i in 0 until minOf(rota.size - 1, 20)) {
            val delta = rota[i + 1].tempo - rota[i].tempo
            if (delta in 100L..5_000L) positivos++
        }
        positivos >= 5
    }

    val tempoMs = LongArray(zonasFronteira.size)
    var totalMs = 0L
    val MS_POR_PONTO = 1_000L  // fallback: 1s por ponto GPS

    for (i in 0 until rota.size - 1) {
        val pace = rota[i].paceNoPonto
        if (pace < 60.0 || pace > 1800.0) continue

        val intervaloMs = if (temTimestampsValidos) {
            val delta = (rota[i + 1].tempo - rota[i].tempo).coerceAtLeast(0L)
            if (delta == 0L || delta > 60_000L) continue else delta
        } else {
            MS_POR_PONTO
        }

        // Zonas ordenadas lenta→rapida (index 0 = mais lenta, lastIndex = mais rapida).
        // Default = 0 (zona mais lenta): paces fora do range (muito lento ou GPS sem valor)
        // caem na zona de recuperacao, nao na mais rapida.
        var zonaIdx = 0
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

// 8 buckets de cor pré-computados: verde (lento) → vermelho (rápido).
// A quantização é o segredo da performance: ao limitar as cores possíveis,
// a mesclagem de polylines adjacentes funciona muito melhor → 8-15 Polylines
// no mapa em vez de centenas. Imperceptível visualmente.
private val HEATMAP_BUCKETS = arrayOf(
    Color(0xFF4CAF50),  // 0 — muito lento    (> 8:00/km)
    Color(0xFF8BC34A),  // 1 — lento           (7:00–8:00)
    Color(0xFFCDDC39),  // 2 — moderado lento  (6:00–7:00)
    Color(0xFFFFEB3B),  // 3 — moderado        (5:30–6:00)
    Color(0xFFFFC107),  // 4 — moderado rápido (5:00–5:30)
    Color(0xFFFF9800),  // 5 — rápido          (4:30–5:00)
    Color(0xFFFF5722),  // 6 — muito rápido    (3:30–4:30)
    Color(0xFFF44336),  // 7 — sprint          (< 3:30/km)
)

/** Mapeia pace (seg/km) para índice de bucket 0–7. */
private fun paceToBucket(pace: Double): Int = when {
    pace > 480 -> 0   // > 8:00/km
    pace > 420 -> 1   // 7:00–8:00
    pace > 360 -> 2   // 6:00–7:00
    pace > 330 -> 3   // 5:30–6:00
    pace > 300 -> 4   // 5:00–5:30
    pace > 270 -> 5   // 4:30–5:00
    pace > 210 -> 6   // 3:30–4:30
    else       -> 7   // < 3:30 (sprint)
}

private fun calcularSegmentosHeatmapDetalhe(rota: List<LatLngPonto>): List<SegDetalhe> {
    if (rota.size < 2) return emptyList()
    return buildList {
        for (i in 0 until rota.size - 1) {
            val p1 = rota[i]; val p2 = rota[i + 1]
            val dtMs = p2.tempo - p1.tempo; if (dtMs <= 0) continue
            val distM = haversineM(p1.lat, p1.lng, p2.lat, p2.lng); if (distM < 1.0) continue
            val pace = (dtMs / 1000.0) / distM * 1000.0
            if (pace < 120.0 || pace > 1200.0) continue
            // Usa cor do bucket, não cor contínua → chave da otimização
            add(SegDetalhe(LatLng(p1.lat, p1.lng), LatLng(p2.lat, p2.lng),
                HEATMAP_BUCKETS[paceToBucket(pace)]))
        }
    }
}

/**
 * Mescla segmentos de mesma cor em Polylines únicas.
 * Com 8 buckets fixos, segmentos adjacentes do mesmo bucket são fundidos
 * → resultado típico: 8–20 Polylines para qualquer corrida.
 */
private fun mesclarPolylinesDetalhe(segmentos: List<SegDetalhe>): List<Pair<List<LatLng>, Color>> {
    if (segmentos.isEmpty()) return emptyList()
    val resultado = mutableListOf<Pair<List<LatLng>, Color>>()
    var corAtual = segmentos[0].cor
    var pontosAtual = mutableListOf(segmentos[0].inicio, segmentos[0].fim)

    for (i in 1 until segmentos.size) {
        val seg = segmentos[i]
        if (seg.cor == corAtual) {   // igualdade exata: buckets são objetos fixos
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

private fun formatarPaceSegKm(s: Double): String {
    if (s !in 60.0..1200.0) return "--:--"
    return "%d:%02d".format((s / 60).toInt(), (s % 60).toInt())
}

private fun lerpD(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t,
    a.blue + (b.blue - a.blue) * t, 1f)

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
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Card do Coach (Gemini 2.5 Flash)
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun CartaoCoach(estado: CoachUiState, onRegenerar: () -> Unit) {
    if (estado is CoachUiState.Inativo) return

    val corAccent = Color(0xFF80CBC4)
    val corFundo  = Color(0xFF1A2928)

    // Expandido por padrão quando o texto está pronto
    var expandido by remember(estado) {
        mutableStateOf(estado is CoachUiState.Pronto)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = corFundo),
        border = androidx.compose.foundation.BorderStroke(1.dp, corAccent.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
        ) {
            // ── Cabeçalho clicável ───────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = estado is CoachUiState.Pronto,
                        onClick = { expandido = !expandido }
                    )
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = null,
                    tint = corAccent,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Análise do Coach",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = corAccent,
                    modifier = Modifier.weight(1f)
                )
                if (estado is CoachUiState.Pronto) {
                    Icon(
                        imageVector = if (expandido) Icons.Default.KeyboardArrowUp
                                      else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expandido) "Recolher" else "Expandir",
                        tint = corAccent.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // ── Corpo ────────────────────────────────────────────────────────
            when (estado) {
                is CoachUiState.Inativo -> {}

                is CoachUiState.Carregando -> {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = corAccent,
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Analisando o treino...",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF888888)
                        )
                    }
                }

                is CoachUiState.Pronto -> {
                    if (expandido) {
                        // Detecta se o texto foi cortado pelo limite de tokens da API.
                        // Resposta cortada: não termina com pontuação de fim de frase.
                        val foiCortado = run {
                            val trimmed = estado.texto.trimEnd()
                            trimmed.isNotEmpty() && trimmed.last() !in listOf('.', '!', '?', '"', ')')
                        }
                        CoachTexto(
                            texto = estado.texto,
                            foiCortado = foiCortado,
                            onRegenerar = onRegenerar,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                        )
                    }
                }

                is CoachUiState.Erro -> {
                    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                        Text(
                            text = "⚠️ Não foi possível gerar a análise.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF888888)
                        )
                        if (estado.mensagem.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = estado.mensagem,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF555555)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Renderiza o texto do Coach parágrafo a parágrafo, com suporte a **negrito**.
 * Mostra o texto completo — sem truncagem.
 */
@Composable
private fun CoachTexto(texto: String, foiCortado: Boolean = false, onRegenerar: () -> Unit = {}, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        texto.split("\n").filter { it.isNotBlank() }.forEach { paragrafo ->
            Text(
                text = parseBold(paragrafo),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFCCCCCC),
                lineHeight = 20.sp
            )
        }
        // Aviso discreto quando o modelo foi interrompido pelo limite de tokens
        if (foiCortado) {
            Text(
                text = "↩ análise incompleta — toque para regenerar",
                fontSize = 10.sp,
                color = Color(0xFF80CBC4).copy(alpha = 0.8f),
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable { onRegenerar() }
            )
        }
    }
}

/**
 * Converte **texto** → AnnotatedString com SpanStyle bold+white.
 * Tudo fora das marcações fica em cor normal.
 */
private fun parseBold(texto: String): androidx.compose.ui.text.AnnotatedString {
    val builder = androidx.compose.ui.text.AnnotatedString.Builder()
    val regex   = Regex("""\*\*(.+?)\*\*""")
    var ultimo  = 0
    regex.findAll(texto).forEach { match ->
        if (match.range.first > ultimo) builder.append(texto.substring(ultimo, match.range.first))
        builder.pushStyle(
            androidx.compose.ui.text.SpanStyle(
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        )
        builder.append(match.groupValues[1])
        builder.pop()
        ultimo = match.range.last + 1
    }
    if (ultimo < texto.length) builder.append(texto.substring(ultimo))
    return builder.toAnnotatedString()
}
