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

enum class SalvamentoEstado { PRONTO, SALVANDO, SALVO, ERRO }
enum class UploadEstado    { PRONTO, ENVIANDO, ENVIADO, ERRO }

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
    val autoPausado: Boolean = false,
    /** Estado do salvamento local (arquivo GPX) */
    val salvamentoEstado: SalvamentoEstado = SalvamentoEstado.PRONTO,
    /** Estado do upload para o Intervals.icu */
    val uploadEstado: UploadEstado = UploadEstado.PRONTO,
    /** Mensagem de erro do salvamento ou upload */
    val erroSalvamento: String? = null,
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
    // ✅ FIX: intervalo de 5s era invasivo; 10s é mais respeitoso durante a corrida
    private val FEEDBACK_PACE_INTERVALO_MS = 10_000L

    /** Arquivo GPX gerado após salvarCorrida() — necessário para uploadParaIntervals() */
    private var arquivoGpxSalvo: java.io.File? = null

    // ── Melhorias de GPS ─────────────────────────────────────────────────────

    /**
     * Filtro de Kalman para suavizar coordenadas GPS.
     * Remove "saltos" impossíveis e mantém a trajetória coerente.
     */
    private val kalmanFilter = KalmanFilter(processNoise = 3f)

    /**
     * Janela deslizante para Média Móvel Simples (SMA) do pace.
     *
     * O Filtro de Kalman já suaviza as coordenadas. Se usarmos uma janela grande
     * aqui em cima, o pace demora 10-15s para reagir a um sprint. Por isso
     * usamos apenas 5 leituras — suficiente para estabilidade sem travar a resposta.
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

    /**
     * Histerese de saída do auto-pause.
     * Só saímos do pause quando velocidade > RESUME_SPEED_MS por pelo menos
     * RESUME_READINGS_NEEDED leituras consecutivas. Isso evita que uma oscilação
     * de sinal de 1 segundo tire e recoloque o pause sem parar.
     */
    private var speedAboveResumeCount = 0

    /**
     * Última posição já passada pelo Filtro de Kalman.
     * Usamos essas coordenadas (e não as brutas do GPS) como ponto de partida
     * do cálculo de distância — garante consistência em todo o pipeline.
     */
    private var ultimaKalmanLat = Double.NaN
    private var ultimaKalmanLng = Double.NaN

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
     * 1. Filtro de Kalman nas coordenadas (suaviza a rota)
     * 2. Sanity check de velocidade (>10 m/s = erro de GPS, descarta)
     * 3. Velocidade via location.speed (Doppler, muito mais estável que distância/tempo)
     * 4. Auto-pause com histerese: entra em <0.8 m/s, sai apenas após 2 leituras >1.2 m/s
     * 5. Média Móvel Simples (SMA) de 5 leituras para exibição do pace
     * 6. Distância acumulada usando exclusivamente coordenadas Kalman → Kalman
     */
    fun onNovaLocalizacao(location: Location) {
        val state = _uiState.value
        if (state.fase != FaseCorrida.CORRENDO) return

        // ── 1. Filtro de Kalman nas coordenadas ────────────────────────────
        // Passamos a accuracy real do ponto ao Kalman. Se for impreciso (ex: 25m em
        // canyon urbano), o Kalman aumenta o measurementNoise e confia menos nessa
        // leitura, sem descartá-la completamente — melhor que um hard cutoff em áreas
        // densas. Só hard-descartamos leituras verdadeiramente inúteis (>50m).
        if (location.accuracy > MAX_ACCURACY_HARD_CUTOFF) return

        val (latSmooth, lngSmooth) = kalmanFilter.process(
            location.latitude,
            location.longitude,
            location.accuracy,  // accuracy real → Kalman calibra o peso automaticamente
            location.time
        )
        val novoPonto = LatLngPonto(latSmooth, lngSmooth)

        // ── 2. Sanity check de velocidade — Outlier Detection ──────────────
        // Às vezes o GPS "pula" 50m em 1 segundo (erro de sinal/multipath).
        // O Kalman ajuda, mas se a velocidade calculada for > 10 m/s (36 km/h),
        // é fisicamente impossível para um corredor → descarta o ponto inteiro.
        val speedMs: Float = if (location.hasSpeed() && location.speed > 0f) {
            location.speed
        } else {
            // Fallback via distância/tempo se Doppler não disponível
            if (!ultimaKalmanLat.isNaN()) {
                val distM = RunningService.distanciaMetros(
                    ultimaKalmanLat, ultimaKalmanLng, latSmooth, lngSmooth
                )
                val dtSec = ((location.time - (ultimaLocalizacao?.time ?: location.time)) / 1000f)
                    .coerceAtLeast(0.1f)
                distM / dtSec
            } else 0f
        }

        if (speedMs > MAX_RUNNING_SPEED_MS) {
            // Pulo impossível: atualiza Kalman mas descarta o ponto de distância/pace
            android.util.Log.w("CorridaVM", "GPS outlier descartado: %.1f m/s".format(speedMs))
            ultimaLocalizacao = location
            return
        }

        // ── 3. Auto-pause com histerese ────────────────────────────────────
        // ENTRAR no pause: imediato quando velocidade < 0.8 m/s
        // SAIR do pause: apenas após RESUME_READINGS_NEEDED leituras consecutivas
        //   com velocidade > RESUME_SPEED_MS (evita que 1 oscilação de sinal tire o pause)
        if (speedMs < AUTO_PAUSE_SPEED_MS) {
            autoPausadoGps = true
            speedAboveResumeCount = 0
        } else if (speedMs > RESUME_SPEED_MS) {
            speedAboveResumeCount++
            if (speedAboveResumeCount >= RESUME_READINGS_NEEDED) {
                autoPausadoGps = false
            }
        } else {
            // Velocidade entre 0.8 e 1.2 m/s: zona neutra, não altera o estado
            speedAboveResumeCount = 0
        }

        ultimaLocalizacao = location

        if (autoPausadoGps) {
            // Apenas atualiza posição no mapa — sem contar distância ou alterar pace
            _uiState.value = state.copy(
                posicaoAtual = novoPonto,
                autoPausado = true,
                paceAtual = "--:--"
            )
            // Atualiza a última posição Kalman mesmo pausado, para não criar salto
            // quando o usuário retomar
            ultimaKalmanLat = latSmooth
            ultimaKalmanLng = lngSmooth
            return
        }

        // ── 4. SMA de 5 leituras para exibição do pace ─────────────────────
        // Kalman já suavizou as coordenadas; 5 amostras é suficiente para estabilidade
        // sem sacrificar a reatividade (ex: ao iniciar um sprint, pace atualiza em ~5s)
        speedWindow.addLast(speedMs)
        if (speedWindow.size > SPEED_WINDOW_SIZE) speedWindow.removeFirst()

        val avgSpeedMs = speedWindow.average().toFloat()
        val paceAtual = RunningService.calcularPace(avgSpeedMs)

        // ── 5. Distância: Kalman → Kalman (pipeline 100% consistente) ──────
        // Calculamos SEMPRE da última posição Kalman para a posição Kalman atual.
        // Misturar coordenada bruta no ponto de partida seria inconsistente e
        // criaria saltos na distância acumulada.
        val distanciaAdicional = if (!ultimaKalmanLat.isNaN()) {
            RunningService.distanciaMetros(
                ultimaKalmanLat, ultimaKalmanLng,
                latSmooth, lngSmooth
            ).toDouble()
        } else 0.0

        ultimaKalmanLat = latSmooth
        ultimaKalmanLng = lngSmooth

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

        // ✅ Salvamento e upload agora são opcionais e disparados pelo usuário
        // na ResumoScreen — não acontecem mais aqui automaticamente.

        val intent = Intent(context, RunningService::class.java).apply {
            action = RunningService.ACTION_STOP
        }
        context.startService(intent)
    }

    /**
     * Salva a corrida como arquivo GPX no armazenamento do dispositivo.
     * Deve ser chamado a partir da ResumoScreen quando o usuário toca "Salvar".
     */
    fun salvarCorrida() {
        val state = _uiState.value
        if (state.salvamentoEstado == SalvamentoEstado.SALVANDO) return

        _uiState.value = state.copy(
            salvamentoEstado = SalvamentoEstado.SALVANDO,
            erroSalvamento = null
        )

        viewModelScope.launch {
            try {
                val apiKey    = container.preferencesRepository.apiKey.first()
                val athleteId = container.preferencesRepository.athleteId.first()

                if (athleteId == null) {
                    _uiState.value = _uiState.value.copy(
                        salvamentoEstado = SalvamentoEstado.ERRO,
                        erroSalvamento = "ID do atleta não configurado"
                    )
                    return@launch
                }

                val repo = workoutRepo
                    ?: container.createWorkoutRepository(apiKey ?: "").also { workoutRepo = it }

                val nomeAtividade = "Corrida RunApp - ${
                    java.time.LocalDateTime.now().format(
                        java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm")
                    )
                }"

                repo.salvarAtividade(
                    context    = context,
                    athleteId  = athleteId,
                    nomeAtividade = nomeAtividade,
                    distanciaMetros = state.distanciaMetros,
                    tempoSegundos   = state.tempoTotalSegundos,
                    paceMedia       = state.paceMedia,
                    rota            = state.rota
                ).fold(
                    onSuccess = { arquivo ->
                        // Guarda referência do arquivo para o upload posterior
                        arquivoGpxSalvo = arquivo
                        _uiState.value = _uiState.value.copy(
                            salvamentoEstado = SalvamentoEstado.SALVO
                        )
                        android.util.Log.d("CorridaVM", "✅ GPX salvo: ${arquivo.absolutePath}")
                    },
                    onFailure = { e ->
                        _uiState.value = _uiState.value.copy(
                            salvamentoEstado = SalvamentoEstado.ERRO,
                            erroSalvamento = e.message ?: "Erro ao salvar"
                        )
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    salvamentoEstado = SalvamentoEstado.ERRO,
                    erroSalvamento = e.message ?: "Erro inesperado"
                )
            }
        }
    }

    /**
     * Faz upload do GPX já salvo para o Intervals.icu.
     * Só disponível após [salvarCorrida] ter concluído com sucesso.
     */
    fun uploadParaIntervals() {
        val arquivo = arquivoGpxSalvo ?: run {
            _uiState.value = _uiState.value.copy(
                uploadEstado = UploadEstado.ERRO,
                erroSalvamento = "Salve a corrida primeiro"
            )
            return
        }

        if (_uiState.value.uploadEstado == UploadEstado.ENVIANDO) return

        _uiState.value = _uiState.value.copy(
            uploadEstado = UploadEstado.ENVIANDO,
            erroSalvamento = null
        )

        viewModelScope.launch {
            try {
                val apiKey    = container.preferencesRepository.apiKey.first()
                val athleteId = container.preferencesRepository.athleteId.first()

                if (apiKey == null || athleteId == null) {
                    _uiState.value = _uiState.value.copy(
                        uploadEstado = UploadEstado.ERRO,
                        erroSalvamento = "API Key ou Athlete ID não configurados"
                    )
                    return@launch
                }

                val repo = workoutRepo
                    ?: container.createWorkoutRepository(apiKey).also { workoutRepo = it }

                repo.uploadParaIntervals(athleteId, arquivo).fold(
                    onSuccess = {
                        _uiState.value = _uiState.value.copy(uploadEstado = UploadEstado.ENVIADO)
                        android.util.Log.d("CorridaVM", "✅ Upload concluído: id=${it.id}")
                    },
                    onFailure = { e ->
                        _uiState.value = _uiState.value.copy(
                            uploadEstado = UploadEstado.ERRO,
                            erroSalvamento = "Erro no upload: ${e.message}"
                        )
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    uploadEstado = UploadEstado.ERRO,
                    erroSalvamento = e.message ?: "Erro inesperado"
                )
            }
        }
    }

    fun pausar() {
        timerJob?.cancel()
        _uiState.value = _uiState.value.copy(fase = FaseCorrida.PAUSADO)
        // Reseta o Kalman ao pausar para não criar saltos quando retomar
        kalmanFilter.reset()
        speedWindow.clear()
        speedAboveResumeCount = 0
        // Invalida última posição Kalman: ao retomar, o primeiro ponto
        // não vai criar uma distância espúria do ponto anterior à pausa
        ultimaKalmanLat = Double.NaN
        ultimaKalmanLng = Double.NaN
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
        /**
         * Hard cutoff de precisão GPS.
         * Elevado de 15m para 50m: leituras entre 15-50m não são descartadas,
         * apenas recebem menor peso no Kalman (measurementNoise ∝ accuracy²).
         * Isso evita "buracos" no trajeto em canyons urbanos com prédios altos.
         * Leituras piores que 50m são genuinamente inúteis e descartadas.
         */
        private const val MAX_ACCURACY_HARD_CUTOFF = 50f

        /**
         * Velocidade máxima razoável para um corredor em m/s.
         * 10 m/s ≈ 36 km/h — se passar disso, é erro de GPS (multipath/salto).
         */
        private const val MAX_RUNNING_SPEED_MS = 10f

        /**
         * Velocidade mínima para considerar que o usuário está correndo.
         * Abaixo → entra em auto-pause imediatamente.
         * 0.8 m/s ≈ 3 km/h (caminhada muito lenta / parado no semáforo).
         */
        private const val AUTO_PAUSE_SPEED_MS = 0.8f

        /**
         * Velocidade para SAIR do auto-pause.
         * Maior que AUTO_PAUSE_SPEED_MS para criar histerese:
         * o app não fica alternando pause/play por oscilações de sinal.
         * 1.2 m/s ≈ 4.3 km/h.
         */
        private const val RESUME_SPEED_MS = 1.2f

        /**
         * Quantas leituras consecutivas acima de RESUME_SPEED_MS são
         * necessárias para sair do auto-pause. Com intervalo de 1s, isso
         * significa esperar pelo menos 2 segundos antes de retomar.
         */
        private const val RESUME_READINGS_NEEDED = 2

        /**
         * Número de amostras na SMA para o pace exibido.
         * 5 amostras × 1s = média dos últimos ~5 segundos.
         * Reduzido de 10 para 5: o Kalman já suaviza as coordenadas, então
         * uma janela menor aqui garante que o pace reage ao sprint em ~5s
         * em vez de 10-15s.
         */
        private const val SPEED_WINDOW_SIZE = 5

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
