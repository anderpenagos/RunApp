package com.runapp.util

import android.util.Log
import com.runapp.data.model.LatLngPonto
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Lê arquivos GPX gerados pelo GpxGenerator e retorna lista de LatLngPonto
 * com elevação, pace e cadência — necessário para os gráficos do dashboard.
 *
 * Compatível com GPX 1.1 em dois formatos de extensão:
 *  · Novo: <gpxtpx:TrackPointExtension> com <gpxtpx:cad> — padrão Garmin/Strava.
 *  · Antigo: <pace> e <cadence> direto em <extensions> — retrocompatibilidade com
 *    arquivos gerados antes da atualização do GpxGenerator.
 */
object GpxParser {

    private const val TAG = "GpxParser"

    /**
     * Parseia um arquivo GPX e retorna a lista de pontos GPS.
     * Retorna emptyList() em caso de erro — nunca lança exceção para a UI.
     */
    fun parsear(arquivo: File): List<LatLngPonto> {
        return runCatching {
            val doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(arquivo)
            doc.documentElement.normalize()

            val trkpts = doc.getElementsByTagName("trkpt")
            val pontos = mutableListOf<LatLngPonto>()

            for (i in 0 until trkpts.length) {
                val node = trkpts.item(i)
                val attrs = node.attributes

                val lat = attrs.getNamedItem("lat")?.nodeValue?.toDoubleOrNull() ?: continue
                val lng = attrs.getNamedItem("lon")?.nodeValue?.toDoubleOrNull() ?: continue

                var alt = 0.0
                var tempo = 0L
                var pace = 0.0
                var cadencia = 0

                val filhos = node.childNodes
                for (j in 0 until filhos.length) {
                    val filho = filhos.item(j)
                    when (filho.nodeName) {
                        "ele"  -> alt   = filho.textContent.toDoubleOrNull() ?: 0.0
                        "time" -> tempo = parseTimestamp(filho.textContent)
                        "extensions" -> {
                            val exts = filho.childNodes
                            for (k in 0 until exts.length) {
                                val ext = exts.item(k)
                                when (ext.nodeName) {
                                    // ── Formato novo: Garmin TrackPointExtension ──────
                                    // Gerado pelo GpxGenerator após a melhoria B.
                                    // <gpxtpx:cad> = SPM (lido também pelo Strava/Garmin).
                                    // <pace> permanece aqui dentro para o dashboard interno.
                                    "gpxtpx:TrackPointExtension" -> {
                                        val tpExts = ext.childNodes
                                        for (m in 0 until tpExts.length) {
                                            val tpExt = tpExts.item(m)
                                            when (tpExt.nodeName) {
                                                "gpxtpx:cad" -> cadencia = tpExt.textContent.toIntOrNull()    ?: cadencia
                                                "pace"        -> pace     = tpExt.textContent.toDoubleOrNull() ?: pace
                                            }
                                        }
                                    }
                                    // ── Formato antigo: tags diretas em <extensions> ──
                                    // Retrocompatibilidade com arquivos gerados antes
                                    // da atualização do GpxGenerator.
                                    "pace"    -> pace     = ext.textContent.toDoubleOrNull() ?: 0.0
                                    "cadence" -> cadencia = ext.textContent.toIntOrNull()    ?: 0
                                }
                            }
                        }
                    }
                }

                pontos.add(LatLngPonto(
                    lat = lat, lng = lng, alt = alt, tempo = tempo,
                    accuracy = 0f,  // não salvo no GPX — não é necessário para gráficos
                    paceNoPonto = pace,
                    cadenciaNoPonto = cadencia
                ))
            }

            Log.d(TAG, "✅ Parseados ${pontos.size} pontos de ${arquivo.name}")
            pontos
        }.onFailure {
            Log.e(TAG, "❌ Erro ao parsear ${arquivo.name}", it)
        }.getOrDefault(emptyList())
    }

    /**
     * Converte timestamp ISO 8601 ("2024-05-15T10:30:00Z") para milissegundos Unix.
     * Usado para reconstruir o campo `tempo` dos pontos.
     */
    private fun parseTimestamp(iso: String): Long {
        return runCatching {
            java.time.Instant.parse(iso).toEpochMilli()
        }.getOrDefault(0L)
    }
}
