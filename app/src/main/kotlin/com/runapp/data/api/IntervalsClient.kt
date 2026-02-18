package com.runapp.data.api

import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.runapp.data.model.StepTarget
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit

object IntervalsClient {

    private const val BASE_URL = "https://intervals.icu/api/v1/"

    /**
     * Deserializador manual para StepTarget.
     *
     * O Gson padrão usa sun.misc.Unsafe para instanciar data classes Kotlin,
     * ignorando valores default e tendo comportamento imprevisível com campos
     * cujo nome colide com palavras reservadas JVM (ex: "end").
     *
     * Aqui lemos o JsonObject diretamente, cobrindo os dois formatos da API:
     *
     * Formato 1 — zona única:
     *   {"value": 6, "units": "pace_zone"}
     *
     * Formato 2 — range de zonas:
     *   {"start": 5, "end": 6, "units": "pace_zone"}
     *
     * Formato 3 — descanso:
     *   {"value": 0, "units": "%pace"}
     */
    private val stepTargetDeserializer = object : JsonDeserializer<StepTarget> {
        override fun deserialize(
            json: JsonElement,
            typeOfT: Type,
            context: JsonDeserializationContext
        ): StepTarget {
            val obj = json.asJsonObject
            return StepTarget(
                value  = obj.get("value")?.takeIf { !it.isJsonNull }?.asDouble  ?: 0.0,
                value2 = obj.get("value2")?.takeIf { !it.isJsonNull }?.asDouble,
                type   = obj.get("type")?.takeIf   { !it.isJsonNull }?.asString  ?: "pace",
                units  = obj.get("units")?.takeIf  { !it.isJsonNull }?.asString,
                start  = obj.get("start")?.takeIf  { !it.isJsonNull }?.asDouble,
                end    = obj.get("end")?.takeIf    { !it.isJsonNull }?.asDouble
            )
        }
    }

    private val gson = GsonBuilder()
        .registerTypeAdapter(StepTarget::class.java, stepTargetDeserializer)
        .create()

    fun create(apiKey: String): IntervalsApi {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val credentials = Credentials.basic("API_KEY", apiKey)
                val request = chain.request().newBuilder()
                    .header("Authorization", credentials)
                    .header("Accept", "application/json")
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(IntervalsApi::class.java)
    }
}
