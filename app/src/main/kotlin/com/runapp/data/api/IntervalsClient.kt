package com.runapp.data.api

import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.runapp.data.model.StepTarget
import com.runapp.data.model.WorkoutStep
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit

/**
 * Deserializador customizado para StepTarget.
 *
 * A API do intervals.icu envia o campo "pace" em 3 formatos:
 *   1. Zona única:      {"units": "pace_zone", "value": 1}
 *   2. Range de zonas:  {"units": "pace_zone", "start": 5, "end": 6}
 *   3. Descanso:        {"units": "%pace", "value": 0}
 */
class StepTargetDeserializer : JsonDeserializer<StepTarget> {
    override fun deserialize(json: JsonElement, typeOfT: Type, ctx: JsonDeserializationContext): StepTarget {
        val obj = json.asJsonObject
        val units  = obj.get("units")?.asString
        val value  = obj.get("value")?.asDouble  ?: 0.0
        val value2 = obj.get("value2")?.asDouble
        val start  = obj.get("start")?.asDouble
        val end    = obj.get("end")?.asDouble
        val type   = obj.get("type")?.asString   ?: "pace"

        return StepTarget(
            value  = value,
            value2 = value2,
            type   = type,
            units  = units,
            start  = start,
            end    = end
        )
    }
}

/**
 * Deserializador customizado para WorkoutStep.
 *
 * Necessário porque o Gson padrão sem KotlinJsonAdapterFactory não respeita
 * default values de campos nullable em data classes Kotlin — campos ausentes
 * no JSON ficam null em vez de usar o default declarado.
 */
class WorkoutStepDeserializer : JsonDeserializer<WorkoutStep> {
    private val stepTargetDeserializer = StepTargetDeserializer()

    override fun deserialize(json: JsonElement, typeOfT: Type, ctx: JsonDeserializationContext): WorkoutStep {
        val obj = json.asJsonObject

        val type     = obj.get("type")?.asString ?: "SteadyState"
        val duration = obj.get("duration")?.asInt ?: 0
        val text     = obj.get("text")?.asString
        val reps     = obj.get("reps")?.asInt

        // Parsear pace usando o deserializador customizado
        val pace = obj.get("pace")?.let { stepTargetDeserializer.deserialize(it, StepTarget::class.java, ctx) }
        val target = obj.get("power")?.let { stepTargetDeserializer.deserialize(it, StepTarget::class.java, ctx) }

        // Parsear sub-steps recursivamente
        val subSteps = obj.getAsJsonArray("steps")?.map { el ->
            deserialize(el, typeOfT, ctx)
        }

        return WorkoutStep(
            type     = type,
            duration = duration,
            target   = target,
            pace     = pace,
            text     = text,
            reps     = reps,
            steps    = subSteps
        )
    }
}

object IntervalsClient {

    private const val BASE_URL = "https://intervals.icu/api/v1/"

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

        // Gson com deserializadores customizados para parsing correto da API
        val gson = GsonBuilder()
            .registerTypeAdapter(StepTarget::class.java, StepTargetDeserializer())
            .registerTypeAdapter(WorkoutStep::class.java, WorkoutStepDeserializer())
            .create()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(IntervalsApi::class.java)
    }
}
