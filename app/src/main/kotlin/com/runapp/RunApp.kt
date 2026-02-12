package com.runapp

import android.app.Application
import com.runapp.data.api.IntervalsClient
import com.runapp.data.datastore.PreferencesRepository
import com.runapp.data.repository.WorkoutRepository

/**
 * Classe Application — inicializada uma vez quando o app abre.
 * Usamos ela para injeção de dependências manual (sem Hilt/Dagger).
 */
class RunApp : Application() {

    // Container com todas as dependências do app
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/**
 * Container de dependências — criado uma vez e compartilhado por todo o app.
 * ViewModels recebem estas dependências via factory.
 */
class AppContainer(private val application: Application) {

    val preferencesRepository by lazy {
        PreferencesRepository(application)
    }

    /**
     * Cria API com a chave fornecida.
     * Chamado após o usuário configurar a API key.
     */
    fun createWorkoutRepository(apiKey: String): WorkoutRepository {
        val api = IntervalsClient.create(apiKey)
        return WorkoutRepository(api)
    }
}
