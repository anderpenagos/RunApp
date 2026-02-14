package com.runapp.data.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.runapp.data.model.CorridaHistorico

/**
 * Repositório local para gerenciar o histórico de corridas salvas no dispositivo.
 *
 * Não precisa de API Key — opera apenas em arquivos locais.
 * Cada corrida salva gera dois arquivos em getExternalFilesDir(null)/gpx/:
 *   - corrida_YYYYMMDD_HHmmss.gpx   → dados GPS completos
 *   - corrida_YYYYMMDD_HHmmss.json  → metadados (distância, tempo, pace...)
 *
 * O WorkoutRepository grava esses arquivos; este repositório apenas lê/deleta.
 */
class HistoricoRepository(private val context: Context) {

    private val TAG = "HistoricoRepo"
    private val gson = Gson()

    private fun pastaGpx() = java.io.File(context.getExternalFilesDir(null), "gpx")

    /**
     * Lê todos os .json de metadados e retorna a lista de corridas
     * ordenada da mais recente para a mais antiga.
     */
    fun listarCorridas(): List<CorridaHistorico> {
        val pasta = pastaGpx()
        if (!pasta.exists()) return emptyList()

        return pasta.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { json ->
                runCatching { gson.fromJson(json.readText(), CorridaHistorico::class.java) }
                    .onFailure { Log.w(TAG, "JSON inválido: ${json.name}", it) }
                    .getOrNull()
            }
            ?.sortedByDescending { it.data }
            ?: emptyList()
    }

    /**
     * Deleta o arquivo GPX e o JSON de metadados de uma corrida.
     * @return true se os arquivos foram deletados com sucesso (ou não existiam)
     */
    fun deletarCorrida(corrida: CorridaHistorico): Boolean {
        val pasta = pastaGpx()
        val gpx  = java.io.File(pasta, corrida.arquivoGpx)
        val json = java.io.File(pasta, corrida.arquivoGpx.replace(".gpx", ".json"))

        val gpxOk  = if (gpx.exists())  gpx.delete()  else true
        val jsonOk = if (json.exists()) json.delete() else true

        Log.d(TAG, "Deletar '${corrida.nome}': gpx=$gpxOk, json=$jsonOk")
        return gpxOk && jsonOk
    }

    /**
     * Retorna o File GPX de uma corrida, ou null se não existir.
     */
    fun obterArquivoGpx(corrida: CorridaHistorico): java.io.File? {
        val arquivo = java.io.File(pastaGpx(), corrida.arquivoGpx)
        return if (arquivo.exists()) arquivo else null
    }
}
