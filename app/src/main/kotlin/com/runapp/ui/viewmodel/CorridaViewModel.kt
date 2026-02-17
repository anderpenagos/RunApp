package com.runapp.ui.viewmodel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.runapp.RunApp
import com.runapp.AppContainer
import com.runapp.data.model.LatLngPonto
import com.runapp.data.model.PassoExecucao
import com.runapp.data.model.WorkoutEvent
import com.runapp.data.repository.WorkoutRepository
import com.runapp.service.AudioCoach
import com.runapp.service.RunningService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Enums
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

enum class FaseCorrida {
    PREPARANDO,
    CORRENDO,
    PAUSADO,
    FINALIZADO
}

enum class SalvamentoEstado {
    NAO_SALVO,
    SALVANDO,
    SALVO,
    ERRO
}

enum class UploadEstado {
    NAO_ENVIADO,
    ENVIANDO,
    ENVIADO,
    ERRO
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// UI State
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

data class CorridaUiState(
    val fase: FaseCorrida = FaseCorrida.PREPARANDO,
    val treino: WorkoutEvent? = null,
    val passos: List<PassoExecucao> = emptyList(),
    val passoAtualIndex: Int = 0,
    val passoAtual: PassoExecucao? = null,
    
    // Métricas em tempo real (vêm do Service)
    val distanciaMetros: Double = 0.0,
    val tempoTotalSegundos: Long = 0,
    val paceAtual: String = "--:--",
    val paceMedia: String = "--:--",
    
    // Rastreamento GPS (vêm do Service)
    val rota: List<LatLngPonto> = emptyList(),
    val posicaoAtual: LatLngPonto? = null,
    val autoPausado: Boolean = false,
    
    // Progresso do passo atual
    val progressoPasso: Float = 0f,
    val tempoPassoRestante: Int = 0,
    
    // Salvamento e upload
    val salvamentoEstado: SalvamentoEstado = SalvamentoEstado.NAO_SALVO,
    val uploadEstado: UploadEstado = UploadEstado.NAO_ENVIADO,
    val erroSalvamento: String? = null,
    
    // Formatação
    val tempoFormatado: String = "00:00:00",
    
    // Erro
    val erro: String? = null
)

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// ViewModel
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

class CorridaViewModel(
    private val context: Context,
    private val container: AppContainer
) : ViewModel() {

    private val _uiState = MutableStateFlow(CorridaUiState())
    val uiState: StateFlow<CorridaUiState> = _uiState.asStateFlow()

    private var workoutRepo: WorkoutRepository? = null
    private var arquivoGpxSalvo: File? = null
    
    // Audio Coach para alertas de voz
    private val audioCoach = AudioCoach(context)
    private var ultimoKmAnunciado = 0
    
    // Service
    private var runningService: RunningService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RunningService.LocalBinder
            runningService = binder.getService()
            serviceBound = true
            
            android.util.Log.d("CorridaVM", "✅ Service conectado")
            
            // Observar dados do service
            observarDadosDoService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            runningService = null
            serviceBound = false
            android.util.Log.d("CorridaVM", "⚠️ Service desconectado")
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Lifecycle
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    init {
        android.util.Log.d("CorridaVM", "✅ ViewModel inicializado")
    }

    override fun onCleared() {
        super.onCleared()
        audioCoach.shutdown()
        
        // Desvincular do service se conectado
        if (serviceBound) {
            try {
                context.unbindService(serviceConnection)
            } catch (e: Exception) {
                android.util.Log.e("CorridaVM", "Erro ao unbind service", e)
            }
        }
        
        android.util.Log.d("CorridaVM", "🧹 ViewModel limpo")
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Observar dados do Service
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun observarDadosDoService() {
        val service = runningService ?: return
        
        viewModelScope.launch {
            service.distanciaMetros.collect { distancia ->
                _uiState.value = _uiState.value.copy(distanciaMetros = distancia)
                verificarAnuncioDistancia(distancia)
            }
        }
        
        viewModelScope.launch {
            service.tempoTotalSegundos.collect { tempo ->
                _uiState.value = _uiState.value.copy(
                    tempoTotalSegundos = tempo,
                    tempoFormatado = formatarTempo(tempo)
                )
            }
        }
        
        viewModelScope.launch {
            service.paceAtual.collect { pace ->
                _uiState.value = _uiState.value.copy(paceAtual = pace)
            }
        }
        
        viewModelScope.launch {
            service.paceMedia.collect { pace ->
                _uiState.value = _uiState.value.copy(paceMedia = pace)
            }
        }
        
        viewModelScope.launch {
            service.rotaAtual.collect { rota ->
                _uiState.value = _uiState.value.copy(rota = rota)
            }
        }
        
        viewModelScope.launch {
            service.posicaoAtual.collect { posicao ->
                _uiState.value = _uiState.value.copy(posicaoAtual = posicao)
            }
        }
        
        viewModelScope.launch {
            service.autoPausado.collect { autoPausado ->
                _uiState.value = _uiState.value.copy(autoPausado = autoPausado)
            }
        }
    }

    private fun verificarAnuncioDistancia(distanciaMetros: Double) {
        val kmPercorridos = (distanciaMetros / 1000).toInt()
        if (kmPercorridos > ultimoKmAnunciado && kmPercorridos > 0) {
            audioCoach.anunciarKm(kmPercorridos)
            ultimoKmAnunciado = kmPercorridos
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Carregar Treino
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    fun carregarTreino(eventId: Long) {
        viewModelScope.launch {
            try {
                val apiKey = container.preferencesRepository.apiKey.first()
                val athleteId = container.preferencesRepository.athleteId.first()
                
                if (athleteId == null) {
                    _uiState.value = _uiState.value.copy(
                        erro = "ID do atleta não configurado"
                    )
                    return@launch
                }
                
                val repo = container.createWorkoutRepository(apiKey ?: "")
                workoutRepo = repo
                
                val resultado = repo.getTreinoDetalhe(athleteId, eventId)
                resultado.fold(
                    onSuccess = { evento ->
                        val zonasResult = repo.getZonas(athleteId)
                        val paceZones = zonasResult.fold(
                            onSuccess = { zonesResponse -> repo.processarZonas(zonesResponse) },
                            onFailure = { emptyList() }
                        )
                        
                        val passosProcessados = repo.converterParaPassos(evento, paceZones)
                        
                        _uiState.value = _uiState.value.copy(
                            treino = evento,
                            passos = passosProcessados,
                            passoAtual = passosProcessados.firstOrNull()
                        )
                        
                        android.util.Log.d("CorridaVM", "✅ Treino carregado: ${evento.name}")
                    },
                    onFailure = { e ->
                        android.util.Log.e("CorridaVM", "❌ Erro ao carregar treino", e)
                        _uiState.value = _uiState.value.copy(
                            erro = "Erro ao carregar treino: ${e.message}"
                        )
                    }
                )
            } catch (e: Exception) {
                android.util.Log.e("CorridaVM", "❌ Erro ao carregar treino", e)
                _uiState.value = _uiState.value.copy(
                    erro = "Erro ao carregar treino: ${e.message}"
                )
            }
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Controle de Corrida
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    fun iniciarCorrida() {
        android.util.Log.d("CorridaVM", "▶️ Iniciando corrida")
        
        // Iniciar o service
        val intent = Intent(context, RunningService::class.java).apply {
            action = RunningService.ACTION_START
        }
        context.startForegroundService(intent)
        
        // Bind ao service para receber atualizações
        Intent(context, RunningService::class.java).also { bindIntent ->
            context.bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
        
        _uiState.value = _uiState.value.copy(fase = FaseCorrida.CORRENDO)
    }

    fun pausarCorrida() {
        android.util.Log.d("CorridaVM", "⏸️ Pausando corrida")
        
        val intent = Intent(context, RunningService::class.java).apply {
            action = RunningService.ACTION_PAUSE
        }
        context.startService(intent)
        
        _uiState.value = _uiState.value.copy(fase = FaseCorrida.PAUSADO)
    }

    fun retomarCorrida() {
        android.util.Log.d("CorridaVM", "▶️ Retomando corrida")
        
        val intent = Intent(context, RunningService::class.java).apply {
            action = RunningService.ACTION_RESUME
        }
        context.startService(intent)
        
        _uiState.value = _uiState.value.copy(fase = FaseCorrida.CORRENDO)
    }

    fun finalizarCorrida() {
        android.util.Log.d("CorridaVM", "⏹️ Finalizando corrida")
        
        val intent = Intent(context, RunningService::class.java).apply {
            action = RunningService.ACTION_STOP
        }
        context.startService(intent)
        
        _uiState.value = _uiState.value.copy(fase = FaseCorrida.FINALIZADO)
    }

    // Aliases para compatibilidade com CorridaScreen e ResumoScreen
    fun pausar() = pausarCorrida()
    fun retomar() = retomarCorrida()
    fun salvarCorrida() = salvarAtividade()

    /**
     * Recebe uma nova localização do GPS gerenciado pela própria tela (legado).
     * Com o RunningService ativo o GPS é gerenciado pelo service; este método
     * existe para manter compatibilidade com código da UI que ainda o chama.
     */
    fun onNovaLocalizacao(location: android.location.Location) {
        // O RunningService já processa a localização via seu próprio callback.
        // Aqui apenas atualizamos a posição visível na UI caso o service
        // ainda não tenha enviado o update.
        val ponto = LatLngPonto(
            lat = location.latitude,
            lng = location.longitude,
            alt = location.altitude,
            tempo = location.time,
            accuracy = location.accuracy
        )
        _uiState.value = _uiState.value.copy(posicaoAtual = ponto)
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Salvamento
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    fun salvarAtividade() {
        val state = _uiState.value
        
        // Validações
        if (state.distanciaMetros < 50) {
            _uiState.value = state.copy(
                salvamentoEstado = SalvamentoEstado.ERRO,
                erroSalvamento = "Distância muito curta: ${state.distanciaMetros.toInt()}m. Percorra pelo menos 50 metros."
            )
            return
        }
        
        if (state.tempoTotalSegundos < 30) {
            _uiState.value = state.copy(
                salvamentoEstado = SalvamentoEstado.ERRO,
                erroSalvamento = "Tempo muito curto: ${state.tempoTotalSegundos}s. Corra por pelo menos 30 segundos."
            )
            return
        }
        
        if (state.salvamentoEstado == SalvamentoEstado.SALVANDO) {
            return
        }

        android.util.Log.d("CorridaVM", "💾 Salvando atividade...")

        _uiState.value = state.copy(
            salvamentoEstado = SalvamentoEstado.SALVANDO,
            erroSalvamento = null
        )

        viewModelScope.launch {
            try {
                val apiKey = container.preferencesRepository.apiKey.first()
                val athleteId = container.preferencesRepository.athleteId.first()

                if (athleteId == null) {
                    _uiState.value = _uiState.value.copy(
                        salvamentoEstado = SalvamentoEstado.ERRO,
                        erroSalvamento = "ID do atleta não configurado"
                    )
                    return@launch
                }

                val repo = workoutRepo ?: container.createWorkoutRepository(apiKey ?: "")
                workoutRepo = repo

                val nomeAtividade = "Corrida RunApp - ${
                    java.time.LocalDateTime.now().format(
                        java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm")
                    )
                }"

                val result = repo.salvarAtividade(
                    context = context,
                    athleteId = athleteId,
                    nomeAtividade = nomeAtividade,
                    distanciaMetros = state.distanciaMetros,
                    tempoSegundos = state.tempoTotalSegundos,
                    paceMedia = state.paceMedia,
                    rota = state.rota
                )
                
                result.fold(
                    onSuccess = { arquivo ->
                        arquivoGpxSalvo = arquivo
                        _uiState.value = _uiState.value.copy(
                            salvamentoEstado = SalvamentoEstado.SALVO
                        )
                        android.util.Log.d("CorridaVM", "✅ GPX salvo: ${arquivo.absolutePath}")
                    },
                    onFailure = { e ->
                        android.util.Log.e("CorridaVM", "❌ Erro ao salvar", e)
                        _uiState.value = _uiState.value.copy(
                            salvamentoEstado = SalvamentoEstado.ERRO,
                            erroSalvamento = "Erro ao salvar: ${e.message}"
                        )
                    }
                )
            } catch (e: Exception) {
                android.util.Log.e("CorridaVM", "❌ Erro no salvamento", e)
                _uiState.value = _uiState.value.copy(
                    salvamentoEstado = SalvamentoEstado.ERRO,
                    erroSalvamento = "Erro: ${e.message}"
                )
            }
        }
    }

    fun uploadParaIntervals() {
        val arquivo = arquivoGpxSalvo ?: return

        _uiState.value = _uiState.value.copy(uploadEstado = UploadEstado.ENVIANDO)

        viewModelScope.launch {
            try {
                val apiKey = container.preferencesRepository.apiKey.first()
                val athleteId = container.preferencesRepository.athleteId.first() ?: return@launch

                val repo = workoutRepo ?: container.createWorkoutRepository(apiKey ?: "")
                
                val result = repo.uploadParaIntervals(athleteId, arquivo)
                result.fold(
                    onSuccess = {
                        _uiState.value = _uiState.value.copy(uploadEstado = UploadEstado.ENVIADO)
                        android.util.Log.d("CorridaVM", "✅ Upload concluído")
                    },
                    onFailure = { e ->
                        android.util.Log.e("CorridaVM", "❌ Erro no upload", e)
                        _uiState.value = _uiState.value.copy(
                            uploadEstado = UploadEstado.ERRO,
                            erroSalvamento = "Erro no upload: ${e.message}"
                        )
                    }
                )
            } catch (e: Exception) {
                android.util.Log.e("CorridaVM", "❌ Erro no upload", e)
                _uiState.value = _uiState.value.copy(
                    uploadEstado = UploadEstado.ERRO,
                    erroSalvamento = "Erro: ${e.message}"
                )
            }
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Utilitários
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun formatarTempo(segundos: Long): String {
        val h = segundos / 3600
        val m = (segundos % 3600) / 60
        val s = segundos % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Factory
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as RunApp
                val container = application.container
                CorridaViewModel(
                    context = application.applicationContext,
                    container = container
                )
            }
        }
    }
}
