package com.runapp.data.repository

import com.runapp.data.api.IntervalsApi
import com.runapp.data.model.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import android.util.Log
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import kotlin.math.abs

class WorkoutRepository(private val api: IntervalsApi) {

    private val TAG = "WorkoutRepo"

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

    suspend fun getTreinoDetalhe(athleteId: String, eventId: Long): Result<WorkoutEvent> {
        return try {
            val evento = api.getEventDetail(athleteId, eventId)
            Result.success(evento)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getZonas(athleteId: String): Result<ZonesResponse> {
        return try {
            val zonas = api.getZones(athleteId)
            Log.d(TAG, "=== ZONAS RECEBIDAS ===")
            Log.d(TAG, "SportSettings: ${zonas.sportSettings.size}")
            
            zonas.sportSettings.forEach { sport ->
                Log.d(TAG, "Sport types: ${sport.types}")
                Log.d(TAG, "Threshold pace: ${sport.thresholdPace} m/s")
                Log.d(TAG, "Pace zones (%%): ${sport.paceZones}")
                Log.d(TAG, "Pace zone names: ${sport.paceZoneNames}")
            }
            
            Result.success(zonas)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao buscar zonas", e)
            Result.failure(e)
        }
    }

    /**
     * Converte porcentagens de zona para PaceZone com valores absolutos
     */
    fun processarZonas(zonesResponse: ZonesResponse): List<PaceZone> {
        // Buscar configuração de corrida
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

        Log.d(TAG, "=== PROCESSANDO ZONAS ===")
        Log.d(TAG, "Threshold: $thresholdPace m/s")
        
        // Converter threshold de m/s para s/m
        val thresholdSecsPerMeter = 1.0 / thresholdPace
        Log.d(TAG, "Threshold: ${formatarPace(thresholdSecsPerMeter)}/km")

        val zonasProcessadas = mutableListOf<PaceZone>()
        
        // Zonas são definidas por limites de porcentagem do threshold pace
        // Ex: [77.5, 87.7, 94.3, 100, 103.4, 111.5, 999]
        // Zone 1: 0% a 77.5% (pace mais lento)
        // Zone 2: 77.5% a 87.7%
        // etc.
        // 
        // IMPORTANTE: % MAIOR = pace MAIS RÁPIDO (menos s/m)
        // Ex: 100% do threshold = threshold pace
        //     77.5% do threshold = pace 29% mais lento que threshold
        
        var limiteAnterior = 0.0
        paceZones.forEachIndexed { index, limitePercent ->
            val nome = paceZoneNames?.getOrNull(index) ?: "Zone ${index + 1}"
            
            // Converter porcentagens para pace em s/m
            // pace(s/m) = thresholdPace(s/m) / (porcentagem / 100)
            
            // Limite superior da zona (pace mais lento = menor porcentagem)
            val paceMaxSecsPerMeter = if (limiteAnterior > 0.0) {
                thresholdSecsPerMeter / (limiteAnterior / 100.0)
            } else {
                // Primeira zona: usar um pace muito lento como limite superior
                thresholdSecsPerMeter * 2.0  // 2x mais lento que threshold
            }
            
            // Limite inferior da zona (pace mais rápido = maior porcentagem)
            val paceMinSecsPerMeter = if (limitePercent < 900) {
                thresholdSecsPerMeter / (limitePercent / 100.0)
            } else {
                // Última zona: pace muito rápido (sem limite inferior)
                0.0
            }

            zonasProcessadas.add(
                PaceZone(
                    id = index + 1,
                    name = nome,
                    min = paceMinSecsPerMeter,
                    max = paceMaxSecsPerMeter,
                    color = null
                )
            )

            Log.d(TAG, "Zona ${index + 1} ($nome): ${formatarPace(paceMinSecsPerMeter)} - ${formatarPace(paceMaxSecsPerMeter)}/km (${limiteAnterior}% - ${limitePercent}%)")
            
            limiteAnterior = limitePercent
        }

        return zonasProcessadas
    }

    fun converterParaPassos(evento: WorkoutEvent, paceZones: List<PaceZone>): List<PassoExecucao> {
        Log.d(TAG, "=== CONVERSÃO DE TREINO ===")
        Log.d(TAG, "Evento: ${evento.name}")

        val rawDoc = evento.workoutDocRaw ?: return listOf(
            PassoExecucao(
                nome = evento.name, duracao = 1800,
                paceAlvoMin = "--:--", paceAlvoMax = "--:--",
                zona = 2, instrucao = evento.description ?: "Siga seu ritmo confortável"
            )
        )

        return try {
            val stepsArray = rawDoc.asJsonObject.getAsJsonArray("steps")
            val steps = stepsArray.map { parseStep(it) }
            Log.d(TAG, "Steps parseados: ${steps.size}")
            expandirPassos(steps, paceZones)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao parsear workout_doc", e)
            listOf(PassoExecucao(
                nome = evento.name, duracao = 1800,
                paceAlvoMin = "--:--", paceAlvoMax = "--:--",
                zona = 2, instrucao = evento.description ?: "Siga seu ritmo confortável"
            ))
        }
    }

    /**
     * Parseia um step do JSON bruto manualmente.
     * Isso garante que campos como "start", "end", "units" sejam lidos
     * corretamente, independente do comportamento do Gson com Kotlin nullable types.
     */
    private fun parseStep(el: com.google.gson.JsonElement): WorkoutStep {
        val obj = el.asJsonObject
        val duration = obj.get("duration")?.asInt ?: 0
        val text     = obj.get("text")?.asString
        val reps     = obj.get("reps")?.asInt
        val isRamp   = obj.get("ramp")?.asBoolean ?: false

        val pace = obj.get("pace")?.asJsonObject?.let { p ->
            StepTarget(
                units = p.get("units")?.asString,
                value = p.get("value")?.asDouble,
                start = p.get("start")?.asDouble,
                end   = p.get("end")?.asDouble
            )
        }

        val subSteps = obj.getAsJsonArray("steps")?.map { parseStep(it) }

        return WorkoutStep(
            duration = duration,
            pace     = pace,
            text     = text,
            reps     = reps,
            steps    = subSteps,
            isRamp   = isRamp
        )
    }

    private fun expandirPassos(
        steps: List<WorkoutStep>,
        paceZones: List<PaceZone>
    ): List<PassoExecucao> {
        val resultado = mutableListOf<PassoExecucao>()

        for (step in steps) {
            if (step.reps != null && step.reps > 1 && !step.steps.isNullOrEmpty()) {
                Log.d(TAG, "🔁 Bloco repetição: ${step.reps}x com ${step.steps.size} sub-passos")
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
        val pace = step.pace
        Log.d(TAG, "Step: units=${pace?.units}, value=${pace?.value}, start=${pace?.start}, end=${pace?.end}, ramp=${step.isRamp}")

        val isDescanso = pace?.isDescanso == true

        var zona = 2
        var paceMinStr = "--:--"
        var paceMaxStr = "--:--"

        when {
            isDescanso -> {
                Log.d(TAG, "✓ Descanso")
                zona = 1
            }

            // Range de zonas: {"start":5,"end":6,"units":"pace_zone"}
            pace != null && pace.zonaStart != null && pace.zonaEnd != null -> {
                val zMin = pace.zonaStart.coerceIn(1, paceZones.size.coerceAtLeast(1))
                val zMax = pace.zonaEnd.coerceIn(1, paceZones.size.coerceAtLeast(1))
                zona = zMax
                Log.d(TAG, "✓ Range Z$zMin-Z$zMax")
                val cfgMin = paceZones.getOrNull(zMin - 1)
                val cfgMax = paceZones.getOrNull(zMax - 1)
                paceMinStr = if (cfgMax != null) formatarPace(cfgMax.min) else getPaceFallback(zMax).first
                paceMaxStr = if (cfgMin != null) formatarPace(cfgMin.max) else getPaceFallback(zMin).second
            }

            // Zona única: {"value":5,"units":"pace_zone"}
            pace != null && pace.zonaUnica != null -> {
                zona = pace.zonaUnica!!.coerceIn(1, paceZones.size.coerceAtLeast(1))
                Log.d(TAG, "✓ Zona única Z$zona")
                val cfg = paceZones.getOrNull(zona - 1)
                if (cfg != null) {
                    paceMinStr = formatarPace(cfg.min)
                    paceMaxStr = formatarPace(cfg.max)
                } else {
                    val (mn, mx) = getPaceFallback(zona); paceMinStr = mn; paceMaxStr = mx
                }
            }

            else -> {
                Log.w(TAG, "Sem info de pace reconhecível")
                val (mn, mx) = getPaceFallback(2); paceMinStr = mn; paceMaxStr = mx
                zona = 2
            }
        }

        Log.d(TAG, "→ Z$zona $paceMinStr-$paceMaxStr")

        val nomePasso = when {
            isDescanso && repAtual != null -> "Recuperação $repAtual/$repsTotal"
            isDescanso                    -> "Descanso"
            step.isRamp                   -> "Aquecimento Progressivo"
            repAtual != null              -> "Esforço $repAtual/$repsTotal"
            else                          -> "Ritmo Constante"
        }

        return PassoExecucao(
            nome        = nomePasso,
            duracao     = step.duration,
            paceAlvoMin = paceMinStr,
            paceAlvoMax = paceMaxStr,
            zona        = zona,
            instrucao   = step.text ?: gerarInstrucao(isDescanso, paceMinStr, paceMaxStr),
            isDescanso  = isDescanso
        )
    }

    private fun getPaceFallback(zona: Int): Pair<String, String> {
        return when (zona) {
            1 -> Pair("6:30", "7:30")
            2 -> Pair("5:30", "6:30")
            3 -> Pair("5:00", "5:30")
            4 -> Pair("4:30", "5:00")
            5 -> Pair("4:00", "4:30")
            else -> Pair("5:30", "6:30")
        }
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

    private fun gerarInstrucao(isDescanso: Boolean, paceMin: String, paceMax: String): String {
        return if (isDescanso) "Caminhada ou trote leve."
        else if (paceMin != "--:--") "Mantenha pace entre $paceMin e $paceMax."
        else "Siga o ritmo indicado."
    }

    /**
     * Gera e salva o arquivo GPX da corrida no armazenamento do app.
     *
     * @param context     contexto Android (necessário para getExternalFilesDir)
     * @param nomeAtividade nome legível da corrida
     * @param rota        lista de pontos GPS gravados durante a corrida
     * @param tempoSegundos duração total da corrida
     * @param dataHora    data/hora de início (ISO 8601), opcional
     * @return Result com o File gerado em caso de sucesso
     */
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
            Log.d(TAG, "Distância: ${"%.2f".format(distanciaMetros / 1000)} km | Pontos GPS: ${rota.size}")

            if (rota.isEmpty()) {
                return Result.failure(Exception("Nenhum dado GPS para salvar"))
            }

            val dataHoraInicio = try {
                java.time.LocalDateTime.parse(dataHora)
            } catch (e: Exception) {
                java.time.LocalDateTime.now()
            }

            // Gera conteúdo GPX
            val gpxContent = com.runapp.util.GpxGenerator.gerarGpx(
                nomeAtividade = nomeAtividade,
                pontos = rota,
                tempoSegundos = tempoSegundos,
                dataHoraInicio = dataHoraInicio
            )

            // Salva em arquivo na pasta pública do app (não precisa de permissão extra)
            val pastaGpx = java.io.File(context.getExternalFilesDir(null), "gpx").also { it.mkdirs() }
            val timestamp = java.time.format.DateTimeFormatter
                .ofPattern("yyyyMMdd_HHmmss")
                .format(dataHoraInicio)
            val arquivo = java.io.File(pastaGpx, "corrida_$timestamp.gpx")
            arquivo.writeText(gpxContent)

            // ── Metadados JSON ────────────────────────────────────────────
            // Salva um .json com o mesmo prefixo de nome do GPX.
            // O HistoricoViewModel lê esses arquivos para montar a lista de corridas.
            val tempoH = tempoSegundos / 3600
            val tempoM = (tempoSegundos % 3600) / 60
            val tempoS = tempoSegundos % 60
            val tempoStr = if (tempoH > 0) "%d:%02d:%02d".format(tempoH, tempoM, tempoS)
                           else "%02d:%02d".format(tempoM, tempoS)

            val meta = CorridaHistorico(
                nome           = nomeAtividade,
                data           = dataHoraInicio.toString(),
                distanciaKm    = distanciaMetros / 1000.0,
                tempoFormatado = tempoStr,
                paceMedia      = paceMedia,
                pontosGps      = rota.size,
                arquivoGpx     = arquivo.name
            )
            val jsonFile = java.io.File(pastaGpx, "corrida_$timestamp.json")
            jsonFile.writeText(Gson().toJson(meta))

            Log.d(TAG, "✅ GPX salvo: ${arquivo.absolutePath}")
            Log.d(TAG, "✅ JSON meta: ${jsonFile.absolutePath}")
            Result.success(arquivo)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao salvar atividade", e)
            Result.failure(e)
        }
    }

    /**
     * Faz upload de um arquivo GPX para o Intervals.icu.
     *
     * O endpoint aceita multipart/form-data com o arquivo no campo "file".
     * A resposta contém o id e o nome da atividade criada no servidor.
     *
     * @param athleteId   ID do atleta no intervals.icu (ex: "i12345")
     * @param arquivo     arquivo .gpx gerado por [salvarAtividade]
     * @return Result com a resposta do servidor
     */
    suspend fun uploadParaIntervals(
        athleteId: String,
        arquivo: java.io.File
    ): Result<ActivityUploadResponse> {
        return try {
            Log.d(TAG, "=== UPLOAD INTERVALS.ICU ===")
            Log.d(TAG, "Arquivo: ${arquivo.name} (${arquivo.length()} bytes)")

            val requestBody = arquivo.asRequestBody("application/gpx+xml".toMediaType())
            val part = okhttp3.MultipartBody.Part.createFormData(
                name = "file",
                filename = arquivo.name,
                body = requestBody
            )

            val resposta = api.uploadActivity(athleteId, part)
            Log.d(TAG, "✅ Upload concluído: id=${resposta.id}, name=${resposta.name}")
            Result.success(resposta)
        } catch (e: Exception) {
            Log.e(TAG, "Erro no upload", e)
            Result.failure(e)
        }
    }

    /**
     * Lê todos os arquivos .json de metadados da pasta gpx/ e retorna
     * a lista de corridas salvas, ordenadas da mais recente para a mais antiga.
     */
    fun listarCorridas(context: android.content.Context): List<CorridaHistorico> {
        val pastaGpx = java.io.File(context.getExternalFilesDir(null), "gpx")
        if (!pastaGpx.exists()) return emptyList()

        return pastaGpx.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { json ->
                runCatching { Gson().fromJson(json.readText(), CorridaHistorico::class.java) }
                    .getOrNull()
            }
            ?.sortedByDescending { it.data }
            ?: emptyList()
    }

    /**
     * Deleta o arquivo GPX e o arquivo JSON de metadados de uma corrida.
     *
     * @return true se ambos foram deletados (ou não existiam)
     */
    fun deletarCorrida(context: android.content.Context, corrida: CorridaHistorico): Boolean {
        val pastaGpx = java.io.File(context.getExternalFilesDir(null), "gpx")
        val gpx  = java.io.File(pastaGpx, corrida.arquivoGpx)
        val json = java.io.File(pastaGpx, corrida.arquivoGpx.replace(".gpx", ".json"))

        val gpxOk  = if (gpx.exists())  gpx.delete()  else true
        val jsonOk = if (json.exists()) json.delete() else true

        Log.d(TAG, "Deletar ${corrida.nome}: gpx=$gpxOk, json=$jsonOk")
        return gpxOk && jsonOk
    }
}
