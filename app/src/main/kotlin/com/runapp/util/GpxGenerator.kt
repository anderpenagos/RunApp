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
 *  D) Pipeline de suavização em 4 estágios antes do export:
 *
 *     ESTÁGIO 1 — Rejeição por accuracy (campo já gravado em LatLngPonto):
 *       Pontos com accuracy > ACCURACY_MAX são substituídos por interpolação
 *       linear no tempo entre o último e o próximo ponto válido. Cobre casos
 *       de multipath GPS (perto de lagos, prédios, túneis) onde o sensor
 *       reporta a própria incerteza via o campo accuracy.
 *       Pontos-âncora (lat/lng == anterior) são sempre preservados.
 *
 *     ESTÁGIO 2 — Rejeição por velocidade física impossível:
 *       Velocidade implícita entre ponto i e i+1 calculada via haversine + Δt.
 *       Se > VELOCIDADE_MAX_MS (12 m/s ≈ 43 km/h), o ponto i+1 é descartado
 *       e interpolado. Threshold conservador: cobre qualquer corredor humano,
 *       inclusive tiros de 50–100m, sem rejeitar nada legítimo.
 *
 *     ESTÁGIO 3 — Filtro de mediana (janela 5):
 *       Cada ponto tem lat/lng substituídos pela mediana dos 5 vizinhos.
 *       Elimina spikes isolados que sobreviveram aos estágios 1 e 2 (ex:
 *       ponto com accuracy boa mas posição errada — acontece em multipath leve).
 *       A mediana ignora outliers em vez de ser puxada por eles (diferença
 *       crucial em relação ao Gaussiano sozinho).
 *
 *     ESTÁGIO 4 — Média ponderada gaussiana (como antes):
 *       Janela 5 para coord, janela 7 para altitude. Suaviza o ruído difuso
 *       remanescente após os estágios anteriores terem removido os spikes.
 *
 *     Em todos os estágios: timestamps, cadência e pace são preservados intactos.
 *     A suavização nunca atravessa fronteiras de segmento (pausa > 3s ou âncora).
 */
object GpxGenerator {

    private const val TAG = "GpxGenerator"

    // ── Parâmetros do pipeline de suavização (FIX D) ──────────────────────────
    //
    // ESTÁGIO 1 — accuracy
    //   25m é o limiar padrão do Android para "sinal degradado".
    //   Abaixo disso o GPS do celular tipicamente está confiável (±5–15m).
    //   Acima, multipath ou cobertura insuficiente de satélites.
    private const val ACCURACY_MAX = 25f
    //
    // ESTÁGIO 2 — velocidade física
    //   12 m/s ≈ 43 km/h. Nenhum corredor humano atinge isso nem em tiro de 50m
    //   (recorde mundial dos 100m = ~10.4 m/s). Threshold conservador.
    private const val VELOCIDADE_MAX_MS = 12.0
    //
    // ESTÁGIO 3 — mediana
    private const val JANELA_MEDIANA = 5
    //
    // ESTÁGIO 4 — gaussiano
    private val PESOS_COORD = intArrayOf(1, 2, 4, 2, 1)   // janela 5
    private val PESOS_ALT   = intArrayOf(1, 2, 3, 4, 3, 2, 1) // janela 7
    //
    // FRONTEIRAS DE SEGMENTO
    private const val GAP_FRONTEIRA_MS = 3_000L

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

        Log.d(TAG, "✅ GPX gerado: ${pontos.size}→${pontosExport.size} pts (pipeline 4 estágios) | ${tempoSegundos}s | '$nomeAtividade'")
        return sb.toString()
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
            total += haversine(a.lat, a.lng, b.lat, b.lng)
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

    // ═══════════════════════════════════════════════════════════════════════════
    // Pipeline de suavização — 4 estágios em sequência
    // ═══════════════════════════════════════════════════════════════════════════

    private fun suavizarPontos(pontos: List<LatLngPonto>): List<LatLngPonto> {
        if (pontos.size < 3) return pontos

        // Os 4 estágios são aplicados em sequência sobre a mesma lista.
        // Timestamps, cadência e pace não são tocados em nenhum estágio.
        val s1 = rejeitarPorAccuracy(pontos)
        val s2 = rejeitarPorVelocidade(s1)
        val s3 = aplicarMediana(s2)
        val s4 = aplicarGaussiano(s3)
        return s4
    }

    // ── Utilitário compartilhado: fronteiras ──────────────────────────────────
    //
    // Uma fronteira separa dois pontos que NÃO devem ser suavizados juntos:
    //   • gap temporal > GAP_FRONTEIRA_MS  → pausa intencional / auto-pause
    //   • lat/lng idêntico ao vizinho      → ponto-âncora do RunningService
    //
    // Retorna array de booleanos onde ehFronteira[i] = true significa que
    // existe uma fronteira APÓS o ponto i (entre i e i+1).

    private fun calcularFronteiras(pontos: List<LatLngPonto>): BooleanArray {
        val f = BooleanArray(pontos.size) { false }
        for (i in 0 until pontos.size - 1) {
            val gapMs  = pontos[i + 1].tempo - pontos[i].tempo
            val ancora = pontos[i].lat == pontos[i + 1].lat &&
                         pontos[i].lng == pontos[i + 1].lng
            if (gapMs > GAP_FRONTEIRA_MS || ancora) f[i] = true
        }
        return f
    }

    private fun dividirEmSegmentos(
        pontos: List<LatLngPonto>,
        fronteiras: BooleanArray
    ): List<IntRange> {
        val segs = mutableListOf<IntRange>()
        var inicio = 0
        for (i in pontos.indices) {
            if (fronteiras[i] || i == pontos.lastIndex) {
                segs.add(inicio..i)
                inicio = i + 1
            }
        }
        return segs
    }

    // ── ESTÁGIO 1: Rejeição por accuracy ──────────────────────────────────────
    //
    // Pontos com accuracy > ACCURACY_MAX são interpolados linearmente no tempo
    // entre o último ponto válido anterior e o próximo ponto válido posterior.
    //
    // "Válido" aqui significa: accuracy <= ACCURACY_MAX OU ponto-âncora.
    // Ponto-âncora nunca é rejeitado — ele representa uma pausa intencional.
    //
    // Se não existe vizinho válido em nenhum dos lados (ex: primeiros/últimos
    // pontos todos ruins), o ponto é mantido como está — melhor que descartar.

    private fun rejeitarPorAccuracy(pontos: List<LatLngPonto>): List<LatLngPonto> {
        val fronteiras = calcularFronteiras(pontos)
        val resultado  = pontos.toMutableList()

        for (i in pontos.indices) {
            val p = pontos[i]

            // Ponto-âncora: preservar sempre
            val ancora = i > 0 && p.lat == pontos[i - 1].lat && p.lng == pontos[i - 1].lng
            if (ancora) continue

            // Accuracy dentro do limite: ok
            if (p.accuracy <= 0f || p.accuracy <= ACCURACY_MAX) continue

            // Ponto ruim — encontrar vizinhos válidos dentro do mesmo segmento
            val limEsq = ultimoValidoAntes(pontos, fronteiras, i)
            val limDir = primeiroValidoDepois(pontos, fronteiras, i)

            resultado[i] = when {
                limEsq != null && limDir != null ->
                    interpolarLinear(pontos[limEsq], pontos[limDir], p.tempo)
                limEsq != null ->
                    p.copy(lat = pontos[limEsq].lat, lng = pontos[limEsq].lng,
                           alt = pontos[limEsq].alt)
                limDir != null ->
                    p.copy(lat = pontos[limDir].lat, lng = pontos[limDir].lng,
                           alt = pontos[limDir].alt)
                else -> p   // nenhum vizinho válido: manter
            }
        }

        Log.d(TAG, "  S1 accuracy: ${pontos.count { it.accuracy > ACCURACY_MAX && it.accuracy > 0f }} pts interpolados")
        return resultado
    }

    /** Índice do último ponto com accuracy boa, antes de [idx], no mesmo segmento. */
    private fun ultimoValidoAntes(
        pontos: List<LatLngPonto>,
        fronteiras: BooleanArray,
        idx: Int
    ): Int? {
        for (j in idx - 1 downTo 0) {
            if (fronteiras[j]) break   // cruzou fronteira: parar
            val p = pontos[j]
            if (p.accuracy <= 0f || p.accuracy <= ACCURACY_MAX) return j
        }
        return null
    }

    /** Índice do próximo ponto com accuracy boa, depois de [idx], no mesmo segmento. */
    private fun primeiroValidoDepois(
        pontos: List<LatLngPonto>,
        fronteiras: BooleanArray,
        idx: Int
    ): Int? {
        for (j in idx + 1..pontos.lastIndex) {
            if (fronteiras[j - 1]) break   // cruzou fronteira: parar
            val p = pontos[j]
            if (p.accuracy <= 0f || p.accuracy <= ACCURACY_MAX) return j
        }
        return null
    }

    /**
     * Interpolação linear no tempo entre dois pontos âncora [a] e [b].
     * O resultado tem o timestamp [tempoAlvo] e lat/lng/alt proporcionais.
     */
    private fun interpolarLinear(
        a: LatLngPonto,
        b: LatLngPonto,
        tempoAlvo: Long
    ): LatLngPonto {
        val span = (b.tempo - a.tempo).toDouble()
        val t    = if (span > 0) (tempoAlvo - a.tempo).toDouble() / span else 0.5
        return a.copy(
            lat   = a.lat + t * (b.lat - a.lat),
            lng   = a.lng + t * (b.lng - a.lng),
            alt   = if (a.alt != 0.0 && b.alt != 0.0) a.alt + t * (b.alt - a.alt) else a.alt,
            tempo = tempoAlvo
        )
    }

    // ── ESTÁGIO 2: Rejeição por velocidade física impossível ──────────────────
    //
    // Calcula a velocidade implícita entre ponto[i] e ponto[i+1] via haversine.
    // Se > VELOCIDADE_MAX_MS, o ponto[i+1] é marcado como inválido e interpolado
    // entre o último ponto bom e o próximo ponto bom (mesma lógica do estágio 1).
    //
    // A varredura é feita da esquerda para a direita, usando o último ponto
    // aceito como referência — assim uma sequência de pontos impossíveis é
    // tratada corretamente (não apenas o primeiro deles).

    private fun rejeitarPorVelocidade(pontos: List<LatLngPonto>): List<LatLngPonto> {
        val fronteiras = calcularFronteiras(pontos)
        val invalido   = BooleanArray(pontos.size) { false }
        var ultimoBom  = 0

        for (i in 1..pontos.lastIndex) {
            if (fronteiras[i - 1]) { ultimoBom = i; continue }  // nova fronteira: resetar

            val ref = pontos[ultimoBom]
            val cur = pontos[i]
            val dtS = ((cur.tempo - ref.tempo) / 1000.0)
            if (dtS <= 0) continue

            val dist = haversine(ref.lat, ref.lng, cur.lat, cur.lng)
            if (dist / dtS > VELOCIDADE_MAX_MS) {
                invalido[i] = true
            } else {
                ultimoBom = i
            }
        }

        val rejeitados = invalido.count { it }
        if (rejeitados == 0) return pontos

        // Interpolar os pontos inválidos
        val resultado = pontos.toMutableList()
        for (i in pontos.indices) {
            if (!invalido[i]) continue
            val limEsq = (i - 1 downTo 0).firstOrNull { !invalido[it] && !fronteiras[it] }
            val limDir = (i + 1..pontos.lastIndex).firstOrNull { !invalido[it] }

            resultado[i] = when {
                limEsq != null && limDir != null ->
                    interpolarLinear(pontos[limEsq], pontos[limDir], pontos[i].tempo)
                limEsq != null ->
                    pontos[i].copy(lat = pontos[limEsq].lat, lng = pontos[limEsq].lng,
                                   alt = pontos[limEsq].alt)
                limDir != null ->
                    pontos[i].copy(lat = pontos[limDir].lat, lng = pontos[limDir].lng,
                                   alt = pontos[limDir].alt)
                else -> pontos[i]
            }
        }

        Log.d(TAG, "  S2 velocidade: $rejeitados pts interpolados (>${VELOCIDADE_MAX_MS} m/s)")
        return resultado
    }

    // ── ESTÁGIO 3: Filtro de mediana (janela deslizante) ─────────────────────
    //
    // Para cada ponto, substitui lat e lng pela mediana dos JANELA_MEDIANA vizinhos
    // centrados nele (borda: janela truncada e renormalizada automaticamente).
    //
    // Mediana vs gaussiana: a mediana IGNORA o outlier; a gaussiana é PUXADA por ele.
    // Com spike isolado de 30m num segmento de 5 pontos [A, A, SPIKE, A, A]:
    //   • Mediana → A  (o spike é o único valor extremo, mediana do conjunto = A)
    //   • Gaussiana → A + 4/10 * SPIKE (ainda distorcida)
    //
    // Altitude: não aplica mediana (já é ruidosa de forma difusa, não por spikes).
    // O gaussiano de janela 7 do estágio 4 cuida dela bem.

    private fun aplicarMediana(pontos: List<LatLngPonto>): List<LatLngPonto> {
        if (pontos.size < JANELA_MEDIANA) return pontos

        val fronteiras = calcularFronteiras(pontos)
        val segmentos  = dividirEmSegmentos(pontos, fronteiras)
        val resultado  = pontos.toMutableList()

        for (seg in segmentos) {
            if (seg.last - seg.first < 2) continue
            val raio = JANELA_MEDIANA / 2
            for (i in seg) {
                val inicio = maxOf(seg.first, i - raio)
                val fim    = minOf(seg.last,  i + raio)
                val lats   = (inicio..fim).map { pontos[it].lat }.sorted()
                val lngs   = (inicio..fim).map { pontos[it].lng }.sorted()
                val meio   = lats.size / 2
                resultado[i] = pontos[i].copy(
                    lat = lats[meio],
                    lng = lngs[meio]
                )
            }
        }

        return resultado
    }

    // ── ESTÁGIO 4: Gaussiano (igual ao original) ──────────────────────────────

    private fun aplicarGaussiano(pontos: List<LatLngPonto>): List<LatLngPonto> {
        if (pontos.size < 3) return pontos

        val fronteiras = calcularFronteiras(pontos)
        val segmentos  = dividirEmSegmentos(pontos, fronteiras)
        val resultado  = pontos.toMutableList()

        for (seg in segmentos) {
            if (seg.last - seg.first < 2) continue
            for (i in seg) {
                resultado[i] = pontos[i].copy(
                    lat = mediaGaussiana(pontos, i, seg, PESOS_COORD) { it.lat },
                    lng = mediaGaussiana(pontos, i, seg, PESOS_COORD) { it.lng },
                    alt = if (pontos[i].alt != 0.0)
                              mediaGaussiana(pontos, i, seg, PESOS_ALT) { it.alt }
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
     * Salva GPX em arquivo.
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
