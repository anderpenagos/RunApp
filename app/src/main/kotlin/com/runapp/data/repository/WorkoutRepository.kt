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
            Log.d(TAG, "Raw event JSON (primeiros 500 chars): ${body.take(500)}")
            val root = JsonParser.parseString(body).asJsonObject

            // Parsear o WorkoutEvent com Gson normal (campos simples funcionam OK)
            val evento = gson.fromJson(root, WorkoutEvent::class.java)

            // Re-parsear workout_doc.steps diretamente do JSON bruto
            val workoutDocJson = root.getAsJsonObject("workout_doc")
            val stepsParseados = if (workoutDocJson != null) {
                parseStepsFromJson(workoutDocJson.getAsJsonArray("steps"))
            } else emptyList()

            // Substituir o workout_doc com steps corretamente parseados
            val docCorrigido = evento.workoutDoc?.copy(steps = stepsParseados)
                ?: WorkoutDoc(steps = stepsParseados)
            val eventoCorrigido = evento.copy(workoutDoc = docCorrigido)

            Log.d(TAG, "Evento parseado: ${eventoCorrigido.name}, steps: ${stepsParseados.size}")
            Result.success(eventoCorrigido)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao buscar treino detalhe", e)
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
        val st = StepTarget(
            value  = o.get("value")?.takeIf  { !it.isJsonNull }?.asDouble ?: 0.0,
            value2 = o.get("value2")?.takeIf { !it.isJsonNull }?.asDouble,
            type   = o.get("type")?.takeIf   { !it.isJsonNull }?.asString ?: "pace",
            units  = o.get("units")?.takeIf  { !it.isJsonNull }?.asString,
            start  = o.get("start")?.takeIf  { !it.isJsonNull }?.asDouble,
            end    = o.get("end")?.takeIf    { !it.isJsonNull }?.asDouble
        )
        Log.d(TAG, "  StepTarget: value=${st.value} start=${st.start} end=${st.end} units=${st.units} → isPaceZone=${st.isPaceZone} effectiveValue=${st.effectiveValue} effectiveEnd=${st.effectiveEnd}")
        return st
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
                // effectiveEnd: usa end se > 0, senão assume zona única
                val zonaFim = (paceTarget.effectiveEnd?.toInt() ?: zonaInicio).coerceAtLeast(zonaInicio)

                // Cor/identidade = zona mais alta do range
                zona = zonaFim

                Log.d(TAG, "  pace_zone: zonaInicio=$zonaInicio zonaFim=$zonaFim")

                val zonaConfigInicio = paceZones.getOrNull(zonaInicio - 1)
                val zonaConfigFim    = paceZones.getOrNull(zonaFim - 1)

                if (zonaFim > zonaInicio && zonaConfigInicio != null && zonaConfigFim != null) {
                    // Range Z5-Z6: min=pace mais rápido de Z6, max=pace mais lento de Z5
                    paceMinStr = formatarPace(zonaConfigFim.min)
                    paceMaxStr = formatarPace(zonaConfigInicio.max)
                    Log.d(TAG, "  ✓ Range Z$zonaInicio-Z$zonaFim: $paceMinStr – $paceMaxStr")
                } else if (zonaConfigInicio != null) {
                    paceMinStr = formatarPace(zonaConfigInicio.min)
                    paceMaxStr = formatarPace(zonaConfigInicio.max)
                    Log.d(TAG, "  ✓ Zona única Z$zonaInicio: $paceMinStr – $paceMaxStr")
                } else {
                    val (min, max) = getPaceFallback(zonaInicio)
                    paceMinStr = min; paceMaxStr = max
                    Log.w(TAG, "  ⚠ Zona $zonaInicio não encontrada, fallback: $min – $max")
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

    suspend fun salvarAtividade(
        context: android.content.Context,
        athleteId: String,
        nomeAtividade: String,
        distanciaMetros: Double,
        tempoSegundos: Long,
        paceMedia: String,
        rota: List<com.runapp.data.model.LatLngPonto>,
        dataHora: String = java.time.LocalDateTime.now().toString()
    ): Result<java.io.File> {
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

            val tempoH = tempoSegundos / 3600
            val tempoM = (tempoSegundos % 3600) / 60
            val tempoS = tempoSegundos % 60
            val tempoStr = if (tempoH > 0) "%d:%02d:%02d".format(tempoH, tempoM, tempoS)
                           else "%02d:%02d".format(tempoM, tempoS)

            val meta = CorridaHistorico(
                nome = nomeAtividade, data = dataHoraInicio.toString(),
                distanciaKm = distanciaMetros / 1000.0, tempoFormatado = tempoStr,
                paceMedia = paceMedia, pontosGps = rota.size, arquivoGpx = arquivo.name
            )
            val jsonFile = java.io.File(pastaGpx, "corrida_$timestamp.json")
            jsonFile.writeText(Gson().toJson(meta))

            Log.d(TAG, "✅ Salvo: ${arquivo.absolutePath}")
            Result.success(arquivo)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao salvar atividade", e)
            Result.failure(e)
        }
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
