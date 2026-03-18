package com.runapp.data.repository

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.runapp.BuildConfig
import com.runapp.data.model.CorridaHistorico
import com.runapp.data.model.PassoResumo
import com.runapp.data.model.WellnessSnapshot
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
     * @param corrida  Dados completos: splits com GAP, biomecânica, zonas, treino planeado.
     * @param wellness Snapshot CTL/ATL/TSB do dia da corrida (opcional — vem do Intervals.icu).
     *                 Quando presente, o Coach contextualiza o desempenho com a fadiga acumulada.
     * @return [Result] com o texto do feedback ou falha com mensagem de erro.
     */
    suspend fun gerarFeedback(
        corrida:  CorridaHistorico,
        wellness: WellnessSnapshot? = null
    ): Result<String> =
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
                val body = construirRequest(corrida, wellness).toRequestBody("application/json".toMediaType())
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

    private fun construirRequest(corrida: CorridaHistorico, wellness: WellnessSnapshot?): String {
        val system = """
            Você é um coach de corrida de elite. Sua comunicação é curta, direta e técnica — como um treinador profissional falaria no vestiário, não como um relatório.
            Regras absolutas:
            - NUNCA narre o que aconteceu. O atleta sabe o que fez. Vá direto ao diagnóstico.
            - NUNCA use frases introdutórias como "Este treino foi...", "Observa-se que...", "Em relação a...".
            - Use números reais do treino em TODA afirmação técnica. Afirmação sem número = afirmação inválida.
            - Cada ponto deve ter no máximo 2 linhas. Se precisar de mais, você está narrando — corte.
            - Tom: seco, honesto, encorajador apenas quando os dados justificam.
        """.trimIndent()

        val prompt = """
            Responda com EXATAMENTE esta estrutura (use os títulos em negrito, sem alterar):

            **EXECUÇÃO**
            [1-2 linhas: adesão ao plano ou objetivo do treino. Só números e desvios. Ex: "Pace alvo 5:00/km, executado 5:12/km (+4%). Dentro do aceitável para Z2."]

            **ESFORÇO REAL**
            [1-2 linhas: GAP vs pace, D+, o que a inclinação explica. Se D+=0, diga em 1 linha.]

            **ESTRUTURA / INTERVALOS**
            [1-2 linhas: se houver tiros — consistência e recuperação com números. Se corrida contínua — variação de pace e o que ela indica.]

            **BIOMECÂNICA**
            [1-2 linhas: cadência, passada vs baseline. Queda >5% = alerta de fadiga mecânica. Se sem baseline, diga só o número e se é adequado para o pace.]

            **PRÓXIMO TREINO**
            [1 linha específica e acionável. Ex: "Adicione 10min ao volume. Mantenha Z2." ou "Reduza carga — TSB -18, risco real de overtraining."]

            ${construirContexto(corrida, wellness)}

            Responda em Português BR. Use **negrito** apenas em métricas numéricas que merecem atenção.
            Seja cirúrgico — cada palavra deve justificar sua presença.
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
                addProperty("temperature", 0.45)
                addProperty("maxOutputTokens", 4096)  // margem para treinos longos com muitos splits
                // Desativa os "thinking tokens" do Gemini 2.5 Flash.
                // Por padrão, o modelo usa tokens internos de raciocínio que consomem
                // do mesmo budget de maxOutputTokens — causando truncação na resposta real.
                // Para feedback estruturado e conciso, o thinking é desnecessário.
                add("thinkingConfig", JsonObject().apply {
                    addProperty("thinkingBudget", 0)
                })
            })
        })
    }

    private fun construirContexto(c: CorridaHistorico, wellness: WellnessSnapshot?): String = buildString {

        // ── Condicionamento físico do dia (Intervals.icu) ─────────────────────
        if (wellness != null) {
            val tsbLabel = when {
                wellness.tsb >  10 -> "Muito descansado — forma excelente"
                wellness.tsb >   5 -> "Descansado — boas condições"
                wellness.tsb in -5.0..5.0 -> "Neutro — estado normal"
                wellness.tsb > -10 -> "Levemente fatigado"
                wellness.tsb > -20 -> "Fatigado — performance pode estar limitada"
                else               -> "⚠ Alta fadiga acumulada — risco de overtraining"
            }
            val rampAlert = if ((wellness.rampRate ?: 0.0) > 8.0)
                "  ⚠ Ramp Rate: +${"%.1f".format(wellness.rampRate)} pts/sem — carga semanal acima do recomendado!" else ""

            appendLine("CONDICIONAMENTO FÍSICO (Intervals.icu — dia do treino):")
            appendLine("  CTL (Fitness):  ${"%.1f".format(wellness.ctl)}")
            appendLine("  ATL (Fadiga):   ${"%.1f".format(wellness.atl)}")
            appendLine("  TSB (Forma):    ${"%.1f".format(wellness.tsb)}  → $tsbLabel")
            wellness.rampRate?.let { appendLine("  Ramp Rate:      ${"%.1f".format(it)} pts/semana$rampAlert") }
            appendLine()
        }

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
        appendLine("  Cadência média: ${if (c.cadenciaMedia > 0) "${c.cadenciaMedia} SPM" else "não disponível"}")
        appendLine("  Desnível positivo: ${c.ganhoElevacaoM}m")

        // Biomecânica
        if (c.stepLengthBaseline > 0.0) {
            val diff = (c.stepLengthTreino - c.stepLengthBaseline) / c.stepLengthBaseline * 100.0
            appendLine()
            appendLine("BIOMECÂNICA:")
            appendLine("  Passada baseline histórico: ${"%.2f".format(c.stepLengthBaseline)}m/passo")
            appendLine("  Passada neste treino: ${"%.2f".format(c.stepLengthTreino)}m/passo")
            appendLine("  Variação: ${if (diff >= 0) "+" else ""}${"%.1f".format(diff)}%  ${if (diff < -5) "⚠ Possível fadiga mecânica" else if (diff > 5) "↑ Melhora técnica" else "✓ Normal"}")
        }

        // Intervalos / voltas detectados automaticamente
        if (c.voltasAnalise.isNotEmpty()) {
            val tiros     = c.voltasAnalise.filter { !it.isDescanso }
            val descansos = c.voltasAnalise.filter {  it.isDescanso }
            appendLine()
            appendLine("ANÁLISE DE INTERVALOS (${c.voltasAnalise.size} blocos detectados):")

            if (tiros.isNotEmpty()) {
                val paces   = tiros.map { it.paceSegKm }
                val paceMin = paces.minOrNull() ?: 0.0
                val paceMax = paces.maxOrNull() ?: 0.0
                val paceMed = paces.average()
                val desvio  = kotlin.math.sqrt(paces.map { (it - paceMed).let { d -> d * d } }.average())
                appendLine("  Tiros de esforço: ${tiros.size}")
                appendLine("  Pace tiros — mín: ${fmt(paceMin)} | máx: ${fmt(paceMax)} | média: ${fmt(paceMed)}/km")
                appendLine("  Desvio de consistência: ±${fmt(desvio)}/km ${if (desvio > 30) "(inconsistente)" else "(consistente)"}")
            }
            if (descansos.isNotEmpty()) {
                val paceMedDesc  = descansos.map { it.paceSegKm }.average()
                val tempoMedDesc = descansos.map { it.tempoSegundos }.average().toLong()
                appendLine("  Recuperações: ${descansos.size} | pace médio: ${fmt(paceMedDesc)}/km | tempo médio: ${tempoMedDesc/60}m${tempoMedDesc%60}s")
            }

            appendLine()
            appendLine("  Detalhes por volta:")
            c.voltasAnalise.forEach { v ->
                val tipo = if (v.isDescanso) "DESCANSO" else "ESFORÇO "
                val cadStr = if (v.cadenciaMedia > 0) " | ${v.cadenciaMedia} spm" else ""
                appendLine("    Volta ${v.numero.toString().padStart(2)} [$tipo] — ${"%.2f".format(v.distanciaKm)}km | ${v.tempoSegundos/60}m${(v.tempoSegundos%60).toString().padStart(2,'0')}s | ${v.paceFormatado}/km$cadStr")
            }
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
                    val grade = s.gradienteMedio?.let { " (${"%.1f".format(it)}%)" } ?: ""
                    " | GAP: ${s.gapFormatado}/km$grade"
                } else ""
                appendLine("  Km ${s.km}: ${s.paceFormatado}/km$gap")
            }
        }
    }

    /** Formata pace em seg/km para "M:SS" — uso interno no contexto */
    private fun fmt(segKm: Double): String {
        if (segKm <= 0) return "--:--"
        val s = segKm.toLong()
        return "%d:%02d".format(s / 60, s % 60)
    }


    // ── Extração da resposta Gemini ───────────────────────────────────────────

    private fun extrairTexto(json: String): String {
        val root       = JsonParser.parseString(json).asJsonObject
        val candidates = root.getAsJsonArray("candidates")
            ?: throw Exception("Resposta Gemini sem 'candidates': $json")

        val candidate    = candidates[0].asJsonObject
        val finishReason = candidate.get("finishReason")?.asString

        // SAFETY / RECITATION = bloquear, MAX_TOKENS = texto parcial (aceitar, UI avisa)
        if (finishReason == "SAFETY" || finishReason == "RECITATION")
            throw Exception("Gemini bloqueou a resposta (finishReason=$finishReason)")
        // MAX_TOKENS: texto foi cortado pelo limite de tokens, mas ainda é útil.
        // Retornamos normalmente — a UI detecta que o texto não termina em pontuação.

        return candidate
            .getAsJsonObject("content")
            ?.getAsJsonArray("parts")
            ?.get(0)?.asJsonObject
            ?.get("text")?.asString
            ?.trim()
            ?: throw Exception("Não foi possível extrair texto da resposta Gemini")
    }
}
