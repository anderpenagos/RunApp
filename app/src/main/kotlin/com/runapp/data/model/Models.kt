package com.runapp.data.model

import com.google.gson.annotations.SerializedName

// ---- Treino / Evento ----

data class WorkoutEvent(
    val id: Long = 0,
    val name: String = "",
    @SerializedName("start_date_local") val startDateLocal: String = "",
    val type: String = "Run",
    val description: String? = null,
    @SerializedName("workout_doc") val workoutDoc: WorkoutDoc? = null,
    val color: String? = null,
    @SerializedName("athlete_id") val athleteId: String = ""
)

data class WorkoutDoc(
    val type: String = "running",
    val duration: Int = 0,
    val steps: List<WorkoutStep> = emptyList(),
    @SerializedName("threshold_pace") val thresholdPace: Double? = null  // m/s — presente em treinos com alvo em %pace
)

data class WorkoutStep(
    val type: String = "SteadyState",  // SteadyState, IntervalsT, Rest, Warmup, Cooldown, Ramp
    val duration: Int = 0,             // segundos
    @SerializedName("power") val target: StepTarget? = null,
    val pace: StepTarget? = null,
    @SerializedName("cadence") val cadence: StepTarget? = null,
    val text: String? = null,
    val reps: Int? = null,
    val steps: List<WorkoutStep>? = null,  // sub-passos em intervalos
    // Pace pré-calculado pelo Intervals.icu (presente em treinos com %pace)
    // start=velocidade mais lenta (% menor), end=velocidade mais rápida (% maior), em m/s
    @SerializedName("_pace") val precomputedPace: StepTarget? = null
)

/**
 * Alvo de pace de um step do treino.
 *
 * A API do intervals.icu usa dois formatos:
 *
 *   Zona única:   {"value": 6,  "units": "pace_zone"}
 *   Range zonas:  {"start": 5, "end": 6, "units": "pace_zone"}
 *   Descanso:     {"value": 0,  "units": "%pace"}
 *
 * Esta classe é desserializada por StepTargetDeserializer em IntervalsClient,
 * que lê o JsonObject manualmente para garantir que todos os campos — incluindo
 * "end" (palavra reservada no JVM) e "start" — sejam mapeados corretamente.
 */
data class StepTarget(
    val value: Double = 0.0,
    val value2: Double? = null,
    val type: String = "pace",
    val units: String? = null,
    @SerializedName("start") val start: Double? = null,
    @SerializedName("end")   val end: Double? = null
) {
    // Se o Intervals enviar {"start": 5, "end": 6}, value será 0.0.
    // Esta lógica garante que pegamos o 5.0 do start.
    val effectiveValue: Double get() = if (value > 0.0) value else (start ?: 0.0)

    // Esta lógica garante que pegamos o 6.0 do end.
    val effectiveEnd: Double? get() = end ?: if (value2 != null && value2 > 0.0) value2 else null

    // Verifica se é zona de pace
    val isPaceZone: Boolean get() = units?.contains("pace_zone", ignoreCase = true) == true ||
                                    type?.contains("zone", ignoreCase = true) == true

    val isRest: Boolean get() = (units == "%pace" && value == 0.0 && (start == null || start == 0.0)) ||
                                (type == "pace" && value == 0.0 && start == null)
}

// ---- Zonas (FORMATO CORRETO DA API) ----

data class ZonesResponse(
    // Formato legado esperado: array sportSettings (nao existe em GET athlete/{id})
    @SerializedName("sportSettings") val sportSettings: List<SportSetting> = emptyList(),
    // Formato real de GET athlete/{id}: campos diretos na raiz do JSON
    @SerializedName("threshold_pace") val thresholdPace: Double? = null,
    @SerializedName("pace_units")     val paceUnits: String? = null,
    @SerializedName("pace_zones")     val paceZones: List<Double>? = null,
    @SerializedName("pace_zone_names") val paceZoneNames: List<String>? = null
)

data class SportSetting(
    val id: Long = 0,
    val types: List<String> = emptyList(),
    @SerializedName("threshold_pace") val thresholdPace: Double? = null,  // m/s
    @SerializedName("pace_units") val paceUnits: String? = null,
    @SerializedName("pace_zones") val paceZones: List<Double>? = null,  // PORCENTAGENS!
    @SerializedName("pace_zone_names") val paceZoneNames: List<String>? = null
)

// ---- Zona processada (para uso interno) ----

data class PaceZone(
    val id: Int = 0,
    val name: String = "",
    val min: Double? = null,   // segundos por metro (pace mais RÁPIDO da zona)
    val max: Double? = null,   // segundos por metro (pace mais LENTO da zona)
    val color: String? = null
)

// ---- Atividade ----

data class ActivityUploadResponse(
    val id: String? = null,
    val name: String? = null
)

// ---- Modelos locais (usados em tela) ----

data class PassoExecucao(
    val nome: String,
    val duracao: Int,           // segundos
    val paceAlvoMin: String,    // ex: "5:00" min/km
    val paceAlvoMax: String,    // ex: "5:30" min/km
    val zona: Int,              // 1-7 para colorir
    val instrucao: String,
    val isDescanso: Boolean = false
)

data class CorridaSnapshot(
    val distanciaMetros: Double = 0.0,
    val tempoSegundos: Long = 0L,
    val paceAtual: String = "--:--",
    val paceMedia: String = "--:--",
    val passoAtualIndex: Int = 0,
    val progressoPasso: Float = 0f,
    val tempoPassoRestante: Int = 0,
    val rota: List<LatLngPonto> = emptyList()
)

data class LatLngPonto(
    val lat: Double,
    val lng: Double,
    val alt: Double = 0.0,
    val tempo: Long = 0L,
    val accuracy: Float = 0f,
    val paceNoPonto: Double = 0.0,  // pace instantâneo em seg/km no momento do ponto
    val cadenciaNoPonto: Int = 0    // SPM via acelerômetro no momento do ponto
)

data class ResumoFinal(
    val distanciaKm: Double,
    val tempoTotal: Long,
    val paceMedia: String,
    val passosCompletos: Int,
    val passosTotal: Int,
    val rota: List<LatLngPonto>
)

/**
 * Snapshot de condicionamento físico do atleta em um dado dia,
 * obtido via GET /api/v1/athlete/{id}/wellness/{date} do Intervals.icu.
 */
data class WellnessSnapshot(
    val id:       String  = "",   // "yyyy-MM-dd" — data do snapshot
    val ctl:      Double,
    val atl:      Double,
    val rampRate: Double? = null,
    @com.google.gson.annotations.SerializedName("atlLoad")
    val atlLoad:  Double? = null
) {
    // TSB não vem da API — calculado localmente como CTL − ATL
    val tsb: Double get() = ctl - atl
}

/**
 * Tendência de condicionamento dos últimos 7 dias — derivada de uma lista de WellnessSnapshot.
 * Usada pelo coach para contextualizar o estado do atleta com progressão/regressão real.
 */
data class WellnessTendencia(
    val snapshot: WellnessSnapshot,           // dado do dia da corrida
    val deltaCTL7d: Double,                   // variação de CTL nos últimos 7 dias (positivo = crescendo)
    val deltaTSB7d: Double,                   // variação de TSB nos últimos 7 dias (positivo = recuperando)
    val diasTSBNegativo: Int,                 // quantos dias consecutivos o TSB está negativo
    val tsbMinimo7d: Double,                  // TSB mais baixo nos últimos 7 dias
    val cargaTotal7d: Double                  // soma de atlLoad dos últimos 7 dias (carga acumulada)
)
