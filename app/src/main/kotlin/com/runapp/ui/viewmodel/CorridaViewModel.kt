package com.runapp.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.Location
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
import com.runapp.util.KalmanFilter
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
    val erro: String? = null,
    /** true quando o usuário está parado (velocidade < limiar). O timer não conta. */
    val autoPausado: Boolean = false
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
    private val FEEDBACK_PACE_INTERVALO_MS = 5_000L

    // ── Melhorias de GPS ─────────────────────────────────────────────────────

    /**
     * Filtro de Kalman para suavizar coordenadas GPS.
     * Remove "saltos" impossíveis e mantém a trajetória coerente.
     */
    private val kalmanFilter = KalmanFilter(processNoise = 3f)

    /**
     * Janela deslizante para Média Móvel Simples (SMA) do pace.
     *
     * Em vez de exibir o pace do último segundo (muito instável), calculamos
     * a média das últimas [SPEED_WINDOW_SIZE] velocidades válidas. Isso evita
     * que o display pule de 5:00 para 8:00 de um segundo para o outro.
     */
    private val speedWindow = ArrayDeque<Float>(SPEED_WINDOW_SIZE)

    /**
     * Flag de auto-pause por GPS. Atualizada em cada onNovaLocalizacao.
     * O timer lê essa flag para decidir se deve incrementar o tempo.
     *
     * @Volatile garante visibilidade entre coroutines.
     */
    @Volatile
    private var autoPausadoGps = false

    // ── ─────────────────────────────────────────────────────────────────────

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
        val intent = Intent(context, RunningService::class.java).apply {
            action = RunningService.ACTION_START
        }
        context.startForegroundService(intent)

        _uiState.value = _uiState.value.copy(fase = FaseCorrida.CORRENDO)
        audioCoach.anunciarInicioCorrida()

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

                // ✅ FIX: Auto-pause — se o GPS indica que o usuário está parado,
                // não contamos esse segundo no tempo total nem no tempo do passo.
                // Isso evita que ficar parado no semáforo infle o pace médio.
                if (autoPausadoGps) {
                    _uiState.value = state.copy(autoPausado = true)
                    continue
                }

                val novoTempoPasso = state.tempoPassoDecorrido + 1
                val novoTempoTotal = state.tempoTotalSegundos + 1

                val passoAtual = state.passoAtual
                if (passoAtual != null && novoTempoPasso >= passoAtual.duracao) {
                    avancarPasso()
                } else {
                    _uiState.value = state.copy(
                        tempoPassoDecorrido = novoTempoPasso,
                        tempoTotalSegundos = novoTempoTotal,
                        autoPausado = false
                    )
                    audioCoach.anunciarUltimosSegundos(state.tempoPassoRestante)
                }
            }
        }
    }

    private fun iniciarGPS() {
        viewModelScope.launch {
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        }
    }

    /**
     * Chamado quando chega uma nova localização do GPS (via CorridaScreen).
     *
     * Pipeline de melhorias aplicadas aqui:
     * 1. Filtro de precisão (já feito no RunningService, mas mantemos como 2a linha de defesa)
     * 2. Filtro de Kalman nas coordenadas (suaviza a rota)
     * 3. Velocidade via location.speed (Doppler, muito mais estável que distância/tempo)
     * 4. Auto-pause: velocidade < 0.8 m/s = parado, não soma tempo nem distância
     * 5. Média Móvel Simples (SMA) de 10 leituras para exibição do pace
     */
    fun onNovaLocalizacao(location: Location) {
        val state = _uiState.value
        if (state.fase != FaseCorrida.CORRENDO) return

        // ── 1. Filtro de precisão ───────────────────────────────────────────
        // Linha de defesa extra, caso o RunningService não tenha filtrado
        if (location.accuracy > MAX_ACCURACY_METERS) return

        // ── 2. Filtro de Kalman nas coordenadas ────────────────────────────
        val (latSmooth, lngSmooth) = kalmanFilter.process(
            location.latitude,
            location.longitude,
            location.accuracy,
            location.time
        )
        val novoPonto = LatLngPonto(latSmooth, lngSmooth)

        // ── 3. Velocidade instantânea via Doppler ──────────────────────────
        // location.speed é calculado internamente pelo chipset GPS via efeito Doppler.
        // É o mesmo princípio que o Garmin usa — muito mais preciso do que
        // calcular distância/tempo entre dois pontos consecutivos.
        val speedMs: Float = if (location.hasSpeed() && location.speed > 0f) {
            location.speed
        } else {
            // Fallback: calcula pela distância se Doppler não disponível
            val anterior = ultimaLocalizacao
            if (anterior != null) {
                val distM = RunningService.distanciaMetros(
                    anterior.latitude, anterior.longitude,
                    location.latitude, location.longitude
                )
                val dtSec = ((location.time - anterior.time) / 1000f).coerceAtLeast(0.1f)
                distM / dtSec
            } else 0f
        }

        // ── 4. Auto-pause ──────────────────────────────────────────────────
        // Se o usuário está andando muito devagar ou parado (<= 0.8 m/s ≈ 3 km/h),
        // ativamos o auto-pause: não somamos distância nem tempo.
        // Sem isso, o drift do GPS parado gera "quilômetros fantasmas" e
        // o timer correndo sem o atleta se mover infla o pace médio.
        autoPausadoGps = speedMs < AUTO_PAUSE_SPEED_MS
        ultimaLocalizacao = location

        if (autoPausadoGps) {
            // Apenas atualiza posição no mapa — sem contar distância ou alterar pace
            _uiState.value = state.copy(
                posicaoAtual = novoPonto,
                autoPausado = true,
                paceAtual = "--:--"
            )
            return
        }

        // ── 5. Média Móvel Simples (SMA) para pace ─────────────────────────
        // Adiciona velocidade à janela e remove a mais antiga se cheia
        speedWindow.addLast(speedMs)
        if (speedWindow.size > SPEED_WINDOW_SIZE) speedWindow.removeFirst()

        val avgSpeedMs = speedWindow.average().toFloat()
        val paceAtual = RunningService.calcularPace(avgSpeedMs)

        // ── Acumula distância usando coordenadas suavizadas ────────────────
        val distanciaAdicional = ultimaLocalizacao?.let { anterior ->
            // Usa as coordenadas Kalman-filtradas para evitar saltos
            RunningService.distanciaMetros(
                anterior.latitude, anterior.longitude,
                latSmooth, lngSmooth
            ).toDouble()
        } ?: 0.0

        val novaDistancia = state.distanciaMetros + distanciaAdicional

        // ── Pace médio da corrida toda ─────────────────────────────────────
        val paceMedia = if (state.tempoTotalSegundos > 0 && novaDistancia > 10) {
            val secsPerKm = (state.tempoTotalSegundos * 1000.0 / novaDistancia)
            val min = (secsPerKm / 60).toInt()
            val seg = (secsPerKm % 60).toInt()
            "%d:%02d".format(min, seg)
        } else "--:--"

        val novaRota = state.rota.toMutableList().also { it.add(novoPonto) }

        _uiState.value = state.copy(
            distanciaMetros = novaDistancia,
            paceAtual = paceAtual,
            paceMedia = paceMedia,
            rota = novaRota,
            posicaoAtual = novoPonto,
            autoPausado = false
        )

        // Anúncio de km completado
        val kmAtual = novaDistancia / 1000.0
        if (kmAtual - ultimoKmAnunciado >= 1.0) {
            ultimoKmAnunciado = Math.floor(kmAtual)
            audioCoach.anunciarKm(kmAtual, paceMedia)
        }

        // Feedback de pace a cada 5s
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

        viewModelScope.launch {
            try {
                val apiKey = container.preferencesRepository.apiKey.first()
                val athleteId = container.preferencesRepository.athleteId.first()

                if (apiKey != null && athleteId != null && workoutRepo != null) {
                    val nomeAtividade = "Corrida RunApp - ${java.time.LocalDateTime.now().format(
                        java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm")
                    )}"

                    workoutRepo?.salvarAtividade(
                        athleteId = athleteId,
                        nomeAtividade = nomeAtividade,
                        distanciaMetros = state.distanciaMetros,
                        tempoSegundos = state.tempoTotalSegundos,
                        paceMedia = state.paceMedia,
                        rota = state.rota
                    )

                    android.util.Log.d("CorridaVM", "✅ Atividade salva!")
                }
            } catch (e: Exception) {
                android.util.Log.e("CorridaVM", "Erro ao salvar atividade", e)
            }
        }

        val intent = Intent(context, RunningService::class.java).apply {
            action = RunningService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun pausar() {
        timerJob?.cancel()
        _uiState.value = _uiState.value.copy(fase = FaseCorrida.PAUSADO)
        // Reseta o Kalman ao pausar para não criar saltos quando retomar
        kalmanFilter.reset()
        speedWindow.clear()
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
        /** Precisão máxima aceita (2ª linha de defesa após RunningService). */
        private const val MAX_ACCURACY_METERS = 15f

        /**
         * Velocidade mínima em m/s para considerar que o usuário está correndo.
         * 0.8 m/s ≈ 3 km/h (caminhada bem lenta / parado no semáforo).
         */
        private const val AUTO_PAUSE_SPEED_MS = 0.8f

        /**
         * Número de amostras na janela de Média Móvel Simples para o pace.
         * 10 amostras × 1s = média dos últimos ~10 segundos.
         * Aumentar suaviza mais, mas deixa o pace "lento" para reagir a mudanças.
         */
        private const val SPEED_WINDOW_SIZE = 10

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
