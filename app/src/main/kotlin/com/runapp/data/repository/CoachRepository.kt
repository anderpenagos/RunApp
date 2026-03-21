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
            - Para treinos estruturados: o mapeamento passo→volta já está calculado. Use-o para diagnosticar desvios, não para descrever o que o atleta pode ver na tabela.
            - Para corridas livres: foque em progressão de ritmo, consistência e o que o GAP revela sobre o esforço real.
        """.trimIndent()

        val prompt = """
            Responda com EXATAMENTE esta estrutura (use os títulos em negrito, sem alterar):

            **EXECUÇÃO**
            [1-2 linhas: para treino estruturado — adesão geral ao plano com os desvios mais relevantes. Para corrida livre — ritmo executado vs objetivo esperado para o tipo de treino.]

            **ESFORÇO REAL**
            [1-2 linhas: GAP vs pace, D+, o que a inclinação explica. Se D+=0, diga em 1 linha.]

            **ESTRUTURA / INTERVALOS**
            [Para treino estruturado: consistência entre repetições e qualidade das recuperações com números. Para corrida livre: variação de pace ao longo da corrida e o que ela indica — não descreva os splits, interprete-os.]

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
                wellness.tsb >  15 -> "Ótima forma — pronto para competir"
                wellness.tsb >   5 -> "Descansado — boas condições de treino"
                wellness.tsb in -5.0..5.0 -> "Neutro — carga e recuperação equilibradas"
                wellness.tsb > -10 -> "Levemente fatigado — treino acumulando"
                wellness.tsb > -20 -> "Fatigado — performance provavelmente limitada"
                else               -> "⚠ Alta fadiga acumulada — risco real de overtraining"
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

        // ── Métricas globais (comuns a todos os tipos de treino) ──────────────
        appendLine("MÉTRICAS GERAIS:")
        appendLine("  Distância: ${"%.2f".format(c.distanciaKm)} km  |  Tempo: ${c.tempoFormatado}  |  Pace médio: ${c.paceMedia}/km")
        appendLine("  Cadência média: ${if (c.cadenciaMedia > 0) "${c.cadenciaMedia} SPM" else "não disponível"}  |  D+: ${c.ganhoElevacaoM}m")

        // GAP médio geral (quando há splits com GAP)
        val gapSplits = c.splitsParciais.filter { it.gapSegKm != null && it.gapSegKm > 0 }
        if (gapSplits.isNotEmpty()) {
            val gapMedioSeg = gapSplits.map { it.gapSegKm!! }.average()
            appendLine("  GAP médio: ${fmt(gapMedioSeg)}/km  (ritmo equivalente em terreno plano)")
        }

        // Biomecânica
        if (c.stepLengthBaseline > 0.0) {
            val diff = (c.stepLengthTreino - c.stepLengthBaseline) / c.stepLengthBaseline * 100.0
            appendLine()
            appendLine("BIOMECÂNICA:")
            appendLine("  Passada baseline: ${"%.2f".format(c.stepLengthBaseline)}m  |  Neste treino: ${"%.2f".format(c.stepLengthTreino)}m  |  Variação: ${if (diff >= 0) "+" else ""}${"%.1f".format(diff)}%  ${if (diff < -5) "⚠ Possível fadiga mecânica" else if (diff > 5) "↑ Melhora técnica" else "✓ Normal"}")
        }
        appendLine()

        // ── Fluxo TREINO ESTRUTURADO ──────────────────────────────────────────
        val passos = c.treinoPassosJson?.let {
            runCatching { gson.fromJson(it, Array<PassoResumo>::class.java).toList() }
                .onFailure { Log.w(TAG, "Falha ao parsear treinoPassosJson", it) }
                .getOrNull()
        }

        if (passos != null && c.voltasAnalise.isNotEmpty()) {
            appendLine("TREINO PLANEJADO: ${c.treinoNome ?: "Treino estruturado"}")
            passos.forEachIndexed { i, p ->
                val tipo = when {
                    i == 0 -> "AQUECIMENTO"
                    i == passos.lastIndex -> "DESAQUECIMENTO"
                    p.isDescanso -> "RECUPERAÇÃO"
                    else -> "ESFORÇO Z${p.zona}"
                }
                appendLine("  Passo ${i+1} [$tipo] ${p.nome}: ${p.duracaoSegundos/60}min | alvo ${p.paceAlvoMin}–${p.paceAlvoMax}/km")
            }
            appendLine()

            // Identifica índices dos passos de esforço real (exclui aquecimento e desaquecimento)
            val indicesEsforco = passos.indices.filter { i ->
                i != 0 && i != passos.lastIndex && !passos[i].isDescanso
            }

            // Mapeia cada passo planejado à volta executada correspondente por índice
            appendLine("PLANO vs EXECUÇÃO (passo a passo):")
            val voltas = c.voltasAnalise
            passos.forEachIndexed { i, passo ->
                val volta = voltas.getOrNull(i)
                val tipo = when {
                    i == 0 -> "AQUECIMENTO"
                    i == passos.lastIndex -> "DESAQUECIMENTO"
                    passo.isDescanso -> "RECUPERAÇÃO"
                    else -> "ESFORÇO Z${passo.zona}"
                }
                if (volta == null) {
                    appendLine("  Passo ${i+1} [$tipo] → não executado")
                    return@forEachIndexed
                }

                val alvoMinSeg = paceParaSegundos(passo.paceAlvoMin).toDouble()
                val alvoMaxSeg = paceParaSegundos(passo.paceAlvoMax).toDouble()
                val executadoSeg = volta.paceSegKm

                // Aquecimento e desaquecimento têm tolerância maior (±30s) — são transições
                val tolerancia = if (i == 0 || i == passos.lastIndex) 30 else 10
                val status = when {
                    alvoMinSeg <= 0 -> "sem alvo definido"
                    executadoSeg < alvoMinSeg - tolerancia -> "ACIMA DO ALVO (+${fmt(alvoMinSeg - executadoSeg)} mais rápido)"
                    executadoSeg > alvoMaxSeg + tolerancia -> "ABAIXO DO ALVO (+${fmt(executadoSeg - alvoMaxSeg)} mais lento)"
                    else -> "dentro do alvo ✓"
                }

                val cadStr = if (volta.cadenciaMedia > 0) " | ${volta.cadenciaMedia} spm" else ""
                appendLine("  Passo ${i+1} [$tipo] → Volta ${volta.numero}: ${volta.paceFormatado}/km | ${"%.2f".format(volta.distanciaKm)}km | ${volta.tempoSegundos/60}m${(volta.tempoSegundos%60).toString().padStart(2,'0')}s$cadStr")
                appendLine("    Alvo: ${passo.paceAlvoMin}–${passo.paceAlvoMax}/km → $status")
            }
            // Voltas extras que não têm passo correspondente
            if (voltas.size > passos.size) {
                appendLine()
                appendLine("  Voltas além do plano (${voltas.size - passos.size} extra):")
                voltas.drop(passos.size).forEach { v ->
                    val tipo = if (v.isDescanso) "RECUPERAÇÃO" else "ESFORÇO"
                    appendLine("    Volta ${v.numero} [$tipo] → ${v.paceFormatado}/km | ${"%.2f".format(v.distanciaKm)}km")
                }
            }

            // Consistência dos tiros de esforço (exclui aquecimento e desaquecimento)
            val tirosEsforco = indicesEsforco.mapNotNull { i -> voltas.getOrNull(i) }
            if (tirosEsforco.size >= 2) {
                appendLine()
                val pacesTiros = tirosEsforco.map { it.paceSegKm }
                val media = pacesTiros.average()
                val desvio = kotlin.math.sqrt(pacesTiros.map { (it - media).let { d -> d * d } }.average())
                val primeiro = pacesTiros.first()
                val ultimo   = pacesTiros.last()
                val deriva   = ultimo - primeiro
                appendLine("CONSISTÊNCIA DOS TIROS (${tirosEsforco.size} esforços — aquec/desaq excluídos):")
                appendLine("  Pace médio: ${fmt(media)}/km  |  Desvio: ±${fmt(desvio)}/km  |  ${if (desvio > 30) "inconsistente" else "consistente"}")
                appendLine("  Deriva: 1º tiro ${fmt(primeiro)}/km → último ${fmt(ultimo)}/km  |  ${if (deriva > 20) "⚠ afundou ${fmt(deriva)}" else if (deriva < -20) "↑ acelerou ${fmt(-deriva)}" else "estável ✓"}")
                // Cadência por tiro — sinal de fadiga mecânica se cair no final
                val cadTiros = tirosEsforco.filter { it.cadenciaMedia > 0 }
                if (cadTiros.size >= 2) {
                    val cadInicio = cadTiros.first().cadenciaMedia
                    val cadFim = cadTiros.last().cadenciaMedia
                    val quedaCad = cadInicio - cadFim
                    appendLine("  Cadência: 1º tiro ${cadInicio} spm → último ${cadFim} spm  |  ${if (quedaCad > 5) "⚠ queda de ${quedaCad} spm — possível fadiga mecânica" else "estável ✓"}")
                }
            }

            // Qualidade das recuperações (passos isDescanso entre os tiros)
            val indicesRecuperacao = passos.indices.filter { i ->
                i != 0 && i != passos.lastIndex && passos[i].isDescanso
            }
            val recuperacoes = indicesRecuperacao.mapNotNull { i -> voltas.getOrNull(i) }
            if (recuperacoes.isNotEmpty() && c.zonasFronteira.isNotEmpty()) {
                appendLine()
                appendLine("QUALIDADE DAS RECUPERAÇÕES (${recuperacoes.size} blocos):")
                recuperacoes.forEach { r ->
                    val zonaReal = c.zonasFronteira.firstOrNull { z ->
                        r.paceSegKm >= z.paceMinSegKm && (z.paceMaxSegKm == null || r.paceSegKm < z.paceMaxSegKm)
                    }?.nome ?: "fora de zona"
                    appendLine("  Volta ${r.numero}: ${r.paceFormatado}/km → $zonaReal")
                }
            }

        } else {
            // ── Fluxo CORRIDA LIVRE ───────────────────────────────────────────
            appendLine("TIPO: Corrida livre${if (c.treinoNome != null) " (${c.treinoNome})" else ""}")
            appendLine()

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
                appendLine("DISTRIBUIÇÃO DE ZONAS:")
                contagem.toSortedMap().forEach { (nome, n) ->
                    appendLine("  $nome: ${"%.0f".format(n / total * 100)}%")
                }
                appendLine()
            }

            // Progressão do pace: terço inicial vs terço final
            val splits = c.splitsParciais.filter { it.paceSegKm in 60.0..1200.0 }
            if (splits.size >= 3) {
                val terco = (splits.size / 3).coerceAtLeast(1)
                val paceInicio = splits.take(terco).map { it.paceSegKm }.average()
                val paceFim    = splits.takeLast(terco).map { it.paceSegKm }.average()
                val deriva     = paceFim - paceInicio
                val tendencia  = when {
                    deriva >  30 -> "⚠ afundou ${fmt(deriva)}/km no final — possível fade de ritmo ou desidratação"
                    deriva < -30 -> "↑ negativo split — acelerou ${fmt(-deriva)}/km no final"
                    else         -> "ritmo estável ✓ (variação ${fmt(kotlin.math.abs(deriva))})"
                }
                appendLine("PROGRESSÃO DE PACE:")
                appendLine("  1º terço: ${fmt(paceInicio)}/km  |  Último terço: ${fmt(paceFim)}/km  |  $tendencia")
                appendLine()
            }

            // Consistência dos splits (desvio padrão)
            if (splits.size >= 2) {
                val media  = splits.map { it.paceSegKm }.average()
                val desvio = kotlin.math.sqrt(splits.map { (it.paceSegKm - media).let { d -> d * d } }.average())
                appendLine("CONSISTÊNCIA DE RITMO:")
                appendLine("  Desvio padrão dos splits: ±${fmt(desvio)}/km  |  ${if (desvio > 45) "ritmo muito irregular" else if (desvio > 20) "alguma variação — verificar terreno ou esforço" else "consistente ✓"}")
                appendLine()
            }

            // Splits por km com GAP (max 20)
            if (c.splitsParciais.isNotEmpty()) {
                appendLine("SPLITS POR KM:")
                c.splitsParciais.take(20).forEach { s ->
                    val gap = if (s.gapFormatado != null) {
                        val grade = s.gradienteMedio?.let { " (${if (it >= 0) "+" else ""}${"%.1f".format(it)}%)" } ?: ""
                        " | GAP ${s.gapFormatado}/km$grade"
                    } else ""
                    appendLine("  Km ${s.km}: ${s.paceFormatado}/km$gap")
                }
            }
        }
    }

    /** Formata pace em seg/km para "M:SS" — uso interno no contexto */
    private fun fmt(segKm: Double): String {
        if (segKm <= 0) return "--:--"
        val s = segKm.toLong()
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** Converte "M:SS" para segundos/km — uso interno no contexto */
    private fun paceParaSegundos(pace: String): Int {
        if (pace == "--:--") return 0
        val partes = pace.split(":")
        if (partes.size != 2) return 0
        return (partes[0].toIntOrNull() ?: 0) * 60 + (partes[1].toIntOrNull() ?: 0)
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
