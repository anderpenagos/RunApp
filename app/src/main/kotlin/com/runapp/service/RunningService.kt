package com.runapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.runapp.MainActivity
import com.runapp.data.model.LatLngPonto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
class RunningService : Service() {

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
    private val JANELA_PACE_SEGUNDOS = 15
    
    // Timestamps
    private var timestampInicio: Long = 0
    private var timestampPausaInicio: Long = 0
    private var tempoPausadoTotal: Long = 0
    
    // Auto-pause
    private var ultimaLocalizacaoSignificativa: Location? = null
    private var contadorSemMovimento = 0
    private var contadorEmMovimento = 0
    private val LIMITE_SEM_MOVIMENTO = 3          // 3s parado → pausa
    private val LIMITE_RETOMAR_MOVIMENTO = 2      // 2 updates em movimento → retoma
    private val DISTANCIA_MINIMA_MOVIMENTO = 4.0  // metros por update (1s)
    
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
        
        // Resetar dados
        rota.clear()
        ultimasLocalizacoes.clear()
        timestampInicio = System.currentTimeMillis()
        tempoPausadoTotal = 0
        _distanciaMetros.value = 0.0
        _tempoTotalSegundos.value = 0
        estaPausado = false
        estaCorrendo = true
        
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
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
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
            accuracy = location.accuracy
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

        // Verificar movimento para auto-pause
        verificarAutoPause(location)
        
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
        
        // Remover localizações antigas da janela (>15 segundos)
        val tempoCorte = agora - (JANELA_PACE_SEGUNDOS * 1000)
        ultimasLocalizacoes.removeAll { it.time < tempoCorte }
        
        // PROTEÇÃO CONTRA SPIKE: Se ficou muito tempo sem GPS, limpar janela
        if (ultimasLocalizacoes.size >= 2) {
            val tempoJanela = (ultimasLocalizacoes.last().time - ultimasLocalizacoes.first().time) / 1000.0
            if (tempoJanela > (JANELA_PACE_SEGUNDOS * 2)) {
                Log.w(TAG, "⚠️ Gap temporal detectado (${tempoJanela}s), resetando janela de pace")
                ultimasLocalizacoes.clear()
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

    private fun calcularPaceAtual() {
        if (ultimasLocalizacoes.size < 3) {
            _paceAtual.value = "--:--"
            return
        }

        // Calcular distância e tempo da janela
        var distanciaJanela = 0.0
        for (i in 1 until ultimasLocalizacoes.size) {
            val loc1 = ultimasLocalizacoes[i - 1]
            val loc2 = ultimasLocalizacoes[i]
            distanciaJanela += calcularDistancia(
                loc1.latitude, loc1.longitude,
                loc2.latitude, loc2.longitude
            )
        }

        val tempoJanelaSegundos = (ultimasLocalizacoes.last().time - ultimasLocalizacoes.first().time) / 1000.0

        if (distanciaJanela < 1.0 || tempoJanelaSegundos < 1.0) {
            _paceAtual.value = "--:--"
            return
        }

        // Pace em segundos por quilômetro
        val paceSegundos = (tempoJanelaSegundos / distanciaJanela) * 1000.0
        _paceAtual.value = formatarPace(paceSegundos)
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
        
        // Limitar pace máximo e mínimo para valores razoáveis
        val pace = when {
            segundosPorKm < 180 -> return "--:--"  // Muito rápido (< 3 min/km)
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
        
        const val MAX_ACCURACY_METERS = 50f
    }
}
