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
import kotlinx.coroutines.delay
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
    private var ultimoPaceFeedback = 0L
    private val INTERVALO_PACE_FEEDBACK_MS = 20_000L
    private var tempoInicioPassoAtual = 0L   // tempo (segundos) em que o passo atual começou
    private var indexPassoAnunciado = -1     // evita reanunciar o mesmo passo
    
    // Service
    private var runningService: RunningService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RunningService.LocalBinder
            val s = binder.getService()
            runningService = s
            serviceBound = true

            android.util.Log.d("CorridaVM", "✅ Service conectado")

            // Restauração de estado: se o service já está rodando (app foi morto e reaberto)
            if (s.isCorrendo()) {
                val treinoRecuperado = s.getTreinoAtivo()
                val passosRecuperados = s.getPassosAtivos()
                val indexRecuperado  = s.getIndexPassoAtivo()

                if (treinoRecuperado != null && passosRecuperados.isNotEmpty()) {
                    android.util.Log.d("CorridaVM", "♻️ Restaurando treino: ${treinoRecuperado.name}")
                    // Sincroniza index para não reanunciar o passo em voz alta ao reconectar
                    indexPassoAnunciado = indexRecuperado
                    _uiState.value = _uiState.value.copy(
                        fase       = if (s.isPausado() || s.autoPausado.value) FaseCorrida.PAUSADO else FaseCorrida.CORRENDO,
                        treino     = treinoRecuperado,
                        passos     = passosRecuperados,
                        passoAtual = passosRecuperados.getOrNull(indexRecuperado.coerceAtLeast(0))
                    )
                }
            }

            // Observar dados do service (sempre, sessão nova ou restaurada)
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
        // Reconexão silenciosa com flag 0: tenta o bind mas NÃO cria o service se ele não existir.
        // - Service parado → bindService retorna false, zero RAM gasta, onServiceConnected não dispara.
        // - Service rodando → conecta e restaura o estado via onServiceConnected.
        // BIND_AUTO_CREATE fica reservado apenas para iniciarCorrida(), onde temos certeza
        // de que o usuário quer o service vivo.
        Intent(context, RunningService::class.java).also { intent ->
            context.bindService(intent, serviceConnection, 0)
        }
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
                atualizarProgressoPasso(tempo)
            }
        }
        
        viewModelScope.launch {
            service.paceAtual.collect { pace ->
                _uiState.value = _uiState.value.copy(paceAtual = pace)
                verificarFeedbackPace(pace)
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
            audioCoach.anunciarKm(kmPercorridos.toDouble(), _uiState.value.paceMedia)
            ultimoKmAnunciado = kmPercorridos
        }
    }

    private fun verificarFeedbackPace(paceAtual: String) {
        val state = _uiState.value
        if (state.fase != FaseCorrida.CORRENDO) return
        if (state.autoPausado) return

        // Sem pace real ainda (início da corrida), não avisa
        if (paceAtual == "--:--") return

        val passo = state.passoAtual ?: return
        if (passo.paceAlvoMin == "--:--" || passo.paceAlvoMax == "--:--") return

        // REGRA DE SILÊNCIO: Tiros curtos (<45s) não recebem feedback corretivo.
        // Entre o GPS estabilizar (~5s) e o corredor reagir fisicamente (~3s),
        // o tiro já passou da metade. O alerta só gera ruído e distração.
        if (passo.duracao < 45) return

        // Respeita intervalo mínimo entre avisos consecutivos
        val agora = System.currentTimeMillis()
        if (agora - ultimoPaceFeedback < INTERVALO_PACE_FEEDBACK_MS) return

        // Avisa se fora do alvo — enquanto continuar fora, avisa a cada 20s
        val avisouFora = audioCoach.anunciarPaceFeedback(paceAtual, passo.paceAlvoMin, passo.paceAlvoMax)
        if (avisouFora) ultimoPaceFeedback = agora
        // Se está dentro do alvo, ultimoPaceFeedback não é atualizado,
        // então na próxima verificação entra aqui sem esperar os 20s
    }

    private fun atualizarProgressoPasso(tempoTotal: Long) {
        val state = _uiState.value
        val passos = state.passos
        if (passos.isEmpty()) return

        // Descobrir qual passo está ativo com base no tempo acumulado
        var tempoAcumulado = 0L
        var indexAtivo = passos.lastIndex
        for (i in passos.indices) {
            val fim = tempoAcumulado + passos[i].duracao.toLong()
            if (tempoTotal < fim) {
                indexAtivo = i
                break
            }
            tempoAcumulado += passos[i].duracao.toLong()
        }

        val passo = passos[indexAtivo]
        val tempoInicioEstePasso = run {
            var acc = 0L
            for (i in 0 until indexAtivo) acc += passos[i].duracao.toLong()
            acc
        }
        val tempoNoPasso = (tempoTotal - tempoInicioEstePasso).coerceAtLeast(0)
        val duracao = passo.duracao.coerceAtLeast(1)
        val progresso = (tempoNoPasso.toFloat() / duracao).coerceIn(0f, 1f)
        val restante = (duracao - tempoNoPasso).coerceAtLeast(0).toInt()

        // Anunciar passo novo via áudio quando muda de passo
        if (indexAtivo != indexPassoAnunciado) {
            indexPassoAnunciado = indexAtivo
            ultimoPaceFeedback = 0L  // reseta aviso de pace ao mudar de passo
            audioCoach.anunciarPasso(passo.nome, passo.paceAlvoMax, passo.duracao)
            // Informar o service da duração e do index atual (persistência de estado)
            runningService?.setDuracaoPassoAtual(passo.duracao)
            runningService?.setIndexPassoAtivo(indexAtivo)
        }

        // Countdown adaptativo: hierarquia de alertas baseada na duração do passo
        audioCoach.anunciarUltimosSegundos(restante, passo.duracao)

        _uiState.value = state.copy(
            passoAtualIndex = indexAtivo,
            passoAtual = passo,
            progressoPasso = progresso,
            tempoPassoRestante = restante
        )
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Carregar Treino
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    fun carregarTreino(eventId: Long) {
        // PROTEÇÃO: Se a corrida já está ativa (dados restaurados do Service),
        // não faz chamada de rede — evita sobrescrever o estado válido com erro de internet.
        if (_uiState.value.fase != FaseCorrida.PREPARANDO) {
            android.util.Log.d("CorridaVM", "♻️ Pulando carregamento via rede: corrida já ativa (${_uiState.value.fase})")
            return
        }

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

        // Resetar controle de passos
        indexPassoAnunciado = -1
        ultimoKmAnunciado = 0
        ultimoPaceFeedback = 0L

        // Iniciar o service
        val intent = Intent(context, RunningService::class.java).apply {
            action = RunningService.ACTION_START
        }
        context.startForegroundService(intent)

        // Bind ao service para receber atualizações
        Intent(context, RunningService::class.java).also { bindIntent ->
            context.bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        // Enviar os dados do treino ao service assim que a conexão for estabelecida.
        // O service passa a ser o "dono" do treino — sobrevive à morte da ViewModel.
        viewModelScope.launch {
            while (runningService == null) { delay(100) }
            _uiState.value.treino?.let { treino ->
                runningService?.setDadosTreino(treino, _uiState.value.passos)
            }
        }

        _uiState.value = _uiState.value.copy(fase = FaseCorrida.CORRENDO)

        // Anunciar início e primeiro passo
        audioCoach.anunciarInicioCorrida()
        val primeiroPasso = _uiState.value.passos.firstOrNull()
        if (primeiroPasso != null) {
            viewModelScope.launch {
                kotlinx.coroutines.delay(2000) // espera o anúncio de início terminar
                audioCoach.anunciarPasso(primeiroPasso.nome, primeiroPasso.paceAlvoMax, primeiroPasso.duracao)
                indexPassoAnunciado = 0
                // Configurar a janela de pace para o primeiro passo imediatamente
                // (atualizarProgressoPasso só dispara na mudança de passo, não no início)
                runningService?.setDuracaoPassoAtual(primeiroPasso.duracao)
            }
        }
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

        // ⚠️ CRÍTICO: capturar os dados ANTES de parar o service
        // Quando o service para, os StateFlows são destruídos e os dados somem
        val service = runningService
        val distanciaFinal   = service?.distanciaMetros?.value  ?: _uiState.value.distanciaMetros
        val tempoFinal       = service?.tempoTotalSegundos?.value ?: _uiState.value.tempoTotalSegundos
        val paceAtualFinal   = service?.paceAtual?.value         ?: _uiState.value.paceAtual
        val paceMediaFinal   = service?.paceMedia?.value         ?: _uiState.value.paceMedia
        val rotaFinal        = service?.rotaAtual?.value         ?: _uiState.value.rota

        // Gravar snapshot no uiState ANTES de parar tudo
        _uiState.value = _uiState.value.copy(
            distanciaMetros   = distanciaFinal,
            tempoTotalSegundos = tempoFinal,
            tempoFormatado    = formatarTempo(tempoFinal),
            paceAtual         = paceAtualFinal,
            paceMedia         = paceMediaFinal,
            rota              = rotaFinal,
            fase              = FaseCorrida.FINALIZADO
        )

        // Agora sim pode parar o service
        val intent = Intent(context, RunningService::class.java).apply {
            action = RunningService.ACTION_STOP
        }
        context.startService(intent)

        // Desvincular do service
        if (serviceBound) {
            try {
                context.unbindService(serviceConnection)
                serviceBound = false
            } catch (e: Exception) {
                android.util.Log.e("CorridaVM", "Erro ao unbind service", e)
            }
        }
        runningService = null
    }

    // Aliases para compatibilidade com CorridaScreen e ResumoScreen
    fun pausar() = pausarCorrida()
    fun retomar() = retomarCorrida()
    fun salvarCorrida() = salvarAtividade()

    /**
     * Reseta completamente o estado para permitir iniciar uma nova corrida
     * sem precisar fechar o app. Deve ser chamado ao descartar ou ao voltar
     * ao início após concluir/salvar.
     */
    fun resetarCorrida() {
        android.util.Log.d("CorridaVM", "🔄 Resetando estado da corrida")

        // Garantir que o service está parado
        try {
            val intent = Intent(context, RunningService::class.java).apply {
                action = RunningService.ACTION_STOP
            }
            context.startService(intent)
        } catch (e: Exception) { /* service pode já ter parado */ }

        if (serviceBound) {
            try {
                context.unbindService(serviceConnection)
                serviceBound = false
            } catch (e: Exception) { /* ignorar */ }
        }
        runningService = null

        // Resetar contadores internos
        ultimoKmAnunciado = 0
        ultimoPaceFeedback = 0L
        indexPassoAnunciado = -1

        // Voltar ao estado inicial — preservando apenas treino e passos carregados
        val state = _uiState.value
        _uiState.value = CorridaUiState(
            treino = state.treino,
            passos = state.passos,
            passoAtual = state.passos.firstOrNull()
        )
    }

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
