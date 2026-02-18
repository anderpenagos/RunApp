package com.runapp.data.model

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

// ---- Treino / Evento ----

data class WorkoutEvent(
    val id: Long = 0,
    val name: String = "",
    @SerializedName("start_date_local") val startDateLocal: String = "",
    val type: String = "Run",
    val description: String? = null,
    @SerializedName("workout_doc") val workoutDocRaw: JsonElement? = null,
    val color: String? = null,
    @SerializedName("athlete_id") val athleteId: String = ""
)

// WorkoutDoc e WorkoutStep são apenas usados internamente após parsing manual
data class WorkoutDoc(
    val duration: Int = 0,
    val steps: List<WorkoutStep> = emptyList()
)

data class WorkoutStep(
    val duration: Int = 0,
    val pace: StepTarget? = null,
    val text: String? = null,
    val reps: Int? = null,
    val steps: List<WorkoutStep>? = null,
    val isRamp: Boolean = false
)

data class StepTarget(
    val units: String? = null,   // "pace_zone", "%pace"
    val value: Double? = null,   // zona única (ex: 1, 5, 6) ou 0 para descanso
    val start: Double? = null,   // zona inicial do range (ex: 5 em Z5-Z6)
    val end: Double? = null      // zona final do range   (ex: 6 em Z5-Z6)
) {
    val isDescanso: Boolean get() = units == "%pace" && (value == null || value == 0.0)
    val zonaUnica: Int? get() = if (!isDescanso && start == null && value != null && value > 0) value.toInt() else null
    val zonaStart: Int? get() = start?.toInt()
    val zonaEnd: Int? get() = end?.toInt()
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
