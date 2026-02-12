package com.runapp.data.repository

import com.runapp.data.api.IntervalsApi
import com.runapp.data.model.PassoExecucao
import com.runapp.data.model.PaceZone
import com.runapp.data.model.WorkoutEvent
import com.runapp.data.model.ZonesResponse
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class WorkoutRepository(private val api: IntervalsApi) {

    /**
     * Busca treinos da semana atual.
     */
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

    /**
     * Busca treinos dos próximos 7 dias.
     */
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
     * Detalhe de um treino específico.
     */
    suspend fun getTreinoDetalhe(athleteId: String, eventId: Long): Result<WorkoutEvent> {
        return try {
            val evento = api.getEventDetail(athleteId, eventId)
            Result.success(evento)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Busca zonas de pace do atleta.
     */
    suspend fun getZonas(athleteId: String): Result<ZonesResponse> {
        return try {
            val zonas = api.getZones(athleteId)
            Result.success(zonas)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Converte um WorkoutEvent na lista de PassoExecucao para a corrida.
     * Expande intervalos repetidos e formata paces.
     */
    fun converterParaPassos(evento: WorkoutEvent, paceZones: List<PaceZone>): List<PassoExecucao> {
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
        return expandirPassos(doc.steps, paceZones)
    }

    private fun expandirPassos(
        steps: List<com.runapp.data.model.WorkoutStep>,
        paceZones: List<PaceZone>
    ): List<PassoExecucao> {
        val resultado = mutableListOf<PassoExecucao>()

        for (step in steps) {
            if (step.type == "IntervalsT" && step.steps != null) {
                // Expansão de intervalos repetidos
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
        step: com.runapp.data.model.WorkoutStep,
        paceZones: List<PaceZone>,
        repAtual: Int? = null,
        repsTotal: Int? = null
    ): PassoExecucao {
        val isDescanso = step.type == "Rest"
        val paceTarget = step.pace ?: step.target

        // Converte pace de s/m para min/km
        val paceMinStr = formatarPace(paceTarget?.value)
        val paceMaxStr = formatarPace(paceTarget?.value2)

        val nomePasso = when (step.type) {
            "Warmup" -> "Aquecimento"
            "Cooldown" -> "Desaceleração"
            "Rest" -> "Descanso / Caminhada"
            "SteadyState" -> if (repAtual != null) "Esforço $repAtual/$repsTotal" else "Ritmo Constante"
            "IntervalsT" -> "Intervalo"
            else -> step.text ?: step.type
        }

        val zona = detectarZona(paceTarget?.value, paceZones)

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

    /**
     * Converte pace de segundos/metro para string "min:seg/km"
     */
    fun formatarPace(secsPerMeter: Double?): String {
        if (secsPerMeter == null || secsPerMeter <= 0) return "--:--"
        val secsPerKm = secsPerMeter * 1000
        val min = (secsPerKm / 60).toInt()
        val seg = (secsPerKm % 60).toInt()
        return "%d:%02d".format(min, seg)
    }

    private fun detectarZona(secsPerMeter: Double?, paceZones: List<PaceZone>): Int {
        if (secsPerMeter == null || paceZones.isEmpty()) return 2
        // Pace mais lento = maior valor de s/m → maior tempo → zona mais baixa
        for ((index, zone) in paceZones.withIndex()) {
            val min = zone.min ?: continue
            val max = zone.max ?: continue
            if (secsPerMeter in min..max) return index + 1
        }
        return 2
    }

    private fun gerarInstrucao(tipo: String, paceMin: String, paceMax: String): String {
        return when (tipo) {
            "Warmup" -> "Aquecimento suave. Comece devagar e vá aumentando gradualmente."
            "Cooldown" -> "Desacelere gradualmente. Pace de $paceMin a $paceMax."
            "Rest" -> "Caminhada ou corrida muito leve para recuperar."
            "SteadyState" -> "Mantenha pace constante entre $paceMin e $paceMax."
            else -> "Pace alvo: $paceMin/km"
        }
    }
}
