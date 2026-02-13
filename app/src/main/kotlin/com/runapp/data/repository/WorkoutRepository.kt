package com.runapp.data.repository

import com.runapp.data.api.IntervalsApi
import com.runapp.data.model.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import android.util.Log
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
        
        Log.d(TAG, "Steps no workout: ${doc.steps.size}")
        return expandirPassos(doc.steps, paceZones)
    }

    private fun expandirPassos(
        steps: List<WorkoutStep>,
        paceZones: List<PaceZone>
    ): List<PassoExecucao> {
        val resultado = mutableListOf<PassoExecucao>()

        for (step in steps) {
            if (step.type == "IntervalsT" && step.steps != null) {
                val reps = step.reps ?: 1
                repeat(reps) { i ->
                    step.steps.forEach { sub ->
                        resultado.add(converterStep(sub, paceZones, i + 1, reps))
                    }
                }
            } else {
                resultado.add(converterStep(step, paceZones))
            }
        }

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
        Log.d(TAG, "Target: value=${paceTarget?.value}, type=${paceTarget?.type}")
        
        val isDescanso = step.type == "Rest"

        var zona = 2
        var paceMinStr = "--:--"
        var paceMaxStr = "--:--"

        when {
            // CASO 1: type="zone" ou valor pequeno (1-10)
            (paceTarget?.type == "zone" || (paceTarget != null && paceTarget.value in 0.5..10.0)) -> {
                zona = if (paceZones.isNotEmpty()) {
                    paceTarget.value.toInt().coerceIn(1, paceZones.size)
                } else {
                    paceTarget.value.toInt().coerceIn(1, 5)  // Fallback para zonas 1-5
                }
                Log.d(TAG, "✓ Detectado zona: Z$zona")
                
                val zonaConfig = paceZones.getOrNull(zona - 1)
                if (zonaConfig != null) {
                    paceMinStr = formatarPace(zonaConfig.min)
                    paceMaxStr = formatarPace(zonaConfig.max)
                    Log.d(TAG, "✓ Pace: $paceMinStr - $paceMaxStr")
                } else {
                    Log.w(TAG, "Zona não encontrada, usando fallback")
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
            paceTarget != null && paceTarget.value > 10.0 -> {
                paceMinStr = formatarPace(paceTarget.value)
                paceMaxStr = formatarPace(paceTarget.value2 ?: paceTarget.value)
                zona = detectarZonaPorPace(paceTarget.value, paceZones)
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
            "Rest" -> "Descanso"
            "SteadyState" -> if (repAtual != null) "Esforço $repAtual/$repsTotal" else "Ritmo Constante"
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
}
