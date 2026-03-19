package com.runapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.os.Handler
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

    // ── Buffer Circular de Rota ───────────────────────────────────────────────
    // Mantém apenas os últimos BUFFER_MAX_PONTOS em RAM (≈ 5 minutos a 1pt/s).
    // Room persiste TODOS os pontos → consumo de memória constante em qualquer
    // distância (5k, maratona, ultramaratona de 24h) → previne ANR em mid-range.
    //
    // getRotaCompleta() faz merge Room + buffer para GPX export e tela de resumo.
    // _rotaAtual emite apenas o buffer (trecho recente → suficiente para o mapa).
    private val rota = ArrayDeque<LatLngPonto>()
    private val BUFFER_MAX_PONTOS = 300          // ~5 minutos de buffer em RAM
    private var rotaTotalPontos   = 0            // contador total (não o tamanho do buffer)

    /** Adiciona ponto ao buffer circular; pontos mais antigos são descartados da RAM (já estão no Room). */
    private fun adicionarPontoRota(ponto: LatLngPonto) {
        rota.addLast(ponto)
        rotaTotalPontos++
        if (rota.size > BUFFER_MAX_PONTOS) rota.removeFirst()
    }

    /**
     * Retorna a rota COMPLETA: Room (histórico) + buffer RAM (últimos 5min).
     *
     * Fence por timestamp: garante que nenhum ponto seja duplicado no limiar
     * entre o que foi persistido e o que ainda está em memória.
     * sortedBy { tempo }: protege contra inserções fora de ordem por latência de IO.
     */
    suspend fun getRotaCompletaComRoom(): List<LatLngPonto> = withContext(Dispatchers.IO) {
        try {
            val pontosRoom   = database.routePointDao().getSessionPoints(sessionId)
                .map { it.toLatLngPonto() }
            val ultimoTempo  = pontosRoom.lastOrNull()?.tempo ?: 0L
            val pontosBuffer = rota.toList().filter { it.tempo > ultimoTempo }
            (pontosRoom + pontosBuffer).sortedBy { it.tempo }
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ Falha no merge Room+buffer", e)
            rota.toList()  // fallback para o buffer em RAM
        }
    }
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
    fun getRotaCompleta(): List<LatLngPonto> = rota.toList()   // buffer RAM (trecho recente)
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

    // ── Kalman Q Dinâmico ─────────────────────────────────────────────────────
    // Q (ruído de processo) controla o equilíbrio entre confiança no modelo de
    // movimento vs. confiança na medição GPS. Com Q fixo, o filtro é igualmente
    // "rígido" com GPS excelente (5m) e GPS degradado (30m).
    //
    // Lógica: GPS ruim → confiar mais no modelo cinético (inércia do corredor).
    //   accuracy < 8m  → Q=2.0  (GPS limpo: deixa a medição ter mais peso)
    //   accuracy 8–20m → Q=3.0  (padrão)
    //   accuracy 20–35m→ Q=6.0  (sinal degradado: aumenta inércia do filtro)
    //   accuracy > 35m → Q=10.0 (urban canyon: filtro quase só usa velocidade)
    private fun kalmanQDinamico(accuracy: Float): Float = when {
        accuracy < 8f  -> 2.0f
        accuracy < 20f -> 3.0f
        accuracy < 35f -> 6.0f
        else           -> 10.0f
    }

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
        kalmanVariancia += kalmanQDinamico(accuracy) * deltaS

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

    // Último pace válido para gravar no gráfico — nunca zerado no SCREEN_ON.
    // Garante que pontos pós-desbloqueio não criem gap no gráfico (pace=0).
    // Só é atualizado quando há um pace real calculado.
    private var ultimoPaceValidoGrafico: Double = 0.0

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

    // Receptor de tela ligada: quando o usuário desbloqueia o telefone, o
    // FusedLocationProvider troca de provedor (cell-assisted → GPS puro) e
    // entrega 1-3 pontos com posição levemente deslocada antes de reacquirir
    // o sinal — causando spikes de pace e registros errados na rota.
    // Ativando modoRecuperacaoGps aqui, o filtro de velocidade já existente
    // descarta esses pontos silenciosamente até o GPS estabilizar.
    private var screenOffTimestampMs = 0L
    private var screenOnTimestampMs  = 0L   // último SCREEN_ON — para logar os 30s seguintes

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // Registra quando a tela apagou para calcular quanto tempo ficou bloqueada
                    screenOffTimestampMs = SystemClock.elapsedRealtime()
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (!estaCorrendo || estaPausado) return

                    // Só ativa o recovery se a tela ficou apagada > 10s.
                    // Se o usuário apenas "espiou" a hora e a tela apagou em <10s,
                    // o GPS não teve tempo de entrar em batch mode — reset desnecessário.
                    val tempoTelaApagadaMs = if (screenOffTimestampMs > 0)
                        SystemClock.elapsedRealtime() - screenOffTimestampMs
                    else
                        Long.MAX_VALUE  // primeira vez sem SCREEN_OFF capturado → assume longo

                    if (tempoTelaApagadaMs < 10_000L) {
                        Log.d(TAG, "📱 Tela ligada após ${tempoTelaApagadaMs}ms — GPS ainda estável, recovery ignorado")
                        return
                    }

                    // FIX: Ativa apenas o filtro de salto de posição (modoRecuperacaoGps).
                    //
                    // BUG ANTERIOR: limpar ultimasLocalizacoes + ultimoPaceEmaInterno +
                    // bufferPace30s causava um spike de pace no desbloqueio da tela.
                    //
                    // Mecanismo do bug:
                    //  1. Buffers zerados → cálculo de pace começa do zero.
                    //  2. O FusedLocationProvider entrega 1-2 pontos com posição
                    //     "travada" (GPS chipset reativando) — distância ≈ 0, mas o
                    //     tempo já avança 1-2s.
                    //  3. paceBruto = tempoJanela / distanciaJanela → valor ENORME
                    //     (ex: 700 s/km) torna-se o valor semente da EMA.
                    //  4. Com alpha = 0.25, a EMA demora 15-20s para convergir ao
                    //     pace real → spike visível no display E gravado como
                    //     paceNoPonto nos pontos da rota → aparece no gráfico e
                    //     no mapa do histórico.
                    //
                    // Solução: o GPS estava funcionando durante a tela apagada
                    // (comprovado pelo pace correto anunciado pelo AudioCoach).
                    // Preservar a EMA e o histórico de localizações garante que o
                    // pace continue estável ao desbloquear. O modoRecuperacaoGps
                    // (filtro de salto de posição) é suficiente para rejeitar
                    // qualquer drift de GPS no momento do wake-up.
                    modoRecuperacaoGps = true
                    contadorPontosRecuperacao = 0
                    screenOnTimestampMs = SystemClock.elapsedRealtime()
                    // Limpa apenas os buffers de janela deslizante: misturar pontos
                    // de minutos atrás com os novos distorceria distJanela/tempoJanela.
                    // A EMA (ultimoPaceEmaInterno) é PRESERVADA intencionalmente —
                    // ela é a âncora histórica que impede spikes de pace no desbloqueio.
                    // O display (_paceAtual) também é preservado: o corredor continua
                    // vendo o último pace válido em vez de "--:--".
                    ultimasLocalizacoes.clear()
                    bufferPace30s.clear()
                    bufferStride5s.clear()
                    Log.d(TAG, "Tela ligada apos ${tempoTelaApagadaMs / 1000}s - buffers limpos, EMA preservada para evitar spike de pace")
                }
            }
        }
    }
    private var screenReceiverRegistrado = false
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

    // ── Barômetro (Fusão GPS + Pressão) ──────────────────────────────────────
    // TYPE_PRESSURE fornece altitude relativa (variações de cm). O GPS fornece
    // a âncora absoluta. A fusão por offset entrega o melhor dos dois mundos:
    // baixo ruído do barômetro + estabilidade de longo prazo do GPS.
    private var barometro: Sensor? = null
    private var altitudeBarometricaUltima: Double? = null
    private var baroOffset: Double = 0.0
    private var baroOffsetInicializado = false
    // ALPHA ultra-lento: corrige drift climático sem injetar ruído do GPS.
    // Com erro inicial de 5m, o offset muda apenas 0.005m/ponto — invisível para a Regressão.
    private val ALPHA_CALIBRACAO_BARO = 0.001

    private val timestampsPassos = ArrayDeque<Long>(50)
    private var ultimoTimestampPasso = 0L

    private var thresholdAceleracao = 13.0f
    private var somaUltimosPicos = 0f
    private var contadorPicos = 0

    // ── Filtro de Stride Length — janela deslizante de 5s ────────────────────
    // Substitui acumuladores globais que perdiam sensibilidade com o tempo.
    // A janela deslizante mantém o filtro sempre "alerta" durante todo o trajeto.
    private data class StrideSnapshot(val timestampMs: Long, val distM: Double, val passos: Int)
    private val bufferStride5s = ArrayDeque<StrideSnapshot>(10)

    // ── Gap-fill por cadência (Dead Reckoning) ────────────────────────────────
    private var ultimoTempoGps      = 0L
    private var passosNoGap         = 0
    private var emGapGps            = false
    private val GAP_THRESHOLD_MS    = 3_000L    // gap > 3s ativa dead reckoning
    private val GAP_TIMEOUT_MS      = 120_000L  // gap > 2min desativa acúmulo (túnel longo)
    private var gapTimeoutNotificado = false     // evita spam de notificação
    private var primeiropontoAposGap = false

    // ── Filtro de Altitude: Mediana(5) + Regressão Linear(10) ────────────────
    // Pipeline: altitude_bruta → Mediana(5 pts) → buffer_regressão(10 pts) → gradiente_GAP
    //
    // Mediana: elimina spikes pontuais do GPS vertical (Urban Canyon pode causar
    // saltos de ±20m). Com N=5, resiste a 2 outliers simultâneos.
    //
    // Regressão linear (mínimos quadrados): calcula a TENDÊNCIA de altitude vs.
    // distância, eliminando o zig-zag sistemático do GPS vertical. Vantagem vs.
    // EMA: sem lag — a inclinação real de uma subida íngreme é detectada imediatamente,
    // não apenas ao chegar ao topo (onde o EMA pesado finalmente converge).
    //
    // Buffer (altMediana, distAcum): usa distância acumulada como eixo X, garantindo
    // que o slope da regressão seja diretamente o gradiente em m/m (adimensional).
    private val bufferAltBruta  = ArrayDeque<Double>(5)
    private data class AltDistPonto(val altMediana: Double, val distAcum: Double)
    private val bufferAltDist   = ArrayDeque<AltDistPonto>(10)

    /** Alimenta o pipeline de altitude e retorna a altitude mediada. */
    private fun processarAltitude(rawAlt: Double): Double {
        bufferAltBruta.addLast(rawAlt)
        if (bufferAltBruta.size > 5) bufferAltBruta.removeFirst()

        val arr = bufferAltBruta.toDoubleArray().also { it.sort() }
        return arr[arr.size / 2]  // mediana — imune a outliers
    }

    /**
     * Gradiente via regressão linear de mínimos quadrados sobre o buffer de 10 pontos.
     *
     * slope = (N·Σxy − Σx·Σy) / (N·Σx² − (Σx)²)
     *   x = distância acumulada (metros) — eixo físico real, não índice temporal
     *   y = altitude mediada (metros)
     *   resultado = m/m = fração adimensional (ex: 0.05 = 5% de subida)
     *
     * Usar distância como X (não índice de ponto) garante que pontos GPS chegando
     * em lotes (batch mode com tela bloqueada) não distorçam o gradiente.
     */
    private fun calcularGradienteRegressao(): Double {
        if (bufferAltDist.size < 3) return 0.0
        val n = bufferAltDist.size.toDouble()
        var sumX = 0.0; var sumY = 0.0; var sumXY = 0.0; var sumX2 = 0.0
        bufferAltDist.forEach { p ->
            sumX  += p.distAcum;  sumY  += p.altMediana
            sumXY += p.distAcum * p.altMediana
            sumX2 += p.distAcum * p.distAcum
        }
        val denom = n * sumX2 - sumX * sumX
        return if (denom == 0.0) 0.0
        else ((n * sumXY - sumX * sumY) / denom).coerceIn(-0.45, 0.45)
    }

    /**
     * Fusão GPS + Barômetro por Offset Dinâmico.
     *
     * Princípio: barômetro fornece VARIAÇÕES de altitude com baixo ruído (~cm),
     * GPS fornece a ÂNCORA ABSOLUTA com estabilidade de longo prazo.
     *
     * offset = gpsAlt - baroAlt (calibrado no primeiro ponto e corrigido lentamente)
     * altitudeFundida = baroAlt + offset
     *
     * Calibração lenta (ALPHA=0.001): corrige drift climático (frentes de pressão)
     * sem injetar o ruído pontual do GPS na altitude calculada.
     *
     * Fallback: se barômetro indisponível (altitudeBarometricaUltima == null),
     * retorna gpsAlt puro — pipeline de Mediana+Regressão continua normalmente.
     */
    private fun obterAltitudeFusion(gpsAlt: Double, accuracy: Float): Double {
        val baroAlt = altitudeBarometricaUltima ?: return gpsAlt

        if (!baroOffsetInicializado) {
            baroOffset = gpsAlt - baroAlt
            baroOffsetInicializado = true
            Log.d(TAG, "⛰️ Baro offset calibrado: ${String.format("%.2f", baroOffset)}m (GPS=${String.format("%.1f", gpsAlt)}m, Baro=${String.format("%.1f", baroAlt)}m)")
            return gpsAlt   // primeiro ponto: GPS é a verdade, baro ainda calibrando
        }

        // Calibração contínua: apenas quando GPS está confiável (accuracy < 15m)
        if (accuracy < 15f) {
            val erro = gpsAlt - (baroAlt + baroOffset)
            baroOffset += erro * ALPHA_CALIBRACAO_BARO
        }

        return baroAlt + baroOffset
    }

    /**
     * Reset completo da pipeline de elevação.
     *
     * Chamado em 3 situações:
     *   1. iniciarRastreamento()      — nova corrida, estado limpo
     *   2. retomarRastreamento()      — pausa pode ter mudado pressão atmosférica
     *   3. recuperarAposProcessDeath() — processo morreu, offset desatualizado
     *
     * Limpar bufferAltBruta + bufferAltDist é essencial: sem limpeza, o primeiro
     * ponto pós-pausa (com novo offset barométrico) criaria um "degrau" que a
     * Regressão Linear interpretaria como inclinação infinita → GAP absurdo por 10s.
     */
    private fun resetarPipelineElevacao() {
        baroOffsetInicializado = false
        bufferAltBruta.clear()
        bufferAltDist.clear()
        Log.d(TAG, "🧹 Pipeline de elevação resetada (offset + buffers Mediana/Regressão)")
    }

    /**
     * Único ponto de registro de sensores — garante idempotência.
     *
     * Realiza unregisterListener(this) preventivo antes de registrar, evitando
     * duplicidade de callbacks em qualquer cenário de reinício parcial pelo Android.
     *
     * ⚠️ NÃO chamar em retomarRastreamento(): sensores permanecem registrados
     * durante a pausa intencionalmente (barômetro deve atualizar a pressão
     * para que o offset seja calibrado com dado fresco ao retomar).
     *
     * Barômetro: Handler(MainLooper) garante entrega na mesma thread do GPS,
     * eliminando race condition com processarNovaLocalizacao.
     */
    private fun registrarSensores() {
        // Unregister preventivo: garante estado limpo mesmo se chamado duas vezes
        sensorManager.unregisterListener(this)

        // 1. Barômetro — MainThread via Handler para thread safety com GPS
        barometro?.let {
            sensorManager.registerListener(
                this, it, SensorManager.SENSOR_DELAY_NORMAL, Handler(Looper.getMainLooper())
            )
            Log.d(TAG, "⛰️ Barômetro registrado (MainThread Handler)")
        }

        // 2. Sensor de passos (hardware nativo) ou acelerômetro (fallback)
        if (usandoStepDetector) {
            stepDetector?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                Log.d(TAG, "👟 STEP_DETECTOR registrado")
            }
        } else {
            acelerometro?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                Log.d(TAG, "📡 LINEAR_ACCELERATION registrado (fallback, SENSOR_DELAY_NORMAL)")
            }
        }
    }

    // ── Retomada Híbrida: STEP_DETECTOR + Validação GPS ──────────────────────
    // O STEP_DETECTOR detecta movimento em ~200ms, enquanto o GPS pode demorar
    // 2-4s para confirmar a retomada após um semáforo. Usar o sensor de passos
    // como gatilho de retomada elimina esse gap e preserva a distância percorrida.
    //
    // Anti falso-positivo: janela deslizante de 3s (não contador absoluto).
    // Passos acumulados ao longo de 2min de semáforo são automaticamente
    // descartados — apenas passos dos últimos 3 segundos contam.
    private val timestampsPassosAutoPause = ArrayDeque<Long>(10)
    private var ultimaLocalizacaoParaAutoPause: Location? = null

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
    // GAP — Grade Adjusted Pace (Minetti 2002)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    // Acumuladores ponderados por distância para o km atual
    // (média simples de pontos seria enviesada por pontos em locais com GPS lento)
    private var gapSomadoPonderadoKm      = 0.0   // soma de (gap_ponto × distancia)
    private var gradienteSomadoPonderadoKm = 0.0   // soma de (gradiente × distancia)
    private var gapDistanciaAcumKm        = 0.0   // denominador compartilhado

    // Resultado que o ViewModel busca no fechamento do km
    private var ultimoGapMedioSegKm = 0.0

    // GAP suavizado para exibição em tempo real (EMA com α=0.2)
    private var gapEmaInterno: Double? = null
    private val _gapAtualSegKm = MutableStateFlow(0.0)
    val gapAtualSegKm: StateFlow<Double> = _gapAtualSegKm.asStateFlow()

    // Modo Montanha — subida > 6% sustentada por >= 100m
    // Aumentado de 4% -> 6%: rampas urbanas de acessibilidade (~4%) disparavam falso positivo.
    // 6% corresponde a uma ladeira claramente ingreme (~1 andar a cada 17m horizontais).
    // Histerese: ativa com 100m, desativa apenas ao sair da subida (< 3%)
    private var distSubidaAcum = 0.0
    private val DISTANCIA_ATIVA_MONTANHA = 100.0  // metros subindo > 6%
    private val LIMIAR_GRADE_MONTANHA    = 0.06   // 6%
    private val LIMIAR_SAIDA_MONTANHA    = 0.06   // 6% -- sem histerese
    private var emModoMontanha           = false
    private val _modoMontanha = MutableStateFlow(false)
    val modoMontanha: StateFlow<Boolean> = _modoMontanha.asStateFlow()

    // Descida tecnica -- grade < -8%
    // Filtros robustos (6 pontos consecutivos + média 10 gradientes) evitam falsos positivos.
    private val LIMIAR_DESCIDA_TECNICA = -0.06

    /**
     * Carrega o resultado do km que acabou de fechar e zera os acumuladores.
     *
     * Retorna null se não houver distância acumulada suficiente (GPS ausente o km todo).
     * O gradiente médio é essencial para o AudioCoach distinguir:
     *   gradiente > 0  → subida → "Ótima subida, esforço equivale a X"
     *   gradiente < -0.15 → descida técnica → "Controle o impacto"
     *   demais → terreno ondulado → anúncio neutro
     */
    data class GapKmResult(
        val gapMedioSegKm: Double,       // GAP ponderado (s/km)
        val gradienteMedio: Double        // inclinação média ponderada do km (fração, ex: 0.05 = 5%)
    )

    fun fecharEObterGapKm(): GapKmResult? {
        // Proteção contra divisão por zero: GPS pode falhar um km inteiro
        if (gapDistanciaAcumKm < 10.0) {
            gapSomadoPonderadoKm       = 0.0
            gradienteSomadoPonderadoKm = 0.0
            gapDistanciaAcumKm         = 0.0
            return null
        }
        val gapMedio      = gapSomadoPonderadoKm       / gapDistanciaAcumKm
        val gradienteMedio = gradienteSomadoPonderadoKm / gapDistanciaAcumKm
        ultimoGapMedioSegKm        = gapMedio
        gapSomadoPonderadoKm       = 0.0
        gradienteSomadoPonderadoKm = 0.0
        gapDistanciaAcumKm         = 0.0
        return GapKmResult(gapMedio, gradienteMedio)
    }

    fun getGapAtualInstantaneo(): Double = _gapAtualSegKm.value
    fun getStepLengthAprendido(): Double = stepLengthAprendido   // EMA do Auto-Learner para o Coach
    fun isModoMontanha(): Boolean = emModoMontanha

    private val _gradienteAtual = MutableStateFlow(0.0)
    val gradienteAtual: StateFlow<Double> = _gradienteAtual.asStateFlow()

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

        // Barômetro: fusão com GPS para altitude de alta fidelidade
        barometro = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if (barometro != null) {
            Log.d(TAG, "⛰️ Barômetro disponível — fusão GPS/Baro ativa")
        } else {
            Log.i(TAG, "📊 Barômetro não disponível — usando altitude GPS pura (Mediana + Regressão)")
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
            // Buffer circular: carrega apenas os últimos BUFFER_MAX_PONTOS em RAM.
            // Room já tem a rota completa — o buffer é só para operações em tempo real.
            rotaTotalPontos = pontosRecuperados.size
            val pontosParaBuffer = if (pontosRecuperados.size > BUFFER_MAX_PONTOS)
                pontosRecuperados.takeLast(BUFFER_MAX_PONTOS) else pontosRecuperados
            rota.clear()
            rota.addAll(pontosParaBuffer)
            // Reconstrói buffer de altitude com os últimos pontos disponíveis
            // resetarPipelineElevacao() garante estado limpo; buffers serão
            // reaquecidos pelos pontos históricos abaixo antes de retomar o GPS ao vivo.
            resetarPipelineElevacao()
            timestampsPassosAutoPause.clear()
            ultimaLocalizacaoParaAutoPause = null
            // Reaquece buffers de regressão com pontos históricos (sem calcular gradiente)
            pontosParaBuffer.takeLast(10).forEach { p ->
                val medianed = processarAltitude(p.alt)
                bufferAltDist.addLast(AltDistPonto(medianed, _distanciaMetros.value))
            }
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

            registrarSensores()

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
        rotaTotalPontos = 0
        ultimasLocalizacoes.clear()
        ultimoPaceEmaInterno = null
        janelaAtualSegundos = 12

        // Reset filtros de altitude
        resetarPipelineElevacao()

        // Reset retomada híbrida
        timestampsPassosAutoPause.clear()
        ultimaLocalizacaoParaAutoPause = null
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

        // Reset acumuladores GAP
        gapSomadoPonderadoKm       = 0.0
        gradienteSomadoPonderadoKm = 0.0
        gapDistanciaAcumKm         = 0.0
        ultimoGapMedioSegKm        = 0.0
        gapEmaInterno              = null
        _gapAtualSegKm.value       = 0.0
        distSubidaAcum             = 0.0
        emModoMontanha             = false
        _modoMontanha.value        = false
        _gradienteAtual.value      = 0.0

        registrarSensores()

        // Trata o início da corrida como se fosse um SCREEN_ON:
        // os primeiros 20s usam alpha=0.02 (EMA quase imóvel) e os primeiros 10s
        // ficam em quarentena (não gravam paceNoPonto). Isso evita que o GPS
        // demorando para calibrar no cold start gere paces ruins no gráfico.
        screenOnTimestampMs = SystemClock.elapsedRealtime()

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

            // Reset da pipeline de elevação: forçar recalibração do offset barométrico.
            // Durante a pausa a pressão atmosférica pode ter mudado (frente de pressão,
            // entrada em local fechado). Com baroOffsetInicializado=false, o próximo
            // ponto GPS bom recalibra o offset contra a pressão ATUAL do ar.
            // bufferAltBruta/AltDist também são limpos para evitar "degrau" de altitude
            // que a Regressão Linear interpretaria como inclinação absurda.
            resetarPipelineElevacao()
            Log.d(TAG, "▶️ Retomada: offset barométrico resetado para recalibração com pressão atual")

            estaPausado = false
            atualizarNotificacao("Corrida em andamento")
        }
    }

    private fun pararRastreamento() {
        Log.d(TAG, "⏹️ Parando rastreamento")

        estaCorrendo = false

        // Emite a rota COMPLETA (Room + buffer) para o ViewModel usar no GPX.
        // Assíncrono — o ViewModel coleta via StateFlow após o stop.
        serviceScope.launch(Dispatchers.IO) {
            try {
                val pontosRoom = database.routePointDao()
                    .getSessionPoints(sessionId)
                    .map { it.toLatLngPonto() }
                val ultimoTempo  = pontosRoom.lastOrNull()?.tempo ?: 0L
                val pontosBuffer = rota.toList().filter { it.tempo > ultimoTempo }
                val rotaCompleta = (pontosRoom + pontosBuffer).sortedBy { it.tempo }
                _rotaAtual.value = rotaCompleta
                Log.d(TAG, "📦 Rota final emitida: ${rotaCompleta.size} pontos (Room=${pontosRoom.size} + buffer=${pontosBuffer.size})")
            } catch (e: Exception) {
                Log.e(TAG, "⚠️ Falha ao carregar rota completa — usando buffer", e)
                _rotaAtual.value = rota.toList()
            }
        }

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
        if (screenReceiverRegistrado) {
            try { unregisterReceiver(screenOnReceiver) } catch (_: Exception) {}
            screenReceiverRegistrado = false
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Cadência via Acelerômetro
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        // BARÔMETRO: atualiza sempre — mesmo pausado ou fora de corrida.
        // Garante que altitudeBarometricaUltima tenha dado FRESCO no exato momento
        // em que obterAltitudeFusion() calibra o novo offset após a retomada.
        // Sem isso, o offset seria calculado contra pressão de "antes da pausa".
        if (event.sensor.type == Sensor.TYPE_PRESSURE) {
            altitudeBarometricaUltima = android.hardware.SensorManager.getAltitude(
                android.hardware.SensorManager.PRESSURE_STANDARD_ATMOSPHERE,
                event.values[0]
            ).toDouble()
            return  // otimização: evita avaliar o when abaixo
        }

        // Sensores de movimento: apenas durante corrida ativa (não pausada)
        if (!estaCorrendo || estaPausado) return

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

        // ── Retomada Híbrida: STEP_DETECTOR como gatilho de auto-resume ───────
        // Janela deslizante de 3s — passos mais antigos são automaticamente
        // descartados, impedindo que passos acumulados durante minutos de semáforo
        // disparem uma retomada indevida.
        if (_autoPausado.value && autoPauseFuncaoAtiva) {
            timestampsPassosAutoPause.addLast(agora)
            while (timestampsPassosAutoPause.isNotEmpty() &&
                   timestampsPassosAutoPause.first() < agora - 3_000L) {
                timestampsPassosAutoPause.removeFirst()
            }
            // 2 passos em 3s → acionar validação; GPS arbitra se retoma ou não
            if (timestampsPassosAutoPause.size >= 2) {
                tentarRetomadaViaPassos()
            }
        } else {
            timestampsPassosAutoPause.clear()
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

    /**
     * Tenta retomar o auto-pause baseado em evidência de passos recentes.
     *
     * O STEP_DETECTOR é o árbitro rápido (~200ms). O GPS é o árbitro de qualidade.
     * Para evitar falsos positivos (pular no lugar, balançar o braço no semáforo):
     *   - Prioridade 1: location.speed (Doppler — muito mais confiável que posição)
     *   - Fallback: distância desde a última posição significativa
     *
     * Só retoma se GPS confirmar movimento real. Se GPS ainda não tem dados,
     * aguarda o próximo passo — a janela de 3s garante que tentará novamente.
     */
    private fun tentarRetomadaViaPassos() {
        val loc = ultimaLocalizacaoParaAutoPause ?: return

        val emMovimento = if (loc.hasSpeed() &&
            (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O ||
             loc.speedAccuracyMetersPerSecond < 1.5f)) {
            loc.speed >= 0.5f
        } else {
            val ultimaSignif = ultimaLocalizacaoSignificativa ?: return
            calcularDistancia(
                ultimaSignif.latitude, ultimaSignif.longitude,
                loc.latitude, loc.longitude
            ) >= DISTANCIA_MINIMA_MOVIMENTO
        }

        if (emMovimento) {
            Log.d(TAG, "👟 Retomada híbrida: ${timestampsPassosAutoPause.size} passos em 3s + GPS confirma movimento (speed=${loc.speed} m/s)")
            _autoPausado.value = false
            contadorEmMovimento = 0
            contadorSemMovimento = 0
            timestampsPassosAutoPause.clear()
            atualizarNotificacao("Corrida em andamento")
        }
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

            // FIX CRÍTICO: processar TODAS as localizações do lote, não só a última.
            //
            // O FusedLocationProvider opera em "batch mode" quando a tela está bloqueada:
            // acumula pontos GPS e os entrega todos de uma vez quando a tela é desbloqueada.
            // Ex: tela bloqueada por 8s → 8 pontos chegam simultaneamente em result.locations.
            //
            // Usando apenas result.lastLocation (posição correta mas sem o percurso intermediário):
            //   - ultimasLocalizacoes recebe 1 ponto onde deveria ter recebido 8
            //   - calcularPaceAtual vê: distancia_do_salto / 1s = velocidade impossível
            //     Ex: salto de 13m em 1s = 13 m/s → passa do filtro 6.5 m/s → "--:--" ou spike
            //   - primeiropontoAposGap=false porque não havia "gap" detectado (GPS estava ok),
            //     então a distância do salto É somada ao total → distância inflada
            //
            // Processando result.locations (lista ordenada cronologicamente):
            //   - Cada ponto é processado em ordem, com deltaMs real entre pontos consecutivos
            //   - ultimasLocalizacoes constrói a janela corretamente (8 pontos, 8s de dados)
            //   - calcularPaceAtual vê velocidades normais em cada segmento
            //   - Distância acumulada correta (cada salto de ~1.6m, não um salto de 13m)
            val locations = result.locations
            if (locations.isNotEmpty()) {
                if (locations.size > 1) {
                    Log.d(TAG, "📦 Lote de ${locations.size} pontos GPS — processando em ordem")
                }
                for (location in locations) {
                    processarNovaLocalizacao(location)
                }
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

        // Registra receptor de tela ligada para ativar modoRecuperacaoGps
        // ao desbloquear o telefone — evita spikes de pace pós-desbloqueio.
        if (!screenReceiverRegistrado) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            registerReceiver(screenOnReceiver, filter)
            screenReceiverRegistrado = true
            Log.d(TAG, "📱 Receptor SCREEN_ON/OFF registrado")
        }
    }

    private fun pararAtualizacoesGPS() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        if (screenReceiverRegistrado) {
            try { unregisterReceiver(screenOnReceiver) } catch (_: Exception) {}
            screenReceiverRegistrado = false
        }
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
                // ── CLEAN SLATE: salto GPS impossível detectado ──────────
                // Limpa as janelas de pace para que o valor inválido não
                // contamine o GPX (bufferPace30s → paceNoPonto) nem o display
                // em tempo real (ultimasLocalizacoes → _paceAtual).
                //
                // ⚠️ NÃO zeramos ultimoPaceEmaInterno aqui.
                // A EMA representa o histórico real de movimento (ex: 645 s/km).
                // Zerá-la foi o BUG ORIGINAL: o próximo ponto ficava sem âncora
                // e paceEma = paceBruto diretamente → spike imediato no display.
                // Com a EMA preservada + alpha=0.02 dos primeiros 20s, qualquer
                // paceBruto errado move a EMA < 1 s/km — invisível ao usuário.
                //
                // ultimaLocalizacaoSignificativa = local do salto ("Marco Zero"):
                // sem isso o PRÓXIMO ponto seria comparado com a posição
                // pré-salto → novo spike em cascata (efeito dominó evitado).
                bufferPace30s.clear()
                ultimasLocalizacoes.clear()
                ultimaLocalizacaoSignificativa = location
                _paceAtual.value = "--:--"

                contadorPontosRecuperacao++
                Log.w(TAG, "🚫 GPS clean-slate: salto impossível " +
                    "${distJump.toInt()}m/${deltaTempoS.toInt()}s " +
                    "(${String.format("%.1f", velocidadeMs)} m/s). " +
                    "Buffers limpos, EMA PRESERVADA=${ultimoPaceEmaInterno?.let { "%.0f".format(it) } ?: "null"}. Marco Zero atualizado.")
                return
            } else {
                modoRecuperacaoGps = false

                // ── ÂNCORA PÓS-SALTO ────────────────────────────────────────────────
                // Repete as coordenadas do ÚLTIMO PONTO VÁLIDO (pré-salto) com o
                // timestamp atual (agora − 1ms). Isso cria no GPX um segmento de
                // distância ≈ zero entre o último ponto bom e o primeiro ponto pós-
                // recovery, que o Intervals.icu e o Strava interpretam como pausa —
                // evitando o spike de velocidade que resultaria de comparar diretamente
                // as posições antes e depois do salto GPS.
                //
                // primeiropontoAposGap = true: garante que a distância entre a âncora
                // e o novo ponto pós-recovery também NÃO seja somada ao total —
                // o mesmo mecanismo já usado na re-entrada após gap de dead reckoning.
                val agoraRecovery = System.currentTimeMillis()
                val ultimoValido = rota.last()
                val pontoAncora = ultimoValido.copy(
                    tempo           = agoraRecovery - 1L,
                    paceNoPonto     = ultimoPaceValidoGrafico,
                    cadenciaNoPonto = 0
                )
                adicionarPontoRota(pontoAncora)
                primeiropontoAposGap = true

                if (ultimoValido.accuracy <= ROOM_ACCURACY_METERS) {
                    serviceScope.launch(Dispatchers.IO) {
                        try {
                            database.routePointDao().insert(
                                RoutePointEntity.from(pontoAncora, sessionId)
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "⚠️ Falha ao persistir ponto-âncora de recovery", e)
                        }
                    }
                }

                Log.d(TAG, "✅ GPS recovery: primeiro ponto válido aceito " +
                    "(${distJump.toInt()}m, ${String.format("%.1f", velocidadeMs)} m/s) — " +
                    "âncora inserida, distância omitida (primeiropontoAposGap=true)")
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

        // ── Pipeline de Altitude: Fusão GPS/Baro → Mediana(5) → Regressão(10) ─
        // 1. obterAltitudeFusion: barômetro fornece variações de cm; GPS ancora a altitude
        //    absoluta. Fallback transparente se barômetro indisponível.
        // 2. processarAltitude (Mediana 5pts): elimina spikes residuais do baro (rafas de
        //    vento no sensor) e glitches pontuais do GPS vertical.
        // 3. bufferAltDist → calcularGradienteRegressao(): gradiente instantâneo sem lag.
        val altitudeFundida = obterAltitudeFusion(location.altitude, location.accuracy)
        val altMediana = processarAltitude(altitudeFundida)
        // Alimenta buffer de (altMediana, distAcum) para regressão linear.
        // distAcum ANTES de somar o novo segmento → eixo X consistente com "onde estava".
        bufferAltDist.addLast(AltDistPonto(altMediana, _distanciaMetros.value))
        if (bufferAltDist.size > 10) bufferAltDist.removeFirst()

        // ── Atualiza referência GPS para retomada híbrida ─────────────────────
        ultimaLocalizacaoParaAutoPause = location

        val pontoNovo = LatLngPonto(
            lat = latK,
            lng = lngK,
            alt = altMediana,       // altitude filtrada (mediana) — melhor para GPX e elevação
            tempo = agora,
            accuracy = location.accuracy,
            paceNoPonto = ultimoPaceValidoGrafico,
            cadenciaNoPonto = _cadencia.value
        )

        _posicaoAtual.value = pontoNovo

        if (rota.isEmpty()) {
            adicionarPontoRota(pontoNovo)
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

        // DISTÂNCIA VIA VELOCIDADE DOPPLER vs HAVERSINE (2D)
        //
        // Estratégia: haversine quando GPS é bom (accuracy < 15m), Doppler quando GPS é ruim.
        // Motivação: em GPS limpo (céu aberto, corrida normal), haversine sobre coordenadas
        // precisas é mais fiel à distância real que Doppler, que subestima em acelerações
        // e curvas. Em GPS degradado (perto de lagos, prédios), coordenadas saltam e o
        // Doppler (baseado no sinal do satélite, não na posição) é mais estável.
        //
        // Transição segura: o deltaTSegundos in 0.5..3.0 garante que qualquer salto de
        // método acontece com intervalo de tempo razoável. A velocidade anterior em
        // ultimasLocalizacoes é sempre de um ponto real — sem descontinuidade na transição.
        val deltaTSegundos = deltaMs / 1000.0
        val gpsRuim = location.accuracy >= 15f
        val usarDoppler = gpsRuim &&
            location.hasSpeed() &&
            location.speed > 0.1f &&
            deltaTSegundos in 0.5..3.0 &&
            (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O ||
             location.speedAccuracyMetersPerSecond < 1.5f)

        val distancia2D = if (usarDoppler) {
            val speedAnterior = ultimasLocalizacoes.lastOrNull()?.speed?.toDouble() ?: location.speed.toDouble()
            val speedMedia = (speedAnterior + location.speed) / 2.0
            speedMedia * deltaTSegundos
        } else {
            calcularDistancia(ultimoPonto.lat, ultimoPonto.lng, pontoNovo.lat, pontoNovo.lng)
        }

        // ── Distância 3D (Pitágoras) ──────────────────────────────────────────
        // Usa altitude FILTRADA (mediana) para evitar que spikes de GPS vertical
        // inflacionem a distância. Efeito relevante em subidas > 10% (Trail Running).
        // Em plano: dist3D ≈ dist2D (diferença < 0.05% com dAlt < 1m).
        val dAltFiltrada = if (bufferAltDist.size >= 2) {
            bufferAltDist.last().altMediana - bufferAltDist[bufferAltDist.size - 2].altMediana
        } else 0.0
        val distancia = sqrt(distancia2D * distancia2D + dAltFiltrada * dAltFiltrada)

        Log.d(TAG, "📏 Dist: ${String.format("%.1f", distancia)}m (2D=${String.format("%.1f", distancia2D)}m dAlt=${String.format("%.1f", dAltFiltrada)}m) via ${if (usarDoppler) "Doppler(${String.format("%.1f", location.speed)}m/s)" else "Haversine"}")

        // Alimenta janela deslizante de 5s para o filtro de stride length
        val passosUltimoSegundo = timestampsPassos.count { it >= System.currentTimeMillis() - 1_000L }
        bufferStride5s.addLast(StrideSnapshot(agora, distancia, passosUltimoSegundo))
        while (bufferStride5s.isNotEmpty() && bufferStride5s.first().timestampMs < agora - 5_000L) {
            bufferStride5s.removeFirst()
        }

        adicionarPontoRota(pontoNovo)

        // Pós-gap: só descarta distância se a velocidade implícita for impossível (> 11 m/s).
        // Antes descartávamos sempre — isso sub-contava distância real em recoveries curtos
        // onde o GPS voltou numa posição próxima e a distância é legítima.
        val ehSaltoImpossivelPosGap = primeiropontoAposGap && run {
            val ultimoValido = if (rota.size >= 2) rota[rota.size - 2] else null
            if (ultimoValido != null) {
                val dtS = ((pontoNovo.tempo - ultimoValido.tempo) / 1000.0).coerceAtLeast(0.1)
                val distM = calcularDistancia(ultimoValido.lat, ultimoValido.lng, pontoNovo.lat, pontoNovo.lng)
                distM / dtS > MAX_VELOCIDADE_HUMANA_MS
            } else false
        }
        if (primeiropontoAposGap) primeiropontoAposGap = false

        if (ehSaltoImpossivelPosGap) {
            Log.d(TAG, "📍 Pós-gap com salto impossível — distância descartada")
        } else {
            // Auto-Learner: calibra step_length quando GPS está excelente
            if (location.accuracy < 8f && _cadencia.value >= 60) {
                passosDesdeUltimoGpsBom++
                distDesdeUltimoGpsBom += distancia

                if (passosDesdeUltimoGpsBom >= 30) {
                    val passadaMedida = distDesdeUltimoGpsBom / passosDesdeUltimoGpsBom

                    val inclinacaoAtual = if (bufferAltDist.size >= 2) {
                        val dAltJanela = bufferAltDist.last().altMediana - bufferAltDist.first().altMediana
                        val dDistJanela = (bufferAltDist.last().distAcum - bufferAltDist.first().distAcum).coerceAtLeast(0.1)
                        kotlin.math.abs(dAltJanela / dDistJanela * 100)
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

            // ── GAP (Grade Adjusted Pace) — Minetti (2002) ────────────────────
            // Gradiente via REGRESSÃO LINEAR sobre os últimos 10 pontos de altitude
            // mediada. Vantagens vs. dAlt pontual:
            //   1. Sem spike: outlier de altitude já foi eliminado pela mediana
            //   2. Sem lag: a regressão responde imediatamente à tendência real
            //   3. Sem zigzag: o zig-zag sistemático do GPS vertical é filtrado
            //      pela melhor reta, não pela diferença entre dois pontos ruidosos
            if (rota.size >= 2 && distancia > 0.5) {
                val gradiente = calcularGradienteRegressao()
                val paceInstantaneo = ultimoPaceEmaInterno ?: 0.0

                if (paceInstantaneo in 60.0..1200.0) {
                    val fator = fatorMinetti(gradiente)
                    val gapPonto = (paceInstantaneo / fator).coerceIn(60.0, 1200.0)

                    // Acumular para o km atual (ponderado por distância)
                    gapSomadoPonderadoKm       += gapPonto  * distancia
                    gradienteSomadoPonderadoKm += gradiente * distancia
                    gapDistanciaAcumKm         += distancia

                    // EMA para display em tempo real (α=0.2 → suavização de ~5 pontos)
                    val alpha = 0.2
                    val novoGapEma = gapEmaInterno?.let { prev ->
                        alpha * gapPonto + (1.0 - alpha) * prev
                    } ?: gapPonto
                    gapEmaInterno = novoGapEma
                    _gapAtualSegKm.value = novoGapEma

                    // Expõe gradiente suavizado para o ViewModel monitorar descida técnica
                    _gradienteAtual.value = gradiente

                    // ── Detector de Modo Montanha com histerese ───────────────
                    when {
                        gradiente >= LIMIAR_GRADE_MONTANHA -> {
                            distSubidaAcum += distancia
                            if (!emModoMontanha && distSubidaAcum >= DISTANCIA_ATIVA_MONTANHA) {
                                emModoMontanha = true
                                _modoMontanha.value = true
                                Log.d(TAG, "⛰️ Modo Montanha ATIVADO: subida > ${(LIMIAR_GRADE_MONTANHA * 100).toInt()}% por ${distSubidaAcum.toInt()}m")
                            }
                        }
                        gradiente < LIMIAR_SAIDA_MONTANHA -> {
                            distSubidaAcum = 0.0
                            if (emModoMontanha) {
                                emModoMontanha = false
                                _modoMontanha.value = false
                                Log.d(TAG, "⛰️ Modo Montanha DESATIVADO (grade=${String.format("%.1f", gradiente * 100)}%)")
                            }
                        }
                        // Zona de histerese (2–4%): mantém estado atual sem acumular
                    }
                }
            }

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
            if (rotaTotalPontos == 1 || rotaTotalPontos % 5 == 0) {
                // Enquanto o buffer ainda não encheu: emite só o buffer (rápido, sem IO)
                // Quando o buffer já está cheio e descartando pontos antigos: a cada 50
                // novos pontos busca Room + buffer para que o mapa mostre a rota completa.
                if (rotaTotalPontos > BUFFER_MAX_PONTOS && rotaTotalPontos % 50 == 0) {
                    serviceScope.launch(Dispatchers.IO) {
                        try {
                            val pontosRoom   = database.routePointDao().getSessionPoints(sessionId).map { it.toLatLngPonto() }
                            val ultimoTempo  = pontosRoom.lastOrNull()?.tempo ?: 0L
                            val pontosBuffer = rota.toList().filter { it.tempo > ultimoTempo }
                            val rotaCompleta = (pontosRoom + pontosBuffer).sortedBy { it.tempo }
                            _rotaAtual.value = rotaCompleta
                            Log.d(TAG, "🗺️ Mapa ao vivo: rota completa emitida (${rotaCompleta.size} pontos, Room=${pontosRoom.size} + buffer=${pontosBuffer.size})")
                        } catch (e: Exception) {
                            Log.e(TAG, "⚠️ Falha ao emitir rota completa para o mapa", e)
                            _rotaAtual.value = rota.toList()
                        }
                    }
                } else {
                    _rotaAtual.value = rota.toList()
                }
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

        calcularPaceAtual(location)
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

    private fun calcularPaceAtual(location: android.location.Location) {
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
            val dtMs = (pontosJanela[i].time - pontosJanela[i - 1].time).coerceAtLeast(100L)
            val dtS  = dtMs / 1000.0
            val d = calcularDistancia(
                pontosJanela[i - 1].latitude, pontosJanela[i - 1].longitude,
                pontosJanela[i].latitude,     pontosJanela[i].longitude
            )
            // FIX: descarta segmentos fora da faixa de velocidade humana (0.5m..6.5 m/s).
            //
            // Bug real: ao desbloquear a tela, o GPS chipset reativa e entrega 1-2 pontos
            // com pequeno drift de posicao (~5-15m a frente). Esse drift nao chega a 11 m/s
            // (filtro modoRecuperacaoGps), mas na janela curta apos limpar o buffer:
            //   distJanela = drift(~10m) + real(~2.8m) = ~12.8m em 1s
            //   paceBruto = (1/12.8)*1000 = 78 s/km = 1:18/km — ultraveloz, passa no filtro >90
            // Isso vira semente da EMA e o pace exibido fica muito rapido por 15-20s,
            // ficando registrado no grafico e no mapa do historico.
            //
            // Filtrando por velocidade do segmento (nao apenas d > 0.5):
            //   - Descarta drift GPS (veloc > 6.5 m/s = ~2:34/km)
            //   - Descarta GPS travado (d < 0.5m)
            // Apenas o segmento outlier e descartado; o tempo total da janela permanece
            // correto (last.time - first.time) pois esses pontos ainda estao na janela.
            val velocidadeSegMs = d / dtS
            if (velocidadeSegMs > 6.5 || d < 0.5) {
                continue
            }
            distanciaJanela += d
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

        val msDesdeScreenOn = if (screenOnTimestampMs > 0)
            SystemClock.elapsedRealtime() - screenOnTimestampMs else Long.MAX_VALUE

        // ── Filtro de Stride Length — janela deslizante de 5s ──────────────────
        //
        // REGRA A (biomecânica): stride = distJanela5s / passosJanela5s > 1.9m → bloqueia
        //   Guard de cold start: só aplica rigorosamente com >= 3s de dados ou >= 4 passos.
        //   Abaixo disso a janela é muito pequena e um tremido isolado dispara falso positivo.
        //
        // REGRA B (fail-safe): passos=0 mas GPS em movimento
        //   Accuracy normal:           <= 15m aceita (sinal limpo)
        //   Durante modoRecuperacaoGps: <= 25m aceita (folga para lag de batching do sensor)
        //   Velocidade > 10 m/s (carro/ônibus): sempre bloqueia.
        run {
            val distJanela5s   = bufferStride5s.sumOf { it.distM }
            val passosJanela5s = bufferStride5s.sumOf { it.passos }
            val janelaSeg      = if (bufferStride5s.size >= 2)
                (bufferStride5s.last().timestampMs - bufferStride5s.first().timestampMs) / 1000.0
                else 0.0
            val velocidadeGps  = location.speed  // m/s
            val janelaQuente   = janelaSeg >= 3.0 || passosJanela5s >= 4

            if (passosJanela5s > 0) {
                val stride = distJanela5s / passosJanela5s
                if (janelaQuente && stride > 1.9) {
                    // REGRA A — passada impossível, GPS exagerando
                    _paceAtual.value = "--:--"
                    return
                }

            } else if (velocidadeGps > 0.5f) {
                // REGRA B — sem passos mas GPS em movimento
                // Folga maior durante recuperação para absorver lag de batching do sensor
                val limiteAccuracy = if (modoRecuperacaoGps) 25f else 15f
                if (location.accuracy > limiteAccuracy || velocidadeGps > 10.0f) {
                    _paceAtual.value = "--:--"
                    return
                }

            }
        }

        // Alpha reduzido nos primeiros 20s após SCREEN_ON/início — GPS ainda estabilizando.
        // alpha=0.05 (tau≈20s): protege contra spikes mas responde em ~20s a um pace real.
        val alpha = when {
            msDesdeScreenOn < 20_000L -> 0.05
            janelaAtualSegundos <= 5  -> 0.4
            else                      -> 0.25
        }
        val paceEma = ultimoPaceEmaInterno?.let { anterior ->
            (paceBruto * alpha) + (anterior * (1.0 - alpha))
        } ?: paceBruto

        // Log para diagnóstico de spike de pace pós-desbloqueio.


        // Quarentena de 10s após SCREEN_ON: GPS ainda estabilizando.
        // Atualiza EMA internamente mas não grava no gráfico nem altera o display.
        // O corredor continua vendo o último pace válido enquanto o chip GNSS
        // recalibra o Doppler. Log confirma que o A54 leva ~10s para convergir.
        if (msDesdeScreenOn < 10_000L) {
            ultimoPaceEmaInterno = paceEma
            return
        }

        ultimoPaceEmaInterno = paceEma
        ultimoPaceValidoGrafico = paceEma
        _paceAtual.value = formatarPace(paceEma)
    }

    private fun calcularPaceMedia() {
        if (_distanciaMetros.value < 10.0 || _tempoTotalSegundos.value < 1) {
            _paceMedia.value = "--:--"
            return
        }

        // Usa _distanciaMetros (acumulado em tempo real via Doppler/Haversine 3D).
        // Nota: ao salvar, paceMediaOficial e recalculado sobre distancia 2D suavizada
        // (GpxGenerator.calcularDistanciaSmoothed), o que pode divergir ligeiramente.
        // A divergencia e aceitavel e esperada: durante a corrida priorizamos responsividade;
        // apos salvar priorizamos coerencia com o que Intervals.icu e Strava mostram.
        val paceSegundos = (_tempoTotalSegundos.value.toDouble() / _distanciaMetros.value) * 1000.0
        _paceMedia.value = formatarPace(paceSegundos)
    }

    /**
     * Fórmula de Minetti (2002) — custo metabólico normalizado pela corrida plana.
     *
     * C(g) = 155.4g⁵ - 30.4g⁴ - 43.3g³ + 46.3g² + 19.5g + 3.6
     *
     * O fator retornado é C(g) / C(0) = C(g) / 3.6
     * Interpretação: pace_gap = pace_real / fator
     *   fator > 1 → subida (corredor trabalha mais → GAP < pace_real = "equivale a mais rápido no plano")
     *   fator ~ 0.6 em ~-10% → descida suave (pico de economia, menor custo metabólico)
     *   fator voltando a 1.0+ em < -15% → descida técnica (frenagem excêntrica — custo sobe novamente)
     *
     * NOTA SOBRE O "PARADOXO DA PIRAMBEIRA":
     * Em grades muito negativas (< -15%), o GAP fica ≥ pace real, o que parece contraintuitivo.
     * É fisicamente correto: você vai rápido mas os quadríceps freiam o corpo com custo metabólico
     * alto. O AudioCoach trata essa faixa com mensagem específica ("controle o impacto") em vez
     * de comparar GAP com pace — essa comparação seria confusa para o corredor.
     *
     * Referência: Minetti AE et al., J Appl Physiol 93(3):1039-1046, 2002.
     */
    private fun fatorMinetti(gradiente: Double): Double {
        val g = gradiente.coerceIn(-0.45, 0.45)
        val custo = 155.4 * Math.pow(g, 5.0) -
                     30.4 * Math.pow(g, 4.0) -
                     43.3 * Math.pow(g, 3.0) +
                     46.3 * Math.pow(g, 2.0) +
                     19.5 * g + 3.6
        return (custo / 3.6).coerceAtLeast(0.1)
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
        
        const val MAX_ACCURACY_METERS = 35f       // descarta da UI (aumentado de 25→35 para reduzir gaps em GPS urbano)
        const val ROOM_ACCURACY_METERS = 20f      // descarta da persistência (aumentado de 15→20 para GPS urbano)
    }
}
