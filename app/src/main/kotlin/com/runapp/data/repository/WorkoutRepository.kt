package com.runapp.data.repository

import com.runapp.data.api.IntervalsApi
import com.runapp.data.model.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody

class WorkoutRepository(private val api: IntervalsApi) {

    private val TAG = "WorkoutRepo"
    private val gson = Gson()

    suspend fun getTreinosSemana(athleteId: String): Result<List<WorkoutEvent>> {
        return try {
            val hoje = LocalDate.now()
            val inicioSemana = hoje.minusDays(hoje.dayOfWeek.value.toLong() - 1)
            val fimSemana = inicioSemana.plusDays(6)
            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            val eventos = api.getEvents(
                athleteId = athleteId,
                oldest = inicioSemana.format(fmt),
                newest = fimSemana.format(fmt)
            ).filter { it.type == "Run" }
            Result.success(eventos)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getProximosTreinos(athleteId: String): Result<List<WorkoutEvent>> {
        return try {
            val hoje = LocalDate.now()
            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            val eventos = api.getEvents(
                athleteId = athleteId,
                oldest = hoje.format(fmt),
                newest = hoje.plusDays(7).format(fmt)
            ).filter { it.type == "Run" }
            Result.success(eventos)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Busca o evento como JSON bruto e parseia manualmente.
     * Isso garante que campos como "start", "end", "units" em StepTarget
     * sejam lidos corretamente, sem depender do comportamento do Gson
     * com data classes Kotlin (que usa Unsafe e ignora defaults/anotações).
     */
    suspend fun getTreinoDetalhe(athleteId: String, eventId: Long): Result<WorkoutEvent> {
        return try {
            val body = api.getEventDetailRaw(athleteId, eventId).string()

            // CORREÇÃO: Tratar se a resposta é um Array [ { ... } ] ou um Objeto { ... }
            val jsonElement = JsonParser.parseString(body)
            val root = if (jsonElement.isJsonArray) {
                jsonElement.asJsonArray.get(0).asJsonObject
            } else {
                jsonElement.asJsonObject
            }

            // Parsear o WorkoutEvent com Gson normal
            val evento = gson.fromJson(root, WorkoutEvent::class.java)

            // Re-parsear workout_doc.steps diretamente do JSON bruto
            val workoutDocJson = root.getAsJsonObject("workout_doc")
            val stepsParseados = if (workoutDocJson != null && workoutDocJson.has("steps")) {
                parseStepsFromJson(workoutDocJson.getAsJsonArray("steps"))
            } else emptyList()

            // Montar o evento corrigido com a árvore de steps completa
            val docCorrigido = (evento.workoutDoc ?: WorkoutDoc()).copy(steps = stepsParseados)
            val eventoCorrigido = evento.copy(workoutDoc = docCorrigido)

            Log.d(TAG, "✅ Treino '${eventoCorrigido.name}' processado com ${stepsParseados.size} steps.")
            Result.success(eventoCorrigido)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erro ao buscar treino detalhe", e)
            Result.failure(e)
        }
    }

    suspend fun getZonas(athleteId: String): Result<ZonesResponse> {
        return try {
            val zonas = api.getZones(athleteId)
            Log.d(TAG, "Zonas recebidas: ${zonas.sportSettings.size} sport settings")
            Result.success(zonas)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao buscar zonas", e)
            Result.failure(e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Parse direto do JSON — sem Gson para StepTarget
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseStepsFromJson(arr: JsonArray?): List<WorkoutStep> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val o = el.asJsonObject
            WorkoutStep(
                type     = o.get("type")?.takeIf { !it.isJsonNull }?.asString ?: "SteadyState",
                duration = o.get("duration")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
                pace     = parseStepTargetFromJson(o.getAsJsonObject("pace")),
                target   = parseStepTargetFromJson(o.getAsJsonObject("power")),
                text     = o.get("text")?.takeIf { !it.isJsonNull }?.asString,
                reps     = o.get("reps")?.takeIf { !it.isJsonNull }?.asInt,
                steps    = parseStepsFromJson(o.getAsJsonArray("steps"))
            )
        }
    }

    private fun parseStepTargetFromJson(o: JsonObject?): StepTarget? {
        if (o == null) return null
        return try {
            val st = StepTarget(
                value  = o.get("value")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0,
                value2 = o.get("value2")?.takeIf { !it.isJsonNull }?.asDouble,
                type   = o.get("type")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: "pace",
                units  = o.get("units")?.takeIf { !it.isJsonNull }?.asString?.trim(),
                start  = o.get("start")?.takeIf { !it.isJsonNull }?.asDouble,
                end    = o.get("end")?.takeIf { !it.isJsonNull }?.asDouble
            )
            // Se no Logcat aparecer 'end=6.0', o range vai funcionar.
            Log.d(TAG, "  DEBUG PARSER: start=${st.start} end=${st.end} effEnd=${st.effectiveEnd}")
            st
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao parsear StepTarget", e)
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Processamento de Zonas
    // ─────────────────────────────────────────────────────────────────────────

    fun processarZonas(zonesResponse: ZonesResponse): List<PaceZone> {
        val runningSetting = zonesResponse.sportSettings.firstOrNull { sport ->
            sport.types.any { it in listOf("Run", "VirtualRun", "TrailRun") }
        }
        if (runningSetting == null) {
            Log.w(TAG, "Configuração de corrida não encontrada")
            return emptyList()
        }

        val thresholdPace = runningSetting.thresholdPace
        val paceZones = runningSetting.paceZones
        val paceZoneNames = runningSetting.paceZoneNames

        if (thresholdPace == null || paceZones == null) {
            Log.w(TAG, "Threshold ou zonas não configuradas")
            return emptyList()
        }

        // threshold em s/m
        val thresholdSecsPerMeter = 1.0 / thresholdPace

        val zonasProcessadas = mutableListOf<PaceZone>()
        var limiteAnterior = 0.0

        paceZones.forEachIndexed { index, limitePercent ->
            val nome = paceZoneNames?.getOrNull(index) ?: "Zone ${index + 1}"

            // % maior = velocidade maior = pace mais RÁPIDO (menos s/m)
            // limiteAnterior = piso de velocidade → pace MAIS LENTO (paceMax)
            // limitePercent  = teto de velocidade → pace MAIS RÁPIDO (paceMin)
            val paceMaxSecsPerMeter = if (limiteAnterior > 0.0)
                thresholdSecsPerMeter / (limiteAnterior / 100.0)
            else
                thresholdSecsPerMeter * 2.0

            val paceMinSecsPerMeter = if (limitePercent < 900)
                thresholdSecsPerMeter / (limitePercent / 100.0)
            else
                0.0

            zonasProcessadas.add(PaceZone(
                id    = index + 1,
                name  = nome,
                min   = paceMinSecsPerMeter,
                max   = paceMaxSecsPerMeter,
                color = null
            ))

            Log.d(TAG, "Z${index + 1} ($nome): ${formatarPace(paceMinSecsPerMeter)} – ${formatarPace(paceMaxSecsPerMeter)}/km  (${limiteAnterior}% – ${limitePercent}%)")
            limiteAnterior = limitePercent
        }

        return zonasProcessadas
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Conversão de treino → passos de execução
    // ─────────────────────────────────────────────────────────────────────────

    fun converterParaPassos(evento: WorkoutEvent, paceZones: List<PaceZone>): List<PassoExecucao> {
        Log.d(TAG, "=== CONVERSÃO DE TREINO ===")
        Log.d(TAG, "Evento: ${evento.name}, steps: ${evento.workoutDoc?.steps?.size}")

        val doc = evento.workoutDoc ?: return listOf(
            PassoExecucao(
                nome = evento.name, duracao = 1800,
                paceAlvoMin = "--:--", paceAlvoMax = "--:--",
                zona = 2, instrucao = evento.description ?: "Siga seu ritmo confortável"
            )
        )

        return expandirPassos(doc.steps, paceZones)
    }

    private fun expandirPassos(steps: List<WorkoutStep>, paceZones: List<PaceZone>): List<PassoExecucao> {
        val resultado = mutableListOf<PassoExecucao>()
        for (step in steps) {
            if (step.reps != null && step.reps > 1 && !step.steps.isNullOrEmpty()) {
                Log.d(TAG, "🔁 Bloco de repetição: ${step.reps}x com ${step.steps.size} sub-passos")
                repeat(step.reps) { i ->
                    step.steps.forEach { subPasso ->
                        resultado.add(converterStep(subPasso, paceZones, i + 1, step.reps))
                    }
                }
            } else {
                resultado.add(converterStep(step, paceZones))
            }
        }
        Log.d(TAG, "✅ Total de passos: ${resultado.size}")
        return resultado
    }

    private fun converterStep(
        step: WorkoutStep,
        paceZones: List<PaceZone>,
        repAtual: Int? = null,
        repsTotal: Int? = null
    ): PassoExecucao {
        val paceTarget = step.pace ?: step.target
        Log.d(TAG, "converterStep → type=${step.type} pace={value=${paceTarget?.value}, start=${paceTarget?.start}, end=${paceTarget?.end}, units=${paceTarget?.units}}")

        val isDescanso = step.type == "Rest" || paceTarget?.isRest == true

        var zona = 2
        var paceMinStr = "--:--"
        var paceMaxStr = "--:--"

        when {
            isDescanso -> {
                zona = 1
            }

            paceTarget?.isPaceZone == true -> {
                val zonaInicio = paceTarget.effectiveValue.toInt().coerceAtLeast(1)
                val zonaFim = (paceTarget.effectiveEnd?.toInt() ?: zonaInicio).coerceAtLeast(zonaInicio)
                // Define a identidade visual pela zona mais alta
                zona = zonaFim
                val zonaConfigInicio = paceZones.getOrNull(zonaInicio - 1)
                val zonaConfigFim    = paceZones.getOrNull(zonaFim - 1)
                if (zonaFim > zonaInicio && zonaConfigInicio != null && zonaConfigFim != null) {
                    // Caso tenha as configurações do servidor e seja um range
                    paceMinStr = formatarPace(zonaConfigFim.min)
                    paceMaxStr = formatarPace(zonaConfigInicio.max)
                } else if (zonaConfigInicio != null && zonaFim == zonaInicio) {
                    // Caso tenha as configurações do servidor e seja zona única
                    paceMinStr = formatarPace(zonaConfigInicio.min)
                    paceMaxStr = formatarPace(zonaConfigInicio.max)
                } else {
                    // --- CORREÇÃO: FALLBACK PARA RANGE ---
                    val fallbackInicio = getPaceFallback(zonaInicio)
                    val fallbackFim = getPaceFallback(zonaFim)

                    if (zonaFim > zonaInicio) {
                        // Se for Z5-Z6 no fallback: pega o min da Z6 e o max da Z5
                        paceMinStr = fallbackFim.first    // "4:13" (Z6)
                        paceMaxStr = fallbackInicio.second // "4:43" (Z5)
                        Log.d(TAG, "  ⚠ Usando Fallback de RANGE Z$zonaInicio-Z$zonaFim: $paceMinStr – $paceMaxStr")
                    } else {
                        paceMinStr = fallbackInicio.first
                        paceMaxStr = fallbackInicio.second
                        Log.w(TAG, "  ⚠ Usando Fallback de ZONA ÚNICA Z$zonaInicio: $paceMinStr – $paceMaxStr")
                    }
                }
            }

            paceTarget != null && paceTarget.effectiveValue in 0.5..10.0 -> {
                zona = paceTarget.effectiveValue.toInt().coerceAtLeast(1)
                val zonaConfig = paceZones.getOrNull(zona - 1)
                if (zonaConfig != null) {
                    paceMinStr = formatarPace(zonaConfig.min)
                    paceMaxStr = formatarPace(zonaConfig.max)
                } else {
                    val (min, max) = getPaceFallback(zona); paceMinStr = min; paceMaxStr = max
                }
            }

            step.text?.contains(Regex("[zZ](\\d)")) == true -> {
                zona = Regex("[zZ](\\d)").find(step.text!!)?.groupValues?.get(1)?.toIntOrNull() ?: 2
                val zonaConfig = paceZones.getOrNull(zona - 1)
                if (zonaConfig != null) {
                    paceMinStr = formatarPace(zonaConfig.min)
                    paceMaxStr = formatarPace(zonaConfig.max)
                } else {
                    val (min, max) = getPaceFallback(zona); paceMinStr = min; paceMaxStr = max
                }
            }

            paceTarget != null && paceTarget.effectiveValue > 10.0 -> {
                paceMinStr = formatarPace(paceTarget.effectiveValue)
                paceMaxStr = formatarPace(paceTarget.value2 ?: paceTarget.effectiveValue)
                zona = detectarZonaPorPace(paceTarget.effectiveValue, paceZones)
            }

            else -> {
                val (min, max) = getPaceFallback(2); paceMinStr = min; paceMaxStr = max; zona = 2
            }
        }

        Log.d(TAG, "  → Final: Z$zona, $paceMinStr – $paceMaxStr")

        val nomePasso = when {
            step.type == "Warmup"   -> "Aquecimento"
            step.type == "Cooldown" -> "Desaceleração"
            isDescanso              -> if (repAtual != null) "Recuperação $repAtual/$repsTotal" else "Descanso"
            repAtual != null        -> "Esforço $repAtual/$repsTotal"
            step.type == "Ramp"     -> "RAMP (progressivo)"
            else                    -> step.text ?: "Ritmo Constante"
        }

        return PassoExecucao(
            nome        = nomePasso,
            duracao     = step.duration,
            paceAlvoMin = paceMinStr,
            paceAlvoMax = paceMaxStr,
            zona        = zona,
            instrucao   = step.text ?: gerarInstrucao(step.type, paceMinStr, paceMaxStr),
            isDescanso  = isDescanso
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun getPaceFallback(zona: Int): Pair<String, String> = when (zona) {
        1    -> Pair("6:05", "7:36")
        2    -> Pair("5:22", "6:05")
        3    -> Pair("5:00", "5:22")
        4    -> Pair("4:43", "5:00")
        5    -> Pair("4:33", "4:43")
        6    -> Pair("4:13", "4:33")
        7    -> Pair("3:51", "4:13")
        else -> Pair("4:33", "4:43")
    }

    fun formatarPace(secsPerMeter: Double?): String {
        if (secsPerMeter == null || secsPerMeter <= 0) return "--:--"
        val secsPerKm = secsPerMeter * 1000
        val min = (secsPerKm / 60).toInt()
        val seg = (secsPerKm % 60).toInt()
        return "%d:%02d".format(min, seg)
    }

    private fun detectarZonaPorPace(secsPerMeter: Double?, paceZones: List<PaceZone>): Int {
        if (secsPerMeter == null || paceZones.isEmpty()) return 2
        for ((index, zone) in paceZones.withIndex()) {
            val min = zone.min ?: continue
            val max = zone.max ?: continue
            if (secsPerMeter in min..max) return index + 1
        }
        return 2
    }

    private fun gerarInstrucao(tipo: String, paceMin: String, paceMax: String): String = when (tipo) {
        "Warmup"      -> "Aquecimento suave de $paceMin a $paceMax."
        "Cooldown"    -> "Desacelere gradualmente."
        "Rest"        -> "Caminhada ou trote leve."
        "SteadyState" -> "Mantenha pace entre $paceMin e $paceMax."
        "Ramp"        -> "Aumente progressivamente o ritmo."
        else          -> if (paceMin != "--:--") "Pace: $paceMin a $paceMax/km" else "Siga o ritmo indicado"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Salvar / Upload / Histórico
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Salva a atividade em disco (GPX + JSON) e computa métricas avançadas (D+, splits, cadência).
     *
     * ⚠️ THREAD — FIX C: Esta função executa I/O de arquivo e loops sobre até 7200 pontos GPS.
     * SEMPRE chame a partir de um contexto de IO no ViewModel:
     *
     *   viewModelScope.launch(Dispatchers.IO) { repository.salvarAtividade(...) }
     *
     * Fazer isso na Main thread causa jank visível na animação de "Salvando..." e pode
     * acionar o ANR watchdog em corridas longas (> 2h).
     */
    /** Resultado de [salvarAtividade]: arquivo GPX + distancia oficial (suavizada).
     *  A ViewModel usa distanciaFinalMetros para popular a ResumoScreen imediatamente,
     *  sem re-buscar do banco, evitando o "Efeito Deception" (app dizia 5.00km,
     *  historico mostra 4.91km). */
    data class SalvarAtividadeResult(
        val arquivo: java.io.File,
        val distanciaFinalMetros: Double
    )

    suspend fun salvarAtividade(
        context: android.content.Context,
        athleteId: String,
        nomeAtividade: String,
        distanciaMetros: Double,
        tempoSegundos: Long,
        paceMedia: String,
        rota: List<com.runapp.data.model.LatLngPonto>,
        dataHora: String = java.time.LocalDateTime.now().toString(),
        paceZones: List<PaceZone> = emptyList(),
        // ── Coach: capturados no momento do save ────────────────────────────
        stepLengthBaseline: Double = 0.0,   // EMA do Auto-Learner no início da corrida
        treinoNome: String? = null,          // nome do WorkoutEvent, se havia plano
        treinoPassosJson: String? = null     // JSON de List<PassoResumo>, se havia plano
    ): Result<SalvarAtividadeResult> {
        return try {
            Log.d(TAG, "=== SALVANDO ATIVIDADE ===")
            if (rota.isEmpty()) return Result.failure(Exception("Nenhum dado GPS para salvar"))

            val dataHoraInicio = try { java.time.LocalDateTime.parse(dataHora) }
                                 catch (e: Exception) { java.time.LocalDateTime.now() }

            val gpxContent = com.runapp.util.GpxGenerator.gerarGpx(
                nomeAtividade = nomeAtividade,
                pontos = rota,
                tempoSegundos = tempoSegundos,
                dataHoraInicio = dataHoraInicio
            )

            val pastaGpx = java.io.File(context.getExternalFilesDir(null), "gpx").also { it.mkdirs() }
            val timestamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(dataHoraInicio)
            val arquivo = java.io.File(pastaGpx, "corrida_$timestamp.gpx")
            arquivo.writeText(gpxContent)

            // Distancia oficial = calculada sobre os pontos suavizados, para que
            // o resumo da corrida bata com o que Intervals.icu e Strava mostram.
            // O coerceAtLeast(95%) e uma seguranca contra edge cases (ex: corrida
            // de 5 pontos onde o smoothing nao se aplica).
            val distanciaFinal = com.runapp.util.GpxGenerator
                .calcularDistanciaSmoothed(rota)
                .coerceAtLeast(distanciaMetros * 0.95)
            Log.d(TAG, "Distancia: bruta=${distanciaMetros.toInt()}m | smooth=${distanciaFinal.toInt()}m")

            val tempoH = tempoSegundos / 3600
            val tempoM = (tempoSegundos % 3600) / 60
            val tempoS = tempoSegundos % 60
            val tempoStr = if (tempoH > 0) "%d:%02d:%02d".format(tempoH, tempoM, tempoS)
                           else "%02d:%02d".format(tempoM, tempoS)

            // ── Métricas avançadas para o dashboard ────────────────────────
            val ganhoElevacao = calcularGanhoElevacao(rota)
            val cadenciaMedia = calcularCadenciaMedia(rota)
            val splits = calcularSplits(rota)
            val voltas = calcularVoltasAnalise(rota)

            // Converte PaceZone (seg/metro) → ZonaFronteira (seg/km) para persistir
            // os limites reais do perfil do atleta junto com a corrida.
            val zonasFronteira = paceZones.map { z ->
                com.runapp.data.model.ZonaFronteira(
                    nome         = z.name,
                    cor          = z.color ?: "",
                    paceMinSegKm = (z.min ?: 0.0) * 1000.0,
                    paceMaxSegKm = z.max?.let { it * 1000.0 }
                )
            }

            // Comprimento de passada neste treino: distância / total de passos
            // Passos totais ≈ cadência (passos/min) × tempo (min)
            val stepLengthTreino = if (cadenciaMedia > 0 && tempoSegundos > 0)
                distanciaFinal / (cadenciaMedia * tempoSegundos / 60.0)
            else 0.0

            val meta = com.runapp.data.model.CorridaHistorico(
                nome = nomeAtividade, data = dataHoraInicio.toString(),
                distanciaKm = distanciaFinal / 1000.0, tempoFormatado = tempoStr,
                paceMedia = paceMedia, pontosGps = rota.size, arquivoGpx = arquivo.name,
                ganhoElevacaoM = ganhoElevacao,
                cadenciaMedia = cadenciaMedia,
                splitsParciais = splits,
                zonasFronteira = zonasFronteira,
                voltasAnalise = voltas,
                stepLengthBaseline = stepLengthBaseline,
                stepLengthTreino = stepLengthTreino,
                treinoNome = treinoNome,
                treinoPassosJson = treinoPassosJson,
                feedbackCoach = null   // gerado sob demanda na primeira abertura do detalhe
            )
            val jsonFile = java.io.File(pastaGpx, "corrida_$timestamp.json")
            jsonFile.writeText(Gson().toJson(meta))

            Log.d(TAG, "✅ Salvo: ${arquivo.absolutePath} | D+=${ganhoElevacao}m | SPM=$cadenciaMedia | ${splits.size} splits | ${voltas.size} voltas")
            Result.success(SalvarAtividadeResult(arquivo, distanciaFinal))
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao salvar atividade", e)
            Result.failure(e)
        }
    }

    /** D+ acumulado: soma apenas os ganhos positivos de altitude (filtro passa-alta ~0.5m) */
    private fun calcularGanhoElevacao(rota: List<com.runapp.data.model.LatLngPonto>): Int {
        if (rota.size < 2) return 0
        // Suaviza altitude com média móvel de 5 pontos antes de calcular D+
        // Elimina o ruído de GPS que causaria "serrote" no gráfico de elevação
        val altsSuavizadas = rota.mapIndexed { i, _ ->
            val inicio = maxOf(0, i - 2)
            val fim = minOf(rota.lastIndex, i + 2)
            rota.subList(inicio, fim + 1).map { it.alt }.average()
        }

        // FIX A: Ignorar ganho de elevação até o GPS estabilizar (primeiros 10m).
        // Nos primeiros segundos, a altitude reportada pode oscilar vários metros,
        // gerando D+ falso antes de qualquer subida real.
        var distAcumulada = 0.0
        val DISTANCIA_ESTABILIZACAO_M = 10.0

        var ganho = 0.0
        for (i in 1 until altsSuavizadas.size) {
            distAcumulada += haversine(
                rota[i - 1].lat, rota[i - 1].lng,
                rota[i].lat,     rota[i].lng
            )
            if (distAcumulada < DISTANCIA_ESTABILIZACAO_M) continue  // aguarda GPS estabilizar

            val delta = altsSuavizadas[i] - altsSuavizadas[i - 1]
            if (delta > 0.5) ganho += delta  // ignora ruídos menores que 0.5m
        }
        return ganho.toInt()
    }

    /** SPM médio: média dos pontos com cadência > 0 (exclui paradas e início sem sensor) */
    private fun calcularCadenciaMedia(rota: List<com.runapp.data.model.LatLngPonto>): Int {
        val pontosComCadencia = rota.filter { it.cadenciaNoPonto > 0 }
        if (pontosComCadencia.isEmpty()) return 0
        return pontosComCadencia.map { it.cadenciaNoPonto }.average().toInt()
    }

    /** Pace por km completo — percorre a rota acumulando distância e marca cada km fechado */
    /**
     * Pace por km completo + GAP (Grade-Adjusted Pace).
     *
     * GAP responde: "que pace equivalente em terreno plano exigiria o mesmo esforço?"
     * Fórmula baseada em Minetti et al. (2002) / aproximação Strava:
     *   gapFactor = 1 + gradePct × coeficiente
     *   gapPace   = realPace / gapFactor
     * Coeficientes assimétricos: subida = 0.0358, descida = 0.0138
     * (descida recupera menos do que a subida exige, fisiologicamente)
     */
    private fun calcularSplits(rota: List<com.runapp.data.model.LatLngPonto>): List<com.runapp.data.model.SplitParcial> {
        if (rota.size < 2) return emptyList()
        val splits = mutableListOf<com.runapp.data.model.SplitParcial>()
        var distAcumulada = 0.0
        var kmAtual = 1
        var indexInicioKm = 0

        for (i in 1 until rota.size) {
            val p1 = rota[i - 1]; val p2 = rota[i]
            distAcumulada += haversine(p1.lat, p1.lng, p2.lat, p2.lng)

            if (distAcumulada >= kmAtual * 1000.0) {
                val tempoKm = (rota[i].tempo - rota[indexInicioKm].tempo) / 1000.0 // segundos
                // FIX B: Protege contra timestamps idênticos (sensor travado) ou valores
                // absurdamente baixos (< 10s para percorrer 1 km = fisicamente impossível).
                val paceSegKm = if (tempoKm > 10.0) tempoKm else 600.0

                // ── GAP ───────────────────────────────────────────────────────────
                val altInicio = rota[indexInicioKm].alt
                val altFim    = rota[i].alt
                // Só calcula GAP se ao menos um ponto tem altitude real (não zero padrão)
                val temAltitude = altInicio != 0.0 || altFim != 0.0

                val gradientePct = if (temAltitude)
                    (altFim - altInicio) / 1000.0 * 100.0   // ΔAlt(m)/1000m × 100 = %
                else 0.0

                val gapFactor = when {
                    !temAltitude       -> 1.0
                    gradientePct >= 0  -> 1.0 + gradientePct * 0.0358   // subida
                    else               -> 1.0 + gradientePct * 0.0138   // descida
                }
                val gapSegKm = if (temAltitude) paceSegKm / gapFactor else null

                fun fmt(s: Double) = "%d:%02d".format((s / 60).toInt(), (s % 60).toInt())

                splits.add(com.runapp.data.model.SplitParcial(
                    km             = kmAtual,
                    paceSegKm      = paceSegKm,
                    paceFormatado  = fmt(paceSegKm),
                    gapSegKm       = gapSegKm,
                    gapFormatado   = gapSegKm?.let { fmt(it) },
                    gradienteMedio = if (temAltitude) gradientePct else null
                ))
                indexInicioKm = i
                kmAtual++
            }
        }
        return splits
    }

    /**
     * Detecta automaticamente laps/intervalos a partir de variações de pace na rota GPS.
     *
     * Algoritmo robusto para treinos estruturados (tiros + recuperação):
     *
     *  1. SUAVIZAÇÃO DE LONGO PRAZO (janela 90s): elimina picos de GPS e ruídos
     *     instantâneos. Uma janela de 30s é insuficiente para treinos intervalados
     *     com blocos de 3-10 minutos — causa dezenas de micro-transições falsas.
     *
     *  2. DISPERSÃO: verifica se o treino é estruturado (ratio p75/p25 ≥ 1.20).
     *     Corridas uniformes retornam vazio (splits por km já cobrem esse caso).
     *
     *  3. CLASSIFICAÇÃO com histerese dupla:
     *     Em vez de um único limiar fixo, usa dois limiares (30% e 70% do range)
     *     para evitar que pontos na "zona cinzenta" fiquem alternando entre estados.
     *     Um ponto só muda de estado se ultrapassar o limiar oposto por pelo menos
     *     DEBOUNCE_MS milissegundos consecutivos.
     *
     *  4. DEBOUNCE TEMPORAL (45s): um bloco só é considerado uma nova fase se durar
     *     pelo menos 45 segundos consecutivos no novo estado. Blocos menores são
     *     absorvidos pelo estado anterior (tratados como ruído ou transição).
     *
     *  5. FUSÃO DE SEGMENTOS ADJACENTES: blocos do mesmo tipo separados por um bloco
     *     oposto muito curto (< 20s) são fundidos em um único segmento, evitando
     *     artefatos como "tiro de 5s" no meio de uma recuperação.
     *
     *  6. FILTRO FINAL: segmentos com < 80m OU < 30s são descartados como ruído GPS.
     *
     * @return lista de VoltaAnalise; vazia se a corrida for uniforme.
     */
    private fun calcularVoltasAnalise(
        rota: List<com.runapp.data.model.LatLngPonto>
    ): List<com.runapp.data.model.VoltaAnalise> {
        if (rota.size < 60) return emptyList()

        // ── 1. SUAVIZAÇÃO: janela deslizante de 90 segundos ─────────────────────
        // Calcula o pace real de cada janela por distância/tempo acumulados,
        // ignorando pontos com pace inválido (paradas, GPS perdido).
        val JANELA_MS = 90_000L
        val rollingPace = DoubleArray(rota.size) { rota[it].paceNoPonto.coerceIn(60.0, 1500.0) }
        for (i in rota.indices) {
            val tInicio = rota[i].tempo - JANELA_MS
            val jInicio = (0..i).firstOrNull { rota[it].tempo >= tInicio } ?: 0
            val seg = rota.subList(jInicio, i + 1)
            if (seg.size < 5) continue
            val dist = seg.zipWithNext().sumOf { (a, b) -> haversine(a.lat, a.lng, b.lat, b.lng) }
            val time = (seg.last().tempo - seg.first().tempo) / 1000.0
            if (dist > 20 && time > 20) {
                rollingPace[i] = (time / dist * 1000).coerceIn(60.0, 1500.0)
            }
        }

        // ── 2. DISPERSÃO: verifica se o treino é estruturado ────────────────────
        val validos = rollingPace.filter { it in 60.0..1200.0 }.sorted()
        if (validos.size < 60) return emptyList()
        val p25 = validos[(validos.size * 0.25).toInt()]
        val p75 = validos[(validos.size * 0.75).toInt()]
        // Exige variação de pelo menos 20% entre quartis para ser "estruturado"
        if (p25 < 1.0 || p75 / p25 < 1.20) return emptyList()

        // ── 3. CLASSIFICAÇÃO COM HISTERESE DUPLA ────────────────────────────────
        // limiarBaixo = 30% do range → entra em "esforço" apenas se pace < limiarBaixo
        // limiarAlto  = 70% do range → entra em "descanso" apenas se pace > limiarAlto
        // Zona cinzenta [limiarBaixo, limiarAlto]: mantém estado anterior (histerese)
        val limiarBaixo = p25 + (p75 - p25) * 0.35   // 35% do range = zona de esforço
        val limiarAlto  = p25 + (p75 - p25) * 0.65   // 65% do range = zona de descanso
        // isFast[i]: true = esforço, false = descanso/recuperação
        val isFast = BooleanArray(rota.size) { rollingPace[it] < limiarBaixo }
        // Aplicar histerese: zona cinzenta herda estado anterior
        for (i in 1 until isFast.size) {
            val p = rollingPace[i]
            isFast[i] = when {
                p < limiarBaixo -> true   // claramente esforço
                p > limiarAlto  -> false  // claramente descanso
                else            -> isFast[i - 1]  // zona cinzenta → herda
            }
        }

        // ── 4. DEBOUNCE TEMPORAL: 45 segundos mínimos por bloco ─────────────────
        // Usa timestamps reais para o debounce, não contagem de pontos.
        // Evita que variações de frequência GPS (1Hz vs 2Hz) afetem o resultado.
        val DEBOUNCE_MS = 45_000L
        val transicoes = mutableListOf(0)  // índices de início de cada bloco
        var estadoAtual = isFast[0]
        var candidatoInicio = -1
        var candidatoEstado = estadoAtual

        for (i in 1 until isFast.size) {
            val novoEstado = isFast[i]
            if (novoEstado != estadoAtual) {
                // Início de possível transição
                if (candidatoInicio < 0 || candidatoEstado != novoEstado) {
                    candidatoInicio = i
                    candidatoEstado = novoEstado
                }
                // Verifica se o candidato durou tempo suficiente
                val duracaoMs = rota[i].tempo - rota[candidatoInicio].tempo
                if (duracaoMs >= DEBOUNCE_MS) {
                    // Transição confirmada — registra no ponto em que começou
                    transicoes.add(candidatoInicio)
                    estadoAtual = novoEstado
                    candidatoInicio = -1
                }
            } else {
                // Voltou ao estado atual — cancela candidato de transição
                candidatoInicio = -1
            }
        }
        transicoes.add(rota.lastIndex)

        // ── 5. FUSÃO DE SEGMENTOS ADJACENTES ────────────────────────────────────
        // Segmento oposto muito curto (< 20s) entre dois segmentos do mesmo tipo
        // → fundidos em um único bloco (ex: descanso de 5s no meio de um tiro)
        val FUSAO_MS = 20_000L
        data class Bloco(val inicio: Int, val fim: Int, val fast: Boolean)
        val blocos = mutableListOf<Bloco>()
        for (t in 0 until transicoes.size - 1) {
            val ini = transicoes[t]; val fim = transicoes[t + 1]
            blocos.add(Bloco(ini, fim, isFast[ini]))
        }
        var fundiu = true
        while (fundiu) {
            fundiu = false
            val novo = mutableListOf<Bloco>()
            var b = 0
            while (b < blocos.size) {
                val atual = blocos[b]
                if (b + 2 < blocos.size) {
                    val proximo = blocos[b + 2]
                    val medio   = blocos[b + 1]
                    val duracaoMedio = rota[medio.fim].tempo - rota[medio.inicio].tempo
                    if (atual.fast == proximo.fast && duracaoMedio < FUSAO_MS) {
                        novo.add(Bloco(atual.inicio, proximo.fim, atual.fast))
                        b += 3; fundiu = true; continue
                    }
                }
                novo.add(atual); b++
            }
            if (fundiu) {
                blocos.clear()
                blocos.addAll(novo)
            }
        }

        // ── 6. CONSTRUIR VoltaAnalise + FILTRO FINAL ─────────────────────────────
        val voltas = mutableListOf<com.runapp.data.model.VoltaAnalise>()
        for (bloco in blocos) {
            val startIdx = bloco.inicio
            val endIdx   = bloco.fim.coerceAtMost(rota.lastIndex)
            if (endIdx <= startIdx) continue

            val seg   = rota.subList(startIdx, endIdx + 1)
            val dist  = seg.zipWithNext().sumOf { (a, b) -> haversine(a.lat, a.lng, b.lat, b.lng) }
            val tempo = (seg.last().tempo - seg.first().tempo) / 1000L

            // Filtro final: descarta segmentos muito curtos (ruído GPS ou transição)
            if (dist < 80 || tempo < 30) continue

            val pace = if (dist > 0) (tempo.toDouble() / dist * 1000).coerceIn(60.0, 1200.0) else 600.0

            voltas.add(
                com.runapp.data.model.VoltaAnalise(
                    numero        = voltas.size + 1,
                    distanciaKm   = dist / 1000.0,
                    tempoSegundos = tempo,
                    paceSegKm     = pace,
                    paceFormatado = "%d:%02d".format((pace / 60).toInt(), (pace % 60).toInt()),
                    isDescanso    = !bloco.fast
                )
            )
        }

        // Exige mínimo de 3 blocos para considerar a corrida estruturada
        return if (voltas.size >= 3) voltas else emptyList()
    }

    private fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2).let { it * it } +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2).let { it * it }
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    suspend fun uploadParaIntervals(athleteId: String, arquivo: java.io.File): Result<ActivityUploadResponse> {
        return try {
            val requestBody = arquivo.asRequestBody("application/gpx+xml".toMediaType())
            val part = okhttp3.MultipartBody.Part.createFormData("file", arquivo.name, requestBody)
            val resposta = api.uploadActivity(athleteId, part)
            Log.d(TAG, "✅ Upload: id=${resposta.id}")
            Result.success(resposta)
        } catch (e: Exception) {
            Log.e(TAG, "Erro no upload", e)
            Result.failure(e)
        }
    }

    fun listarCorridas(context: android.content.Context): List<CorridaHistorico> {
        val pastaGpx = java.io.File(context.getExternalFilesDir(null), "gpx")
        if (!pastaGpx.exists()) return emptyList()
        return pastaGpx.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { json -> runCatching { Gson().fromJson(json.readText(), CorridaHistorico::class.java) }.getOrNull() }
            ?.sortedByDescending { it.data }
            ?: emptyList()
    }

    fun deletarCorrida(context: android.content.Context, corrida: CorridaHistorico): Boolean {
        val pastaGpx = java.io.File(context.getExternalFilesDir(null), "gpx")
        val gpx  = java.io.File(pastaGpx, corrida.arquivoGpx)
        val json = java.io.File(pastaGpx, corrida.arquivoGpx.replace(".gpx", ".json"))
        return (if (gpx.exists()) gpx.delete() else true) && (if (json.exists()) json.delete() else true)
    }
}
