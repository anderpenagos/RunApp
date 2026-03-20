package com.runapp.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.runapp.data.model.LatLngPonto

/**
 * Entidade Room para cada ponto GPS de uma corrida.
 *
 * DESIGN: inserido imediatamente a cada update do GPS (1/s) no Dispatchers.IO.
 * Isso garante perda ZERO de dados: se o processo morrer entre checkpoints,
 * todos os pontos já estão no SQLite. O Service lê de volta via sessionId ao
 * ser reiniciado pelo Android (START_STICKY recovery).
 *
 * Index em (sessionId, tempo) para queries eficientes por sessão e ordenação
 * temporal sem full-scan.
 */
@Entity(
    tableName = "route_points",
    indices = [Index(value = ["sessionId", "tempo"])]
)
data class RoutePointEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** UUID da sessão de corrida — permite separar runs diferentes no mesmo banco */
    val sessionId: String,

    val lat: Double,
    val lng: Double,
    val alt: Double,
    val tempo: Long,
    val accuracy: Float,
    val paceNoPonto: Double,
    val cadenciaNoPonto: Int
) {
    fun toLatLngPonto() = LatLngPonto(
        lat            = lat,
        lng            = lng,
        alt            = alt,
        tempo          = tempo,
        accuracy       = accuracy,
        paceNoPonto    = paceNoPonto,
        cadenciaNoPonto = cadenciaNoPonto
    )

    companion object {
        fun from(ponto: LatLngPonto, sessionId: String) = RoutePointEntity(
            sessionId       = sessionId,
            lat             = ponto.lat,
            lng             = ponto.lng,
            alt             = ponto.alt,
            tempo           = ponto.tempo,
            accuracy        = ponto.accuracy,
            paceNoPonto     = ponto.paceNoPonto,
            cadenciaNoPonto = ponto.cadenciaNoPonto
        )
    }
}
