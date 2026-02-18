package com.runapp.data.api

import com.runapp.data.model.ActivityUploadResponse
import com.runapp.data.model.WorkoutEvent
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

    @GET("athlete/{id}/zones")
    suspend fun getZones(
        @Path("id") athleteId: String
    ): ZonesResponse

    @POST("athlete/{id}/activities")
    @Multipart
    suspend fun uploadActivity(
        @Path("id") athleteId: String,
        @Part file: MultipartBody.Part
    ): ActivityUploadResponse
}
