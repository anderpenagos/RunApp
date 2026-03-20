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

    /**
     * Persiste o feedback do Coach no arquivo .json de metadados da corrida.
     *
     * Chamado uma única vez após o Gemini gerar o feedback com sucesso.
     * Re-lê o JSON, atualiza apenas [feedbackCoach] e re-escreve.
     * Nas próximas aberturas do detalhe o campo já está preenchido — sem custo de API.
     *
     * @return true se guardado com sucesso.
     */
    fun salvarFeedback(arquivoGpx: String, feedback: String): Boolean {
        return runCatching {
            val pasta    = pastaGpx()
            val jsonFile = java.io.File(pasta, arquivoGpx.replace(".gpx", ".json"))
            if (!jsonFile.exists()) return false

            val corrida    = gson.fromJson(jsonFile.readText(), CorridaHistorico::class.java)
            val atualizado = corrida.copy(feedbackCoach = feedback)
            jsonFile.writeText(gson.toJson(atualizado))
            Log.d(TAG, "✅ Feedback do Coach salvo: $arquivoGpx")
            true
        }.onFailure {
            Log.e(TAG, "❌ Erro ao salvar feedback do Coach", it)
        }.getOrDefault(false)
    }
}
