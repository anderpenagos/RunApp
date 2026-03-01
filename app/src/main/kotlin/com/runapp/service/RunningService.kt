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

    private val checkpointFile: File
        get() = File(applicationContext.filesDir, "emergency_run_backup.json")

    private val gson = Gson()

    private lateinit var database: RunDatabase

    private var sessionId: String = ""

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
                val tmpFile = File(applicationContext.filesDir, "emergency_run_backup.tmp")
                tmpFile.writeText(json)
                if (!tmpFile.renameTo(checkpointFile)) {
                    checkpointFile.writeText(json)
                    tmpFile.delete()
                }
                Log.d(TAG, "💾 Checkpoint atômico: ${_distanciaMetros.value.toInt()}m (Room: ${rota.size} pts)")
            } catch (e: Exception) {
                Log.e(TAG, "⚠️ Falha ao salvar checkpoint", e)
            }
        }
    }

    private fun deletarCheckpoint() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                checkpointFile.delete()
                File(applicationContext.filesDir, "emergency_run_backup.tmp").delete()
                if (sessionId.isNotEmpty()) {
                    database.routePointDao().deleteSession(sessionId)
                }
            } catch (_: Exception) {}
        }
    }

    private fun lerCheckpointSync(): CheckpointData? {
        return try {
            if (!checkpointFile.exists()) null
            else gson.fromJson(checkpointFile.readText(), CheckpointData::class.java)
        } catch (_: Exception) { null }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Coroutines e Lifecycle
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private val serviceJob = kotlinx.coroutines.SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var timerJob: Job? = null
    
    private var wakeLock: PowerManager.WakeLock? = null

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // GPS
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    
    private val rota = mutableListOf<LatLngPonto>()
    private val ultimasLocalizacoes = mutableListOf<Location>()

    private var janelaAtualSegundos = 12

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Custódia do Treino — sobrevive à morte da ViewModel
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private var treinoAtivo: WorkoutEvent? = null
    private var passosAtivos: List<PassoExecucao> = emptyList()
    private var indexPassoAtivo: Int = -1

    fun setDadosTreino(treino: WorkoutEvent, passos: List<PassoExecucao>) {
        treinoAtivo = treino
        passosAtivos = passos
        Log.d(TAG, "📋 Treino salvo no service: ${treino.name} (${passos.size} passos)")
        atualizarNotificacao()
    }

    fun setIndexPassoAtivo(index: Int) { indexPassoAtivo = index }
    fun getTreinoAtivo(): WorkoutEvent? = treinoAtivo
    fun getPassosAtivos(): List<PassoExecucao> = passosAtivos
    fun getIndexPassoAtivo(): Int = indexPassoAtivo
    fun isCorrendo(): Boolean = estaCorrendo
    fun isPausado(): Boolean = estaPausado
    fun getRotaCompleta(): List<LatLngPonto> = rota.toList()
    fun getSessionId(): String = sessionId

    fun pularPasso() {
        if (passosAtivos.isEmpty()) return
        val indexAtual = indexPassoAtivo.coerceAtLeast(0)
        if (indexAtual >= passosAtivos.lastIndex) return

        val agora = System.currentTimeMillis()
        if (agora - ultimoCliquePasso < 1000L) return
        ultimoCliquePasso = agora

        var tempoDestino = 0L
        for (i in 0..indexAtual) tempoDestino += passosAtivos[i].duracao

        val delta = (tempoDestino - _tempoTotalSegundos.value) * 1000L
        tempoPausadoTotal -= delta
        _tempoTotalSegundos.value = tempoDestino

        vibrar()
        Log.d(TAG, "⏭️ Passo ${indexAtual} → ${indexAtual + 1} | tempo → ${tempoDestino}s | delta=${delta}ms")
    }

    fun voltarPasso() {
        if (passosAtivos.isEmpty()) return
        val indexAtual = indexPassoAtivo.coerceAtLeast(0)

        val agora = System.currentTimeMillis()
        if (agora - ultimoCliquePasso < 1000L) return
        ultimoCliquePasso = agora

        val tempoInicioAtual = passosAtivos.take(indexAtual).sumOf { it.duracao.toLong() }
        val tempoNoPasso = _tempoTotalSegundos.value - tempoInicioAtual

        val tempoDestino = if (tempoNoPasso > 3 || indexAtual == 0) {
            tempoInicioAtual
        } else {
            passosAtivos.take(indexAtual - 1).sumOf { it.duracao.toLong() }
        }

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

    // ── Filtro de Kalman para suavização de posição GPS ──────────────────────
    private var kalmanLat: Double = 0.0
    private var kalmanLng: Double = 0.0
    private var kalmanVariancia: Float = -1f
    private val KALMAN_Q = 3.0f

    private var timestampVoltouDoGap = 0L
    private val KALMAN_REENTRY_MS = 3_000L

    private var cadenciaAnteriorKalman = 0

    private fun aplicarKalman(lat: Double, lng: Double, accuracy: Float, deltaMs: Long): Pair<Double, Double> {
        if (kalmanVariancia < 0f) {
            kalmanLat = lat
            kalmanLng = lng
            kalmanVariancia = accuracy * accuracy
            cadenciaAnteriorKalman = _cadencia.value
            return Pair(lat, lng)
        }

        val deltaS = (deltaMs / 1000.0f).coerceAtLeast(0.1f)
        kalmanVariancia += KALMAN_Q * deltaS

        val agora = System.currentTimeMillis()
        val emReentrada = timestampVoltouDoGap > 0 && (agora - timestampVoltouDoGap) < KALMAN_REENTRY_MS
        val inflacaoReentrada = if (emReentrada) {
            val progresso = (agora - timestampVoltouDoGap).toFloat() / KALMAN_REENTRY_MS
            10f - 9f * progresso  // 10× → 1× ao longo de 3s
        } else 1f

        val medicaoVariancia = accuracy * accuracy * inflacaoReentrada

        val cadenciaAtual = _cadencia.value
        val fatorMovimento = when {
            cadenciaAnteriorKalman < 60 && cadenciaAtual >= 60 -> 1.0f
            cadenciaAtual < 60 -> 0.0f
            else -> (cadenciaAtual.toFloat() / 120f).coerceIn(0.1f, 1.0f)
        }
        cadenciaAnteriorKalman = cadenciaAtual

        val kBase = kalmanVariancia / (kalmanVariancia + medicaoVariancia)
        val k = kBase * fatorMovimento
        kalmanLat += k * (lat - kalmanLat)
        kalmanLng += k * (lng - kalmanLng)
        kalmanVariancia = (1f - k) * kalmanVariancia

        return Pair(kalmanLat, kalmanLng)
    }

    // ── FIX BUG 1: propriedade restaurada — get/set estavam órfãos sem ela ───
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
    private var ultimoCliquePasso: Long = 0L

    // ── GPS Cold Start (salto inicial após recovery) ──────────────────────────
    private var modoRecuperacaoGps = false
    private var contadorPontosRecuperacao = 0
    private val MAX_VELOCIDADE_HUMANA_MS = 11.0  // ~40 km/h — cobre sprints de elite

    // Auto-pause
    private var ultimaLocalizacaoSignificativa: Location? = null
    private var contadorSemMovimento = 0
    private var contadorEmMovimento = 0
    private val LIMITE_SEM_MOVIMENTO = 3
    private val LIMITE_RETOMAR_MOVIMENTO = 2
    private val DISTANCIA_MINIMA_MOVIMENTO = 1.5
    private var autoPauseFuncaoAtiva = true
    
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

    private val _cadencia = MutableStateFlow(0)
    val cadencia: StateFlow<Int> = _cadencia.asStateFlow()

    private lateinit var sensorManager: SensorManager
    private var stepDetector: Sensor? = null
    private var acelerometro: Sensor? = null
    private var usandoStepDetector = false

    private val timestampsPassos = ArrayDeque<Long>(50)
    private var ultimoTimestampPasso = 0L

    private var thresholdAceleracao = 13.0f
    private var somaUltimosPicos = 0f
    private var contadorPicos = 0

    // ── Gap-fill por cadência (Dead Reckoning) ────────────────────────────────
    private var ultimoTempoGps      = 0L
    private var passosNoGap         = 0
    private var emGapGps            = false
    private val GAP_THRESHOLD_MS    = 3_000L    // gap > 3s ativa dead reckoning
    private val GAP_TIMEOUT_MS      = 120_000L  // gap > 2min desativa acúmulo (túnel longo)
    private var gapTimeoutNotificado = false     // evita spam de notificação
    private var primeiropontoAposGap = false

    // ── Buffer de pace dos últimos 30s ────────────────────────────────────────
    private data class PaceSnapshot(val timestampMs: Long, val paceSegKm: Double)
    private val bufferPace30s = ArrayDeque<PaceSnapshot>(35)
    private var stepLengthNoGap = 0.0

    // ── Auto-Learner de step length ───────────────────────────────────────────
    private var stepLengthAprendido = 0.0
    private var passosDesdeUltimoGpsBom = 0
    private var distDesdeUltimoGpsBom = 0.0
    private val ALPHA_STEP_LEARNER = 0.3

    // FIX 7: Separação entre valor interno de EMA e string da UI.
    private var ultimoPaceEmaInterno: Double? = null
    
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

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        if (stepDetector != null) {
            usandoStepDetector = true
            Log.d(TAG, "👟 TYPE_STEP_DETECTOR disponível — usando hardware nativo (economia de bateria)")
        } else {
            acelerometro = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            usandoStepDetector = false
            if (acelerometro == null) {
                Log.w(TAG, "⚠️ Nenhum sensor de passo disponível — cadência desativada")
            } else {
                Log.d(TAG, "📡 Fallback para TYPE_LINEAR_ACCELERATION (STEP_DETECTOR não encontrado)")
            }
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RunApp::RunningServiceWakeLock"
        )

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
                Log.w(TAG, "⚠️ Service reiniciado pelo Android (intent null) — tentando recuperar via Room")
                recuperarAposProcessDeath()
            }
        }

        return START_STICKY
    }

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

            sessionId           = checkpoint.sessionId
            timestampInicioWall = checkpoint.timestampInicioWall
            tempoPausadoTotalMs = checkpoint.tempoPausadoTotalMs

            // FIX 1 — PROTEÇÃO CONTRA REINÍCIO DO CELULAR:
            // elapsedRealtime é monotônico MAS reseta quando o aparelho desliga/reinicia.
            val agora = SystemClock.elapsedRealtime()
            if (agora < checkpoint.elapsedRealtimeInicio) {
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

            // RECONCILIAÇÃO distância checkpoint vs Room
            if (pontosRecuperados.size >= 2) {
                var distanciaRoom = 0.0
                for (i in 1 until pontosRecuperados.size) {
                    distanciaRoom += calcularDistancia(
                        pontosRecuperados[i-1].lat, pontosRecuperados[i-1].lng,
                        pontosRecuperados[i].lat,   pontosRecuperados[i].lng
                    )
                }
                val distanciaFinal = maxOf(checkpoint.distanciaMetros, distanciaRoom)
                if (kotlin.math.abs(distanciaFinal - checkpoint.distanciaMetros) > 1.0) {
                    Log.d(TAG, "📐 Distância reconciliada: ${checkpoint.distanciaMetros.toInt()}m → ${distanciaFinal.toInt()}m")
                    _distanciaMetros.value = distanciaFinal
                }
            }

            Log.d(TAG, "✅ ${pontosRecuperados.size} pontos GPS recuperados do Room")

            if (pontosRecuperados.isNotEmpty()) {
                modoRecuperacaoGps = true
                contadorPontosRecuperacao = 0
                Log.d(TAG, "🛡️ Modo GPS recovery ativado — filtrando saltos impossíveis nos primeiros pontos")
            }

            iniciarForeground("♻️ Corrida recuperada — ${String.format("%.2f", checkpoint.distanciaMetros / 1000)}km já registrados")
            wakeLock?.acquire(6 * 60 * 60 * 1000L)

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
        
        criarCanalNotificacao()
        iniciarForeground()
        
        wakeLock?.acquire(6 * 60 * 60 * 1000L /*6 horas*/)
        
        serviceScope.launch {
            val prefs = PreferencesRepository(applicationContext)
            autoPauseFuncaoAtiva = prefs.autoPauseEnabled.first()
            Log.d(TAG, "⚙️ Auto-pause ${if (autoPauseFuncaoAtiva) "ativado" else "desativado"}")
        }
        
        sessionId = UUID.randomUUID().toString()
        Log.d(TAG, "🆔 Sessão iniciada: $sessionId")

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

        // Reset Kalman — nova corrida começa sem histórico de posição anterior
        kalmanVariancia = -1f

        rota.clear()
        ultimasLocalizacoes.clear()
        ultimoPaceEmaInterno = null
        janelaAtualSegundos = 12
        // Capturar ambas âncoras no mesmo instante
        timestampInicioWall   = System.currentTimeMillis()     // para display
        elapsedRealtimeInicio = SystemClock.elapsedRealtime()  // para cronômetro
        tempoPausadoTotalMs   = 0
        _distanciaMetros.value = 0.0
        _tempoTotalSegundos.value = 0
        estaPausado = false
        estaCorrendo = true

        timestampsPassos.clear()
        ultimoTimestampPasso = 0L
        thresholdAceleracao = 13.0f
        somaUltimosPicos = 0f
        contadorPicos = 0
        _cadencia.value = 0

        ultimoTempoGps = System.currentTimeMillis()
        passosNoGap = 0
        emGapGps = false
        gapTimeoutNotificado = false
        primeiropontoAposGap = false
        bufferPace30s.clear()
        stepLengthNoGap = 0.0
        stepLengthAprendido = 0.0
        passosDesdeUltimoGpsBom = 0
        distDesdeUltimoGpsBom = 0.0

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
        
        iniciarAtualizacoesGPS()
        iniciarTimer()
    }

    private fun pausarRastreamento() {
        Log.d(TAG, "⏸️ Pausando rastreamento")
        estaPausado = true
        elapsedRealtimePausaInicio = SystemClock.elapsedRealtime()
        atualizarNotificacao("Corrida pausada")
    }

    private fun retomarRastreamento() {
        Log.d(TAG, "▶️ Retomando rastreamento")
        
        if (estaPausado) {
            val tempoPausaMs = SystemClock.elapsedRealtime() - elapsedRealtimePausaInicio
            tempoPausadoTotalMs += tempoPausaMs
            estaPausado = false
            atualizarNotificacao("Corrida em andamento")
        }
    }

    private fun pararRastreamento() {
        Log.d(TAG, "⏹️ Parando rastreamento")

        estaCorrendo = false
        _rotaAtual.value = rota.toList()

        sensorManager.unregisterListener(this)
        _cadencia.value = 0

        timerJob?.cancel()
        timerJob = null
        
        pararAtualizacoesGPS()
        
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        
        deletarCheckpoint()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "🔴 Service onDestroy")

        timerJob?.cancel()
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
        if (!estaCorrendo || estaPausado || event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> {
                registrarPasso(System.currentTimeMillis())
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val magnitude = kotlin.math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
                detectarPassoPorMagnitude(magnitude)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* não usado */ }

    private fun registrarPasso(agora: Long) {
        if (agora - ultimoTimestampPasso < 200L) return
        ultimoTimestampPasso = agora

        timestampsPassos.addLast(agora)
        while (timestampsPassos.isNotEmpty() && timestampsPassos.first() < agora - 10_000L) {
            timestampsPassos.removeFirst()
        }

        if (timestampsPassos.size >= 3) {
            val spm = (timestampsPassos.size / 10.0 * 60).toInt()
            if (spm in 60..220) {
                _cadencia.value = spm
            }
        }

        if (emGapGps && estaCorrendo && !estaPausado && !_autoPausado.value) {
            passosNoGap++
        }
    }

    fun verificarGapGps() {
        if (!estaCorrendo || estaPausado || _autoPausado.value) return

        val agora = System.currentTimeMillis()
        val gapMs = agora - ultimoTempoGps

        // ── FIX A — "Abismo de Cadência" (parada brusca) ─────────────────────
        // verificarGapGps() é chamada a cada segundo pelo timer — é o único lugar
        // que roda continuamente MESMO quando não há passos.
        // Se o último passo foi há > 2s e o buffer ainda tem timestamps antigos,
        // o fatorMovimento do Kalman continua em 1.0 por inércia e o GPS pode
        // fazer a posição "derivar" enquanto o corredor está parado.
        // Solução: limpar o buffer e zerar a cadência imediatamente.
        // 3000ms (não 2000ms): dá margem para caminhadas de recuperação ultra lentas
        // em subidas íngremes (~28 SPM = 1 passo a cada 2.1s). Com 2s o timer zeraria
        // a cadência entre dois passos legítimos. 3s ainda é rápido para paradas reais.
        if (ultimoTimestampPasso > 0L && agora - ultimoTimestampPasso > 3_000L && _cadencia.value > 0) {
            timestampsPassos.clear()
            _cadencia.value = 0
            Log.d(TAG, "🛑 Cadência zerada (parada detectada: ${agora - ultimoTimestampPasso}ms sem passo)")
        }

        if (!emGapGps && gapMs > GAP_THRESHOLD_MS) {
            emGapGps = true
            passosNoGap = 0
            gapTimeoutNotificado = false  // reset do timeout a cada novo gap

            val pace30s = calcularPaceUltimos30s()
            val cadenciaAtual = _cadencia.value

            stepLengthNoGap = when {
                stepLengthAprendido > 0.3 -> stepLengthAprendido
                pace30s > 0 && cadenciaAtual >= 60 -> {
                    val velocidadeMs = 1000.0 / pace30s
                    velocidadeMs / (cadenciaAtual / 60.0)
                }
                else -> {
                    val paceGlobal = calcularPaceSegKmInterno(_paceMedia.value)
                    if (paceGlobal > 0 && cadenciaAtual >= 60)
                        (1000.0 / paceGlobal) / (cadenciaAtual / 60.0)
                    else 0.0
                }
            }.coerceIn(0.5, 2.5)

            Log.w(TAG, "🔴 Gap GPS (${gapMs}ms) — dead reckoning ativo. " +
                "stepLength=${String.format("%.2f", stepLengthNoGap)}m " +
                "(learner=${String.format("%.2f", stepLengthAprendido)}m, " +
                "pace30s=${if (pace30s > 0) formatarPace(pace30s) else "--"})")
        }

        // ── FIX B — "Túnel Infinito" (timeout de 2 minutos) ──────────────────
        // Dead reckoning acumula erro ~5% por km. Após 2min sem GPS (≈ 1-2km de
        // túnel), o erro já seria de 50-100m — pior do que parar de contar.
        // Desativamos o acúmulo e notificamos o usuário UMA VEZ (anti-spam).
        if (emGapGps && gapMs > GAP_TIMEOUT_MS) {
            if (!gapTimeoutNotificado) {
                gapTimeoutNotificado = true
                passosNoGap = 0  // descarta passos acumulados além do limite
                Log.w(TAG, "⏱️ GPS ausente há ${gapMs / 1000}s — dead reckoning suspenso (limite de 2min)")

                val notif = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("RunApp — Sem sinal GPS 🛰️")
                    .setContentText("Sinal perdido há ${gapMs / 60_000}min. Distância pausada até o GPS voltar.")
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .build()
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_GPS_ERROR_ID, notif)
            }
            return  // para de acumular — não executa o bloco de distância abaixo
        }

        if (emGapGps && passosNoGap > 0 && stepLengthNoGap > 0.0) {
            val distanciaEstimada = passosNoGap * stepLengthNoGap
            _distanciaMetros.value += distanciaEstimada
            calcularPaceMedia()
            Log.d(TAG, "🦶 Gap-fill: ${passosNoGap}p × ${String.format("%.2f", stepLengthNoGap)}m = ${String.format("%.1f", distanciaEstimada)}m")
            passosNoGap = 0
        }
    }

    private fun calcularPaceUltimos30s(): Double {
        val agora = System.currentTimeMillis()
        while (bufferPace30s.isNotEmpty() && bufferPace30s.first().timestampMs < agora - 30_000L) {
            bufferPace30s.removeFirst()
        }
        if (bufferPace30s.isEmpty()) return 0.0
        val validos = bufferPace30s.filter { it.paceSegKm in 60.0..1200.0 }
        return if (validos.isEmpty()) 0.0 else validos.map { it.paceSegKm }.average()
    }

    private fun calcularPaceSegKmInterno(paceFormatado: String): Double {
        if (paceFormatado == "--:--") return 0.0
        return runCatching {
            val partes = paceFormatado.split(":")
            partes[0].toLong() * 60.0 + partes[1].toLong()
        }.getOrDefault(0.0)
    }

    private fun detectarPassoPorMagnitude(magnitude: Float) {
        val agora = System.currentTimeMillis()

        if (agora - ultimoTimestampPasso > 2000L && ultimoTimestampPasso > 0L) {
            thresholdAceleracao = 13.0f
        }

        if (magnitude < thresholdAceleracao) return

        if (agora - ultimoTimestampPasso < 350L) return

        somaUltimosPicos += magnitude
        contadorPicos++
        if (contadorPicos >= 8) {
            val mediaPicos = somaUltimosPicos / contadorPicos
            thresholdAceleracao = (mediaPicos * 0.72f).coerceAtLeast(12.5f)
            somaUltimosPicos = 0f
            contadorPicos = 0
        }

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

                salvarCheckpoint()
            }
        }
    }

    private fun iniciarAtualizacoesGPS() {
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000L
        )
            .setMinUpdateDistanceMeters(0f)
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
        if (estaPausado) return
        
        // FILTRO DE PONTO "ZUMBI" (GPS Stale):
        // Usa elapsedRealtimeNanos (monotônico) — imune a saltos de NTP/fuso.
        val idadeMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
            SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        )
        if (idadeMs > 10_000L) {
            Log.d(TAG, "👻 Ponto GPS 'zumbi' descartado: ${idadeMs}ms de atraso (elapsedRealtimeNanos)")
            return
        }

        if (location.accuracy > MAX_ACCURACY_METERS) {
            Log.d(TAG, "⚠️ Localização descartada: accuracy=${location.accuracy}m")
            return
        }

        // FILTRO DE SALTO INICIAL (GPS Cold Start após recovery)
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
                modoRecuperacaoGps = false
                Log.d(TAG, "✅ GPS recovery: primeiro ponto válido aceito (${distJump.toInt()}m, " +
                    "${String.format("%.1f", velocidadeMs)} m/s)")
            }
        }

        val agora = System.currentTimeMillis()

        if (emGapGps) {
            val duracaoGapMs = agora - ultimoTempoGps
            Log.d(TAG, "✅ GPS recuperado — re-entrada suave ativada por ${KALMAN_REENTRY_MS}ms")
            emGapGps = false
            passosNoGap = 0
            primeiropontoAposGap = true
            timestampVoltouDoGap = agora
            gapTimeoutNotificado = false  // libera notificação para o próximo gap
            passosDesdeUltimoGpsBom = 0
            distDesdeUltimoGpsBom = 0.0

            // "Toque de mestre": confirma ao usuário que o sistema funcionou.
            // Só notifica se o gap foi longo o suficiente para o usuário ter percebido
            // (> 10s). Gaps de 3-10s são silenciosos — o corredor nem notou.
            // Notificação SILENCIOSA (sem som/vibração) para não assustar no meio da corrida.
            if (duracaoGapMs > 10_000L) {
                val distKm = String.format("%.2f", _distanciaMetros.value / 1000.0)
                val msg = if (duracaoGapMs >= GAP_TIMEOUT_MS)
                    "GPS voltou após ${duracaoGapMs / 1000}s. Distância retomada em ${distKm}km — percurso sincronizando..."
                else
                    "Sinal GPS recuperado. Sincronizando percurso..."

                val notif = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("RunApp — GPS Recuperado ✅")
                    .setContentText(msg)
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)  // silenciosa
                    .setAutoCancel(true)
                    .build()
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_GPS_ERROR_ID, notif)  // substitui a de erro no mesmo ID
                Log.d(TAG, "📡 Notificação de GPS recuperado enviada (gap=${duracaoGapMs / 1000}s)")
            }
        }
        ultimoTempoGps = agora

        val deltaMs = if (rota.isNotEmpty()) (agora - rota.last().tempo).coerceAtLeast(100L) else 1000L
        val (latK, lngK) = aplicarKalman(location.latitude, location.longitude, location.accuracy, deltaMs)

        val pontoNovo = LatLngPonto(
            lat = latK,
            lng = lngK,
            alt = location.altitude,
            tempo = agora,
            accuracy = location.accuracy,
            paceNoPonto = ultimoPaceEma ?: 0.0,
            cadenciaNoPonto = _cadencia.value
        )

        _posicaoAtual.value = pontoNovo

        if (rota.isEmpty()) {
            rota.add(pontoNovo)
            _rotaAtual.value = rota.toList()
            ultimaLocalizacaoSignificativa = location
            return
        }

        if (autoPauseFuncaoAtiva) {
            verificarAutoPause(location)
        }
        
        if (_autoPausado.value) {
            return
        }

        val ultimoPonto = rota.last()

        // DISTÂNCIA VIA VELOCIDADE DOPPLER vs HAVERSINE
        val deltaTSegundos = deltaMs / 1000.0
        val usarDoppler = location.hasSpeed() &&
            location.speed > 0.1f &&
            deltaTSegundos in 0.5..3.0 &&
            (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O ||
             location.speedAccuracyMetersPerSecond < 0.5f)

        val distancia = if (usarDoppler) {
            val speedAnterior = ultimasLocalizacoes.lastOrNull()?.speed?.toDouble() ?: location.speed.toDouble()
            val speedMedia = (speedAnterior + location.speed) / 2.0
            speedMedia * deltaTSegundos
        } else {
            calcularDistancia(ultimoPonto.lat, ultimoPonto.lng, pontoNovo.lat, pontoNovo.lng)
        }

        Log.d(TAG, "📏 Dist: ${String.format("%.1f", distancia)}m via ${if (usarDoppler) "Doppler(${String.format("%.1f", location.speed)}m/s)" else "Haversine"}")

        rota.add(pontoNovo)

        if (primeiropontoAposGap) {
            primeiropontoAposGap = false
            Log.d(TAG, "📍 Ponto de relocalização pós-gap — distância não somada (Fix A)")
            // Continua: persiste no Room, emite StateFlow, mas NÃO soma distância
        } else {
            // Auto-Learner: calibra step_length quando GPS está excelente
            if (location.accuracy < 8f && _cadencia.value >= 60) {
                passosDesdeUltimoGpsBom++
                distDesdeUltimoGpsBom += distancia

                if (passosDesdeUltimoGpsBom >= 30) {
                    val passadaMedida = distDesdeUltimoGpsBom / passosDesdeUltimoGpsBom

                    val inclinacaoAtual = if (rota.size >= 2) {
                        val dAlt = rota.last().alt - rota[rota.size - 2].alt
                        val dDist = calcularDistancia(
                            rota[rota.size - 2].lat, rota[rota.size - 2].lng,
                            rota.last().lat, rota.last().lng
                        ).coerceAtLeast(0.1)
                        kotlin.math.abs(dAlt / dDist * 100)
                    } else 0.0

                    val emInclinacaoAcentuada = inclinacaoAtual > 2.0

                    val ehOutlier = stepLengthAprendido > 0.0 &&
                        kotlin.math.abs(passadaMedida - stepLengthAprendido) / stepLengthAprendido > 0.30

                    when {
                        emInclinacaoAcentuada ->
                            Log.d(TAG, "📐 Auto-Learner em hold (inclinação=${String.format("%.1f", inclinacaoAtual)}%)")
                        ehOutlier ->
                            Log.d(TAG, "📐 Auto-Learner outlier rejeitado: ${String.format("%.2f", passadaMedida)}m (EMA=${String.format("%.2f", stepLengthAprendido)}m)")
                        passadaMedida in 0.5..2.5 -> {
                            stepLengthAprendido = if (stepLengthAprendido == 0.0) {
                                passadaMedida
                            } else {
                                ALPHA_STEP_LEARNER * passadaMedida + (1 - ALPHA_STEP_LEARNER) * stepLengthAprendido
                            }
                            Log.d(TAG, "📐 Auto-Learner: passada=${String.format("%.2f", passadaMedida)}m → EMA=${String.format("%.2f", stepLengthAprendido)}m")
                        }
                    }

                    passosDesdeUltimoGpsBom = 0
                    distDesdeUltimoGpsBom = 0.0
                }
            } else {
                if (location.accuracy >= 8f) {
                    passosDesdeUltimoGpsBom = 0
                    distDesdeUltimoGpsBom = 0.0
                }
            }

            _distanciaMetros.value += distancia

            val paceAtualSegKm = calcularPaceSegKmInterno(_paceAtual.value)
            if (paceAtualSegKm in 60.0..1200.0) {
                bufferPace30s.addLast(PaceSnapshot(agora, paceAtualSegKm))
                while (bufferPace30s.isNotEmpty() && bufferPace30s.first().timestampMs < agora - 35_000L) {
                    bufferPace30s.removeFirst()
                }
            }
        }
        // ── FIX BUG 2: comentário estava como texto solto após o } — corrigido ──
        // PERSISTÊNCIA NO ROOM — só pontos com GPS confiável (< 20m)
        // O limiar é mais rígido que o da UI (25m) para evitar "saltos" que inflam
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

        if (_rotaAtual.subscriptionCount.value > 0) {
            if (rota.size == 1 || rota.size % 5 == 0) {
                _rotaAtual.value = rota.toList()
            }
        }

        ultimasLocalizacoes.add(location)
        
        val tempoCorte = agora - (janelaAtualSegundos * 1000)
        ultimasLocalizacoes.removeAll { it.time < tempoCorte }
        
        if (ultimasLocalizacoes.size >= 2) {
            val tempoJanela = (ultimasLocalizacoes.last().time - ultimasLocalizacoes.first().time) / 1000.0
            if (tempoJanela > (janelaAtualSegundos * 2)) {
                Log.w(TAG, "⚠️ Gap temporal detectado (${tempoJanela}s), resetando janela de pace")
                ultimasLocalizacoes.clear()
                ultimasLocalizacoes.add(location)
                _paceAtual.value = "--:--"
                return
            }
        }

        if (location.hasSpeed() && location.speed > 6.5f) {
            Log.w(TAG, "⚠️ Velocidade GPS suspeita: ${location.speed} m/s, descartando ponto de pace")
            _paceAtual.value = "--:--"
            return
        }

        calcularPaceAtual()
        calcularPaceMedia()

        Log.d(TAG, "📍 Dist: ${String.format("%.1f", _distanciaMetros.value)}m | Pace: ${_paceAtual.value} | Janela: ${ultimasLocalizacoes.size}")
    }

    private fun verificarAutoPause(location: Location) {
        val ultimaLoc = ultimaLocalizacaoSignificativa ?: run {
            ultimaLocalizacaoSignificativa = location
            return
        }

        val emMovimento: Boolean = if (location.hasSpeed() &&
            (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O || location.speedAccuracyMetersPerSecond < 1.5f)) {
            location.speed >= 0.5f
        } else {
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
            contadorSemMovimento++
            contadorEmMovimento = 0

            if (_autoPausado.value) {
                ultimaLocalizacaoSignificativa = location
            }

            if (contadorSemMovimento >= LIMITE_SEM_MOVIMENTO && !_autoPausado.value) {
                Log.d(TAG, "⏸️ Auto-pause ativado (${contadorSemMovimento}s sem movimento, speed=${location.speed} m/s)")
                _autoPausado.value = true
                atualizarNotificacao("Auto-pausado (sem movimento)")
            }
        } else {
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

    private fun calcularPaceSegKm(paceFormatado: String): Double {
        if (paceFormatado == "--:--") return 0.0
        return runCatching {
            val partes = paceFormatado.split(":")
            partes[0].toLong() * 60.0 + partes[1].toLong()
        }.getOrDefault(0.0)
    }

    private fun calcularPaceAtual() {
        if (ultimasLocalizacoes.size < 2) {
            _paceAtual.value = "--:--"
            return
        }

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

        var distanciaJanela = 0.0
        for (i in 1 until pontosJanela.size) {
            val d = calcularDistancia(
                pontosJanela[i - 1].latitude, pontosJanela[i - 1].longitude,
                pontosJanela[i].latitude,     pontosJanela[i].longitude
            )
            if (d > 0.5) distanciaJanela += d
        }

        val tempoJanelaSegundos = (pontosJanela.last().time - pontosJanela.first().time) / 1000.0

        if (distanciaJanela < 1.0 || tempoJanelaSegundos < 1.0) {
            _paceAtual.value = "--:--"
            return
        }

        val paceBruto = (tempoJanelaSegundos / distanciaJanela) * 1000.0

        if (paceBruto < 90.0 || paceBruto > 1200.0) {
            _paceAtual.value = "--:--"
            return
        }

        val alpha = if (janelaAtualSegundos <= 5) 0.4 else 0.25
        val paceEma = ultimoPaceEmaInterno?.let { anterior ->
            (paceBruto * alpha) + (anterior * (1.0 - alpha))
        } ?: paceBruto

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

                    verificarGapGps()
                    
                    if (tempoDecorrido % 5 == 0L) {
                        atualizarNotificacao()
                    }

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

    private fun iniciarForeground(texto: String? = null) {
        val notif = criarNotificacao(texto)
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
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
                startForeground(
                    NOTIFICATION_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            }
            else -> {
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
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_SHOW_RUNNING
            putExtra(EXTRA_EVENT_ID, treinoAtivo?.id ?: -1L)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val conteudo = texto ?: "GPS registrando sua corrida..."

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
            .addAction(pausaResumeIcon, pausaResumeLabel, pausaResumePendingIntent)
            .addAction(android.R.drawable.ic_media_next, "⏹ Parar", pararPendingIntent)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "📱 App removido da lista de recentes")
        if (!estaCorrendo) {
            Log.d(TAG, "⏹️ Sem corrida ativa — parando service")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
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
        val R = 6371000.0
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
        
        val pace = when {
            segundosPorKm < 90   -> return "--:--"
            segundosPorKm > 1200 -> return "--:--"
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

        const val ACTION_SHOW_RUNNING = "ACTION_SHOW_RUNNING_SCREEN"
        const val EXTRA_EVENT_ID = "EVENT_ID"
        
        const val MAX_ACCURACY_METERS = 25f       // descarta da UI
        const val ROOM_ACCURACY_METERS = 15f      // descarta da persistência
    }
}
