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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Foreground Service para rastreamento GPS contínuo, mesmo com tela bloqueada.
 * 
 * IMPORTANTE: Este serviço roda independentemente do ciclo de vida das Activities.
 * Ele mantém o GPS ativo e processa todos os cálculos de pace, distância, etc.
 */
class RunningService : Service(), SensorEventListener {

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Coroutines e Lifecycle
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    // Scope próprio do serviço - NÃO usa viewModelScope
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
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

    // EMA (Média Móvel Exponencial) — suaviza sem perder reatividade
    private var ultimoPaceEma: Double? = null  // null = sem valor anterior ainda
    
    // Timestamps
    private var timestampInicio: Long = 0
    private var timestampPausaInicio: Long = 0
    private var tempoPausadoTotal: Long = 0
    private var ultimoCliquePasso: Long = 0L  // debounce para pularPasso/voltarPasso
    
    // Auto-pause
    private var ultimaLocalizacaoSignificativa: Location? = null
    private var contadorSemMovimento = 0
    private var contadorEmMovimento = 0
    private val LIMITE_SEM_MOVIMENTO = 3          // 3s parado → pausa
    private val LIMITE_RETOMAR_MOVIMENTO = 2      // 2 updates em movimento → retoma
    private val DISTANCIA_MINIMA_MOVIMENTO = 4.0  // metros por update (1s)
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
    private var acelerometro: Sensor? = null

    // Buffer circular dos últimos timestamps de passo (janela de 10s)
    private val timestampsPassos = ArrayDeque<Long>(50)
    private var ultimoTimestampPasso = 0L

    // Threshold adaptativo: começa em 13.0 (mais resistente a trepidação de bolso)
    private var thresholdAceleracao = 13.0f
    private var somaUltimosPicos = 0f
    private var contadorPicos = 0
    
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

        // Sensores — acelerômetro para cadência
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        acelerometro = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (acelerometro == null) Log.w(TAG, "⚠️ TYPE_LINEAR_ACCELERATION não disponível")

        // Adquirir WakeLock parcial
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RunApp::RunningServiceWakeLock"
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "📌 onStartCommand: action=${intent?.action}")
        
        when (intent?.action) {
            ACTION_START -> iniciarRastreamento()
            ACTION_PAUSE -> pausarRastreamento()
            ACTION_RESUME -> retomarRastreamento()
            ACTION_STOP -> pararRastreamento()
        }
        
        return START_STICKY  // Importante para o sistema tentar reiniciar o service
    }

    private fun iniciarRastreamento() {
        Log.d(TAG, "▶️ Iniciando rastreamento")
        
        // Configurar como Foreground Service
        criarCanalNotificacao()
        startForeground(NOTIFICATION_ID, criarNotificacao())
        
        // Adquirir WakeLock
        wakeLock?.acquire(10 * 60 * 1000L /*10 minutos*/)
        
        // Ler preferência de auto-pause antes de começar
        serviceScope.launch {
            val prefs = PreferencesRepository(applicationContext)
            autoPauseFuncaoAtiva = prefs.autoPauseEnabled.first()
            Log.d(TAG, "⚙️ Auto-pause ${if (autoPauseFuncaoAtiva) "ativado" else "desativado"}")
        }
        
        // Resetar dados
        rota.clear()
        ultimasLocalizacoes.clear()
        ultimoPaceEma = null
        janelaAtualSegundos = 12
        timestampInicio = System.currentTimeMillis()
        tempoPausadoTotal = 0
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
        acelerometro?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            Log.d(TAG, "📡 Acelerômetro registrado para cadência")
        }
        
        // Iniciar GPS
        iniciarAtualizacoesGPS()
        
        // Iniciar timer
        iniciarTimer()
    }

    private fun pausarRastreamento() {
        Log.d(TAG, "⏸️ Pausando rastreamento")
        estaPausado = true
        timestampPausaInicio = System.currentTimeMillis()
        
        // Atualizar notificação
        atualizarNotificacao("Corrida pausada")
    }

    private fun retomarRastreamento() {
        Log.d(TAG, "▶️ Retomando rastreamento")
        
        if (estaPausado) {
            val tempoPausa = System.currentTimeMillis() - timestampPausaInicio
            tempoPausadoTotal += tempoPausa
            estaPausado = false
            
            // Atualizar notificação
            atualizarNotificacao("Corrida em andamento")
        }
    }

    private fun pararRastreamento() {
        Log.d(TAG, "⏹️ Parando rastreamento")

        estaCorrendo = false

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
        
        // Parar foreground e service
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "🔴 Service onDestroy")

        // Garantir limpeza
        timerJob?.cancel()
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
        if (event.sensor.type != Sensor.TYPE_LINEAR_ACCELERATION) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        // Norma do vetor de aceleração linear (sem gravidade)
        val magnitude = kotlin.math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()

        detectarPasso(magnitude)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* não usado */ }

    private fun detectarPasso(magnitude: Float) {
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

        ultimoTimestampPasso = agora

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

        // Buffer circular: mantém apenas timestamps dos últimos 10s
        timestampsPassos.addLast(agora)
        while (timestampsPassos.isNotEmpty() && timestampsPassos.first() < agora - 10_000L) {
            timestampsPassos.removeFirst()
        }

        // Cadência = (passos em 10s / 10) * 60, só se tiver dados suficientes (≥3 passos)
        if (timestampsPassos.size >= 3) {
            val spm = (timestampsPassos.size / 10.0 * 60).toInt()
            // Sanidade: cobre caminhada (~60 SPM) até corrida rápida (220 SPM)
            // ATENÇÃO: o range anterior era 120–220, o que silenciosamente ignorava
            // cadências corretas de caminhada (~100–115 SPM). Corrigido para 60–220.
            if (spm in 60..220) {
                _cadencia.value = spm
            }
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // GPS Tracking
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { location ->
                processarNovaLocalizacao(location)
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
        
        // Filtro básico de qualidade
        if (location.accuracy > MAX_ACCURACY_METERS) {
            Log.d(TAG, "⚠️ Localização descartada: accuracy=${location.accuracy}m")
            return
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

        // Adicionar ponto à rota
        rota.add(pontoNovo)
        _rotaAtual.value = rota.toList()

        // Atualizar distância total
        _distanciaMetros.value += distancia

        // Gerenciar janela móvel para pace atual
        ultimasLocalizacoes.add(location)
        
        // Remover localizações antigas da janela (janela adaptativa)
        val tempoCorte = agora - (janelaAtualSegundos * 1000)
        ultimasLocalizacoes.removeAll { it.time < tempoCorte }
        
        // PROTEÇÃO CONTRA SPIKE: Se ficou muito tempo sem GPS, limpar janela
        if (ultimasLocalizacoes.size >= 2) {
            val tempoJanela = (ultimasLocalizacoes.last().time - ultimasLocalizacoes.first().time) / 1000.0
            if (tempoJanela > (janelaAtualSegundos * 2)) {
                Log.w(TAG, "⚠️ Gap temporal detectado (${tempoJanela}s), resetando janela de pace")
                ultimasLocalizacoes.clear()
                ultimoPaceEma = null
                _paceAtual.value = "--:--"
                return
            }
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

        // BUG FIX 1: Limitar o limiar dinâmico a no máximo 8m.
        // Antes, usava location.accuracy diretamente — com sinal fraco (20-40m de accuracy),
        // o limiar ficava tão alto que nenhum movimento real conseguia superá-lo,
        // travando o app em auto-pause para sempre.
        val LIMIAR_MAXIMO_METROS = 8.0
        val limiarMovimento = minOf(
            maxOf(DISTANCIA_MINIMA_MOVIMENTO, location.accuracy.toDouble()),
            LIMIAR_MAXIMO_METROS
        )

        val distanciaDesdeUltima = calcularDistancia(
            ultimaLoc.latitude, ultimaLoc.longitude,
            location.latitude, location.longitude
        )

        if (distanciaDesdeUltima < limiarMovimento) {
            // Sem movimento suficiente
            contadorSemMovimento++
            contadorEmMovimento = 0

            // BUG FIX 2: Atualizar a referência mesmo durante auto-pause.
            // Antes, ultimaLocalizacaoSignificativa só era atualizada ao detectar movimento.
            // Isso fazia com que, ao retomar, a distância fosse calculada desde um ponto
            // antigo (pré-pausa), resultando em valores incorretos ou bloqueio da retomada.
            if (_autoPausado.value) {
                ultimaLocalizacaoSignificativa = location
            }

            if (contadorSemMovimento >= LIMITE_SEM_MOVIMENTO && !_autoPausado.value) {
                Log.d(TAG, "⏸️ Auto-pause ativado (${contadorSemMovimento}s sem movimento, accuracy=${location.accuracy}m, limiar=${limiarMovimento}m)")
                _autoPausado.value = true
                atualizarNotificacao("Auto-pausado (sem movimento)")
            }
        } else {
            // Em movimento real
            contadorEmMovimento++
            contadorSemMovimento = 0
            ultimaLocalizacaoSignificativa = location

            if (_autoPausado.value && contadorEmMovimento >= LIMITE_RETOMAR_MOVIMENTO) {
                Log.d(TAG, "▶️ Auto-pause desativado (movimento confirmado, ${contadorEmMovimento} updates)")
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
        if (paceBruto < 90.0 || paceBruto > 1200.0) {
            _paceAtual.value = "--:--"
            return
        }

        // EMA: alpha depende da janela — janela curta reage mais rápido
        val alpha = if (janelaAtualSegundos <= 5) 0.4 else 0.25
        val paceEma = ultimoPaceEma?.let { anterior ->
            (paceBruto * alpha) + (anterior * (1.0 - alpha))
        } ?: paceBruto  // primeiro valor: sem histórico, usa direto

        ultimoPaceEma = paceEma
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
                    val tempoDecorrido = (System.currentTimeMillis() - timestampInicio - tempoPausadoTotal) / 1000
                    _tempoTotalSegundos.value = tempoDecorrido
                    
                    // Atualizar notificação a cada 5 segundos
                    if (tempoDecorrido % 5 == 0L) {
                        atualizarNotificacao()
                    }
                }
            }
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Notificações
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RunApp — Corrida Ativa 🏃")
            .setContentText(conteudo)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
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
        
        const val ACTION_START = "START"
        const val ACTION_PAUSE = "PAUSE"
        const val ACTION_RESUME = "RESUME"
        const val ACTION_STOP = "STOP"

        // Intent da notificação persistente → navegação direta para a corrida
        const val ACTION_SHOW_RUNNING = "ACTION_SHOW_RUNNING_SCREEN"
        const val EXTRA_EVENT_ID = "EVENT_ID"
        
        const val MAX_ACCURACY_METERS = 50f
    }
}
