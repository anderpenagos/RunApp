package com.runapp.data.api

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object IntervalsClient {

    private const val BASE_URL = "https://intervals.icu/api/v1/"

    /**
     * Cria uma instância da API autenticada.
     * A API usa Basic Auth com:
     *   - username: "API_KEY" (literal, não muda)
     *   - password: a sua chave de API do intervals.icu
     *
     * Onde encontrar sua API Key:
     *   intervals.icu → Settings → Developer Settings → API Key
     */
    fun create(apiKey: String): IntervalsApi {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                // Basic Auth: usuário = "API_KEY", senha = sua chave
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
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(IntervalsApi::class.java)
    }
}
