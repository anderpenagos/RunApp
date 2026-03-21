package com.runapp.data.api

import com.runapp.data.model.ActivityUploadResponse
import com.runapp.data.model.WorkoutEvent
import com.runapp.data.model.WellnessSnapshot
import com.runapp.data.model.ZonesResponse
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.http.*

interface IntervalsApi {

    @GET("athlete/{id}/events")
    suspend fun getEvents(
        @Path("id") athleteId: String,
        @Query("oldest") oldest: String,
        @Query("newest") newest: String,
        @Query("category") category: String = "WORKOUT"
    ): List<WorkoutEvent>

    /**
     * Retorna o JSON bruto do evento para que o WorkoutRepository
     * possa parsear os steps manualmente (evitando bugs do Gson com
     * campos "start"/"end"/"units" em StepTarget).
     */
    @GET("athlete/{id}/events/{eventId}")
    suspend fun getEventDetailRaw(
        @Path("id") athleteId: String,
        @Path("eventId") eventId: Long
    ): ResponseBody

    @GET("athlete/{id}")
    suspend fun getZones(
        @Path("id") athleteId: String
    ): ZonesResponse

    /**
     * Retorna o snapshot de condicionamento físico (CTL/ATL/TSB) de um dia específico.
     *
     * Endpoint: GET /api/v1/athlete/{id}/wellness/{date}
     * Formato de data: "yyyy-MM-dd"
     */
    @GET("athlete/{id}/wellness/{date}")
    suspend fun getWellness(
        @Path("id") athleteId: String,
        @Path("date") date: String     // formato "yyyy-MM-dd"
    ): WellnessSnapshot

    /**
     * Retorna wellness de um período — usado para calcular tendência de CTL/TSB.
     *
     * Endpoint: GET /api/v1/athlete/{id}/wellness?oldest=...&newest=...
     * Retorna lista ordenada do mais antigo para o mais recente.
     */
    @GET("athlete/{id}/wellness")
    suspend fun getWellnessPeriodo(
        @Path("id") athleteId: String,
        @Query("oldest") oldest: String,   // "yyyy-MM-dd"
        @Query("newest") newest: String    // "yyyy-MM-dd"
    ): List<WellnessSnapshot>

    @POST("athlete/{id}/activities")
    @Multipart
    suspend fun uploadActivity(
        @Path("id") athleteId: String,
        @Part file: MultipartBody.Part
    ): ActivityUploadResponse
}
