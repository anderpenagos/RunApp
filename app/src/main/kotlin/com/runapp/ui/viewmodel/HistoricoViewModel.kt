package com.runapp.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.runapp.RunApp
import com.runapp.data.model.CorridaHistorico
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HistoricoUiState(
    val corridas: List<CorridaHistorico> = emptyList(),
    val carregando: Boolean = true,
    /** Índice da corrida sendo carregada para upload, ou null */
    val uploadEmAndamento: String? = null,
    /** Mensagem de feedback (sucesso/erro) para snackbar */
    val mensagem: String? = null
)

class HistoricoViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context get() = getApplication()
    private val container = (application as RunApp).container

    private val _uiState = MutableStateFlow(HistoricoUiState())
    val uiState: StateFlow<HistoricoUiState> = _uiState

    init {
        carregarHistorico()
    }

    fun carregarHistorico() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(carregando = true)
            val corridas = withContext(Dispatchers.IO) {
                runCatching {
                    container.historicoRepository.listarCorridas()
                }.getOrDefault(emptyList())
            }
            _uiState.value = _uiState.value.copy(corridas = corridas, carregando = false)
        }
    }

    /**
     * Deleta uma corrida do dispositivo e atualiza a lista.
     */
    fun deletarCorrida(corrida: CorridaHistorico) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                container.historicoRepository.deletarCorrida(corrida)
            }
            if (ok) {
                _uiState.value = _uiState.value.copy(
                    corridas = _uiState.value.corridas.filter { it.arquivoGpx != corrida.arquivoGpx },
                    mensagem = "Corrida deletada"
                )
            } else {
                _uiState.value = _uiState.value.copy(mensagem = "Erro ao deletar")
            }
        }
    }

    /**
     * Abre o seletor do Android para compartilhar o arquivo GPX com qualquer app
     * (Strava, WhatsApp, e-mail, etc.).
     *
     * Usa FileProvider para expor o arquivo com uma URI segura, sem precisar
     * de permissão de armazenamento externo.
     */
    fun compartilharGpx(corrida: CorridaHistorico): Intent? {
        return runCatching {
            val arquivo = container.historicoRepository.obterArquivoGpx(corrida) ?: return null

            val uri: Uri = FileProvider.getUriForFile(
                context,
                "com.runapp.fileprovider",
                arquivo
            )

            Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, corrida.nome)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }.getOrNull()
    }

    /**
     * Faz upload do GPX de uma corrida do histórico para o Intervals.icu.
     */
    fun uploadParaIntervals(corrida: CorridaHistorico) {
        if (_uiState.value.uploadEmAndamento != null) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(uploadEmAndamento = corrida.arquivoGpx)

            val arquivo = container.historicoRepository.obterArquivoGpx(corrida)
            if (arquivo == null) {
                _uiState.value = _uiState.value.copy(
                    uploadEmAndamento = null,
                    mensagem = "Arquivo GPX não encontrado"
                )
                return@launch
            }

            try {
                val apiKey    = container.preferencesRepository.apiKey.first()
                val athleteId = container.preferencesRepository.athleteId.first()

                if (apiKey == null || athleteId == null) {
                    _uiState.value = _uiState.value.copy(
                        uploadEmAndamento = null,
                        mensagem = "API Key ou Athlete ID não configurados"
                    )
                    return@launch
                }

                val repo = container.createWorkoutRepository(apiKey)
                repo.uploadParaIntervals(athleteId, arquivo).fold(
                    onSuccess = {
                        _uiState.value = _uiState.value.copy(
                            uploadEmAndamento = null,
                            mensagem = "✅ Enviado para Intervals.icu!"
                        )
                    },
                    onFailure = { e ->
                        _uiState.value = _uiState.value.copy(
                            uploadEmAndamento = null,
                            mensagem = "Erro no upload: ${e.message}"
                        )
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    uploadEmAndamento = null,
                    mensagem = "Erro: ${e.message}"
                )
            }
        }
    }

    fun limparMensagem() {
        _uiState.value = _uiState.value.copy(mensagem = null)
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras
            ): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                return HistoricoViewModel(app) as T
            }
        }
    }
}
