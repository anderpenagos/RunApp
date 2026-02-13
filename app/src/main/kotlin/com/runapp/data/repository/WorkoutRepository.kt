package com.runapp.data.repository

import com.runapp.data.api.IntervalsApi
import com.runapp.data.model.PassoExecucao
import com.runapp.data.model.PaceZone
import com.runapp.data.model.WorkoutEvent
import com.runapp.data.model.ZonesResponse
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import android.util.Log

/**
 * IMPORTANTE: Certifique-se de que o modelo StepTarget em Models.kt tenha este formato:
 * 
 * data class StepTarget(
 *     val value: Double = 0.0,
 *     val value2: Double? = null,
 *     val type: String = "pace"  // "zone", "pace", "heart_rate", "power"
 * )
 */

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

        // Log para debug
        Log.d("WorkoutRepo", "Step: ${step.type}, pace: ${paceTarget?.value}, type: ${paceTarget?.type}, text: ${step.text}")

        // DETECÇÃO INTELIGENTE: Verificar se é referência a zona
        val isZonaRef = when {
            // Método 1: API retorna type="zone"
            paceTarget?.type == "zone" -> true
            // Método 2: Valor pequeno (0-10) provavelmente é zona
            paceTarget?.value != null && paceTarget.value > 0 && paceTarget.value <= 10.0 -> true
            // Método 3: Texto contém "Z1", "Z2", etc
            step.text?.contains(Regex("[zZ]\\d")) == true -> true
            else -> false
        }

        val (paceMinStr, paceMaxStr, zona) = when {
            // É referência a zona
            isZonaRef && paceTarget != null -> {
                val zonaNum = if (paceTarget.value <= 10.0) {
                    paceTarget.value.toInt()
                } else {
                    extrairZonaDoTexto(step.text) ?: 2
                }
                
                Log.d("WorkoutRepo", "Detectado como zona: Z$zonaNum")
                
                // Buscar pace da zona configurada
                val zonaConfig = paceZones.getOrNull(zonaNum - 1)
                if (zonaConfig != null) {
                    Triple(
                        formatarPace(zonaConfig.min),
                        formatarPace(zonaConfig.max),
                        zonaNum
                    )
                } else {
                    Log.w("WorkoutRepo", "Zona $zonaNum não encontrada, usando padrão")
                    Triple("--:--", "--:--", 2)
                }
            }
            
            // É pace absoluto
            paceTarget != null -> {
                val paceCalc = formatarPace(paceTarget.value)
                
                // Validação: Se pace > 10min/km, algo está errado
                val minutos = paceCalc.split(":").firstOrNull()?.toIntOrNull() ?: 0
                if (minutos > 10) {
                    Log.w("WorkoutRepo", "Pace muito lento detectado: $paceCalc - tentando usar zona do texto")
                    val zonaTexto = extrairZonaDoTexto(step.text)
                    if (zonaTexto != null) {
                        val zonaConfig = paceZones.getOrNull(zonaTexto - 1)
                        if (zonaConfig != null) {
                            Triple(
                                formatarPace(zonaConfig.min),
                                formatarPace(zonaConfig.max),
                                zonaTexto
                            )
                        } else {
                            Triple(paceCalc, formatarPace(paceTarget.value2), 2)
                        }
                    } else {
                        Triple(paceCalc, formatarPace(paceTarget.value2), 2)
                    }
                } else {
                    Triple(
                        paceCalc,
                        formatarPace(paceTarget.value2),
                        detectarZonaPorPace(paceTarget.value, paceZones)
                    )
                }
            }
            
            // Sem target, usar padrão
            else -> {
                val zonaTexto = extrairZonaDoTexto(step.text) ?: 2
                val zonaConfig = paceZones.getOrNull(zonaTexto - 1)
                if (zonaConfig != null) {
                    Triple(
                        formatarPace(zonaConfig.min),
                        formatarPace(zonaConfig.max),
                        zonaTexto
                    )
                } else {
                    Triple("--:--", "--:--", 2)
                }
            }
        }

        val nomePasso = when (step.type) {
            "Warmup" -> "Aquecimento"
            "Cooldown" -> "Desaceleração"
            "Rest" -> "Descanso / Caminhada"
            "SteadyState" -> if (repAtual != null) "Esforço $repAtual/$repsTotal" else "Ritmo Constante"
            "IntervalsT" -> "Intervalo"
            "Ramp" -> "RAMP (aquecimento progressivo)"
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

    /**
     * Extrai número da zona do texto (ex: "Z1 Pace" -> 1, "Z2 Pace" -> 2)
     */
    private fun extrairZonaDoTexto(texto: String?): Int? {
        if (texto == null) return null
        val regex = Regex("[zZ](\\d)")
        val match = regex.find(texto)
        return match?.groupValues?.get(1)?.toIntOrNull()
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

    private fun detectarZonaPorPace(secsPerMeter: Double?, paceZones: List<PaceZone>): Int {
        if (secsPerMeter == null || paceZones.isEmpty()) return 2
        
        // Se o valor é muito pequeno (<10), pode ser referência direta a zona
        if (secsPerMeter < 10.0) {
            return secsPerMeter.toInt().coerceIn(1, 5)
        }
        
        // Pace mais lento = maior valor de s/m → maior tempo
        // Encontrar em qual zona este pace se encaixa
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
            "Ramp" -> "Aumente o ritmo progressivamente de $paceMin até $paceMax."
            else -> if (paceMin != "--:--") "Pace alvo: $paceMin a $paceMax/km" else "Siga o ritmo indicado"
        }
    }
}
