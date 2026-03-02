package com.runapp.data.repository

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.runapp.BuildConfig
import com.runapp.data.model.CorridaHistorico
import com.runapp.data.model.PassoResumo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Gera o feedback de Coach via Gemini 2.5 Flash.
 *
 * A API Key é injetada em tempo de build via [BuildConfig.GEMINI_API_KEY]:
 *   Local:   export GEMINI_API_KEY=AIza...  (macOS/Linux)
 *            $env:GEMINI_API_KEY="AIza..."  (PowerShell)
 *   GitHub:  Settings → Secrets → Actions → GEMINI_API_KEY
 *
 * O feedback é gerado UMA VEZ e persistido no .json da corrida por
 * [HistoricoRepository.salvarFeedback]. As próximas aberturas do detalhe
 * usam o valor em cache — sem custo de API.
 */
class CoachRepository {

    private val TAG = "CoachRepository"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)   // Gemini 2.5 Flash pode demorar até ~60s
        .build()

    private val gson = Gson()

    private val GEMINI_MODEL = "gemini-2.5-flash"
    private val GEMINI_URL   =
        "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent"

    /**
     * Gera o feedback de Coach para a corrida fornecida.
     *
     * @param corrida Dados completos: splits com GAP, biomecânica, zonas, treino planeado.
     * @return [Result] com o texto do feedback ou falha com mensagem de erro.
     */
    suspend fun gerarFeedback(corrida: CorridaHistorico): Result<String> =
        withContext(Dispatchers.IO) {
            val apiKey = BuildConfig.GEMINI_API_KEY
            if (apiKey.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "GEMINI_API_KEY não configurada. " +
                        "Defina a variável de ambiente antes de buildar " +
                        "ou adicione-a aos Secrets do GitHub Actions."
                    )
                )
            }
            runCatching {
                val body = construirRequest(corrida).toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$GEMINI_URL?key=$apiKey")
                    .post(body)
                    .build()

                Log.d(TAG, "📤 Enviando treino ao Gemini: '${corrida.nome}'")
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    Log.e(TAG, "❌ Gemini HTTP ${response.code}: $responseBody")
                    throw Exception("Erro HTTP ${response.code} da API Gemini")
                }

                extrairTexto(responseBody).also {
                    Log.d(TAG, "✅ Feedback gerado: ${it.length} chars")
                }
            }
        }

    // ── Construção do payload ─────────────────────────────────────────────────

    private fun construirRequest(corrida: CorridaHistorico): String {
        val system = """
            Você é o RunApp Pro Coach, treinador de corrida de elite especializado em fisiologia e biomecânica.
            Suas diretrizes:
            1. Adesão ao Plano: se houver treino planejado, compare pace real vs alvo de cada passo.
            2. Esforço Real (GAP): use o Ritmo Ajustado à Inclinação para avaliar subidas.
            3. Biomecânica: compare passada deste treino com o baseline. Queda >5% = fadiga mecânica.
            4. Zonas: verifique se a distribuição condiz com o objetivo (rodagem →>70% Z1/Z2; tiros →>50% Z4/Z5).
            5. Tom: profissional, encorajador, honesto. Sem rodeios.
        """.trimIndent()

        val prompt = """
            Analise o seguinte treino em exatamente 4 parágrafos curtos (sem títulos):
            1. Avaliação geral (adesão ao plano, se houver).
            2. Esforço real: GAP e elevação — destaque splits relevantes.
            3. Biomecânica: cadência e passada — fadiga ou evolução?
            4. Recomendação prática para o próximo treino.

            ${construirContexto(corrida)}

            Português BR. **Negrito** apenas em métricas numéricas. Máx 200 palavras.
        """.trimIndent()

        return gson.toJson(JsonObject().apply {
            add("system_instruction", JsonObject().apply {
                add("parts", JsonArray().apply {
                    add(JsonObject().apply { addProperty("text", system) })
                })
            })
            add("contents", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("parts", JsonArray().apply {
                        add(JsonObject().apply { addProperty("text", prompt) })
                    })
                })
            })
            add("generationConfig", JsonObject().apply {
                addProperty("temperature", 0.7)
                addProperty("maxOutputTokens", 512)
            })
        })
    }

    private fun construirContexto(c: CorridaHistorico): String = buildString {

        // Treino planeado
        if (c.treinoNome != null) {
            appendLine("TREINO PLANEJADO: ${c.treinoNome}")
            c.treinoPassosJson?.let {
                runCatching {
                    gson.fromJson(it, Array<PassoResumo>::class.java).forEachIndexed { i, p ->
                        appendLine("  Passo ${i+1} — ${p.nome}: ${p.duracaoSegundos/60}min | pace alvo ${p.paceAlvoMin}–${p.paceAlvoMax}/km")
                    }
                }.onFailure { Log.w(TAG, "Falha ao parsear treinoPassosJson", it) }
            }
        } else {
            appendLine("TREINO PLANEJADO: Corrida livre")
        }
        appendLine()

        // Métricas globais
        appendLine("EXECUÇÃO REAL:")
        appendLine("  Distância: ${"%.2f".format(c.distanciaKm)} km")
        appendLine("  Tempo: ${c.tempoFormatado}")
        appendLine("  Pace médio: ${c.paceMedia}/km")
        appendLine("  Cadência média: ${c.cadenciaMedia} SPM")
        appendLine("  Desnível positivo: ${c.ganhoElevacaoM}m")

        // Biomecânica
        if (c.stepLengthBaseline > 0.0) {
            val diff = (c.stepLengthTreino - c.stepLengthBaseline) / c.stepLengthBaseline * 100.0
            appendLine()
            appendLine("BIOMECÂNICA:")
            appendLine("  Passada baseline: ${"%.2f".format(c.stepLengthBaseline)}m/passo")
            appendLine("  Passada neste treino: ${"%.2f".format(c.stepLengthTreino)}m/passo")
            appendLine("  Variação: ${if (diff >= 0) "+" else ""}${"%.1f".format(diff)}%")
        }

        // Distribuição de zonas
        if (c.zonasFronteira.isNotEmpty() && c.splitsParciais.isNotEmpty()) {
            val total = c.splitsParciais.size.toDouble()
            val contagem = mutableMapOf<String, Int>()
            c.splitsParciais.forEach { split ->
                val nome = c.zonasFronteira.firstOrNull { z ->
                    split.paceSegKm >= z.paceMinSegKm &&
                    (z.paceMaxSegKm == null || split.paceSegKm < z.paceMaxSegKm)
                }?.nome ?: "Fora de zona"
                contagem[nome] = (contagem[nome] ?: 0) + 1
            }
            if (contagem.isNotEmpty()) {
                appendLine()
                appendLine("DISTRIBUIÇÃO DE ZONAS:")
                contagem.toSortedMap().forEach { (nome, n) ->
                    appendLine("  $nome: ${"%.0f".format(n / total * 100)}%")
                }
            }
        }

        // Splits por km (max 20 para não explodir tokens)
        if (c.splitsParciais.isNotEmpty()) {
            appendLine()
            appendLine("SPLITS POR KM:")
            c.splitsParciais.take(20).forEach { s ->
                val gap = if (s.gapFormatado != null) {
                    val grade = s.gradienteMedio?.let { " (${"%+.1f".format(it)}%)" } ?: ""
                    " | GAP: ${s.gapFormatado}/km$grade"
                } else ""
                appendLine("  Km ${s.km}: ${s.paceFormatado}/km$gap")
            }
        }
    }

    // ── Extração da resposta Gemini ───────────────────────────────────────────

    private fun extrairTexto(json: String): String {
        val root       = JsonParser.parseString(json).asJsonObject
        val candidates = root.getAsJsonArray("candidates")
            ?: throw Exception("Resposta Gemini sem 'candidates': $json")

        val candidate    = candidates[0].asJsonObject
        val finishReason = candidate.get("finishReason")?.asString

        if (finishReason == "SAFETY" || finishReason == "RECITATION")
            throw Exception("Gemini bloqueou a resposta (finishReason=$finishReason)")

        return candidate
            .getAsJsonObject("content")
            ?.getAsJsonArray("parts")
            ?.get(0)?.asJsonObject
            ?.get("text")?.asString
            ?.trim()
            ?: throw Exception("Não foi possível extrair texto da resposta Gemini")
    }
}
