package com.runapp.util

import com.runapp.data.model.LatLngPonto
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Gera arquivo GPX (GPS Exchange Format) a partir dos dados da corrida.
 * 
 * GPX é um formato XML padrão para dados GPS, compatível com a maioria
 * dos aplicativos de corrida e plataformas (Strava, Garmin, Intervals.icu, etc)
 */
object GpxGenerator {

    /**
     * Gera um arquivo GPX completo da corrida
     */
    fun gerarGpx(
        nomeAtividade: String,
        pontos: List<LatLngPonto>,
        tempoSegundos: Long,
        dataHoraInicio: LocalDateTime = LocalDateTime.now()
    ): String {
        val sb = StringBuilder()
        
        // Cabeçalho GPX
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<gpx version="1.1" creator="RunApp" xmlns="http://www.topografix.com/GPX/1/1" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">""")
        
        // Metadata
        sb.appendLine("  <metadata>")
        sb.appendLine("    <name>$nomeAtividade</name>")
        sb.appendLine("    <time>${formatarDataHora(dataHoraInicio)}</time>")
        sb.appendLine("  </metadata>")
        
        // Track
        sb.appendLine("  <trk>")
        sb.appendLine("    <name>$nomeAtividade</name>")
        sb.appendLine("    <type>running</type>")
        sb.appendLine("    <trkseg>")
        
        // Pontos GPS (trackpoints)
        val intervaloSegundos = if (pontos.isNotEmpty()) tempoSegundos.toDouble() / pontos.size else 1.0
        pontos.forEachIndexed { index, ponto ->
            val tempoDecorrido = (index * intervaloSegundos).toLong()
            val timestamp = dataHoraInicio.plusSeconds(tempoDecorrido)
            
            sb.appendLine("      <trkpt lat=\"${ponto.lat}\" lon=\"${ponto.lng}\">")
            sb.appendLine("        <time>${formatarDataHora(timestamp)}</time>")
            sb.appendLine("      </trkpt>")
        }
        
        sb.appendLine("    </trkseg>")
        sb.appendLine("  </trk>")
        sb.appendLine("</gpx>")
        
        return sb.toString()
    }

    /**
     * Salva GPX em arquivo
     */
    fun salvarArquivo(gpxContent: String, caminhoArquivo: File): File {
        caminhoArquivo.writeText(gpxContent)
        return caminhoArquivo
    }

    /**
     * Formata data/hora no formato ISO 8601 (padrão GPX)
     * Exemplo: 2024-05-15T10:30:00Z
     */
    private fun formatarDataHora(dateTime: LocalDateTime): String {
        return dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z"
    }
}
