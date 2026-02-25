package com.runapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.runapp.MainActivity
import com.runapp.data.datastore.PreferencesRepository
import com.runapp.data.datastore.dataStore
import com.runapp.data.model.LatLngPonto
import com.runapp.data.model.PassoExecucao
import com.runapp.data.model.WorkoutEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import android.os.Build
import android.content.pm.ServiceInfo
import android.os.SystemClock
import com.google.gson.Gson
import com.runapp.data.db.RoutePointEntity
import com.runapp.data.db.RunDatabase
import java.io.File
import java.util.UUID

/**
 * Foreground Service para rastreamento GPS contínuo, mesmo com tela bloqueada.
 * 
 * IMPORTANTE: Este serviço roda independentemente do ciclo de vida das Activities.
 * Ele mantém o GPS ativo e processa todos os cálculos de pace, distância, etc.
 */
class RunningService : Service(), SensorEventListener {

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Checkpoint em disco — sobrevive à morte do processo
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    // Mesmo arquivo que o ViewModel lê ao restaurar — mantém compatibilidade.
    // Salvo a cada 30s pelo Service, que é o único componente que sobrevive
    // enquanto o processo estiver vivo (o ViewModel pode morrer com a Activity).
    private val checkpointFile: File
        get() = File(applicationContext.filesDir, "emergency_run_backup.json")

    private val gson = Gson()

    // Banco de dados Room — injetado via AppContainer na inicialização do Service.
    // Inserção de pontos GPS é não-bloqueante (Dispatchers.IO no serviceScope).
    private lateinit var database: RunDatabase

    // ID único da sessão atual — gerado no início de cada corrida.
    // Usado como chave primária no banco e no checkpoint JSON para recovery.
    private var sessionId: String = ""

    /**
     * Checkpoint LEVE: apenas metadados (sem rota — rota está no Room).
     * Economiza tempo de escrita e evita arquivos JSON de vários MB.
     *
     * Compatibilidade: o ViewModel lê o mesmo arquivo, mas o campo "rota"
     * agora pode ser null — ele consulta o Room via sessionId se necessário.
     */
    private data class CheckpointData(
        val sessionId: String,
        val distanciaMetros: Double,
        val tempoTotalSegundos: Long,
        val paceMedia: String,
        // WALL CLOCK: apenas para exibir "Corrida iniciada às 08:00"
        val timestampInicioWall: Long,
        // ELAPSED REALTIME: âncora monotônica imune a NTP/DST — para o cronômetro
        val elapsedRealtimeInicio: Long,
        val tempoPausadoTotalMs: Long,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Salva checkpoint de metadados de forma ATÔMICA.
     *
     * ATOMICIDADE: escreve em arquivo .tmp e depois faz rename().
     * Em Linux (que é o kernel do Android), rename() é uma operação atômica
     * do filesystem — nunca gera arquivo parcialmente escrito, mesmo se o
     * processo morrer no meio da operação.
     *
     * O arquivo contem apenas metadados (sem rota). A rota está no Room.
     * Uma corrida de 2h gera <1KB de checkpoint vs ~500KB com rota em JSON.
     */
    private fun salvarCheckpoint() {
        if (!estaCorrendo || _distanciaMetros.value < 10.0) return

        serviceScope.launch(Dispatchers.IO) {
            try {
                val checkpoint = CheckpointData(
                    sessionId             = sessionId,
                    distanciaMetros       = _distanciaMetros.value,
                    tempoTotalSegundos    = _tempoTotalSegundos.value,
                    paceMedia             = _paceMedia.value,
                    timestampInicioWall   = timestampInicioWall,
                    elapsedRealtimeInicio = elapsedRealtimeInicio,
                    tempoPausadoTotalMs   = tempoPausadoTotalMs
                )
                val json = gson.toJson(checkpoint)
                // ESCRITA ATÔMICA: tmp → rename (nunca corrompe o arquivo de backup)
                val tmpFile = File(applicationContext.filesDir, "emergency_run_backup.tmp")
                tmpFile.writeText(json)
                if (!tmpFile.renameTo(checkpointFile)) {
                    // Fallback: rename falha apenas entre partições diferentes (raro no Android)
                    checkpointFile.writeText(json)
                    tmpFile.delete()
                }
                Log.d(TAG, "💾 Checkpoint atômico: ${_distanciaMetros.value.toInt()}m (Room: ${rota.size} pts)")
            } catch (e: Exception) {
                Log.e(TAG, "⚠️ Falha ao salvar checkpoint", e)
            }
        }
    }

    /**
     * Remove checkpoint E dados do Room ao terminar normalmente.
     * Sem isso, na próxima abertura o ViewModel restauraria uma sessão antiga.
     */
    private fun deletarCheckpoint() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                checkpointFile.delete()
                File(applicationContext.filesDir, "emergency_run_backup.tmp").delete()
                // Deleta também dados do Room desta sessão — já foram salvos no servidor
                if (sessionId.isNotEmpty()) {
                    database.routePointDao().deleteSession(sessionId)
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Lê checkpoint JSON de forma síncrona (para uso no recovery via START_STICKY).
     * Retorna null se não existe ou se está corrompido.
     */
    private fun lerCheckpointSync(): CheckpointData? {
        return try {
            if (!checkpointFile.exists()) null
            else gson.fromJson(checkpointFile.readText(), CheckpointData::class.java)
        } catch (_: Exception) { null }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Coroutines e Lifecycle
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    // Scope próprio do serviço - NÃO usa viewModelScope
    // FIX: SupervisorJob permite cancelar o scope todo no onDestroy sem afetar coroutines irmãs.
    // O Job() original nunca era cancelado, causando um pequeno leak de coroutines.
    private val serviceJob = kotlinx.coroutines.SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var timerJob: Job? = null
    
    // WakeLock para manter CPU parcialmente ativa
    private var wakeLock: PowerManager.WakeLock? = null

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // GPS
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    
    // Dados de rastreamento
    private val rota = mutableListOf<LatLngPonto>()
    private val ultimasLocalizacoes = mutableListOf<Location>()

    // Janela adaptativa: ajustada dinamicamente pelo ViewModel conforme duração do passo
    // Passo curto (<60s) → 5s | Passo longo → 12s
    private var janelaAtualSegundos = 12
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Custódia do Treino — sobrevive à morte da ViewModel
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private var treinoAtivo: WorkoutEvent? = null
    private var passosAtivos: List<PassoExecucao> = emptyList()
    private var indexPassoAtivo: Int = -1  // para não reanunciar ao reconectar

    fun setDadosTreino(treino: WorkoutEvent, passos: List<PassoExecucao>) {
        treinoAtivo = treino
        passosAtivos = passos
        Log.d(TAG, "📋 Treino salvo no service: ${treino.name} (${passos.size} passos)")
        // CRÍTICO: Atualiza a notificação imediatamente para que o Intent contenha
        // o eventId correto. Sem isso, a notificação criada antes do setDadosTreino
        // carregava id=-1 e o clique nela não conseguia navegar para a corrida.
        atualizarNotificacao()
    }

    fun setIndexPassoAtivo(index: Int) { indexPassoAtivo = index }
    fun getTreinoAtivo(): WorkoutEvent? = treinoAtivo
    fun getPassosAtivos(): List<PassoExecucao> = passosAtivos
    fun getIndexPassoAtivo(): Int = indexPassoAtivo
    fun isCorrendo(): Boolean = estaCorrendo
    fun isPausado(): Boolean = estaPausado
    /** Retorna snapshot completo da rota — usado pelo ViewModel ao reconectar (sem depender do StateFlow). */
    fun getRotaCompleta(): List<LatLngPonto> = rota.toList()
    /** Retorna o sessionId da corrida ativa — usado pelo ViewModel para queries no Room. */
    fun getSessionId(): String = sessionId

    // Teletransporta o cronômetro para o início do próximo passo.
    // O ViewModel detectará a mudança de index via atualizarProgressoPasso e anunciará o passo.
    fun pularPasso() {
        if (passosAtivos.isEmpty()) return
        val indexAtual = indexPassoAtivo.coerceAtLeast(0)
        if (indexAtual >= passosAtivos.lastIndex) return // já no último passo

        // Debounce: ignora cliques com menos de 1s de intervalo
        val agora = System.currentTimeMillis()
        if (agora - ultimoCliquePasso < 1000L) return
        ultimoCliquePasso = agora

        var tempoDestino = 0L
        for (i in 0..indexAtual) tempoDestino += passosAtivos[i].duracao

        // CORREÇÃO CRÍTICA DO TIMER: o timerJob recalcula o tempo a cada segundo usando
        // (System.currentTimeMillis() - timestampInicio - tempoPausadoTotal).
        // Se apenas atribuirmos _tempoTotalSegundos.value, o próximo tick desfaz o pulo.
        // A solução é ajustar tempoPausadoTotal para que a fórmula produza tempoDestino.
        // Prova: tempoDestino = (agora - timestampInicio - novoPausado) / 1000
        //        novoPausado = agora - timestampInicio - (tempoDestino * 1000)
        val delta = (tempoDestino - _tempoTotalSegundos.value) * 1000L
        tempoPausadoTotal -= delta
        _tempoTotalSegundos.value = tempoDestino

        vibrar()
        Log.d(TAG, "⏭️ Passo ${indexAtual} → ${indexAtual + 1} | tempo → ${tempoDestino}s | delta=${delta}ms")
    }

    // Teletransporta o cronômetro para o início do passo anterior (ou reinicia o atual).
    fun voltarPasso() {
        if (passosAtivos.isEmpty()) return
        val indexAtual = indexPassoAtivo.coerceAtLeast(0)

        // Debounce: ignora cliques com menos de 1s de intervalo
        val agora = System.currentTimeMillis()
        if (agora - ultimoCliquePasso < 1000L) return
        ultimoCliquePasso = agora

        // Se estiver nos primeiros 3s do passo atual, vai para o anterior; senão reinicia o atual
        val tempoInicioAtual = passosAtivos.take(indexAtual).sumOf { it.duracao.toLong() }
        val tempoNoPasso = _tempoTotalSegundos.value - tempoInicioAtual

        val tempoDestino = if (tempoNoPasso > 3 || indexAtual == 0) {
            tempoInicioAtual // reinicia o passo atual
        } else {
            passosAtivos.take(indexAtual - 1).sumOf { it.duracao.toLong() } // passo anterior
        }

        // CORREÇÃO CRÍTICA DO TIMER: mesmo raciocínio do pularPasso
        val delta = (tempoDestino - _tempoTotalSegundos.value) * 1000L
        tempoPausadoTotal -= delta
        _tempoTotalSegundos.value = tempoDestino

        vibrar()
        Log.d(TAG, "⏮️ Voltando passo | tempo → ${tempoDestino}s | delta=${delta}ms")
    }

    private fun vibrar() {
        try {
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createOneShot(60L, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            Log.w(TAG, "Vibração não disponível: ${e.message}")
        }
    }

    fun setDuracaoPassoAtual(duracaoSegundos: Int) {
        janelaAtualSegundos = if (duracaoSegundos < 60) 5 else 12
        Log.d(TAG, "⚙️ Janela de pace ajustada para ${janelaAtualSegundos}s (passo=${duracaoSegundos}s)")
    }

    // EMA (Média Móvel Exponencial) — alias para compatibilidade interna
    // Use ultimoPaceEmaInterno diretamente em todo o código novo
    private var ultimoPaceEma: Double?
        get() = ultimoPaceEmaInterno
        set(value) { ultimoPaceEmaInterno = value }
    
    // Timestamps — DOIS conjuntos por design intencional:
    // *Wall clock* (currentTimeMillis): para exibir horário de início ("às 08:00")
    // *ElapsedRealtime* (SystemClock): monotônico, imune a NTP/fuso/DST — para duração
    private var timestampInicioWall: Long = 0
    private var elapsedRealtimeInicio: Long = 0
    private var elapsedRealtimePausaInicio: Long = 0
    private var tempoPausadoTotalMs: Long = 0
    // Alias para compatibilidade com código que usa timestampInicio (GPS, window etc.)
    private var timestampInicio: Long
        get() = timestampInicioWall
        set(value) { timestampInicioWall = value }
    private var tempoPausadoTotal: Long
        get() = tempoPausadoTotalMs
        set(value) { tempoPausadoTotalMs = value }
    private var ultimoCliquePasso: Long = 0L  // debounce para pularPasso/voltarPasso
    
    // ── GPS Cold Start (salto inicial após recovery) ──────────────────────────
    // Após recuperar de process death, o primeiro ping GPS pode vir de uma torre
    // de celular (500m+ de distância) antes de o chip adquirir satélites de verdade.
    // Mantemos um contador de pontos "suspeitos" a ignorar logo após o recovery.
    private var modoRecuperacaoGps = false
    private var contadorPontosRecuperacao = 0
    private val MAX_VELOCIDADE_HUMANA_MS = 11.0  // ~40 km/h — cobre sprints de elite

    // Auto-pause
    private var ultimaLocalizacaoSignificativa: Location? = null
    private var contadorSemMovimento = 0
    private var contadorEmMovimento = 0
    private val LIMITE_SEM_MOVIMENTO = 3          // 3s parado → pausa
    private val LIMITE_RETOMAR_MOVIMENTO = 2      // 2 updates em movimento → retoma
    // CORREÇÃO CRÍTICA: valor anterior era 4.0m, que é mais do que um corredor de 5:30/km
    // percorre em 1 segundo (3m/s). Isso causava auto-pause durante a corrida, inflando
    // o pace médio (timer continuava mas distância parava de acumular).
    // Novo valor: 1.5m — suficiente para filtrar drift de GPS parado (~1-2m de ruído)
    // sem acionar para qualquer ritmo humano realista (caminhada lenta = 1 m/s = 1m/update).
    private val DISTANCIA_MINIMA_MOVIMENTO = 1.5  // metros por update (1s)
    private var autoPauseFuncaoAtiva = true       // lido das preferências ao iniciar
    
    // Estados
    private var estaPausado = false
    private var estaCorrendo = false

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // StateFlows para comunicação com o ViewModel
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private val _distanciaMetros = MutableStateFlow(0.0)
    val distanciaMetros: StateFlow<Double> = _distanciaMetros.asStateFlow()
    
    private val _tempoTotalSegundos = MutableStateFlow(0L)
    val tempoTotalSegundos: StateFlow<Long> = _tempoTotalSegundos.asStateFlow()
    
    private val _paceAtual = MutableStateFlow("--:--")
    val paceAtual: StateFlow<String> = _paceAtual.asStateFlow()
    
    private val _paceMedia = MutableStateFlow("--:--")
    val paceMedia: StateFlow<String> = _paceMedia.asStateFlow()

    // ── Cadência (passos por minuto) via acelerômetro ─────────────────────────
    private val _cadencia = MutableStateFlow(0)
    val cadencia: StateFlow<Int> = _cadencia.asStateFlow()

    private lateinit var sensorManager: SensorManager
    // FIX 4: Sensor de cadência — estratégia em duas camadas:
    // Camada 1 (primária): TYPE_STEP_DETECTOR — chip dedicado de hardware presente na
    //   maioria dos dispositivos modernos. Usa muito menos bateria que o acelerômetro
    //   porque roda no DSP, não na CPU. Funciona bem independente de como o usuário
    //   carrega o celular (bolso, braçadeira, colete).
    // Camada 2 (fallback): TYPE_LINEAR_ACCELERATION — software-based, threshold adaptativo.
    //   Ativado apenas se o hardware não tiver STEP_DETECTOR.
    private var stepDetector: Sensor? = null
    private var acelerometro: Sensor? = null
    // Flag que indica qual sensor está em uso (evita dupla contagem)
    private var usandoStepDetector = false

    // Buffer circular dos últimos timestamps de passo (janela de 10s)
    private val timestampsPassos = ArrayDeque<Long>(50)
    private var ultimoTimestampPasso = 0L

    // Threshold adaptativo: começa em 13.0 (mais resistente a trepidação de bolso)
    private var thresholdAceleracao = 13.0f
    private var somaUltimosPicos = 0f
    private var contadorPicos = 0

    // FIX 7: Separação entre valor interno de EMA e string da UI.
    // Problema original: ultimoPaceEma era null quando o pace estava fora da faixa válida
    // (corredor parado, spike GPS). Isso causava "buracos" no heatmap (paceNoPonto=0.0)
    // e quebrava a continuidade do EMA (perdia o histórico toda vez que o GPS flutuava).
    // Solução: ultimoPaceEmaInterno mantém o ÚLTIMO valor numérico válido indefinidamente,
    // mesmo quando a UI mostra "--:--". Só é zerado no início de uma nova corrida.
    // O paceNoPonto do LatLngPonto sempre recebe um valor numérico real (nunca 0.0 espúrio).
    private var ultimoPaceEmaInterno: Double? = null  // valor numérico, nunca zerado por --:--
    
    private val _rotaAtual = MutableStateFlow<List<LatLngPonto>>(emptyList())
    val rotaAtual: StateFlow<List<LatLngPonto>> = _rotaAtual.asStateFlow()
    
    private val _posicaoAtual = MutableStateFlow<LatLngPonto?>(null)
    val posicaoAtual: StateFlow<LatLngPonto?> = _posicaoAtual.asStateFlow()
    
    private val _autoPausado = MutableStateFlow(false)
    val autoPausado: StateFlow<Boolean> = _autoPausado.asStateFlow()

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Binder para comunicação local
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    inner class LocalBinder : Binder() {
        fun getService(): RunningService = this@RunningService
    }

    private val binder = LocalBinder()
    
    override fun onBind(intent: Intent?): IBinder = binder

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Lifecycle do Service
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🔵 Service onCreate")
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // FIX 4: Inicialização de sensores com estratégia em duas camadas.
        // STEP_DETECTOR é a opção preferida: chip de hardware dedicado, gasta ~10x menos
        // bateria que o acelerômetro por software, funciona bem em qualquer posição de
        // carregamento (bolso frontal, braçadeira, colete).
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        if (stepDetector != null) {
            usandoStepDetector = true
            Log.d(TAG, "👟 TYPE_STEP_DETECTOR disponível — usando hardware nativo (economia de bateria)")
        } else {
            // Fallback: acelerômetro por software com threshold adaptativo
            acelerometro = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            usandoStepDetector = false
            if (acelerometro == null) {
                Log.w(TAG, "⚠️ Nenhum sensor de passo disponível — cadência desativada")
            } else {
                Log.d(TAG, "📡 Fallback para TYPE_LINEAR_ACCELERATION (STEP_DETECTOR não encontrado)")
            }
        }

        // Adquirir WakeLock parcial
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RunApp::RunningServiceWakeLock"
        )

        // Inicializar banco de dados Room via AppContainer (singleton — thread-safe)
        val app = applicationContext as com.runapp.RunApp
        database = app.container.runDatabase
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "📌 onStartCommand: action=${intent?.action}")
        
        when (intent?.action) {
            ACTION_START  -> iniciarRastreamento()
            ACTION_PAUSE  -> pausarRastreamento()
            ACTION_RESUME -> retomarRastreamento()
            ACTION_STOP   -> pararRastreamento()
            null -> {
                // O Android reiniciou o Service (START_STICKY) após matar o processo.
                // O estado em RAM foi perdido, mas temos: (1) checkpoint JSON com metadados,
                // (2) Room DB com todos os pontos GPS. Tentamos recuperar e continuar silenciosamente.
                Log.w(TAG, "⚠️ Service reiniciado pelo Android (intent null) — tentando recuperar via Room")
                recuperarAposProcessDeath()
            }
        }

        return START_STICKY  // Android reinicia o Service se for morto por OOM — recovery via Room
    }

    /**
     * Recovery após o Android reiniciar o Service (START_STICKY + process death).
     *
     * FLUXO:
     * 1. Lê checkpoint JSON (síncrono — arquivo pequeno, < 1KB)
     * 2. Restaura metadados (timer, distância, pace)
     * 3. Restaura rota do Room (assíncrono — pode ser grande)
     * 4. Reinicia GPS e timer com estado restaurado
     *
     * Se não há checkpoint (primeira run, ou corrida já finalizada): para limpo.
     * Notificação atualizada automaticamente — o usuário vê "Corrida recuperada"
     * sem precisar abrir o app.
     */
    private fun recuperarAposProcessDeath() {
        criarCanalNotificacao()

        val checkpoint = lerCheckpointSync()
        if (checkpoint == null || checkpoint.distanciaMetros < 10.0) {
            Log.w(TAG, "⚠️ Sem checkpoint válido para recuperar — encerrando")
            iniciarForeground("Sessão encerrada pelo sistema. Inicie uma nova corrida.")
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
            return
        }

        serviceScope.launch {
            Log.d(TAG, "♻️ Recuperando: ${checkpoint.distanciaMetros.toInt()}m, ${checkpoint.tempoTotalSegundos}s")

            // Restaurar metadados do timer com elapsedRealtime
            sessionId           = checkpoint.sessionId
            timestampInicioWall = checkpoint.timestampInicioWall
            tempoPausadoTotalMs = checkpoint.tempoPausadoTotalMs

            // FIX 1 — PROTEÇÃO CONTRA REINÍCIO DO CELULAR:
            // elapsedRealtime é monotônico MAS reseta quando o aparelho desliga/reinicia.
            // Se agora < elapsedRealtimeInicio salvo, o celular foi reiniciado e o cronômetro
            // ficaria negativo ou bizarro. Nesse caso, recalculamos a âncora usando o wall
            // clock (currentTimeMillis), que persiste entre reinícios via RTC do hardware.
            val agora = SystemClock.elapsedRealtime()
            if (agora < checkpoint.elapsedRealtimeInicio) {
                // Celular reiniciou: reconstrói âncora elapsedRealtime a partir do wall clock.
                // duracaoReal = quanto tempo de corrida (descontando pausas) já havia passado.
                val duracaoRealMs = (System.currentTimeMillis() - checkpoint.timestampInicioWall
                    - checkpoint.tempoPausadoTotalMs).coerceAtLeast(0L)
                elapsedRealtimeInicio = (agora - duracaoRealMs).coerceAtMost(agora)
                Log.w(TAG, "📱 Reinício do celular detectado! Recalibrando âncora: " +
                    "elapsed salvo=${checkpoint.elapsedRealtimeInicio}ms > agora=${agora}ms. " +
                    "Nova âncora: ${elapsedRealtimeInicio}ms (baseada em wall clock)")
            } else {
                elapsedRealtimeInicio = checkpoint.elapsedRealtimeInicio
            }
            _distanciaMetros.value    = checkpoint.distanciaMetros
            _tempoTotalSegundos.value = checkpoint.tempoTotalSegundos
            _paceMedia.value    = checkpoint.paceMedia
            estaPausado         = false
            estaCorrendo        = true

            // Restaurar rota do Room (I/O assíncrono)
            val pontosRecuperados = withContext(Dispatchers.IO) {
                database.routePointDao()
                    .getSessionPoints(checkpoint.sessionId)
                    .map { it.toLatLngPonto() }
            }
            rota.addAll(pontosRecuperados)
            if (pontosRecuperados.isNotEmpty()) {
                _rotaAtual.value = rota.toList()
                _posicaoAtual.value = pontosRecuperados.last()
            }

            // PONTO 3 — RECONCILIAÇÃO distância checkpoint vs Room:
            // Se o processo morreu após um insert no Room mas antes do próximo checkpoint (30s),
            // o checkpoint tem distância N-1 enquanto o Room tem N pontos.
            // Solução: recalcular haversine sobre os pontos do Room e usar o maior valor.
            if (pontosRecuperados.size >= 2) {
                var distanciaRoom = 0.0
                for (i in 1 until pontosRecuperados.size) {
                    distanciaRoom += calcularDistancia(
                        pontosRecuperados[i-1].lat, pontosRecuperados[i-1].lng,
                        pontosRecuperados[i].lat,   pontosRecuperados[i].lng
                    )
                }
                // Usa máximo: protege contra spike GPS que inflasse o recalculado
                val distanciaFinal = maxOf(checkpoint.distanciaMetros, distanciaRoom)
                if (kotlin.math.abs(distanciaFinal - checkpoint.distanciaMetros) > 1.0) {
                    Log.d(TAG, "📐 Distância reconciliada: ${checkpoint.distanciaMetros.toInt()}m → ${distanciaFinal.toInt()}m")
                    _distanciaMetros.value = distanciaFinal
                }
            }

            Log.d(TAG, "✅ ${pontosRecuperados.size} pontos GPS recuperados do Room")

            // FIX 4 — GPS COLD START: ativa modo de recuperação para filtrar os primeiros
            // pontos GPS que chegam com precisão ruim (torre de celular, fix desatualizado).
            if (pontosRecuperados.isNotEmpty()) {
                modoRecuperacaoGps = true
                contadorPontosRecuperacao = 0
                Log.d(TAG, "🛡️ Modo GPS recovery ativado — filtrando saltos impossíveis nos primeiros pontos")
            }

            // Reiniciar como se fosse nova corrida (WakeLock, GPS, Timer, Sensores)
            iniciarForeground("♻️ Corrida recuperada — ${String.format("%.2f", checkpoint.distanciaMetros / 1000)}km já registrados")
            wakeLock?.acquire(6 * 60 * 60 * 1000L)

            // Registrar sensor de cadência
            if (usandoStepDetector) {
                stepDetector?.let { sensorManager.registerListener(this@RunningService, it, SensorManager.SENSOR_DELAY_NORMAL) }
            } else {
                acelerometro?.let { sensorManager.registerListener(this@RunningService, it, SensorManager.SENSOR_DELAY_GAME) }
            }

            iniciarAtualizacoesGPS()
            iniciarTimer()
        }
    }

    private fun iniciarRastreamento() {
        Log.d(TAG, "▶️ Iniciando rastreamento")
        
        // Configurar como Foreground Service
        criarCanalNotificacao()
        iniciarForeground()
        
        // Adquirir WakeLock — timeout de 6h cobre qualquer ultramaratona realista.
        // O wakelock anterior de 10 minutos era a causa raiz do service morrer em corridas longas:
        // após 10min a CPU dormia, o GPS parava e o treino era perdido.
        wakeLock?.acquire(6 * 60 * 60 * 1000L /*6 horas*/)
        
        // Ler preferência de auto-pause antes de começar
        serviceScope.launch {
            val prefs = PreferencesRepository(applicationContext)
            autoPauseFuncaoAtiva = prefs.autoPauseEnabled.first()
            Log.d(TAG, "⚙️ Auto-pause ${if (autoPauseFuncaoAtiva) "ativado" else "desativado"}")
        }
        
        // Gerar novo sessionId único para esta corrida
        // Usado como chave no Room para separar sessões diferentes no mesmo banco
        sessionId = UUID.randomUUID().toString()
        Log.d(TAG, "🆔 Sessão iniciada: $sessionId")

        // FIX 2 — HIGIENE DO BANCO DE DADOS:
        // Deleta pontos de sessões anteriores (crashes, testes, corridas incompletas) que
        // não foram limpos normalmente. O banco só precisa ter dados da sessão ATIVA.
        // Feito de forma assíncrona para não bloquear o início da corrida.
        serviceScope.launch(Dispatchers.IO) {
            try {
                database.routePointDao().deleteOtherSessions(sessionId)
                Log.d(TAG, "🗑️ Sessões órfãs removidas do Room (mantendo: $sessionId)")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Falha ao limpar sessões órfãs: ${e.message}")
            }
        }

        // Resetar modo GPS recovery (nova corrida sempre começa limpa)
        modoRecuperacaoGps = false
        contadorPontosRecuperacao = 0

        // Resetar dados
        rota.clear()
        ultimasLocalizacoes.clear()
        ultimoPaceEmaInterno = null  // FIX 7: reset completo intencional no início de NOVA corrida
        janelaAtualSegundos = 12
        // Capturar ambas âncoras no mesmo instante
        timestampInicioWall   = System.currentTimeMillis()     // para display
        elapsedRealtimeInicio = SystemClock.elapsedRealtime()  // para cronômetro
        tempoPausadoTotalMs   = 0
        _distanciaMetros.value = 0.0
        _tempoTotalSegundos.value = 0
        estaPausado = false
        estaCorrendo = true

        // Resetar cadência e registrar sensor
        timestampsPassos.clear()
        ultimoTimestampPasso = 0L
        thresholdAceleracao = 13.0f
        somaUltimosPicos = 0f
        contadorPicos = 0
        _cadencia.value = 0

        // FIX 4: Registra o sensor correto dependendo do que o hardware suporta.
        // STEP_DETECTOR: usa SENSOR_DELAY_NORMAL — o chip de hardware não se beneficia
        //   de polling mais rápido e taxa alta só drena bateria desnecessariamente.
        // LINEAR_ACCELERATION: usa SENSOR_DELAY_GAME (50ms) para capturar os picos
        //   de impacto do passo que têm duração ~100-200ms.
        if (usandoStepDetector) {
            stepDetector?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                Log.d(TAG, "👟 STEP_DETECTOR registrado")
            }
        } else {
            acelerometro?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                Log.d(TAG, "📡 LINEAR_ACCELERATION registrado (fallback)")
            }
        }
        
        // Iniciar GPS
        iniciarAtualizacoesGPS()
        
        // Iniciar timer
        iniciarTimer()
    }

    private fun pausarRastreamento() {
        Log.d(TAG, "⏸️ Pausando rastreamento")
        estaPausado = true
        elapsedRealtimePausaInicio = SystemClock.elapsedRealtime()
        
        // Atualizar notificação
        atualizarNotificacao("Corrida pausada")
    }

    private fun retomarRastreamento() {
        Log.d(TAG, "▶️ Retomando rastreamento")
        
        if (estaPausado) {
            val tempoPausaMs = SystemClock.elapsedRealtime() - elapsedRealtimePausaInicio
            tempoPausadoTotalMs += tempoPausaMs
            estaPausado = false
            
            // Atualizar notificação
            atualizarNotificacao("Corrida em andamento")
        }
    }

    private fun pararRastreamento() {
        Log.d(TAG, "⏹️ Parando rastreamento")

        estaCorrendo = false

        // Forçar emissão da rota COMPLETA antes de parar.
        // A emissão a cada 5 pontos pode ter deixado os últimos pontos sem emissão.
        // Fazemos isso independente de subscriptionCount — ao parar, sempre queremos
        // que a UI tenha o estado final completo para o ResumoScreen.
        _rotaAtual.value = rota.toList()

        // Parar sensor de cadência
        sensorManager.unregisterListener(this)
        _cadencia.value = 0

        // Parar timer
        timerJob?.cancel()
        timerJob = null
        
        // Parar GPS
        pararAtualizacoesGPS()
        
        // Liberar WakeLock
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        
        // Deletar checkpoint — corrida finalizada normalmente, não há dados a recuperar
        deletarCheckpoint()

        // Parar foreground e service
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "🔴 Service onDestroy")

        // Garantir limpeza
        timerJob?.cancel()
        // Cancela o serviceScope inteiro, encerrando todas as coroutines pendentes
        serviceJob.cancel()
        pararAtualizacoesGPS()
        sensorManager.unregisterListener(this)
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Cadência via Acelerômetro
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    override fun onSensorChanged(event: SensorEvent?) {
        // Ignora quando pausado ou se não está correndo
        if (!estaCorrendo || estaPausado || event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> {
                // FIX 4: STEP_DETECTOR já entrega exatamente 1 evento por passo detectado.
                // Não precisamos de threshold, debounce de magnitude ou cálculos haversine —
                // o chip de hardware já faz todo esse trabalho. Apenas registramos o timestamp.
                registrarPasso(System.currentTimeMillis())
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                // Fallback: algoritmo de threshold adaptativo original
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val magnitude = kotlin.math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
                detectarPassoPorMagnitude(magnitude)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* não usado */ }

    // FIX 4: Lógica de contagem de cadência extraída para função compartilhada.
    // Usada tanto pelo STEP_DETECTOR (caminho simples) quanto pelo LINEAR_ACCELERATION (fallback).
    private fun registrarPasso(agora: Long) {
        // Debounce mínimo de 200ms entre passos: cobre até 300 SPM (corrida olímpica)
        // e filtra duplos eventos espúrios em raros dispositivos com STEP_DETECTOR ruidoso
        if (agora - ultimoTimestampPasso < 200L) return
        ultimoTimestampPasso = agora

        // Buffer circular: mantém apenas timestamps dos últimos 10s
        timestampsPassos.addLast(agora)
        while (timestampsPassos.isNotEmpty() && timestampsPassos.first() < agora - 10_000L) {
            timestampsPassos.removeFirst()
        }

        // Cadência = (passos em 10s / 10) * 60, só se tiver dados suficientes (≥3 passos)
        if (timestampsPassos.size >= 3) {
            val spm = (timestampsPassos.size / 10.0 * 60).toInt()
            if (spm in 60..220) {
                _cadencia.value = spm
            }
        }
    }

    // FIX 4: Renomeado de detectarPasso → detectarPassoPorMagnitude para clareza.
    // Este é o fallback para dispositivos sem TYPE_STEP_DETECTOR.
    private fun detectarPassoPorMagnitude(magnitude: Float) {
        val agora = System.currentTimeMillis()

        // ANTI-DEADLOCK: se passou mais de 2s sem passo, o usuário mudou de ritmo
        // (parou, desacelerou, trocou de superfície). Reseta o threshold para o valor base.
        if (agora - ultimoTimestampPasso > 2000L && ultimoTimestampPasso > 0L) {
            thresholdAceleracao = 13.0f
        }

        // FILTRO DE FORÇA: descarta sinal abaixo do threshold (ruído de bolso/pochete)
        if (magnitude < thresholdAceleracao) return

        // DEBOUNCE DE 350ms: limita a ~171 SPM máximo.
        // O objetivo principal é matar o "repique" (segundo pico de vibração do mesmo passo)
        // que chegava ~150-200ms depois e dobrava a contagem.
        // 350ms é seguro até para corridas rápidas (~170 SPM), que é o teto real de caminhada/corrida casual.
        if (agora - ultimoTimestampPasso < 350L) return

        // THRESHOLD ADAPTATIVO COM "PISO":
        // Ajusta gradualmente à força do impacto do usuário, mas nunca cai abaixo de 12.5
        // para não voltar a contar repiques quando o usuário desacelera.
        somaUltimosPicos += magnitude
        contadorPicos++
        if (contadorPicos >= 8) {
            val mediaPicos = somaUltimosPicos / contadorPicos
            // 72% da média dos picos, com piso em 12.5 → nunca volta a "cair" demais
            thresholdAceleracao = (mediaPicos * 0.72f).coerceAtLeast(12.5f)
            somaUltimosPicos = 0f
            contadorPicos = 0
        }

        // Delega para o registrador comum (buffer + contagem de cadência)
        registrarPasso(agora)
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // GPS Tracking
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private var gpsDisponivel = true

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (!gpsDisponivel) {
                gpsDisponivel = true
                Log.d(TAG, "✅ GPS recuperado")
                getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_GPS_ERROR_ID)
                atualizarNotificacao()
            }
            result.lastLocation?.let { location ->
                processarNovaLocalizacao(location)
            }
        }

        /**
         * Disparado quando o GPS fica indisponível: avião, GPS desativado ou
         * permissão revogada enquanto a corrida está ativa.
         * Emite notificação de erro crítico imediatamente para alertar o usuário.
         */
        override fun onLocationAvailability(availability: LocationAvailability) {
            val disponivel = availability.isLocationAvailable
            if (disponivel == gpsDisponivel) return
            gpsDisponivel = disponivel

            if (!disponivel && estaCorrendo) {
                Log.w(TAG, "⚠️ GPS indisponível durante corrida")
                val temPermissao = checkSelfPermission(
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                val msg = if (!temPermissao)
                    "⚠️ Permissão de GPS revogada — corrida pausada!"
                else
                    "⚠️ Sinal GPS perdido — aguardando reconexão..."

                val notif = androidx.core.app.NotificationCompat.Builder(this@RunningService, CHANNEL_ID)
                    .setContentTitle("RunApp — GPS Interrompido 🛑")
                    .setContentText(msg)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .build()
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_GPS_ERROR_ID, notif)

                // Salvar checkpoint imediatamente — preserva dados até o momento da perda
                salvarCheckpoint()
            }
        }
    }

    private fun iniciarAtualizacoesGPS() {
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000L  // Atualização a cada 1 segundo
        )
            .setMinUpdateDistanceMeters(0f)  // Sem filtro de distância
            .setGranularity(Granularity.GRANULARITY_FINE)
            .setWaitForAccurateLocation(false)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.d(TAG, "✅ GPS iniciado")
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Erro de permissão GPS", e)
        }
    }

    private fun pararAtualizacoesGPS() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        Log.d(TAG, "⏹️ GPS parado")
    }

    private fun processarNovaLocalizacao(location: Location) {
        // Não processar se pausado
        if (estaPausado) return
        
        // FILTRO DE PONTO "ZUMBI" (GPS Stale):
        // Após sair de um túnel ou reiniciar o GPS, o FusedLocationProvider pode enviar
        // o último ponto cacheado com um timestamp antigo antes de obter a posição real.
        // Esses pontos têm `location.time` (wall clock do momento da medição) muito
        // defasado em relação ao `System.currentTimeMillis()`.
        // Se deixarmos passar, o dtMs entre o ponto anterior e este "zumbi" vai parecer
        // pequeno (pois o timestamp é antigo), mas a distância pode ser enorme — produzindo
        // um pace impossível ou quebrando o filtro de gap de 30s do heatmap.
        // Limiar de 10s cobre atrasos normais de processamento do chip GPS (~1-3s)
        // sem descartar pontos legítimos em hardware mais lento.
        // Usa elapsedRealtimeNanos em vez de location.time (wall clock) para consistência:
        // todo o cronômetro do app já opera em elapsedRealtime, imune a saltos de NTP
        // ou mudanças de fuso que podem ocorrer no meio de uma corrida longa.
        val idadeMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
            SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        )
        if (idadeMs > 10_000L) {
            Log.d(TAG, "👻 Ponto GPS 'zumbi' descartado: ${idadeMs}ms de atraso (elapsedRealtimeNanos)")
            return
        }

        // Filtro básico de qualidade
        if (location.accuracy > MAX_ACCURACY_METERS) {
            Log.d(TAG, "⚠️ Localização descartada: accuracy=${location.accuracy}m")
            return
        }

        // FIX 4 — FILTRO DE SALTO INICIAL (GPS Cold Start após recovery):
        // Os primeiros pings pós-recuperação podem vir de torre de celular, muito longe da
        // posição real. Descartamos qualquer ponto onde a velocidade implícita desde o último
        // ponto salvo seja humanamente impossível (> MAX_VELOCIDADE_HUMANA_MS m/s).
        if (modoRecuperacaoGps && rota.isNotEmpty()) {
            val ultimoPontoSalvo = rota.last()
            val distJump = calcularDistancia(
                ultimoPontoSalvo.lat, ultimoPontoSalvo.lng,
                location.latitude, location.longitude
            )
            val deltaTempoS = ((System.currentTimeMillis() - ultimoPontoSalvo.tempo) / 1000.0).coerceAtLeast(1.0)
            val velocidadeMs = distJump / deltaTempoS

            if (velocidadeMs > MAX_VELOCIDADE_HUMANA_MS) {
                contadorPontosRecuperacao++
                Log.w(TAG, "🚫 Ponto GPS descartado por salto impossível: " +
                    "${distJump.toInt()}m em ${deltaTempoS.toInt()}s " +
                    "(${String.format("%.1f", velocidadeMs)} m/s). " +
                    "Ponto ${contadorPontosRecuperacao} descartado.")
                return
            } else {
                // Ponto plausível: desativa modo de recuperação
                modoRecuperacaoGps = false
                Log.d(TAG, "✅ GPS recovery: primeiro ponto válido aceito (${distJump.toInt()}m, " +
                    "${String.format("%.1f", velocidadeMs)} m/s)")
            }
        }

        val agora = System.currentTimeMillis()
        val pontoNovo = LatLngPonto(
            lat = location.latitude,
            lng = location.longitude,
            alt = location.altitude,
            tempo = agora,
            accuracy = location.accuracy,
            // Snapshot do pace e cadência no momento exato do ponto GPS
            // Permite gráficos "pace ao longo do percurso" e correlação com altitude
            paceNoPonto = ultimoPaceEma ?: 0.0,
            cadenciaNoPonto = _cadencia.value
        )

        // Atualizar posição atual
        _posicaoAtual.value = pontoNovo

        // Se é o primeiro ponto, apenas adicionar
        if (rota.isEmpty()) {
            rota.add(pontoNovo)
            _rotaAtual.value = rota.toList()
            ultimaLocalizacaoSignificativa = location
            return
        }

        // Verificar movimento para auto-pause (somente se a função estiver ativa)
        if (autoPauseFuncaoAtiva) {
            verificarAutoPause(location)
        }
        
        // Se está em auto-pause, não adicionar pontos
        if (_autoPausado.value) {
            return
        }

        // Calcular distância desde o último ponto
        val ultimoPonto = rota.last()
        val distancia = calcularDistancia(
            ultimoPonto.lat, ultimoPonto.lng,
            pontoNovo.lat, pontoNovo.lng
        )

        // Adicionar ponto à rota em memória
        rota.add(pontoNovo)

        // PERSISTÊNCIA IMEDIATA NO ROOM — só pontos com GPS confiável (< 20m)
        // O limiar é mais rígido que o da UI (50m) para evitar "saltos" que inflam
        // distância e sujam o heatmap. Pontos ruins continuam visíveis na tela, mas
        // não entram no histórico permanente.
        if (location.accuracy <= ROOM_ACCURACY_METERS) {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    database.routePointDao().insert(RoutePointEntity.from(pontoNovo, sessionId))
                } catch (e: Exception) {
                    Log.e(TAG, "⚠️ Falha ao inserir ponto no Room", e)
                }
            }
        } else {
            Log.d(TAG, "📍 Ponto não persistido (accuracy=${location.accuracy}m > ${ROOM_ACCURACY_METERS}m)")
        }

        // EMISSÃO INTELIGENTE DO STATEFLOW
        // subscriptionCount == 0 significa que a UI está em background (tela bloqueada).
        // Nesse caso, NÃO geramos a cópia toList() — eliminamos 100% da alocação de RAM
        // enquanto o usuário corre com tela bloqueada, que é exatamente quando o OOM
        // killer atacava. Quando a UI voltar, onServiceConnected lê getRotaCompleta()
        // diretamente da lista em memória, sem depender do StateFlow.
        if (_rotaAtual.subscriptionCount.value > 0) {
            // Emitir a cada 5 pontos (5s) para reduzir pressão no GC quando UI está ativa
            if (rota.size == 1 || rota.size % 5 == 0) {
                _rotaAtual.value = rota.toList()
            }
        }

        // Atualizar distância total
        _distanciaMetros.value += distancia

        // Gerenciar janela móvel para pace atual
        ultimasLocalizacoes.add(location)
        
        // Remover localizações antigas da janela (janela adaptativa)
        val tempoCorte = agora - (janelaAtualSegundos * 1000)
        ultimasLocalizacoes.removeAll { it.time < tempoCorte }
        
        // PROTEÇÃO CONTRA SPIKE: Se ficou muito tempo sem GPS, limpar janela
        // mas re-adicionar a localização atual como novo ponto de partida para
        // evitar ficar "cego" até a janela encher de novo.
        if (ultimasLocalizacoes.size >= 2) {
            val tempoJanela = (ultimasLocalizacoes.last().time - ultimasLocalizacoes.first().time) / 1000.0
            if (tempoJanela > (janelaAtualSegundos * 2)) {
                Log.w(TAG, "⚠️ Gap temporal detectado (${tempoJanela}s), resetando janela de pace")
                ultimasLocalizacoes.clear()
                ultimasLocalizacoes.add(location)   // ponto atual como nova âncora
                // FIX 7: NÃO zera ultimoPaceEmaInterno — mantém o último valor numérico
                // válido para: (a) continuar o heatmap sem buracos e (b) reiniciar o EMA
                // de onde parou (não do zero) assim que os pontos chegarem novamente.
                // Só resetamos a STRING da UI para "--:--" (sinal visual de "sem leitura").
                _paceAtual.value = "--:--"
                return
            }
        }

        // FILTRO DE SPIKE DE VELOCIDADE: O GPS pode reportar uma posição "saltada" logo
        // após uma reconexão, causando paces impossíveis (ex: 3:39/km a 5:30/km real).
        // Se a Location tiver speed disponível (hasSpeed()), usamos como sanidade:
        // speed > 6.5 m/s (~4:17/km) é provavelmente ruído para corrida casual no campus.
        // O limiar é generoso o suficiente para não cortar sprints legítimos de curto prazo.
        if (location.hasSpeed() && location.speed > 6.5f) {
            Log.w(TAG, "⚠️ Velocidade GPS suspeita: ${location.speed} m/s, descartando ponto de pace")
            _paceAtual.value = "--:--"
            // FIX 7: Mesmo aqui, NÃO zeramos o EMA interno — o heatmap e o próximo
            // cálculo real de pace continuam com o contexto histórico preservado.
            return
        }

        // Calcular pace atual usando a janela móvel
        calcularPaceAtual()
        
        // Calcular pace médio
        calcularPaceMedia()

        Log.d(TAG, "📍 Dist: ${String.format("%.1f", _distanciaMetros.value)}m | Pace: ${_paceAtual.value} | Janela: ${ultimasLocalizacoes.size}")
    }

    private fun verificarAutoPause(location: Location) {
        val ultimaLoc = ultimaLocalizacaoSignificativa ?: run {
            ultimaLocalizacaoSignificativa = location
            return
        }

        // DETECÇÃO DE MOVIMENTO: preferir GPS speed (Doppler) quando disponível,
        // pois é muito mais preciso que distância ponto-a-ponto para detectar movimento real.
        // GPS speed < 0.5 m/s = praticamente parado; >= 0.5 m/s = algum movimento.
        val emMovimento: Boolean = if (location.hasSpeed() &&
            (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O || location.speedAccuracyMetersPerSecond < 1.5f)) {
            // Usa velocidade Doppler do GPS — mais confiável que delta de coordenadas
            location.speed >= 0.5f  // ≥ 0.5 m/s = 1.8 km/h = caminhada muito lenta
        } else {
            // Fallback: distância ponto-a-ponto com limiar adaptativo
            val LIMIAR_MAXIMO_METROS = 8.0
            val limiarMovimento = minOf(
                maxOf(DISTANCIA_MINIMA_MOVIMENTO, location.accuracy.toDouble()),
                LIMIAR_MAXIMO_METROS
            )
            val distanciaDesdeUltima = calcularDistancia(
                ultimaLoc.latitude, ultimaLoc.longitude,
                location.latitude, location.longitude
            )
            distanciaDesdeUltima >= limiarMovimento
        }

        if (!emMovimento) {
            // Sem movimento suficiente
            contadorSemMovimento++
            contadorEmMovimento = 0

            // Atualizar referência mesmo durante auto-pause para que ao retomar
            // a distância seja calculada desde a posição atual, não de um ponto antigo.
            if (_autoPausado.value) {
                ultimaLocalizacaoSignificativa = location
            }

            if (contadorSemMovimento >= LIMITE_SEM_MOVIMENTO && !_autoPausado.value) {
                Log.d(TAG, "⏸️ Auto-pause ativado (${contadorSemMovimento}s sem movimento, speed=${location.speed} m/s)")
                _autoPausado.value = true
                atualizarNotificacao("Auto-pausado (sem movimento)")
            }
        } else {
            // Em movimento real
            contadorEmMovimento++
            contadorSemMovimento = 0
            ultimaLocalizacaoSignificativa = location

            if (_autoPausado.value && contadorEmMovimento >= LIMITE_RETOMAR_MOVIMENTO) {
                Log.d(TAG, "▶️ Auto-pause desativado (movimento confirmado, speed=${location.speed} m/s)")
                _autoPausado.value = false
                contadorEmMovimento = 0
                atualizarNotificacao("Corrida em andamento")
            }
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Cálculos de Pace
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** Converte "5:30" → 330.0 seg/km. Retorna 0.0 para "--:--" ou inválido. */
    private fun calcularPaceSegKm(paceFormatado: String): Double {
        if (paceFormatado == "--:--") return 0.0
        return runCatching {
            val partes = paceFormatado.split(":")
            partes[0].toLong() * 60.0 + partes[1].toLong()
        }.getOrDefault(0.0)
    }

    private fun calcularPaceAtual() {
        // Mínimo de 2 pontos (janelas curtas ficam responsivas mais rápido)
        if (ultimasLocalizacoes.size < 2) {
            _paceAtual.value = "--:--"
            return
        }

        // Ajuste fino da janela pela accuracy do último ponto:
        // GPS ruim (>20m) → janela maior para estabilizar
        // GPS excelente (<5m) → pode confiar numa janela mínima de 3s
        val accuracyAtual = ultimasLocalizacoes.last().accuracy
        val janelaEfetiva = when {
            accuracyAtual > 20f -> (janelaAtualSegundos * 1.5).toInt().coerceAtMost(15)
            accuracyAtual < 5f  -> janelaAtualSegundos.coerceAtLeast(3)
            else                -> janelaAtualSegundos
        }
        val corte = System.currentTimeMillis() - (janelaEfetiva * 1000L)
        val pontosJanela = ultimasLocalizacoes.filter { it.time >= corte }
        if (pontosJanela.size < 2) {
            _paceAtual.value = "--:--"
            return
        }

        // Somar distância entre pontos consecutivos da janela
        var distanciaJanela = 0.0
        for (i in 1 until pontosJanela.size) {
            val d = calcularDistancia(
                pontosJanela[i - 1].latitude, pontosJanela[i - 1].longitude,
                pontosJanela[i].latitude,     pontosJanela[i].longitude
            )
            // Filtro de threshold: ignora micro-deslocamentos (<0.5m) que são só ruído GPS
            if (d > 0.5) distanciaJanela += d
        }

        val tempoJanelaSegundos = (pontosJanela.last().time - pontosJanela.first().time) / 1000.0

        if (distanciaJanela < 1.0 || tempoJanelaSegundos < 1.0) {
            _paceAtual.value = "--:--"
            return
        }

        // Pace bruto em s/km
        val paceBruto = (tempoJanelaSegundos / distanciaJanela) * 1000.0

        // Sanidade: ignora valores impossíveis (< 1:30/km ou > 20:00/km)
        // FIX 7: NÃO retornamos mais sem atualizar o EMA interno.
        // Se o pace bruto for inválido, apenas mostramos "--:--" na UI mas
        // preservamos o ultimo EMA válido para o heatmap não ter buracos.
        if (paceBruto < 90.0 || paceBruto > 1200.0) {
            _paceAtual.value = "--:--"
            // ultimoPaceEmaInterno permanece inalterado — heatmap continua
            return
        }

        // EMA: alpha depende da janela — janela curta reage mais rápido
        val alpha = if (janelaAtualSegundos <= 5) 0.4 else 0.25
        val paceEma = ultimoPaceEmaInterno?.let { anterior ->
            (paceBruto * alpha) + (anterior * (1.0 - alpha))
        } ?: paceBruto  // primeiro valor: sem histórico, usa direto

        // FIX 7: Atualiza SEMPRE o valor numérico interno.
        // A string da UI é gerada separadamente e pode ser "--:--",
        // mas ultimoPaceEmaInterno sempre guarda o último Double válido.
        ultimoPaceEmaInterno = paceEma
        _paceAtual.value = formatarPace(paceEma)
    }

    private fun calcularPaceMedia() {
        if (_distanciaMetros.value < 10.0 || _tempoTotalSegundos.value < 1) {
            _paceMedia.value = "--:--"
            return
        }

        val paceSegundos = (_tempoTotalSegundos.value.toDouble() / _distanciaMetros.value) * 1000.0
        _paceMedia.value = formatarPace(paceSegundos)
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Timer
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun iniciarTimer() {
        timerJob = serviceScope.launch {
            while (isActive && estaCorrendo) {
                delay(1000)
                
                if (!estaPausado && !_autoPausado.value) {
                    // ElapsedRealtime: monotônico, nunca salta com NTP/DST/fuso
                    val tempoDecorrido = (SystemClock.elapsedRealtime() - elapsedRealtimeInicio - tempoPausadoTotalMs) / 1000
                    _tempoTotalSegundos.value = tempoDecorrido
                    
                    // Atualizar notificação a cada 5 segundos
                    if (tempoDecorrido % 5 == 0L) {
                        atualizarNotificacao()
                    }

                    // CHECKPOINT PERIÓDICO A CADA 30 SEGUNDOS NO DISCO
                    // Salvo pelo SERVICE (não pelo ViewModel), garantindo persistência
                    // mesmo quando a Activity e o ViewModel são destruídos pelo sistema
                    // durante corridas longas com tela bloqueada. O ViewModel lê este
                    // arquivo ao abrir o app e restaura o estado FINALIZADO automaticamente.
                    if (tempoDecorrido % 30 == 0L && tempoDecorrido > 0) {
                        salvarCheckpoint()
                    }
                }
            }
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Notificações
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Chama startForeground() com o foregroundServiceType correto conforme a API.
     *
     * PROBLEMA RAIZ DO CRASH:
     * Fix 6 adicionou foregroundServiceType="location|health" ao AndroidManifest.
     * No Android 14+ (API 34+), quando o manifesto declara foregroundServiceType, o sistema
     * EXIGE que startForeground() seja chamado com o 3º argumento (o tipo de serviço) E que
     * a permissão correspondente (ACTIVITY_RECOGNITION para "health") esteja concedida.
     * Chamar a versão de 2 argumentos resulta em tipo=0, que viola a validação do Android 14+
     * e lança SecurityException → o app fecha imediatamente ao dar Play.
     *
     * SOLUÇÃO: usar o 3º argumento com os tipos corretos, com fallbacks por API level.
     *   API 34+ com ACTIVITY_RECOGNITION concedida → LOCATION | HEALTH (cadência ativa)
     *   API 34+ sem ACTIVITY_RECOGNITION            → só LOCATION (cadência desativada mas roda)
     *   API 29-33 → FOREGROUND_SERVICE_TYPE_LOCATION (HEALTH não existe nessas versões)
     *   API < 29  → versão de 2 argumentos (types não existem antes do Q)
     */
    private fun iniciarForeground(texto: String? = null) {
        val notif = criarNotificacao(texto)
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                // API 34+ (Android 14+): verifica ACTIVITY_RECOGNITION em runtime.
                // Se concedida: usa HEALTH para manter acesso ao TYPE_STEP_DETECTOR em background.
                // Se negada: usa só LOCATION — cadência desativada mas corrida funciona normalmente.
                val temActivityRecognition = checkSelfPermission(
                    android.Manifest.permission.ACTIVITY_RECOGNITION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                val tipoServico = if (temActivityRecognition) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                } else {
                    Log.w(TAG, "⚠️ ACTIVITY_RECOGNITION não concedida — cadência pode não funcionar no Android 14+")
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }
                startForeground(NOTIFICATION_ID, notif, tipoServico)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                // API 29-33 (Android 10-13): HEALTH não existe; usa só LOCATION.
                startForeground(
                    NOTIFICATION_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            }
            else -> {
                // API < 29 (Android 9 e abaixo): tipos de serviço não existem.
                startForeground(NOTIFICATION_ID, notif)
            }
        }
    }

    private fun criarCanalNotificacao() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Corrida em Andamento",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mostra informações da sua corrida atual"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun criarNotificacao(texto: String? = null): Notification {
        // FLAG_ACTIVITY_SINGLE_TOP — reutiliza a Activity existente em vez de criar nova
        // FLAG_ACTIVITY_CLEAR_TOP — garante que não empilha Activities duplicadas
        val intent = Intent(this, MainActivity::class.java).apply {
            // Ação específica para distinguir clique na notificação de abertura normal
            action = ACTION_SHOW_RUNNING
            // Carrega o ID do treino para navegação direta — sem passar pela Home
            putExtra(EXTRA_EVENT_ID, treinoAtivo?.id ?: -1L)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val conteudo = texto ?: "GPS registrando sua corrida..."

        // FIX 3 — PENDINGINTENT ROBUSTO: botões apontam para o SERVICE, não Activity.
        // getService() garante que o comando é processado mesmo com o app morto em background
        // (process death). O Service está vivo (foreground) e processa imediatamente.
        val pausaResumeIntent = Intent(this, RunningService::class.java).apply {
            action = if (estaPausado || _autoPausado.value) ACTION_RESUME else ACTION_PAUSE
        }
        val pausaResumePendingIntent = PendingIntent.getService(
            this, 1, pausaResumeIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val pararIntent = Intent(this, RunningService::class.java).apply {
            action = ACTION_STOP
        }
        val pararPendingIntent = PendingIntent.getService(
            this, 2, pararIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val pausaResumeLabel = if (estaPausado || _autoPausado.value) "▶ Retomar" else "⏸ Pausar"
        val pausaResumeIcon  = if (estaPausado || _autoPausado.value)
            android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RunApp — Corrida Ativa 🏃")
            .setContentText(conteudo)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            // Botão 1: Pausar / Retomar — aponta direto ao Service (não à Activity)
            .addAction(pausaResumeIcon, pausaResumeLabel, pausaResumePendingIntent)
            // Botão 2: Parar — aponta direto ao Service, funciona mesmo com UI morta
            .addAction(android.R.drawable.ic_media_next, "⏹ Parar", pararPendingIntent)
            .build()
    }

    // Chamado quando o usuário fecha o app pelo botão recents (X no multitarefa)
    // Se não há corrida ativa, para o service e remove a notificação
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "📱 App removido da lista de recentes")
        if (!estaCorrendo) {
            Log.d(TAG, "⏹️ Sem corrida ativa — parando service")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        // Se corrida ativa: service continua rodando em background (comportamento correto)
    }

    private fun atualizarNotificacao(textoCustomizado: String? = null) {
        val texto = textoCustomizado ?: run {
            val dist = _distanciaMetros.value / 1000.0
            val tempo = formatarTempo(_tempoTotalSegundos.value)
            "${String.format("%.2f", dist)} km | $tempo | ${_paceAtual.value} /km"
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, criarNotificacao(texto))
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Utilitários
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun calcularDistancia(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371000.0  // Raio da Terra em metros
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2) * sin(dLng / 2)
        
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    private fun formatarPace(segundosPorKm: Double): String {
        if (segundosPorKm <= 0 || segundosPorKm.isNaN() || segundosPorKm.isInfinite()) {
            return "--:--"
        }
        
        // Sanidade: apenas remove valores fisicamente impossíveis
        // 90s/km = 1:30/km cobre até sprints de elite em Z7
        val pace = when {
            segundosPorKm < 90  -> return "--:--"  // Impossível (< 1:30/km)
            segundosPorKm > 1200 -> return "--:--"  // Muito lento (> 20 min/km)
            else -> segundosPorKm
        }
        
        val minutos = (pace / 60).toInt()
        val segundos = (pace % 60).toInt()
        return "%d:%02d".format(minutos, segundos)
    }

    private fun formatarTempo(segundos: Long): String {
        val h = segundos / 3600
        val m = (segundos % 3600) / 60
        val s = segundos % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Constantes
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    companion object {
        private const val TAG = "RunningService"
        const val CHANNEL_ID = "running_channel"
        const val NOTIFICATION_ID = 42
        const val NOTIFICATION_GPS_ERROR_ID = 43
        
        const val ACTION_START = "START"
        const val ACTION_PAUSE = "PAUSE"
        const val ACTION_RESUME = "RESUME"
        const val ACTION_STOP = "STOP"

        // Intent da notificação persistente → navegação direta para a corrida
        const val ACTION_SHOW_RUNNING = "ACTION_SHOW_RUNNING_SCREEN"
        const val EXTRA_EVENT_ID = "EVENT_ID"
        
        // DOIS LIMIARES DE ACCURACY — comportamento diferente por contexto:
        //
        // IN_MEMORY_ACCURACY (50m): limiar permissivo para o StateFlow / UI.
        //   Mantém o ponto "visível" no mapa mesmo com GPS ruim (ex: túnel, prédios).
        //   O usuário vê a posição continuar atualizada, reduzindo a sensação de "freeze".
        //
        // ROOM_ACCURACY (20m): limiar rígido para persistência no banco de dados.
        //   Só salva pontos com leitura GPS confiável — evita "saltos" de 50-100m no
        //   histórico que inflam a distância total e sujam o heatmap de pace.
        //   20m é o padrão usado pelo Strava/Garmin para corridas urbanas.
        const val MAX_ACCURACY_METERS = 50f       // descarta da UI
        const val ROOM_ACCURACY_METERS = 20f      // descarta da persistência
    }
}
