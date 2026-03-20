package com.runapp.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.runapp.RunApp
import com.runapp.data.model.WorkoutEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class TreinosUiState(
    val treinos: List<WorkoutEvent> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

class TreinosViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as RunApp).container

    private val _uiState = MutableStateFlow(TreinosUiState())
    val uiState: StateFlow<TreinosUiState> = _uiState

    init {
        carregarTreinos()
    }

    fun carregarTreinos() {
        viewModelScope.launch {
            _uiState.value = TreinosUiState(isLoading = true)
            try {
                val apiKey = container.preferencesRepository.apiKey.first()
                val athleteId = container.preferencesRepository.athleteId.first()

                if (apiKey.isNullOrBlank() || athleteId.isNullOrBlank()) {
                    _uiState.value = TreinosUiState(error = "Configure a API Key primeiro")
                    return@launch
                }

                val repo = container.createWorkoutRepository(apiKey)
                val result = repo.getTreinosSemana(athleteId)

                result.fold(
                    onSuccess = { treinos ->
                        _uiState.value = TreinosUiState(treinos = treinos)
                    },
                    onFailure = { e ->
                        _uiState.value = TreinosUiState(
                            error = "Erro ao carregar treinos: ${e.message}"
                        )
                    }
                )
            } catch (e: Exception) {
                _uiState.value = TreinosUiState(error = "Erro inesperado: ${e.message}")
            }
        }
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(
                modelClass: Class<T>,
                extras: androidx.lifecycle.viewmodel.CreationExtras
            ): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                return TreinosViewModel(app) as T
            }
        }
    }
}
