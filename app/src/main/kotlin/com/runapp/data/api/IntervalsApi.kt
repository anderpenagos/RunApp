package com.runapp.data.api

import com.runapp.data.model.ActivityUploadResponse
import com.runapp.data.model.WorkoutEvent
import com.runapp.data.model.ZonesResponse
import okhttp3.MultipartBody
import retrofit2.http.*

interface IntervalsApi {

    /**
     * Busca eventos/treinos planejados para um período.
     * Exemplo de datas: "2024-05-01", "2024-05-31"
     */
    @GET("athlete/{id}/events")
    suspend fun getEvents(
        @Path("id") athleteId: String,
        @Query("oldest") oldest: String,
        @Query("newest") newest: String,
        @Query("category") category: String = "WORKOUT"
    ): List<WorkoutEvent>

    /**
     * Detalhe completo de um treino (com workout_doc e steps).
     */
    @GET("athlete/{id}/events/{eventId}")
    suspend fun getEventDetail(
        @Path("id") athleteId: String,
        @Path("eventId") eventId: Long
    ): WorkoutEvent

    /**
     * Zonas do atleta (pace, FC, potência).
     */
    @GET("athlete/{id}/zones")
    suspend fun getZones(
        @Path("id") athleteId: String
    ): ZonesResponse

    /**
     * Upload de atividade finalizada (.fit file).
     */
    @POST("athlete/{id}/activities")
    @Multipart
    suspend fun uploadActivity(
        @Path("id") athleteId: String,
        @Part file: MultipartBody.Part
    ): ActivityUploadResponse
}
