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
    val type: String = "SteadyState",  // SteadyState, IntervalsT, Rest, Warmup, Cooldown
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
    val type: String = "pace"  // "zone", "pace", "heart_rate", "power"
)

// ---- Zonas ----

data class ZonesResponse(
    val running: RunningZones? = null
)

data class RunningZones(
    val pace: List<PaceZone> = emptyList()
)

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
    val lng: Double
)

data class ResumoFinal(
    val distanciaKm: Double,
    val tempoTotal: Long,
    val paceMedia: String,
    val passosCompletos: Int,
    val passosTotal: Int,
    val rota: List<LatLngPonto>
)
