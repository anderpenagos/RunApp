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
 * Repositório responsável por gerar o feedback de Coach via Gemini 2.5 Flash.
 *
 * A API Key é injetada em tempo de build via [BuildConfig.GEMINI_API_KEY], que lê a
 * variável de ambiente GEMINI_API_KEY:
 *   - Local:   export GEMINI_API_KEY=AIza...  (macOS/Linux)
 *              $env:GEMINI_API_KEY="AIza..."  (PowerShell)
 *   - GitHub:  Settings → Secrets → Actions → GEMINI_API_KEY
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
     * @param corrida Dados completos da corrida (splits com GAP, biomecânica,
     *                zonas e treino planeado, se disponível).
     * @return [Result] com o texto formatado do feedback, ou falha com mensagem de erro.
     */
    suspend fun gerarFeedback(corrida: CorridaHistorico): Result<String> =
        withContext(Dispatchers.IO) {
            val apiKey = BuildConfig.GEMINI_API_KEY
            if (apiKey.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "GEMINI_API_KEY não configurada. " +
                        "Defina a variável de ambiente antes de buildar, " +
                        "ou adicione-a aos Secrets do GitHub Actions."
                    )
                )
            }

            runCatching {
                val requestJson = construirRequest(corrida)
                Log.d(TAG, "📤 Enviando treino ao Gemini: '${corrida.nome}'")

                val body = requestJson.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$GEMINI_URL?key=$apiKey")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    Log.e(TAG, "❌ Gemini HTTP ${response.code}: $responseBody")
                    throw Exception("Erro HTTP ${response.code} da API Gemini: $responseBody")
                }

                val feedback = extrairTexto(responseBody)
                Log.d(TAG, "✅ Feedback gerado com ${feedback.length} caracteres")
                feedback
            }
        }

    // ──────────────────────────────────────────────────────────────────────────
    // Construção do Request JSON para a API Gemini
    // ──────────────────────────────────────────────────────────────────────────

    private fun construirRequest(corrida: CorridaHistorico): String {
        val systemInstruction = """
            Você é o RunApp Pro Coach, um treinador de corrida de elite especializado em fisiologia do exercício e biomecânica.
            Sua missão é analisar os dados técnicos de um treino e fornecer feedback motivador, técnico e honesto.

            Suas diretrizes de análise:
            1. **Adesão ao Plano**: Se houver treino planejado, compare o pace real com o pace alvo de cada passo. Seja específico sobre onde houve desvios.
            2. **Esforço Real (GAP)**: Use o Ritmo Ajustado à Inclinação (GAP) para avaliar subidas. Se o pace caiu mas o GAP se manteve, elogie o controlo de esforço. Se ambos caíram, alerte sobre possível arrancada forte.
            3. **Biomecânica**: Compare a passada deste treino com o baseline histórico do atleta. Queda > 5% indica fadiga mecânica acumulada. Subida indica evolução técnica.
            4. **Zonas de Intensidade**: Verifique se a distribuição de zonas condiz com o objetivo (rodagem → >70% Z1/Z2; tiros → >50% Z4/Z5).
            5. **Tom**: Profissional, encorajador, mas honesto. Use o nome do atleta se disponível no nome da corrida. Seja direto — sem rodeios nem exageros.
        """.trimIndent()

        val dadosTreino = construirContextoDados(corrida)

        val promptFinal = """
            Analise o seguinte treino e escreva um feedback em exatamente 4 parágrafos curtos:

            $dadosTreino

            Estrutura obrigatória (um parágrafo por ponto, sem títulos):
            1. Avaliação geral da execução. Se houver plano, avalie a adesão explicitamente.
            2. Análise de esforço com foco no GAP e elevação. Destaque os splits mais significativos.
            3. Biomecânica: cadência e comprimento de passada. Identifique fadiga ou evolução.
            4. Uma recomendação prática e objetiva para o próximo treino.

            Responda em Português do Brasil. Use **negrito** apenas para métricas numéricas chave (paces, distâncias, percentagens). Máximo 200 palavras no total.
        """.trimIndent()

        val requestObj = JsonObject().apply {
            add("system_instruction", JsonObject().apply {
                add("parts", JsonArray().apply {
                    add(JsonObject().apply { addProperty("text", systemInstruction) })
                })
            })
            add("contents", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("parts", JsonArray().apply {
                        add(JsonObject().apply { addProperty("text", promptFinal) })
                    })
                })
            })
            add("generationConfig", JsonObject().apply {
                addProperty("temperature", 0.7)
                addProperty("maxOutputTokens", 512)
            })
        }

        return gson.toJson(requestObj)
    }

    private fun construirContextoDados(corrida: CorridaHistorico): String {
        val sb = StringBuilder()

        // ── Treino Planeado ───────────────────────────────────────────────────
        if (corrida.treinoNome != null) {
            sb.appendLine("TREINO PLANEJADO: ${corrida.treinoNome}")
            if (corrida.treinoPassosJson != null) {
                runCatching {
                    val passos = gson.fromJson(corrida.treinoPassosJson, Array<PassoResumo>::class.java)
                    passos.forEachIndexed { idx, p ->
                        val duracaoMin = p.duracaoSegundos / 60
                        sb.appendLine("  Passo ${idx + 1} — ${p.nome}: ${duracaoMin}min | Pace alvo: ${p.paceAlvoMin}–${p.paceAlvoMax}/km")
                    }
                }.onFailure {
                    Log.w(TAG, "Não foi possível parsear treinoPassosJson", it)
                }
            }
        } else {
            sb.appendLine("TREINO PLANEJADO: Corrida livre (sem estrutura definida)")
        }
        sb.appendLine()

        // ── Métricas Globais ──────────────────────────────────────────────────
        sb.appendLine("EXECUÇÃO REAL:")
        sb.appendLine("  Distância: ${"%.2f".format(corrida.distanciaKm)} km")
        sb.appendLine("  Tempo total: ${corrida.tempoFormatado}")
        sb.appendLine("  Pace médio: ${corrida.paceMedia}/km")
        sb.appendLine("  Cadência média: ${corrida.cadenciaMedia} SPM")
        sb.appendLine("  Desnível positivo acumulado: ${corrida.ganhoElevacaoM}m")

        // ── Biomecânica ───────────────────────────────────────────────────────
        if (corrida.stepLengthBaseline > 0.0) {
            val diffPct = (corrida.stepLengthTreino - corrida.stepLengthBaseline) /
                          corrida.stepLengthBaseline * 100.0
            val sinal = if (diffPct >= 0) "+" else ""
            sb.appendLine()
            sb.appendLine("BIOMECÂNICA (Auto-Learner):")
            sb.appendLine("  Passada baseline do atleta: ${"%.2f".format(corrida.stepLengthBaseline)}m/passo")
            sb.appendLine("  Passada neste treino: ${"%.2f".format(corrida.stepLengthTreino)}m/passo")
            sb.appendLine("  Variação: $sinal${"%.1f".format(diffPct)}%")
        }

        // ── Distribuição de Zonas ─────────────────────────────────────────────
        if (corrida.zonasFronteira.isNotEmpty() && corrida.splitsParciais.isNotEmpty()) {
            val zonas = calcularDistribuicaoZonas(corrida)
            if (zonas.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("DISTRIBUIÇÃO DE ZONAS (por km completo):")
                zonas.forEach { (nome, pct) ->
                    sb.appendLine("  $nome: ${"%.0f".format(pct)}%")
                }
            }
        }

        // ── Splits com GAP ────────────────────────────────────────────────────
        if (corrida.splitsParciais.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("SPLITS POR KM:")
            // Limita a 20 splits para não exceder o context window do modelo
            corrida.splitsParciais.take(20).forEach { split ->
                val gapPart = if (split.gapSegKm != null && split.gapFormatado != null) {
                    val gradePart = split.gradienteMedio?.let {
                        " (inclinação ${"%.1f".format(it)}%)"
                    } ?: ""
                    " | GAP: ${split.gapFormatado}/km$gradePart"
                } else ""
                sb.appendLine("  Km ${split.km}: ${split.paceFormatado}/km$gapPart")
            }
        }

        return sb.toString()
    }

    /**
     * Calcula a percentagem de tempo (em nº de splits) em cada zona de ritmo.
     * Usa as fronteiras de zonas do perfil Intervals.icu guardadas na corrida.
     */
    private fun calcularDistribuicaoZonas(corrida: CorridaHistorico): Map<String, Double> {
        if (corrida.zonasFronteira.isEmpty() || corrida.splitsParciais.isEmpty()) return emptyMap()

        val contagem = mutableMapOf<String, Int>()
        val total    = corrida.splitsParciais.size

        corrida.splitsParciais.forEach { split ->
            val zona = corrida.zonasFronteira.firstOrNull { z ->
                split.paceSegKm >= z.paceMinSegKm &&
                (z.paceMaxSegKm == null || split.paceSegKm < z.paceMaxSegKm)
            }
            val nomeZona = zona?.nome ?: "Fora de zona"
            contagem[nomeZona] = (contagem[nomeZona] ?: 0) + 1
        }

        return contagem
            .filter { it.value > 0 }
            .mapValues { it.value.toDouble() / total * 100.0 }
            .toSortedMap()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Extração do texto da resposta Gemini
    // ──────────────────────────────────────────────────────────────────────────

    private fun extrairTexto(responseJson: String): String {
        val root = JsonParser.parseString(responseJson).asJsonObject

        val candidates = root.getAsJsonArray("candidates")
            ?: throw Exception("Resposta do Gemini sem 'candidates': $responseJson")

        val candidate    = candidates[0].asJsonObject
        val finishReason = candidate.get("finishReason")?.asString

        if (finishReason == "SAFETY" || finishReason == "RECITATION") {
            throw Exception("Gemini bloqueou a resposta (finishReason=$finishReason)")
        }

        return candidate
            .getAsJsonObject("content")
            ?.getAsJsonArray("parts")
            ?.get(0)?.asJsonObject
            ?.get("text")?.asString
            ?.trim()
            ?: throw Exception("Não foi possível extrair texto da resposta Gemini: $responseJson")
    }
}
