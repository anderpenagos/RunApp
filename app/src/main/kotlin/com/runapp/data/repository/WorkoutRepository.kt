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
            //
            // As porcentagens representam % da VELOCIDADE do threshold (m/s).
            // % MAIOR = velocidade MAIOR = pace MAIS RAPIDO (menos s/m)
            //
            // limitePercent  = teto de velocidade da zona -> pace MAIS RAPIDO (paceMin em s/m)
            // limiteAnterior = piso de velocidade da zona -> pace MAIS LENTO  (paceMax em s/m)
            //
            // Exemplo Z5 (100% - 103.4%):
            //   paceMax = threshold / 1.000 = 4:43/km  (velocidade minima da zona)
            //   paceMin = threshold / 1.034 = 4:34/km  (velocidade maxima da zona)
            // Exemplo Z6 (103.4% - 111.5%):
            //   paceMax = threshold / 1.034 = 4:34/km
            //   paceMin = threshold / 1.115 = 4:14/km
            // Portanto Z5-Z6 = 4:14 - 4:43  (correto!)

            // Pace mais lento da zona (piso de velocidade = limiteAnterior)
            val paceMaxSecsPerMeter = if (limiteAnterior > 0.0) {
                thresholdSecsPerMeter / (limiteAnterior / 100.0)
            } else {
                thresholdSecsPerMeter * 2.0  // Z1: sem limite inferior de velocidade
            }

            // Pace mais rapido da zona (teto de velocidade = limitePercent)
            val paceMinSecsPerMeter = if (limitePercent < 900) {
                thresholdSecsPerMeter / (limitePercent / 100.0)
            } else {
                0.0  // Z7: sem limite superior de velocidade
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
        Log.d(TAG, "Zonas processadas: ${paceZones.size}")

        val doc = evento.workoutDoc ?: return listOf(
            PassoExecucao(
                nome = evento.name,
                duracao = 1800,
                paceAlvoMin = "--:--",
                paceAlvoMax = "--:--",
                zona = 2,
                instrucao = evento.description ?: "Siga seu ritmo confortável"
            )
        )

        // Re-parsear o workout_doc do JSON raw para garantir que campos como
        // "start", "end" e "units" em StepTarget sejam lidos corretamente,
        // independente de como o Gson desserializou o objeto (Unsafe vs construtor).
        val stepsParseados = try {
            val jsonStr = com.google.gson.Gson().toJson(doc)
            Log.d(TAG, "workout_doc JSON: $jsonStr")
            val jsonDoc = com.google.gson.JsonParser.parseString(jsonStr).asJsonObject
            parseSteps(jsonDoc.getAsJsonArray("steps"))
        } catch (e: Exception) {
            Log.w(TAG, "Falha no re-parse do workout_doc, usando steps do Gson: ${e.message}")
            doc.steps
        }

        Log.d(TAG, "Steps no workout: ${stepsParseados.size}")
        return expandirPassos(stepsParseados, paceZones)
    }

    /** Parseia um JsonArray de steps diretamente, sem depender do Gson para StepTarget */
    private fun parseSteps(arr: com.google.gson.JsonArray?): List<WorkoutStep> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val o = el.asJsonObject
            WorkoutStep(
                type     = o.get("type")?.takeIf { !it.isJsonNull }?.asString ?: "SteadyState",
                duration = o.get("duration")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
                pace     = parseStepTarget(o.getAsJsonObject("pace")),
                target   = parseStepTarget(o.getAsJsonObject("power")),
                text     = o.get("text")?.takeIf { !it.isJsonNull }?.asString,
                reps     = o.get("reps")?.takeIf { !it.isJsonNull }?.asInt,
                steps    = parseSteps(o.getAsJsonArray("steps"))
            )
        }
    }

    /** Parseia um JsonObject de pace/power diretamente para StepTarget */
    private fun parseStepTarget(o: com.google.gson.JsonObject?): StepTarget? {
        if (o == null) return null
        val st = StepTarget(
            value  = o.get("value")?.takeIf  { !it.isJsonNull }?.asDouble ?: 0.0,
            value2 = o.get("value2")?.takeIf { !it.isJsonNull }?.asDouble,
            type   = o.get("type")?.takeIf   { !it.isJsonNull }?.asString ?: "pace",
            units  = o.get("units")?.takeIf  { !it.isJsonNull }?.asString,
            start  = o.get("start")?.takeIf  { !it.isJsonNull }?.asDouble,
            end    = o.get("end")?.takeIf    { !it.isJsonNull }?.asDouble
        )
        Log.d(TAG, "parseStepTarget: value=${st.value} units=${st.units} start=${st.start} end=${st.end} isPaceZone=${st.isPaceZone} effectiveValue=${st.effectiveValue} effectiveEnd=${st.effectiveEnd}")
        return st
    }

    private fun expandirPassos(
        steps: List<WorkoutStep>,
        paceZones: List<PaceZone>
    ): List<PassoExecucao> {
        val resultado = mutableListOf<PassoExecucao>()

        for (step in steps) {
            // DETECTA INTERVALO: se tem reps E steps (sub-passos)
            if (step.reps != null && step.reps > 1 && step.steps != null && step.steps.isNotEmpty()) {
                Log.d(TAG, "🔁 Detectado bloco de repetição: ${step.reps}x com ${step.steps.size} sub-passos")
                
                // Expandir cada repetição
                repeat(step.reps) { i ->
                    step.steps.forEach { subPasso ->
                        resultado.add(converterStep(subPasso, paceZones, i + 1, step.reps))
                    }
                }
            } else {
                // Passo comum (aquecimento, desaquecimento, etc)
                resultado.add(converterStep(step, paceZones))
            }
        }

        Log.d(TAG, "✅ Total de passos após expansão: ${resultado.size}")
        return resultado
    }

    private fun converterStep(
        step: WorkoutStep,
        paceZones: List<PaceZone>,
        repAtual: Int? = null,
        repsTotal: Int? = null
    ): PassoExecucao {
        Log.d(TAG, "--- Step: ${step.type} ---")
        Log.d(TAG, "Text: ${step.text}")
        
        val paceTarget = step.pace ?: step.target
        Log.d(TAG, "Target: value=${paceTarget?.value}, start=${paceTarget?.start}, end=${paceTarget?.end}, type=${paceTarget?.type}, units=${paceTarget?.units}")

        val isDescanso = step.type == "Rest" || paceTarget?.isRest == true

        var zona = 2
        var paceMinStr = "--:--"
        var paceMaxStr = "--:--"

        when {
            // CASO 0: Descanso/recuperação — não mostrar pace
            isDescanso -> {
                Log.d(TAG, "✓ Descanso/recuperação")
                zona = 1
                paceMinStr = "--:--"
                paceMaxStr = "--:--"
            }

            // CASO 1: pace_zone (zona única ou range de zonas ex: Z5-Z6)
            paceTarget?.isPaceZone == true -> {
                val zonaInicio = paceTarget.effectiveValue.toInt().coerceAtLeast(1)
                val zonaFim = paceTarget.effectiveEnd?.toInt() ?: zonaInicio
                zona = zonaInicio
                Log.d(TAG, "✓ Detectado pace_zone: Z$zonaInicio-Z$zonaFim (paceZones.size=${paceZones.size})")

                val zonaConfigInicio = paceZones.getOrNull(zonaInicio - 1)
                val zonaConfigFim = paceZones.getOrNull(zonaFim - 1)

                if (zonaConfigInicio != null && zonaConfigFim != null && zonaFim != zonaInicio) {
                    // Range Z5-Z6: paceMin = pace mais rápido (fim da zona maior), paceMax = pace mais lento (início da zona menor)
                    paceMinStr = formatarPace(zonaConfigFim.min)
                    paceMaxStr = formatarPace(zonaConfigInicio.max)
                    Log.d(TAG, "✓ Pace range Z$zonaInicio-Z$zonaFim: $paceMinStr - $paceMaxStr")
                } else if (zonaConfigInicio != null) {
                    paceMinStr = formatarPace(zonaConfigInicio.min)
                    paceMaxStr = formatarPace(zonaConfigInicio.max)
                    Log.d(TAG, "✓ Pace: $paceMinStr - $paceMaxStr")
                } else {
                    Log.w(TAG, "Zona $zonaInicio não encontrada na lista (size=${paceZones.size}), usando fallback")
                    val (min, max) = getPaceFallback(zonaInicio)
                    paceMinStr = min
                    paceMaxStr = max
                }
            }

            // CASO 1b: valor pequeno (1-10) sem units — compatibilidade com formato antigo
            paceTarget != null && paceTarget.effectiveValue in 0.5..10.0 -> {
                zona = paceTarget.effectiveValue.toInt().coerceAtLeast(1)
                Log.d(TAG, "✓ Zona por valor legado: Z$zona")
                val zonaConfig = paceZones.getOrNull(zona - 1)
                if (zonaConfig != null) {
                    paceMinStr = formatarPace(zonaConfig.min)
                    paceMaxStr = formatarPace(zonaConfig.max)
                    Log.d(TAG, "✓ Pace: $paceMinStr - $paceMaxStr")
                } else {
                    val (min, max) = getPaceFallback(zona)
                    paceMinStr = min
                    paceMaxStr = max
                }
            }
            
            // CASO 2: Texto com Z1, Z2, etc
            step.text?.contains(Regex("[zZ](\\d)")) == true -> {
                val regex = Regex("[zZ](\\d)")
                zona = regex.find(step.text!!)?.groupValues?.get(1)?.toIntOrNull() ?: 2
                Log.d(TAG, "✓ Zona do texto: Z$zona")
                
                val zonaConfig = paceZones.getOrNull(zona - 1)
                if (zonaConfig != null) {
                    paceMinStr = formatarPace(zonaConfig.min)
                    paceMaxStr = formatarPace(zonaConfig.max)
                    Log.d(TAG, "✓ Pace: $paceMinStr - $paceMaxStr")
                } else {
                    val (min, max) = getPaceFallback(zona)
                    paceMinStr = min
                    paceMaxStr = max
                }
            }
            
            // CASO 3: Pace absoluto (valor > 10)
            paceTarget != null && paceTarget.effectiveValue > 10.0 -> {
                paceMinStr = formatarPace(paceTarget.effectiveValue)
                paceMaxStr = formatarPace(paceTarget.value2 ?: paceTarget.effectiveValue)
                zona = detectarZonaPorPace(paceTarget.effectiveValue, paceZones)
                Log.d(TAG, "Pace absoluto: $paceMinStr, zona=$zona")
            }
            
            // CASO 4: Sem info, usar Z2 padrão
            else -> {
                Log.w(TAG, "Sem info, usando Z2 padrão")
                val (min, max) = getPaceFallback(2)
                paceMinStr = min
                paceMaxStr = max
                zona = 2
            }
        }

        Log.d(TAG, "→ Final: Z$zona, $paceMinStr-$paceMaxStr")

        val nomePasso = when (step.type) {
            "Warmup" -> "Aquecimento"
            "Cooldown" -> "Desaceleração"
            "Rest" -> if (repAtual != null) "Recuperação $repAtual/$repsTotal" else "Descanso"
            "SteadyState" -> {
                if (isDescanso) {
                    if (repAtual != null) "Recuperação $repAtual/$repsTotal" else "Descanso"
                } else if (repAtual != null) {
                    "Esforço $repAtual/$repsTotal"
                } else {
                    "Ritmo Constante"
                }
            }
            "IntervalsT" -> "Intervalo"
            "Ramp" -> "RAMP (progressivo)"
            else -> step.text ?: step.type
        }

        return PassoExecucao(
            nome = nomePasso,
            duracao = step.duration,
            paceAlvoMin = paceMinStr,
            paceAlvoMax = paceMaxStr,
            zona = zona,
            instrucao = step.text ?: gerarInstrucao(step.type, paceMinStr, paceMaxStr),
            isDescanso = isDescanso
        )
    }

    private fun getPaceFallback(zona: Int): Pair<String, String> {
        return when (zona) {
            1 -> Pair("6:05", "7:36")
            2 -> Pair("5:23", "6:05")
            3 -> Pair("5:03", "5:23")
            4 -> Pair("4:43", "5:03")
            5 -> Pair("4:34", "4:43")
            6 -> Pair("4:14", "4:34")
            7 -> Pair("3:51", "4:14")
            else -> Pair("4:34", "4:43")
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

    private fun gerarInstrucao(tipo: String, paceMin: String, paceMax: String): String {
        return when (tipo) {
            "Warmup" -> "Aquecimento suave de $paceMin a $paceMax."
            "Cooldown" -> "Desacelere gradualmente."
            "Rest" -> "Caminhada ou trote leve."
            "SteadyState" -> "Mantenha pace entre $paceMin e $paceMax."
            "Ramp" -> "Aumente progressivamente o ritmo."
            else -> if (paceMin != "--:--") "Pace: $paceMin a $paceMax/km" else "Siga o ritmo indicado"
        }
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
