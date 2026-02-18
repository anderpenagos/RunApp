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
    val steps: List<WorkoutStep> = emptyList()
)

data class WorkoutStep(
    val type: String = "SteadyState",  // SteadyState, IntervalsT, Rest, Warmup, Cooldown, Ramp
    val duration: Int = 0,             // segundos
    @SerializedName("power") val target: StepTarget? = null,
    val pace: StepTarget? = null,
    @SerializedName("cadence") val cadence: StepTarget? = null,
    val text: String? = null,
    val reps: Int? = null,
    val steps: List<WorkoutStep>? = null  // sub-passos em intervalos
)

data class StepTarget(
    val value: Double = 0.0,
    val value2: Double? = null,
    val type: String = "pace",   // "zone", "pace", "heart_rate", "power"
    val units: String? = null,   // "pace_zone", "%pace", "power_zone", etc. (campo da API intervals.icu)
    val start: Double? = null,   // usado quando pace é um range: {"start":5,"end":6,"units":"pace_zone"}
    val end: Double? = null      // usado quando pace é um range
) {
    /** Zona efetiva: prioriza 'value' se preenchido, senão usa 'start' (início do range) */
    val effectiveValue: Double get() = if (value > 0.0) value else (start ?: 0.0)

    /** Zona final do range (para ranges como Z5-Z6) */
    val effectiveEnd: Double? get() = end ?: if (value2 != null && value2 > 0.0) value2 else null

    /** true se o target representa uma pace zone (não % de pace) */
    val isPaceZone: Boolean get() = units == "pace_zone" || type == "zone" || type == "pace_zone"

    /** true se é recuperação/descanso (0% de pace) */
    val isRest: Boolean get() = (units == "%pace" && value == 0.0) || (type == "pace" && value == 0.0 && start == null)
}

// ---- Zonas (FORMATO CORRETO DA API) ----

data class ZonesResponse(
    @SerializedName("sportSettings") val sportSettings: List<SportSetting> = emptyList()
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
    val min: Double? = null,   // segundos por metro
    val max: Double? = null,
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
    val zona: Int,              // 1-5 para colorir
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
    val accuracy: Float = 0f
)

data class ResumoFinal(
    val distanciaKm: Double,
    val tempoTotal: Long,
    val paceMedia: String,
    val passosCompletos: Int,
    val passosTotal: Int,
    val rota: List<LatLngPonto>
)
