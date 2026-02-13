package com.runapp.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.google.android.gms.location.LocationServices
import com.runapp.RunApp
import com.runapp.data.model.*
import com.runapp.data.repository.WorkoutRepository
import com.runapp.service.AudioCoach
import com.runapp.service.RunningService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class CorridaUiState(
    val fase: FaseCorrida = FaseCorrida.PREPARANDO,
    val passos: List<PassoExecucao> = emptyList(),
    val passoAtualIndex: Int = 0,
    val tempoPassoDecorrido: Int = 0,
    val distanciaMetros: Double = 0.0,
    val tempoTotalSegundos: Long = 0L,
    val paceAtual: String = "--:--",
    val paceMedia: String = "--:--",
    val rota: List<LatLngPonto> = emptyList(),
    val posicaoAtual: LatLngPonto? = null,
    val erro: String? = null
) {
    val passoAtual: PassoExecucao? get() = passos.getOrNull(passoAtualIndex)
    val progressoPasso: Float get() {
        val passo = passoAtual ?: return 0f
        if (passo.duracao <= 0) return 0f
        return (tempoPassoDecorrido.toFloat() / passo.duracao).coerceIn(0f, 1f)
    }
    val tempoPassoRestante: Int get() = ((passoAtual?.duracao ?: 0) - tempoPassoDecorrido).coerceAtLeast(0)
    val tempoFormatado: String get() {
        val h = tempoTotalSegundos / 3600
        val m = (tempoTotalSegundos % 3600) / 60
        val s = tempoTotalSegundos % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s)
        else "%02d:%02d".format(m, s)
    }
}

enum class FaseCorrida { PREPARANDO, CORRENDO, PAUSADO, FINALIZADO }

class CorridaViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val context: Context get() = getApplication()
    private val container = (application as RunApp).container
    private val audioCoach = AudioCoach(context)

    private val _uiState = MutableStateFlow(CorridaUiState())
    val uiState: StateFlow<CorridaUiState> = _uiState

    private var timerJob: Job? = null
    private var ultimaLocalizacao: Location? = null
    private var ultimoKmAnunciado = 0.0
    private var ultimoFeedbackPace = 0L
    private val FEEDBACK_PACE_INTERVALO_MS = 30_000L // a cada 30s

    // Repositório instanciado após ter as credenciais
    private var workoutRepo: WorkoutRepository? = null

    fun carregarTreino(eventId: Long) {
        viewModelScope.launch {
            try {
                val apiKey = container.preferencesRepository.apiKey.first() ?: return@launch
                val athleteId = container.preferencesRepository.athleteId.first() ?: return@launch
                val repo = container.createWorkoutRepository(apiKey).also { workoutRepo = it }
                val evento = repo.getTreinoDetalhe(athleteId, eventId).getOrThrow()

                val zonasResponse = repo.getZonas(athleteId).getOrDefault(null)
                val paceZones = if (zonasResponse != null) {
                    repo.processarZonas(zonasResponse)
                } else {
                    emptyList()
                }
                
                val passos = repo.converterParaPassos(evento, paceZones)
                _uiState.value = _uiState.value.copy(
                    passos = passos,
                    fase = FaseCorrida.PREPARANDO
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(erro = "Erro ao carregar treino: ${e.message}")
            }
        }
    }

    fun iniciarCorrida() {
        // Inicia o Foreground Service de GPS
        val intent = Intent(context, RunningService::class.java).apply {
            action = RunningService.ACTION_START
        }
        context.startForegroundService(intent)

        _uiState.value = _uiState.value.copy(fase = FaseCorrida.CORRENDO)
        audioCoach.anunciarInicioCorrida()

        // Anuncia o primeiro passo
        _uiState.value.passoAtual?.let { passo ->
            audioCoach.anunciarPasso(passo.nome, passo.paceAlvoMin, passo.duracao)
        }

        iniciarTimer()
        iniciarGPS()
    }

    private fun iniciarTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (_uiState.value.fase == FaseCorrida.CORRENDO) {
                delay(1000)
                val state = _uiState.value
                val novoTempoPasso = state.tempoPassoDecorrido + 1
                val novoTempoTotal = state.tempoTotalSegundos + 1

                // Verifica se o passo terminou
                val passoAtual = state.passoAtual
                if (passoAtual != null && novoTempoPasso >= passoAtual.duracao) {
                    avancarPasso()
                } else {
                    _uiState.value = state.copy(
                        tempoPassoDecorrido = novoTempoPasso,
                        tempoTotalSegundos = novoTempoTotal
                    )
                    // Aviso de últimos segundos
                    audioCoach.anunciarUltimosSegundos(state.tempoPassoRestante)
                }
            }
        }
    }

    private fun iniciarGPS() {
        viewModelScope.launch {
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            // Registra callback via flow — simplificado aqui
            // Na prática, escutamos via callback diretamente
        }
    }

    /**
     * Chamado quando chega uma nova localização do GPS.
     * Conecte isso ao LocationCallback no MainActivity ou via Service.
     */
    fun onNovaLocalizacao(location: Location) {
        val state = _uiState.value
        if (state.fase != FaseCorrida.CORRENDO) return

        val novaRota = state.rota.toMutableList()
        val novoPonto = LatLngPonto(location.latitude, location.longitude)
        novaRota.add(novoPonto)

        // Calcula distância acumulada
        val distanciaAdicional = ultimaLocalizacao?.let { anterior ->
            RunningService.distanciaMetros(
                anterior.latitude, anterior.longitude,
                location.latitude, location.longitude
            ).toDouble()
        } ?: 0.0

        val novaDistancia = state.distanciaMetros + distanciaAdicional
        ultimaLocalizacao = location

        // Pace atual (velocidade GPS)
        val paceAtual = RunningService.calcularPace(location.speed)

        // Pace médio
        val paceMedia = if (state.tempoTotalSegundos > 0 && novaDistancia > 10) {
            val secsPerKm = (state.tempoTotalSegundos * 1000.0 / novaDistancia)
            val min = (secsPerKm / 60).toInt()
            val seg = (secsPerKm % 60).toInt()
            "%d:%02d".format(min, seg)
        } else "--:--"

        _uiState.value = state.copy(
            distanciaMetros = novaDistancia,
            paceAtual = paceAtual,
            paceMedia = paceMedia,
            rota = novaRota,
            posicaoAtual = novoPonto
        )

        // Anúncio de km
        val kmAtual = novaDistancia / 1000.0
        if (kmAtual - ultimoKmAnunciado >= 1.0) {
            ultimoKmAnunciado = Math.floor(kmAtual)
            audioCoach.anunciarKm(kmAtual, paceMedia)
        }

        // Feedback de pace a cada 30s
        val agora = System.currentTimeMillis()
        if (agora - ultimoFeedbackPace > FEEDBACK_PACE_INTERVALO_MS) {
            val passo = state.passoAtual
            if (passo != null && !passo.isDescanso) {
                audioCoach.anunciarPaceFeedback(paceAtual, passo.paceAlvoMin, passo.paceAlvoMax)
            }
            ultimoFeedbackPace = agora
        }
    }

    private fun avancarPasso() {
        val state = _uiState.value
        val proximoIndex = state.passoAtualIndex + 1

        if (proximoIndex >= state.passos.size) {
            finalizarCorrida()
            return
        }

        val proximoPasso = state.passos[proximoIndex]
        _uiState.value = state.copy(
            passoAtualIndex = proximoIndex,
            tempoPassoDecorrido = 0,
            tempoTotalSegundos = state.tempoTotalSegundos
        )

        // Anuncia próximo passo
        if (proximoPasso.isDescanso) {
            audioCoach.anunciarDescanso()
        } else {
            audioCoach.anunciarPasso(proximoPasso.nome, proximoPasso.paceAlvoMin, proximoPasso.duracao)
        }
    }

    fun finalizarCorrida() {
        timerJob?.cancel()

        val state = _uiState.value
        audioCoach.anunciarFimCorrida(
            state.distanciaMetros / 1000.0,
            state.tempoFormatado,
            state.paceMedia
        )

        _uiState.value = state.copy(fase = FaseCorrida.FINALIZADO)

        // Para o Foreground Service
        val intent = Intent(context, RunningService::class.java).apply {
            action = RunningService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun pausar() {
        timerJob?.cancel()
        _uiState.value = _uiState.value.copy(fase = FaseCorrida.PAUSADO)
    }

    fun retomar() {
        _uiState.value = _uiState.value.copy(fase = FaseCorrida.CORRENDO)
        iniciarTimer()
    }

    override fun onCleared() {
        super.onCleared()
        audioCoach.shutdown()
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras
            ): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                val handle = extras.createSavedStateHandle()
                return CorridaViewModel(app, handle) as T
            }
        }
    }
}
