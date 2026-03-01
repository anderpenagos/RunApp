package com.runapp.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.runapp.RunApp
import com.runapp.data.datastore.PreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class ConfigUiState(
    val apiKey: String = "",
    val athleteId: String = "",
    val isSaving: Boolean = false,
    val error: String? = null,
    val isConfigured: Boolean = false,
    val autoPauseEnabled: Boolean = true,
    val gapTelemetriaReduzida: Boolean = false,
    // true enquanto o Room ainda não respondeu — impede decisões de navegação precipitadas
    val isLoading: Boolean = true
)

class ConfigViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = (application as RunApp).container.preferencesRepository

    private val _uiState = MutableStateFlow(ConfigUiState())
    val uiState: StateFlow<ConfigUiState> = _uiState

    init {
        viewModelScope.launch {
            val apiKey              = prefs.apiKey.first()
            val athleteId           = prefs.athleteId.first()
            val autoPause           = prefs.autoPauseEnabled.first()
            val telemetriaReduzida  = prefs.gapTelemetriaReduzida.first()
            _uiState.value = ConfigUiState(
                apiKey                = apiKey ?: "",
                athleteId             = athleteId ?: "",
                isConfigured          = !apiKey.isNullOrBlank() && !athleteId.isNullOrBlank(),
                autoPauseEnabled      = autoPause,
                gapTelemetriaReduzida = telemetriaReduzida,
                isLoading             = false  // Room respondeu — navegação pode prosseguir
            )
        }
    }

    fun onAutoPauseToggle(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(autoPauseEnabled = enabled)
        viewModelScope.launch {
            prefs.setAutoPauseEnabled(enabled)
        }
    }

    fun onGapTelemetriaReduzidaToggle(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(gapTelemetriaReduzida = enabled)
        viewModelScope.launch {
            prefs.setGapTelemetriaReduzida(enabled)
        }
    }

    fun onApiKeyChange(value: String) {
        _uiState.value = _uiState.value.copy(apiKey = value, error = null)
    }

    fun onAthleteIdChange(value: String) {
        _uiState.value = _uiState.value.copy(athleteId = value, error = null)
    }

    fun salvarCredenciais(onSuccess: () -> Unit) {
        val state = _uiState.value
        if (state.apiKey.isBlank()) {
            _uiState.value = state.copy(error = "API Key não pode estar vazia")
            return
        }
        if (state.athleteId.isBlank()) {
            _uiState.value = state.copy(error = "Athlete ID não pode estar vazio")
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isSaving = true)
            try {
                prefs.saveCredentials(state.apiKey.trim(), state.athleteId.trim())
                _uiState.value = state.copy(isSaving = false, isConfigured = true)
                onSuccess()
            } catch (e: Exception) {
                _uiState.value = state.copy(isSaving = false, error = "Erro ao salvar: ${e.message}")
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
                return ConfigViewModel(app) as T
            }
        }
    }
}
