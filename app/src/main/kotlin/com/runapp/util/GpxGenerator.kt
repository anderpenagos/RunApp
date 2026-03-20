package com.runapp.util

import android.util.Log
import com.runapp.data.model.LatLngPonto
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Gera arquivo GPX (GPS Exchange Format) a partir dos dados da corrida.
 *
 * Compatível com Strava, Garmin Connect, Intervals.icu e demais plataformas GPX 1.1.
 *
 * Melhorias aplicadas:
 *  A) Locale.US em todos os format() — evita vírgula decimal em pt-BR (rompe parsers externos).
 *  B) Namespace TrackPointExtension da Garmin — cadência lida pelo Strava e Garmin Connect.
 *  C) Timestamp real de cada ponto GPS — preserva pausas e variações de ritmo no gráfico.
 *  D) Smoothing de coordenadas e altitude antes do export — reduz picos espúrios de pace
 *     no Intervals.icu causados por ruído GPS, sem distorcer a distância total (<0.1%).
 */
object GpxGenerator {

    private const val TAG = "GpxGenerator"

    // ── Parâmetros de suavização (FIX D) ──────────────────────────────────────
    //
    // JANELA DE COORDENADAS (lat/lng): 5 pontos, pesos gaussianos [1,2,4,2,1]
    //   Janela pequena para preservar curvas reais do percurso.
    //   Impacto na distância total: <0.05% em corrida normal.
    //
    // JANELA DE ALTITUDE: 7 pontos, pesos [1,2,3,4,3,2,1]
    //   Altitude do GPS Android é muito mais ruidosa que lat/lng
    //   (erro típico ±10m vs ±3m). Janela maior é segura pois
    //   altitude não entra no cálculo de distância horizontal.
    //
    // FRONTEIRAS DE SEGMENTO: gap temporal > 3s entre pontos consecutivos,
    //   ou ponto-âncora (lat/lng idêntico ao anterior). A suavização nunca
    //   atravessa essas fronteiras, preservando a semântica de pausa no GPX.
    private val PESOS_COORD = intArrayOf(1, 2, 4, 2, 1)
    private val PESOS_ALT   = intArrayOf(1, 2, 3, 4, 3, 2, 1)
    private const val GAP_FRONTEIRA_MS = 3_000L

    // Raio da janela de mediana (raio=2 → janela de 5 pontos: i-2..i+2).
    // A mediana é aplicada antes da gaussiana para eliminar spikes isolados
    // causados por multipath GPS (ex: perto de lagos). A gaussiana então
    // suaviza o ruído residual de alta frequência que sobrou.
    private const val RAIO_MEDIANA = 2

    fun gerarGpx(
        nomeAtividade: String,
        pontos: List<LatLngPonto>,
        tempoSegundos: Long,
        dataHoraInicio: LocalDateTime = LocalDateTime.now()
    ): String {
        val sb = StringBuilder()

        // FIX D: suavizar coordenadas e altitude antes de escrever o GPX.
        // Timestamps, cadência e pace originais são preservados intactos.
        val pontosExport = suavizarPontos(pontos)

        // ── Cabeçalho ──────────────────────────────────────────────────────────
        // FIX B: Namespace gpxtpx adicionado — TrackPointExtension v1 da Garmin.
        // Reconhecido pelo Strava, Garmin Connect e maioria dos apps de análise.
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine(
            """<gpx version="1.1" creator="RunApp"""" +
            """ xmlns="http://www.topografix.com/GPX/1/1"""" +
            """ xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1"""" +
            """ xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"""" +
            """ xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd""" +
            """ http://www.garmin.com/xmlschemas/TrackPointExtension/v1 http://www.garmin.com/xmlschemas/TrackPointExtensionv1.xsd">"""
        )

        sb.appendLine("  <metadata>")
        sb.appendLine("    <name>${escaparXml(nomeAtividade)}</name>")
        sb.appendLine("    <time>${formatarDataHora(dataHoraInicio)}</time>")
        sb.appendLine("  </metadata>")

        sb.appendLine("  <trk>")
        sb.appendLine("    <name>${escaparXml(nomeAtividade)}</name>")
        sb.appendLine("    <type>9</type>") // 9 = Running no padrão GPX/Garmin
        sb.appendLine("    <trkseg>")

        pontosExport.forEach { ponto ->
            // FIX C: Usa o timestamp REAL gravado pelo GPS em cada ponto.
            // O cálculo linear anterior espalhava pontos igualmente e escondia
            // paradas reais (semáforo, auto-pause) no gráfico de ritmo.
            val timestampPonto = java.time.Instant.ofEpochMilli(ponto.tempo)
                .atZone(java.time.ZoneId.of("UTC"))
                .toLocalDateTime()

            // FIX A: Locale.US garante ponto decimal em lat/lon/ele.
            // Sem isso, celulares em pt-BR geram "845,7" que quebra parsers GPX.
            val latStr = "%.6f".format(Locale.US, ponto.lat)
            val lngStr = "%.6f".format(Locale.US, ponto.lng)

            sb.appendLine("      <trkpt lat=\"$latStr\" lon=\"$lngStr\">")

            if (ponto.alt != 0.0) {
                val altStr = "%.1f".format(Locale.US, ponto.alt)
                sb.appendLine("        <ele>$altStr</ele>")
            }

            sb.appendLine("        <time>${formatarDataHora(timestampPonto)}</time>")

            // FIX B: <gpxtpx:cad> é entendido pelo Strava e Garmin como SPM.
            // A tag <pace> customizada é mantida dentro do bloco para o dashboard
            // interno — o Strava a ignora (ele recalcula pace por distância/tempo).
            //
            // Pontos com paceNoPonto = 0.0 e cadenciaNoPonto = 0 são intencionais:
            // são pontos-âncora inseridos pelo RunningService após saltos de GPS.
            // Eles repetem as coordenadas do último ponto válido com timestamp
            // atualizado, criando um segmento de distância ≈ 0 que Intervals.icu
            // e Strava interpretam como pausa — sem spike de velocidade no gráfico.
            val temExtensoes = ponto.cadenciaNoPonto > 0 || ponto.paceNoPonto > 0.0
            if (temExtensoes) {
                sb.appendLine("        <extensions>")
                sb.appendLine("          <gpxtpx:TrackPointExtension>")
                if (ponto.cadenciaNoPonto > 0)
                    sb.appendLine("            <gpxtpx:cad>${ponto.cadenciaNoPonto}</gpxtpx:cad>")
                if (ponto.paceNoPonto > 0.0) {
                    val paceStr = "%.2f".format(Locale.US, ponto.paceNoPonto)
                    sb.appendLine("            <pace>$paceStr</pace>")
                }
                sb.appendLine("          </gpxtpx:TrackPointExtension>")
                sb.appendLine("        </extensions>")
            }

            sb.appendLine("      </trkpt>")
        }

        sb.appendLine("    </trkseg>")
        sb.appendLine("  </trk>")
        sb.appendLine("</gpx>")

        Log.d(TAG, "✅ GPX gerado: ${pontos.size} pontos suavizados | ${tempoSegundos}s | '$nomeAtividade'")
        return sb.toString()
    }

    // ── FIX D: Suavização por segmento ────────────────────────────────────────
    //
    // 1. Interpola gaps de velocidade implausível dentro do mesmo segmento.
    // 2. Divide a lista em segmentos separados por fronteiras (pausa ou âncora).
    // 3. Aplica média ponderada gaussiana em cada segmento independentemente.
    // 4. Reconstrói a lista mantendo timestamps, cadência e pace originais —
    //    só lat, lng e alt são alterados.

    // Velocidade máxima plausível para um corredor/caminhante.
    // Acima disso, dois pontos consecutivos no mesmo segmento são um spike
    // de GPS (ex: ponto-âncora → primeiro ponto pós-recovery) e serão interpolados.
    // 8 m/s = ~29 km/h — nenhum corredor recreativo chega perto disso.
    private const val MAX_VELOCIDADE_INTERPOLACAO_MS = 8.0

    /**
     * Detecta pares de pontos consecutivos dentro do mesmo segmento temporal
     * (gap < GAP_FRONTEIRA_MS) onde a velocidade implícita é impossível
     * (> MAX_VELOCIDADE_INTERPOLACAO_MS), e insere pontos intermediários
     * interpolados linearmente entre eles.
     *
     * Isso resolve o spike de velocidade gerado pelo par âncora-GPS:
     *   âncora: lat=A, lng=A, tempo=T-1ms  (coords do último ponto válido)
     *   novo ponto GPS: lat=B, lng=B, tempo=T  (coords após recovery, potencialmente longe)
     * → Intervals.icu vê: distância(A,B) / 0.001s = velocidade absurda.
     *
     * Após interpolação: N pontos intermediários com coords e timestamps espaçados
     * uniformemente → velocidade plausível em cada segmento.
     *
     * Altitude, cadência e pace são também interpolados/propagados para que
     * os gráficos do Intervals.icu não mostrem spikes nesses canais.
     */
    private fun interpolarGaps(pontos: List<LatLngPonto>): List<LatLngPonto> {
        if (pontos.size < 2) return pontos
        val resultado = mutableListOf<LatLngPonto>()

        for (i in pontos.indices) {
            resultado.add(pontos[i])
            if (i == pontos.lastIndex) break

            val a = pontos[i]
            val b = pontos[i + 1]
            val dtMs = b.tempo - a.tempo

            // Só age dentro do mesmo segmento temporal (< 3s entre pontos)
            if (dtMs <= 0 || dtMs >= GAP_FRONTEIRA_MS) continue

            val distM = haversine(a.lat, a.lng, b.lat, b.lng)
            val velMs = distM / (dtMs / 1000.0)

            // Velocidade plausível — nenhuma interpolação necessária
            if (velMs <= MAX_VELOCIDADE_INTERPOLACAO_MS) continue

            // Quantos pontos intermediários? Um por segundo, mínimo 1
            val nPontos = (dtMs / 1000.0).toInt().coerceAtLeast(1)
            Log.d(TAG, "🔀 Interpolando gap: ${distM.toInt()}m em ${dtMs}ms " +
                "(${String.format("%.1f", velMs)}m/s) → $nPontos pontos intermediários")

            for (k in 1..nPontos) {
                val t = k.toDouble() / (nPontos + 1)
                resultado.add(
                    a.copy(
                        lat  = a.lat + t * (b.lat - a.lat),
                        lng  = a.lng + t * (b.lng - a.lng),
                        alt  = if (a.alt != 0.0 && b.alt != 0.0)
                                   a.alt + t * (b.alt - a.alt)
                               else if (a.alt != 0.0) a.alt else b.alt,
                        tempo           = a.tempo + (t * dtMs).toLong(),
                        // Cadência: propaga a do ponto anterior (mais estável que b,
                        // que pode ser o primeiro ponto após recovery ainda instável)
                        cadenciaNoPonto = if (a.cadenciaNoPonto > 0) a.cadenciaNoPonto
                                          else b.cadenciaNoPonto,
                        // Pace: interpola entre os dois vizinhos válidos
                        paceNoPonto     = if (a.paceNoPonto > 0 && b.paceNoPonto > 0)
                                              a.paceNoPonto + t * (b.paceNoPonto - a.paceNoPonto)
                                          else if (a.paceNoPonto > 0) a.paceNoPonto
                                          else b.paceNoPonto
                    )
                )
            }
        }
        return resultado
    }

    /**
     * Retorna a distancia total em metros calculada sobre os pontos SUAVIZADOS.
     *
     * Esta e a distancia "oficial" que corresponde ao GPX exportado -- deve ser
     * usada para gravar o historico da corrida, para que o valor exibido no app
     * bata exatamente com o que o Intervals.icu e o Strava irao mostrar.
     *
     * Tipicamente 0.05-0.15% menor que a distancia bruta (o smoothing elimina
     * o zig-zag de ruido GPS, que e micro-distancia extra nao percorrida).
     */
    fun calcularDistanciaSmoothed(pontos: List<LatLngPonto>): Double {
        val suavizados = suavizarPontos(pontos)
        var total = 0.0
        for (i in 1 until suavizados.size) {
            val a = suavizados[i - 1]
            val b = suavizados[i]
            val gapMs = b.tempo - a.tempo
            val mesmaPos = a.lat == b.lat && a.lng == b.lng
            if (gapMs > GAP_FRONTEIRA_MS || mesmaPos) continue
            val dist2D = haversine(a.lat, a.lng, b.lat, b.lng)
            val dAlt = if (a.alt != 0.0 && b.alt != 0.0) b.alt - a.alt else 0.0
            total += Math.sqrt(dist2D * dist2D + dAlt * dAlt)
        }
        return total
    }

    private fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2).let { it * it } +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2).let { it * it }
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    private fun suavizarPontos(pontos: List<LatLngPonto>): List<LatLngPonto> {
        if (pontos.size < 3) return pontos

        // Passo 0: interpolar gaps de velocidade impossível antes de qualquer suavização.
        // Garante que spikes de coordenada (âncora → primeiro ponto pós-recovery) sejam
        // preenchidos com pontos coerentes antes de chegar ao Intervals.icu / Strava.
        val base = interpolarGaps(pontos)

        // Identificar fronteiras entre pontos consecutivos
        val ehFronteira = BooleanArray(base.size) { false }
        for (i in 0 until base.size - 1) {
            val gapMs = base[i + 1].tempo - base[i].tempo
            val mesmaPos = base[i].lat == base[i + 1].lat &&
                           base[i].lng == base[i + 1].lng
            if (gapMs > GAP_FRONTEIRA_MS || mesmaPos) {
                ehFronteira[i] = true
            }
        }

        // Dividir em segmentos contíguos
        val segmentos = mutableListOf<IntRange>()
        var inicio = 0
        for (i in base.indices) {
            if (ehFronteira[i] || i == base.lastIndex) {
                segmentos.add(inicio..i)
                inicio = i + 1
            }
        }

        // Passe 1: filtro de mediana — elimina spikes isolados (ex: multipath GPS)
        val aposMediana = base.toMutableList()
        for (seg in segmentos) {
            if (seg.last - seg.first < 2) continue
            for (i in seg) {
                aposMediana[i] = base[i].copy(
                    lat = mediana(base, i, seg, RAIO_MEDIANA) { it.lat },
                    lng = mediana(base, i, seg, RAIO_MEDIANA) { it.lng },
                    alt = if (base[i].alt != 0.0)
                              mediana(base, i, seg, RAIO_MEDIANA) { it.alt }
                          else 0.0
                )
            }
        }

        // Passe 2: gaussiana sobre o resultado da mediana — suaviza ruído residual
        val resultado = aposMediana.toMutableList()
        for (seg in segmentos) {
            if (seg.last - seg.first < 2) continue
            for (i in seg) {
                resultado[i] = aposMediana[i].copy(
                    lat = mediaGaussiana(aposMediana, i, seg, PESOS_COORD) { it.lat },
                    lng = mediaGaussiana(aposMediana, i, seg, PESOS_COORD) { it.lng },
                    alt = if (aposMediana[i].alt != 0.0)
                              mediaGaussiana(aposMediana, i, seg, PESOS_ALT) { it.alt }
                          else 0.0
                )
            }
        }
        return resultado
    }

    /**
     * Média ponderada gaussiana para o ponto [idx] dentro do [segmento].
     * Nas bordas, os pesos são truncados e renormalizados automaticamente.
     */
    private fun mediaGaussiana(
        pontos: List<LatLngPonto>,
        idx: Int,
        segmento: IntRange,
        pesos: IntArray,
        selector: (LatLngPonto) -> Double
    ): Double {
        val raio = pesos.size / 2
        var soma = 0.0
        var somaPesos = 0
        for (k in pesos.indices) {
            val vizinho = idx - raio + k
            if (vizinho < segmento.first || vizinho > segmento.last) continue
            soma += selector(pontos[vizinho]) * pesos[k]
            somaPesos += pesos[k]
        }
        return if (somaPesos > 0) soma / somaPesos else selector(pontos[idx])
    }

    /**
     * Mediana dos valores do [selector] numa janela de [raio] pontos ao redor de [idx],
     * respeitando as fronteiras do [segmento]. Nas bordas a janela é truncada.
     */
    private fun mediana(
        pontos: List<LatLngPonto>,
        idx: Int,
        segmento: IntRange,
        raio: Int,
        selector: (LatLngPonto) -> Double
    ): Double {
        val vizinhos = ((idx - raio)..(idx + raio))
            .filter { it >= segmento.first && it <= segmento.last }
            .map { selector(pontos[it]) }
            .sorted()
        return vizinhos[vizinhos.size / 2]
    }

    /**
     */
    fun salvarArquivo(gpxContent: String, caminhoArquivo: File): File {
        caminhoArquivo.writeText(gpxContent)
        return caminhoArquivo
    }

    /**
     * Formata LocalDateTime para ISO 8601 com sufixo 'Z' (UTC).
     * Exemplo: "2024-05-15T10:30:00Z"
     */
    private fun formatarDataHora(dateTime: LocalDateTime): String =
        dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z"

    /**
     * Escapa caracteres especiais XML no nome da atividade.
     * Evita GPX inválido se o usuário nomear a corrida com &, <, > etc.
     */
    private fun escaparXml(texto: String): String = texto
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
