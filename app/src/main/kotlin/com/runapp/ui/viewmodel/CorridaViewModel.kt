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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.runapp.RunApp
import com.runapp.AppContainer
import com.runapp.data.model.LatLngPonto
import com.runapp.data.model.PassoExecucao
import com.runapp.data.model.WorkoutEvent
import com.runapp.data.repository.WorkoutRepository
import com.runapp.service.AudioCoach
import com.runapp.service.RunningService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.runapp.data.db.RunDatabase
import com.runapp.util.DouglasPeucker

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
// Backup de emergência — serializado para disco logo ao parar a corrida.
// Garante que nenhum dado seja perdido mesmo se o app travar durante o save.
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

private data class BackupEmergencia(
    val distanciaMetros: Double,
    val tempoTotalSegundos: Long,
    val paceMedia: String,
    // v2: rota armazenada no Room (via sessionId). Campo mantido para retrocompatibilidade
    // com backups antigos que salvavam a lista inteira em JSON.
    val rota: List<LatLngPonto>? = null,
    // v2: ID da sessão Room para recuperar rota sem carregar JSON inteiro
    val sessionId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

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
    val cadencia: Int = 0,          // passos por minuto via acelerômetro
    
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
    
    // Carregando rota após recovery (Douglas-Peucker rodando em background)
    val carregandoRota: Boolean = false,

    // Erro
    val erro: String? = null,

    // Corrida livre (sem plano de treino — só rastreamento GPS)
    val corridaLivre: Boolean = false,

    // Distancia oficial pos-treino (suavizada, coincide com o GPX exportado).
    // Populada no momento do save — usada pela ResumoScreen para evitar que
    // o usuario veja um valor diferente do que estava no contador em tempo real.
    val distanciaFinalMetros: Double = 0.0
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

    // Rota completa sem simplificação Douglas-Peucker — usada para export GPX fiel.
    // A UI exibe rota simplificada (rotaParaDisplay) para performance do mapa.
    // O GPX exporta esta versão para precisão máxima na análise de treino.
    private var rotaCompleataParaExport: List<LatLngPonto>? = null
    private var paceZonesSalvas: List<com.runapp.data.model.PaceZone> = emptyList()

    // Gson para backup de emergência
    private val gson = Gson()

    // Banco Room — acessado via AppContainer (singleton compartilhado com o Service)
    private val runDatabase: RunDatabase by lazy {
        (context.applicationContext as RunApp).container.runDatabase
    }

    // Arquivo de backup de emergência no diretório privado do app
    // FIX: salvo em cache interno — não precisa de permissão de armazenamento externo
    private val backupFile: File
        get() = File(context.filesDir, "emergency_run_backup.json")

    // FIX 3: CompletableDeferred substitui o loop de 30 tentativas (repeat(30)).
    // Não tem tempo fixo — resolve exatamente quando onServiceConnected é chamado,
    // seja em 50ms ou em 2s, sem desperdiçar CPU em polling.
    // É cancelado automaticamente com o viewModelScope se o ViewModel for destruído.
    private val serviceConectadoDeferred = CompletableDeferred<RunningService>()
    
    // Audio Coach para alertas de voz
    private val audioCoach = AudioCoach(context)
    private var ultimoKmAnunciado = 0
    private var ultimoPaceFeedback = 0L
    private val INTERVALO_PACE_FEEDBACK_MS = 20_000L
    private var tempoInicioPassoAtual = 0L   // tempo (segundos) em que o passo atual começou

    // Preferência lida uma vez ao iniciar a corrida e mantida durante a sessão.
    // Não precisa ser reativa — mudar no meio de uma corrida não faz sentido de UX.
    private var gapTelemetriaReduzida = false
    private var indexPassoAnunciado = -1     // evita reanunciar o mesmo passo
    
    // Service
    private var runningService: RunningService? = null
    private var serviceBound = false
    // true = Android confirmou que o service existe e está conectando.
    // false = service não estava rodando (treino novo) — pula a espera ativa no carregarTreino.
    private var isBindingTentativo = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RunningService.LocalBinder
            val s = binder.getService()
            runningService = s
            serviceBound = true

            android.util.Log.d("CorridaVM", "✅ Service conectado")

            // FIX 3: Sinaliza o CompletableDeferred — carregarTreino() e iniciarCorrida()
            // estão esperando por este momento com await() sem fazer polling.
            serviceConectadoDeferred.complete(s)

            // Restauração de estado: se o service já está rodando (app foi morto e reaberto)
            if (s.isCorrendo()) {
                val treinoRecuperado = s.getTreinoAtivo()
                val passosRecuperados = s.getPassosAtivos()
                val indexRecuperado  = s.getIndexPassoAtivo()

                if (treinoRecuperado != null && passosRecuperados.isNotEmpty()) {
                    android.util.Log.d("CorridaVM", "♻️ Restaurando treino: ${treinoRecuperado.name}")
                    indexPassoAnunciado = indexRecuperado
                    // RESTAURAÇÃO IMEDIATA SEM ROTA: entrega métricas e posição no primeiro
                    // milissegundo, sem bloquear a main thread com D-P de 3000+ pontos.
                    // carregandoRota=true sinaliza ao mapa para mostrar um loading indicator.
                    _uiState.value = _uiState.value.copy(
                        fase               = if (s.isPausado() || s.autoPausado.value) FaseCorrida.PAUSADO else FaseCorrida.CORRENDO,
                        treino             = treinoRecuperado,
                        passos             = passosRecuperados,
                        passoAtual         = passosRecuperados.getOrNull(indexRecuperado.coerceAtLeast(0)),
                        posicaoAtual       = s.posicaoAtual.value,
                        distanciaMetros    = s.distanciaMetros.value,
                        tempoTotalSegundos = s.tempoTotalSegundos.value,
                        tempoFormatado     = formatarTempo(s.tempoTotalSegundos.value),
                        paceAtual          = s.paceAtual.value,
                        paceMedia          = s.paceMedia.value,
                        carregandoRota     = true
                    )
                    // FIX BUG 1: D-P de 3000+ pontos NUNCA pode rodar na main thread.
                    // onServiceConnected é invocado na main thread — > 16ms causa jank,
                    // > 5s causa ANR. Movemos para Dispatchers.Default.
                    viewModelScope.launch {
                        val rotaCompleta = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            s.getRotaCompleta()
                        }
                        val rotaSimplificada = withContext(kotlinx.coroutines.Dispatchers.Default) {
                            simplificarParaDisplay(rotaCompleta)
                        }
                        rotaCompleataParaExport = rotaCompleta
                        _uiState.value = _uiState.value.copy(
                            rota           = rotaSimplificada,
                            carregandoRota = false
                        )
                        android.util.Log.d("CorridaVM", "♻️ Rota restaurada: ${rotaCompleta.size} → ${rotaSimplificada.size} pontos")
                    }
                    android.util.Log.d("CorridaVM", "♻️ Estado recuperado do Service com sucesso")
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
        // O retorno boolean indica se o service estava rodando:
        //   true  → service existe, onServiceConnected vai disparar (espera ativa válida)
        //   false → service não existe, treino novo (pula a espera no carregarTreino)
        Intent(context, RunningService::class.java).also { intent ->
            isBindingTentativo = context.bindService(intent, serviceConnection, 0)
            android.util.Log.d("CorridaVM", "Bind silencioso: service ${if (isBindingTentativo) "encontrado ✅" else "não existe (treino novo)"}")
        }

        // FIX: Só verifica backup se NÃO há service rodando (isBindingTentativo = false).
        // Se o service está ativo (corrida em andamento), o backup de uma sessão anterior
        // seria stale e NUNCA deve sobrescrever o estado da corrida atual.
        // Isso elimina a race condition entre verificarBackupEmergencia e onServiceConnected.
        if (!isBindingTentativo) {
            verificarBackupEmergencia()
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
    // FIX: Backup e Recuperação de Emergência
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Salva imediatamente um backup JSON com todos os dados da corrida.
     * Chamado logo quando o usuário pressiona STOP, ANTES de qualquer I/O pesado.
     * Se o app travar depois, os dados são recuperados na próxima abertura.
     */
    private fun salvarBackupEmergencia(
        distancia: Double,
        tempo: Long,
        paceMedia: String,
        rota: List<LatLngPonto>,
        sessionId: String = ""
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val backup = BackupEmergencia(
                    distanciaMetros    = distancia,
                    tempoTotalSegundos = tempo,
                    paceMedia          = paceMedia,
                    // v2: não serializa rota no JSON — está no Room via sessionId
                    // v1 legacy: mantém rota como fallback se sessionId for vazio (backups antigos)
                    rota               = if (sessionId.isEmpty()) rota else null,
                    sessionId          = sessionId.ifEmpty { null }
                )
                // ESCRITA ATÔMICA: tmp → rename para evitar corrupção em crash
                val json = gson.toJson(backup)
                val tmpFile = File(context.filesDir, "emergency_run_backup.tmp")
                tmpFile.writeText(json)
                if (!tmpFile.renameTo(backupFile)) {
                    backupFile.writeText(json)
                    tmpFile.delete()
                }
                android.util.Log.d("CorridaVM", "🛡️ Backup atômico: ${distancia.toInt()}m, sessionId=${sessionId.take(8)}")
            } catch (e: Exception) {
                android.util.Log.e("CorridaVM", "⚠️ Falha ao salvar backup emergência", e)
            }
        }
    }

    /**
     * Verifica na inicialização se existe um backup de uma sessão que travou.
     *
     * FLUXO v2 (com Room):
     * 1. Lê metadados do JSON (rápido — arquivo < 1KB)
     * 2. Se tem sessionId: busca rota completa no Room (pode ter 2400+ pontos)
     * 3. Se não tem sessionId (backup antigo): usa rota do JSON como fallback
     * 4. Aplica Douglas-Peucker para exibição no mapa (evita ANR)
     * 5. Mantém rota COMPLETA no estado para export GPX fiel
     */
    private fun verificarBackupEmergencia() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!backupFile.exists()) return@launch

                val json = backupFile.readText()
                val backup = gson.fromJson(json, BackupEmergencia::class.java) ?: return@launch

                // Valida se o backup tem dados reais (ao menos 50m e 30s)
                if (backup.distanciaMetros < 50 || backup.tempoTotalSegundos < 30) {
                    backupFile.delete()
                    return@launch
                }

                // RECOVERY DA ROTA: preferir Room (sessionId) sobre JSON (campo legacy)
                val rotaRecuperada: List<LatLngPonto> = when {
                    backup.sessionId != null -> {
                        // v2: busca pontos no Room via sessionId
                        val pontos = runDatabase.routePointDao()
                            .getSessionPoints(backup.sessionId)
                            .map { it.toLatLngPonto() }
                        android.util.Log.w("CorridaVM", "🔄 Recovery via Room: ${pontos.size} pts, ${backup.distanciaMetros.toInt()}m")
                        pontos
                    }
                    backup.rota != null -> {
                        // v1 legacy: rota estava no JSON
                        android.util.Log.w("CorridaVM", "🔄 Recovery via JSON (legado): ${backup.rota.size} pts")
                        backup.rota
                    }
                    else -> {
                        backupFile.delete()
                        return@launch
                    }
                }

                if (rotaRecuperada.isEmpty()) {
                    android.util.Log.w("CorridaVM", "⚠️ Rota vazia no backup — descartando")
                    backupFile.delete()
                    return@launch
                }

                android.util.Log.d("CorridaVM", "✅ Recovery: ${rotaRecuperada.size} pontos GPS — simplificando para display...")

                // FASE 1: mostra métricas imediatamente (sem rota) com indicador de loading.
                // O usuário vê distância, tempo e pace enquanto D-P processa em background.
                // Isso substitui a "tela preta/paralisada" por uma UI responsiva.
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        fase               = FaseCorrida.FINALIZADO,
                        distanciaMetros    = backup.distanciaMetros,
                        tempoTotalSegundos = backup.tempoTotalSegundos,
                        tempoFormatado     = formatarTempo(backup.tempoTotalSegundos),
                        paceMedia          = backup.paceMedia,
                        rota               = emptyList(),   // mapa vazio enquanto processa
                        carregandoRota     = rotaRecuperada.size > 500,  // só mostra loading se vai demorar
                        salvamentoEstado   = SalvamentoEstado.NAO_SALVO
                    )
                }

                // FASE 2: Douglas-Peucker em Dispatchers.Default (CPU-bound, não bloqueia IO).
                // Com 2400 pontos (40min), reduz para ~200-400 pts em ~5-15ms.
                val rotaParaDisplay = if (rotaRecuperada.size > 500) {
                    withContext(kotlinx.coroutines.Dispatchers.Default) {
                        DouglasPeucker.simplify(rotaRecuperada, toleranceMeters = 5.0)
                            .also { android.util.Log.d("CorridaVM", "📐 D-P: ${rotaRecuperada.size} → ${it.size} pontos") }
                    }
                } else {
                    rotaRecuperada
                }

                // Guardar rota completa para export GPX (sem simplificação)
                rotaCompleataParaExport = rotaRecuperada

                // FASE 3: atualiza mapa e remove loading indicator
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        rota           = rotaParaDisplay,
                        carregandoRota = false
                    )
                }
                android.util.Log.d("CorridaVM", "✅ Recovery completo e mapa pronto")
            } catch (e: Exception) {
                android.util.Log.e("CorridaVM", "Erro ao ler backup emergência", e)
                // Backup corrompido — deleta para não travar a próxima sessão
                try { backupFile.delete() } catch (_: Exception) {}
            }
        }
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
            service.cadencia.collect { spm ->
                _uiState.value = _uiState.value.copy(cadencia = spm)
            }
        }
        
        viewModelScope.launch {
            // FIX BUG 2 + 3: quando a UI reconecta após process death, o StateFlow emite
            // imediatamente seu valor atual (potencialmente vazio/stale, pois o service parou
            // de emitir com tela bloqueada). Isso apagava a rota restaurada em onServiceConnected.
            //
            // Regras:
            // - Ignora emissões vazias (stale do StateFlow sem subscribers ativos)
            // - Aplica D-P quando a rota "pula" 50+ pontos (cenário de reconexão com rota grande)
            //   evitando que 3000+ pontos cheguem ao mapa de uma vez → OOM/crash
            // - Durante corrida normal (incremento de 5 pontos), passa direto sem D-P
            service.rotaAtual.collect { rota ->
                if (rota.isEmpty()) return@collect  // ignora stale vazio

                val rotaAtualNoUi = _uiState.value.rota
                val rotaParaDisplay = if (rota.size > rotaAtualNoUi.size + 50) {
                    // Salto grande = reconexão após process death → aplica D-P em background
                    withContext(kotlinx.coroutines.Dispatchers.Default) {
                        simplificarParaDisplay(rota)
                    }
                } else {
                    rota  // incremento normal durante corrida ativa
                }
                _uiState.value = _uiState.value.copy(rota = rotaParaDisplay)
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

        // ── Modo Montanha — aviso motivacional com GAP em subidas íngremes ────
        viewModelScope.launch {
            service.modoMontanha.collect { emSubida ->
                if (emSubida) {
                    // Pequeno delay para o GAP instantâneo estabilizar após entrar na subida
                    kotlinx.coroutines.delay(3_000)
                    val paceAtual = _uiState.value.paceAtual
                    val gapSegKm  = service.getGapAtualInstantaneo()
                    audioCoach.anunciarModoMontanha(paceAtual, gapSegKm)
                }
            }
        }

        // ── Descida Técnica — grade < -15% (paradoxo de Minetti) ─────────────
        // Filtro de persistência: exige 3 pontos GPS CONSECUTIVOS abaixo de -15%
        // antes de disparar o aviso. Um único ponto ruidoso de altitude (ex: GPS urbano
        // sob viaduto) pode gerar gradiente de -18% por 1s e voltar a -12% imediatamente.
        // Sem esse filtro, o corredor ouviria alertas falsos em trechos planos.
        // O debounce de 24s no AudioCoach garante que ladeiras longas não repitam o aviso.
        var pontosDescidaTecnicaConsecutivos = 0
        val PERSISTENCIA_DESCIDA_TECNICA = 3  // pontos GPS ~ 3 segundos em movimento

        viewModelScope.launch {
            service.gradienteAtual.collect { gradiente ->
                if (gradiente < -0.15) {
                    pontosDescidaTecnicaConsecutivos++
                    if (pontosDescidaTecnicaConsecutivos >= PERSISTENCIA_DESCIDA_TECNICA) {
                        audioCoach.anunciarDescidaTecnica()
                        // Após disparar, não zera — o throttle do AudioCoach (24s) evita spam.
                        // Manter o contador alto evita re-contar 3 pontos a cada janela de 24s.
                    }
                } else {
                    // Qualquer ponto fora do limiar quebra a sequência — garante que
                    // a ladeira é contínua, não uma série de picos isolados.
                    pontosDescidaTecnicaConsecutivos = 0
                }
            }
        }

        // NOTA: O backup periódico foi movido para o RunningService (salvarCheckpoint a cada 30s).
        // Isso é essencial porque o viewModelScope pode ser cancelado quando a Activity morre
        // durante corridas longas com tela bloqueada, enquanto o Service permanece vivo.
        // O ViewModel ainda faz backup imediato ao finalizar (finalizarCorrida) como segurança extra.
    }

    private fun verificarAnuncioDistancia(distanciaMetros: Double) {
        val kmPercorridos = (distanciaMetros / 1000).toInt()
        if (kmPercorridos > ultimoKmAnunciado && kmPercorridos > 0) {
            val service = runningService

            // Fechar o acumulador GAP do km que acabou de ser concluído.
            // `null` significa que o GPS foi insuficiente durante o km — usa anúncio simples.
            val gapResult  = service?.fecharEObterGapKm()
            val paceMedia  = _uiState.value.paceMedia

            if (gapResult != null) {
                val paceRealSegKm      = paceParaSegundos(paceMedia).toDouble()
                // Pace médio geral da corrida inteira (usado para avaliar eficiência na subida)
                val paceMediaGeralSegKm = paceParaSegundos(_uiState.value.paceMedia).toDouble()
                audioCoach.anunciarKmComGap(
                    distanciaKm           = kmPercorridos.toDouble(),
                    paceMedia             = paceMedia,
                    paceRealSegKm         = paceRealSegKm,
                    gapMedioSegKm         = gapResult.gapMedioSegKm,
                    gradienteMedio        = gapResult.gradienteMedio,
                    paceMediaGeralSegKm   = paceMediaGeralSegKm,
                    telemetriaReduzida    = gapTelemetriaReduzida
                )
            } else {
                // Fallback: GPS ruim o km todo, ou primeiros metros sem altitude
                audioCoach.anunciarKm(kmPercorridos.toDouble(), paceMedia)
            }

            ultimoKmAnunciado = kmPercorridos
        }
    }

    /** Converte "M:SS" → segundos totais. Retorna 0 se inválido. */
    private fun paceParaSegundos(pace: String): Int {
        if (pace == "--:--") return 0
        val partes = pace.split(":")
        if (partes.size != 2) return 0
        return (partes[0].toIntOrNull() ?: 0) * 60 + (partes[1].toIntOrNull() ?: 0)
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
        // Corrida livre: sem plano de treino, apenas rastreamento GPS.
        if (eventId == 0L) {
            android.util.Log.d("CorridaVM", "🏃 Corrida livre — sem treino a carregar.")
            _uiState.value = _uiState.value.copy(treino = null, erro = null, corridaLivre = true)
            return
        }
        viewModelScope.launch {
            android.util.Log.d("CorridaVM", "⏱️ carregarTreino($eventId) iniciou. isBindingTentativo=$isBindingTentativo")

            // ESPERA ATIVA: verifica o service a cada 100ms enquanto ele ainda não conectou.
            // Checa na ENTRADA de cada ciclo, não após o delay — assim captura o service
            // imediatamente quando o bind completa, sem esperar o próximo tick.
            // Só entra no loop se o Android confirmou que o service existe (isBindingTentativo=true).
            if (isBindingTentativo) {
                // FIX 3: await() suspende a coroutine até onServiceConnected ser chamado.
            // Não tem loop fixo, não usa CPU enquanto espera, e resolve instantaneamente
            // se o service já estava conectado antes de carregarTreino() ser chamado.
            // withTimeoutOrNull(5000) protege contra o caso raro em que o service nunca
            // conecta (processo morto, Android agindo estranhamente): após 5s, segue
            // para a busca na rede como se não houvesse service rodando.
            val serviceRecuperado = kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                serviceConectadoDeferred.await()
            }
            val s = serviceRecuperado
            if (s != null && s.isCorrendo()) {
                val treinoNoService = s.getTreinoAtivo()
                if (treinoNoService != null) {
                    android.util.Log.d("CorridaVM", "✅ Treino ${treinoNoService.id} recuperado via Deferred.")
                    val passos = s.getPassosAtivos()
                    val indexAtual = s.getIndexPassoAtivo()
                    _uiState.value = _uiState.value.copy(
                        treino       = treinoNoService,
                        passos       = passos,
                        passoAtual   = passos.getOrNull(indexAtual.coerceAtLeast(0)),
                        fase         = if (s.isPausado() || s.autoPausado.value) FaseCorrida.PAUSADO else FaseCorrida.CORRENDO,
                        posicaoAtual = s.posicaoAtual.value,
                        // FIX: rotaAtual.value está stale (service parou de emitir com tela bloqueada).
                        // getRotaCompleta() lê diretamente a lista em memória — sempre atualizada.
                        rota         = s.getRotaCompleta(),
                        erro         = null
                    )
                    indexPassoAnunciado = indexAtual
                    return@launch
                }
            }
            }

            // Se chegou aqui: corrida não está ativa — é um treino novo. Vai para a rede.
            if (_uiState.value.fase != FaseCorrida.PREPARANDO) return@launch
            android.util.Log.d("CorridaVM", "🌐 Buscando treino via rede (Intervals.icu)...")
            try {
                val apiKey   = container.preferencesRepository.apiKey.first()
                val athleteId = container.preferencesRepository.athleteId.first()

                if (athleteId == null) {
                    _uiState.value = _uiState.value.copy(erro = "ID do atleta não configurado")
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
                        paceZonesSalvas = paceZones  // guarda para usar no save da corrida

                        // Persiste no DataStore para sobreviver a process death e
                        // ficar disponível no dashboard sem nova chamada à API.
                        if (paceZones.isNotEmpty()) {
                            val zonasFronteira = paceZones.map { z ->
                                com.runapp.data.model.ZonaFronteira(
                                    nome         = z.name,
                                    cor          = z.color ?: "",
                                    paceMinSegKm = (z.min ?: 0.0) * 1000.0,
                                    paceMaxSegKm = z.max?.let { m -> m * 1000.0 }
                                )
                            }
                            container.preferencesRepository.salvarZonasFronteira(zonasFronteira)
                        }

                        val passosProcessados = repo.converterParaPassos(evento, paceZones)
                        _uiState.value = _uiState.value.copy(
                            treino     = evento,
                            passos     = passosProcessados,
                            passoAtual = passosProcessados.firstOrNull(),
                            erro       = null
                        )
                        android.util.Log.d("CorridaVM", "✅ Treino carregado: ${evento.name}")
                    },
                    onFailure = { e ->
                        // Se o service conectou enquanto a rede falhava, ignora o erro
                        if (_uiState.value.treino == null) {
                            android.util.Log.e("CorridaVM", "❌ Erro de rede: ${e.message}")
                            _uiState.value = _uiState.value.copy(erro = "Sem conexão. Verifique a internet e tente novamente.")
                        } else {
                            android.util.Log.w("CorridaVM", "⚠️ Erro de rede ignorado — service já restaurou o treino")
                        }
                    }
                )
            } catch (e: Exception) {
                if (_uiState.value.treino == null) {
                    android.util.Log.e("CorridaVM", "❌ Erro ao carregar treino", e)
                    _uiState.value = _uiState.value.copy(erro = "Erro ao carregar: ${e.message}")
                }
            }
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Controle de Corrida
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    fun iniciarCorrida() {
        android.util.Log.d("CorridaVM", "▶️ Iniciando corrida")

        // Resetar controle de passos
        // indexPassoAnunciado começa em 0 (não -1) para evitar que atualizarProgressoPasso
        // detecte "novo passo" no index 0 e duplique o áudio com o anunciarInicioCorrida().
        indexPassoAnunciado = 0
        ultimoKmAnunciado = 0
        ultimoPaceFeedback = 0L

        // Ler preferências de corrida uma única vez no início da sessão.
        // Não faz sentido alterar mid-run: o corredor está com fone na orelha, não no telefone.
        viewModelScope.launch {
            gapTelemetriaReduzida = container.preferencesRepository.gapTelemetriaReduzida.first()
            android.util.Log.d("CorridaVM", "⚙️ GAP Telemetria Reduzida: $gapTelemetriaReduzida")
        }

        // Iniciar o service
        val intent = Intent(context, RunningService::class.java).apply {
            action = RunningService.ACTION_START
        }
        context.startForegroundService(intent)

        // Bind ao service para receber atualizações
        Intent(context, RunningService::class.java).also { bindIntent ->
            context.bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        // FIX 3: await() substitui o loop while(runningService == null) { delay(100) }.
        // Resolve imediatamente quando onServiceConnected for chamado, sem polling.
        viewModelScope.launch {
            val s = kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                serviceConectadoDeferred.await()
            } ?: return@launch
            _uiState.value.treino?.let { treino ->
                s.setDadosTreino(treino, _uiState.value.passos)
            }
        }

        _uiState.value = _uiState.value.copy(fase = FaseCorrida.CORRENDO)

        // Anunciar início e primeiro passo.
        // indexPassoAnunciado já está em 0, então atualizarProgressoPasso não vai
        // re-anunciar o passo 0 quando o primeiro tick do service chegar.
        audioCoach.anunciarInicioCorrida()
        val primeiroPasso = _uiState.value.passos.firstOrNull()
        if (primeiroPasso != null) {
            viewModelScope.launch {
                kotlinx.coroutines.delay(2000) // espera o anúncio de início terminar
                audioCoach.anunciarPasso(primeiroPasso.nome, primeiroPasso.paceAlvoMax, primeiroPasso.duracao)
                // Configurar a janela de pace para o primeiro passo imediatamente
                // (atualizarProgressoPasso só dispara na mudança de passo, não no início)
                runningService?.setDuracaoPassoAtual(primeiroPasso.duracao)
                runningService?.setIndexPassoAtivo(0)
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
        val rotaCompletaFinal = service?.getRotaCompleta()         ?: _uiState.value.rota
        val rotaFinal         = simplificarParaDisplay(rotaCompletaFinal)
        // Guarda rota completa para GPX export — o mapa usa a simplificada
        rotaCompleataParaExport = rotaCompletaFinal

        // FIX: Salvar backup de emergência IMEDIATAMENTE, antes de qualquer outra operação.
        // Isso garante que, mesmo que o app trave durante o salvamento normal, os dados
        // da corrida estão persistidos em disco e serão recuperados na próxima abertura.
        if (distanciaFinal >= 50 && tempoFinal >= 30) {
            // Passa sessionId para que o backup use Room (v2) em vez de JSON com rota
            val sid = service?.getSessionId() ?: ""
            salvarBackupEmergencia(distanciaFinal, tempoFinal, paceMediaFinal, rotaCompletaFinal, sid)
        }

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

        // Antes de parar o service, forçar emissão da rota completa final
        // (pode haver até 4 pontos que não foram emitidos pela otimização de 5-em-5)
        service?.let { s ->
            val rotaCompleta = s.rotaAtual.value
            if (rotaCompleta.size != rotaFinal.size) {
                _uiState.value = _uiState.value.copy(rota = rotaCompleta)
            }
        }

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

    // Navegação de passos — delega ao Service que controla o cronômetro oficial.
    // O atualizarProgressoPasso detectará a mudança de index e anunciará o novo passo via áudio.
    fun pularPasso() { runningService?.pularPasso() }
    fun voltarPasso() { runningService?.voltarPasso() }
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

        // Deleta backup de emergência E dados do Room ao descartar intencionalmente.
        // Sem isso, na próxima abertura o app tentaria "restaurar" uma corrida descartada.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (backupFile.exists()) backupFile.delete()
                File(context.filesDir, "emergency_run_backup.tmp").delete()
                // Limpa também dados do Room para não deixar sessões órfãs
                val latestSession = runDatabase.routePointDao().getLatestSessionId()
                if (latestSession != null) {
                    runDatabase.routePointDao().deleteSession(latestSession)
                }
            } catch (_: Exception) {}
        }

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

        // FIX CRÍTICO: usar Dispatchers.IO para todo o bloco de salvamento.
        //
        // O bug original usava viewModelScope.launch { } sem dispatcher, o que faz a
        // coroutine rodar na Dispatchers.Main (thread principal da UI).
        // Para uma corrida de 40min (~2400 pontos GPS), o processamento de elevação,
        // cálculo de splits, geração do XML GPX e escrita em disco na thread principal
        // dispara um ANR (Application Not Responding) em 5 segundos → Android mata o
        // app → todos os dados da corrida são perdidos.
        //
        // Com Dispatchers.IO, todo o I/O e processamento pesado acontece em background.
        // As atualizações de _uiState.value são thread-safe com MutableStateFlow.
        viewModelScope.launch(Dispatchers.IO) {
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

                // FIX: usa os dados do uiState atual, que podem ter sido restaurados
                // do backup de emergência caso o app tenha sido morto anteriormente.
                // estar vazio se: (a) o ViewModel foi recriado após process death entre
                // correr e salvar, (b) a corrida foi iniciada sem treino estruturado.
                // Busca as zonas agora se ainda não foram carregadas — é uma chamada leve
                // (apenas metadados do perfil, não pontos GPS) e garante que o gráfico
                // de zonas sempre aparece no histórico independente do fluxo seguido.
                if (paceZonesSalvas.isEmpty()) {
                    // 1ª tentativa: lê do cache local (DataStore) — sem rede, instantâneo.
                    // Garante zonas mesmo com process death ou corrida livre.
                    val cachedZonas = container.preferencesRepository.getZonasFronteiraCached()
                    if (cachedZonas.isNotEmpty()) {
                        // Reconstrói PaceZone a partir do cache (s/km → s/m)
                        paceZonesSalvas = cachedZonas.mapIndexed { i, z ->
                            com.runapp.data.model.PaceZone(
                                id    = i + 1,
                                name  = z.nome,
                                min   = z.paceMinSegKm / 1000.0,
                                max   = z.paceMaxSegKm?.let { it / 1000.0 },
                                color = z.cor.ifEmpty { null }
                            )
                        }
                        android.util.Log.d("CorridaVM", "✅ Zonas lidas do cache: ${paceZonesSalvas.size} zonas")
                    } else {
                        // 2ª tentativa: busca na API (requer rede)
                        val zonasResult = runCatching { repo.getZonas(athleteId) }
                        zonasResult.getOrNull()?.fold(
                            onSuccess = { zonesResponse ->
                                paceZonesSalvas = repo.processarZonas(zonesResponse)
                                android.util.Log.d("CorridaVM", "✅ Zonas buscadas da API no save: ${paceZonesSalvas.size} zonas")
                                // Aproveita para popular o cache
                                if (paceZonesSalvas.isNotEmpty()) {
                                    val zonasFronteira = paceZonesSalvas.map { z ->
                                        com.runapp.data.model.ZonaFronteira(
                                            nome         = z.name,
                                            cor          = z.color ?: "",
                                            paceMinSegKm = (z.min ?: 0.0) * 1000.0,
                                            paceMaxSegKm = z.max?.let { m -> m * 1000.0 }
                                        )
                                    }
                                    container.preferencesRepository.salvarZonasFronteira(zonasFronteira)
                                }
                            },
                            onFailure = {
                                android.util.Log.w("CorridaVM", "⚠️ Zonas indisponíveis (sem cache e sem rede)")
                            }
                        )
                    }
                }

                // FIX: usa os dados do uiState atual, que podem ter sido restaurados
                // do backup de emergência caso o app tenha sido morto anteriormente.
                val stateAtual = _uiState.value

                // Nome da atividade: usa o nome do treino importado + data,
                // ou "Corrida Livre" quando não havia plano vinculado.
                val dataFormatada = java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")
                )
                val nomeAtividade = if (stateAtual.treino?.name?.isNotBlank() == true) {
                    "${stateAtual.treino.name} — $dataFormatada"
                } else {
                    "Corrida Livre — $dataFormatada"
                }

                val rotaParaExport = rotaCompleataParaExport ?: stateAtual.rota

                // ── Dados para o Coach ──────────────────────────────────────────────
                // Captura o stepLength ANTES do save — o service ainda está ligado aqui.
                val stepLengthBaseline = runningService?.getStepLengthAprendido() ?: 0.0

                // Serializa o treino planeado de forma compacta (PassoResumo sem estado de UI).
                val treinoNome = stateAtual.treino?.name
                val treinoPassosJson = if (stateAtual.passos.isNotEmpty()) {
                    val resumos = stateAtual.passos.map { p ->
                        com.runapp.data.model.PassoResumo(
                            nome            = p.nome,
                            duracaoSegundos = p.duracao,
                            paceAlvoMin     = p.paceAlvoMin,
                            paceAlvoMax     = p.paceAlvoMax
                        )
                    }
                    com.google.gson.Gson().toJson(resumos)
                } else null

                val result = repo.salvarAtividade(
                    context            = context,
                    athleteId          = athleteId,
                    nomeAtividade      = nomeAtividade,
                    distanciaMetros    = stateAtual.distanciaMetros,
                    tempoSegundos      = stateAtual.tempoTotalSegundos,
                    paceMedia          = stateAtual.paceMedia,
                    rota               = rotaParaExport,
                    paceZones          = paceZonesSalvas,
                    stepLengthBaseline = stepLengthBaseline,
                    treinoNome         = treinoNome,
                    treinoPassosJson   = treinoPassosJson
                )
                
                result.fold(
                    onSuccess = { resultado ->
                        arquivoGpxSalvo = resultado.arquivo
                        _uiState.value = _uiState.value.copy(
                            salvamentoEstado = SalvamentoEstado.SALVO,
                            // Distancia final (suavizada) — mostrar na ResumoScreen
                            // para que bata com o valor que Intervals/Strava vao exibir.
                            distanciaFinalMetros = resultado.distanciaFinalMetros
                        )
                        // FIX: Só deleta o backup após confirmar que o save foi bem sucedido.
                        // Isso garante que um save parcial não apague o backup prematuramente.
                        try { backupFile.delete() } catch (_: Exception) {}
                        android.util.Log.d("CorridaVM", "✅ GPX salvo: ${arquivo.absolutePath}")
                    },
                    onFailure = { e ->
                        android.util.Log.e("CorridaVM", "❌ Erro ao salvar", e)
                        _uiState.value = _uiState.value.copy(
                            salvamentoEstado = SalvamentoEstado.ERRO,
                            erroSalvamento = "Erro ao salvar: ${e.message}"
                        )
                        // Backup permanece em disco — o usuário pode tentar salvar novamente
                    }
                )
            } catch (e: Exception) {
                android.util.Log.e("CorridaVM", "❌ Erro no salvamento", e)
                _uiState.value = _uiState.value.copy(
                    salvamentoEstado = SalvamentoEstado.ERRO,
                    erroSalvamento = "Erro: ${e.message}"
                )
                // Backup permanece em disco — o usuário pode tentar salvar novamente
            }
        }
    }

    fun uploadParaIntervals() {
        val arquivo = arquivoGpxSalvo ?: return

        _uiState.value = _uiState.value.copy(uploadEstado = UploadEstado.ENVIANDO)

        viewModelScope.launch(Dispatchers.IO) {
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

    /**
     * Aplica Douglas-Peucker para display no mapa.
     *
     * Rotas grandes (> 500 pontos) são simplificadas para evitar ANR ao renderizar.
     * O threshold de 500 pontos cobre ~8min de corrida — abaixo disso, a simplificação
     * seria perceptível visualmente; acima, o ganho de performance justifica.
     *
     * @param rota Rota completa com todos os pontos GPS
     * @return Rota simplificada para exibição (ou original se pequena o suficiente)
     */
    private fun simplificarParaDisplay(rota: List<LatLngPonto>): List<LatLngPonto> {
        return if (rota.size > 500) {
            DouglasPeucker.simplify(rota, toleranceMeters = 5.0)
                .also { android.util.Log.d("CorridaVM", "📐 D-P mapa: ${rota.size} → ${it.size} pontos") }
        } else {
            rota
        }
    }

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
