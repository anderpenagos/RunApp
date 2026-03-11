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
    // Controles de áudio
    val audioMasterEnabled: Boolean = true,
    val audioPaceAlerts: Boolean = true,
    val audioSplitsKm: Boolean = true,
    val splitIntervaloMetros: Int = 1000,
    val splitDadosFlags: Set<String> = setOf("distancia", "pace_medio"),
    // true enquanto o Room ainda não respondeu — impede decisões de navegação precipitadas
    val isLoading: Boolean = true,
    // Diagnóstico de zonas — visível no app (sem logcat)
    val zonasCarregadas: Int = -1,           // -1 = ainda não verificado
    val zonasDiagTexto: String = "",          // descrição das zonas carregadas
    val zonasTestandoApi: Boolean = false,    // true enquanto testa a API
    val zonasTeste: String = ""              // resultado do teste ao vivo
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
            val audioMaster         = prefs.audioMasterEnabled.first()
            val audioPace           = prefs.audioPaceAlerts.first()
            val audioSplits         = prefs.audioSplitsKm.first()
            val splitIntervalo      = prefs.splitIntervaloMetros.first()
            val splitFlags          = prefs.splitDadosFlags.first()

            // Lê zonas do cache para mostrar diagnóstico imediato
            val zonasCached = prefs.getZonasFronteiraCached()
            val diagTexto = if (zonasCached.isEmpty()) {
                "Nenhuma zona em cache — será buscada ao salvar/correr"
            } else {
                zonasCached.joinToString("\n") { z ->
                    val min = z.paceMinSegKm.toLong().let { "%d:%02d".format(it / 60, it % 60) }
                    val max = z.paceMaxSegKm?.toLong()?.let { "%d:%02d".format(it / 60, it % 60) } ?: "∞"
                    "• ${z.nome}: $min – $max /km"
                }
            }

            _uiState.value = ConfigUiState(
                apiKey                = apiKey ?: "",
                athleteId             = athleteId ?: "",
                isConfigured          = !apiKey.isNullOrBlank() && !athleteId.isNullOrBlank(),
                autoPauseEnabled      = autoPause,
                gapTelemetriaReduzida = telemetriaReduzida,
                audioMasterEnabled    = audioMaster,
                audioPaceAlerts       = audioPace,
                audioSplitsKm         = audioSplits,
                splitIntervaloMetros  = splitIntervalo,
                splitDadosFlags       = splitFlags,
                isLoading             = false,
                zonasCarregadas       = zonasCached.size,
                zonasDiagTexto        = diagTexto
            )
        }
    }

    /** Testa busca de zonas ao vivo na API e mostra o resultado no card de diagnóstico. */
    fun testarZonas() {
        val state = _uiState.value
        if (state.apiKey.isBlank() || state.athleteId.isBlank()) {
            _uiState.value = state.copy(zonasTeste = "⚠️ Configure API Key e Athlete ID primeiro")
            return
        }
        _uiState.value = state.copy(zonasTestandoApi = true, zonasTeste = "")
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val app = getApplication<com.runapp.RunApp>()
                val repo = app.container.createWorkoutRepository(state.apiKey.trim())
                val resultado = repo.getZonas(state.athleteId.trim())
                resultado.fold(
                    onSuccess = { zonesResponse ->
                        val zonas = repo.processarZonas(zonesResponse)
                        val texto = if (zonas.isEmpty()) {
                            "❌ API respondeu mas não encontrou zonas de corrida.\n" +
                            "Verifique se há zonas de pace configuradas no Intervals.icu.\n" +
                            "sportSettings recebidos: ${zonesResponse.sportSettings.size}\n" +
                            "threshold_pace raiz: ${zonesResponse.thresholdPace}\n" +
                            "pace_zones raiz: ${zonesResponse.paceZones?.size ?: 0} zonas"
                        } else {
                            "✅ ${zonas.size} zonas recebidas:\n" +
                            zonas.joinToString("\n") { z ->
                                val min = z.min?.let { (it * 1000).toLong() }?.let { "%d:%02d".format(it / 60, it % 60) } ?: "--"
                                val max = z.max?.let { (it * 1000).toLong() }?.let { "%d:%02d".format(it / 60, it % 60) } ?: "∞"
                                "  ${z.name}: $min – $max /km"
                            }
                        }
                        // Atualiza cache se veio com dados
                        if (zonas.isNotEmpty()) {
                            val fronteiras = zonas.map { z ->
                                com.runapp.data.model.ZonaFronteira(
                                    nome = z.name, cor = z.color ?: "",
                                    paceMinSegKm = (z.min ?: 0.0) * 1000.0,
                                    paceMaxSegKm = z.max?.let { it * 1000.0 }
                                )
                            }
                            prefs.salvarZonasFronteira(fronteiras)
                        }
                        _uiState.value = _uiState.value.copy(
                            zonasTestandoApi = false,
                            zonasTeste = texto,
                            zonasCarregadas = zonas.size,
                            zonasDiagTexto = if (zonas.isNotEmpty()) zonas.joinToString("\n") { z ->
                                val min = z.min?.let { (it * 1000).toLong() }?.let { "%d:%02d".format(it / 60, it % 60) } ?: "--"
                                val max = z.max?.let { (it * 1000).toLong() }?.let { "%d:%02d".format(it / 60, it % 60) } ?: "∞"
                                "• ${z.name}: $min – $max /km"
                            } else _uiState.value.zonasDiagTexto
                        )
                    },
                    onFailure = { erro ->
                        _uiState.value = _uiState.value.copy(
                            zonasTestandoApi = false,
                            zonasTeste = "❌ Erro na API: ${erro.message}"
                        )
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    zonasTestandoApi = false,
                    zonasTeste = "❌ Exceção: ${e.message}"
                )
            }
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

    fun onAudioMasterToggle(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(audioMasterEnabled = enabled)
        viewModelScope.launch { prefs.setAudioMasterEnabled(enabled) }
    }

    fun onAudioPaceAlertsToggle(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(audioPaceAlerts = enabled)
        viewModelScope.launch { prefs.setAudioPaceAlerts(enabled) }
    }

    fun onAudioSplitsKmToggle(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(audioSplitsKm = enabled)
        viewModelScope.launch { prefs.setAudioSplitsKm(enabled) }
    }

    fun onSplitIntervaloChange(metros: Int) {
        _uiState.value = _uiState.value.copy(splitIntervaloMetros = metros)
        viewModelScope.launch { prefs.setSplitIntervaloMetros(metros) }
    }

    fun onSplitDadosFlagToggle(flag: String) {
        val flags = _uiState.value.splitDadosFlags.toMutableSet()
        if (flag in flags) flags.remove(flag) else flags.add(flag)
        // Garante ao menos 1 dado selecionado — silêncio total seria confuso
        if (flags.isEmpty()) flags.add("distancia")
        _uiState.value = _uiState.value.copy(splitDadosFlags = flags)
        viewModelScope.launch { prefs.setSplitDadosFlags(flags) }
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
