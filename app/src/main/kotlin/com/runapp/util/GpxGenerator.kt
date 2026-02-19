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
 */
object GpxGenerator {

    private const val TAG = "GpxGenerator"

    fun gerarGpx(
        nomeAtividade: String,
        pontos: List<LatLngPonto>,
        tempoSegundos: Long,
        dataHoraInicio: LocalDateTime = LocalDateTime.now()
    ): String {
        val sb = StringBuilder()

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

        pontos.forEach { ponto ->
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

        Log.d(TAG, "✅ GPX gerado: ${pontos.size} pontos | ${tempoSegundos}s | '$nomeAtividade'")
        return sb.toString()
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
