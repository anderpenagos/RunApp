package com.runapp.ui.viewmodel

import android.content.Context
import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.android.gms.location.LocationServices
import com.runapp.RunApp
import com.runapp.AppContainer
import com.runapp.data.model.LatLngPonto
import com.runapp.data.model.PassoExecucao
import com.runapp.data.model.WorkoutEvent
import com.runapp.data.repository.WorkoutRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Date
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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
    
    // Métricas em tempo real
    val distanciaMetros: Double = 0.0,
    val tempoTotalSegundos: Long = 0,
    val tempoCorridaSegundos: Long = 0,
    val tempoPassoAtualSegundos: Long = 0,
    val paceAtual: String = "--:--",
    val paceMedia: String = "--:--",
    val progressoPasso: Float = 0f,
    val tempoPassoRestante: Int = 0,
    
    // Rastreamento GPS
    val rota: List<LatLngPonto> = emptyList(),
    val posicaoAtual: LatLngPonto? = null,
    val autoPausado: Boolean = false,
    
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

    private var timerJob: Job? = null
    private var workoutRepo: WorkoutRepository? = null
    private var arquivoGpxSalvo: File? = null
    
    // Audio Coach para alertas de voz
    private val audioCoach = com.runapp.service.AudioCoach(context)
    private var ultimoKmAnunciado = 0
    private var ultimoAlertaPace = 0L
    private val INTERVALO_ALERTA_PACE_MS = 15000L  // Alerta de pace a cada 15s
    
    // Timestamps
    private var timestampInicio: Long = 0
    private var timestampPassoAtual: Long = 0
    private var timestampPausaInicio: Long = 0
    private var tempoPausadoTotal: Long = 0
    
    // Auto-pause
    private var ultimaLocalizacaoSignificativa: Location? = null
    private var contadorSemMovimento = 0
    private val LIMITE_SEM_MOVIMENTO = 5  // 5 atualizações sem movimento
    private val DISTANCIA_MINIMA_MOVIMENTO = 5.0  // metros
    
    // Rolling Window para Pace Atual Suavizado
    private val ultimasLocalizacoes = mutableListOf<Location>()
    private val JANELA_PACE_SEGUNDOS = 15  // Média dos últimos 15 segundos

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Lifecycle
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    init {
        android.util.Log.d("CorridaVM", "✅ ViewModel inicializado")
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        audioCoach.shutdown()
        android.util.Log.d("CorridaVM", "🧹 ViewModel limpo (AudioCoach desligado)")
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
                        // Buscar zonas para processar os passos
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
                        
                        android.util.Log.d("CorridaVM", "✅ Treino carregado: ${evento.name} (${passosProcessados.size} passos)")
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
        android.util.Log.d("CorridaVM", """
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            ▶️ INICIANDO CORRIDA
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            Treino: ${_uiState.value.treino?.name ?: "Sem treino"}
            Passos: ${_uiState.value.passos.size}
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        """.trimIndent())
        
        timestampInicio = System.currentTimeMillis()
        timestampPassoAtual = timestampInicio
        tempoPausadoTotal = 0
        ultimoKmAnunciado = 0
        
        // Limpar rolling window ao iniciar nova corrida
        ultimasLocalizacoes.clear()
        
        _uiState.value = _uiState.value.copy(
            fase = FaseCorrida.CORRENDO
        )
        
        iniciarTimer()
        iniciarGPS()
        
        // 🔊 Anunciar início
        audioCoach.anunciarInicioCorrida()
        
        android.util.Log.d("CorridaVM", "✅ Corrida iniciada - aguardando pontos GPS...")
    }

    fun pausar() {
        android.util.Log.d("CorridaVM", "⏸️ Corrida pausada")
        
        timestampPausaInicio = System.currentTimeMillis()
        
        _uiState.value = _uiState.value.copy(
            fase = FaseCorrida.PAUSADO,
            autoPausado = false
        )
        
        timerJob?.cancel()
        
        // Limpar rolling window do pace ao pausar
        ultimasLocalizacoes.clear()
        android.util.Log.d("CorridaVM", "🧹 Rolling window do pace limpo")
    }

    fun retomar() {
        android.util.Log.d("CorridaVM", "▶️ Corrida retomada")
        
        val tempoPausa = System.currentTimeMillis() - timestampPausaInicio
        tempoPausadoTotal += tempoPausa
        
        _uiState.value = _uiState.value.copy(
            fase = FaseCorrida.CORRENDO,
            autoPausado = false
        )
        
        iniciarTimer()
    }

    fun finalizarCorrida() {
        android.util.Log.d("CorridaVM", "⏹️ Corrida finalizada")
        
        timerJob?.cancel()
        
        val state = _uiState.value
        _uiState.value = state.copy(
            fase = FaseCorrida.FINALIZADO
        )
        
        // 🔊 Anunciar fim
        audioCoach.anunciarFimCorrida(
            distanciaKm = state.distanciaMetros / 1000.0,
            tempoTotal = state.tempoFormatado,
            paceMedia = state.paceMedia
        )
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Timer
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun iniciarTimer() {
        timerJob?.cancel()
        android.util.Log.d("CorridaVM", "⏰ Timer iniciado")
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                atualizarMetricas()
            }
        }
    }

    private fun atualizarMetricas() {
        val agora = System.currentTimeMillis()
        val tempoTotal = ((agora - timestampInicio - tempoPausadoTotal) / 1000).coerceAtLeast(0)
        val tempoPasso = ((agora - timestampPassoAtual - tempoPausadoTotal) / 1000).coerceAtLeast(0)
        
        val state = _uiState.value
        val passoAtual = state.passoAtual
        
        // Log a cada 10 segundos
        if (tempoTotal % 10 == 0L) {
            android.util.Log.d("CorridaVM", """
                ⏱️ Timer: ${formatarTempo(tempoTotal)}
                   Pontos GPS: ${state.rota.size}
                   Distância: ${"%.3f".format(state.distanciaMetros / 1000)} km
                   Fase: ${state.fase}
            """.trimIndent())
        }
        
        // Atualizar progresso do passo
        val progresso = if (passoAtual != null && passoAtual.duracao > 0) {
            (tempoPasso.toFloat() / passoAtual.duracao).coerceIn(0f, 1f)
        } else 0f
        
        val tempoRestante = if (passoAtual != null) {
            (passoAtual.duracao - tempoPasso).coerceAtLeast(0)
        } else 0
        
        // 🔊 Anunciar últimos segundos do passo
        if (passoAtual != null && tempoRestante in 3..10) {
            audioCoach.anunciarUltimosSegundos(tempoRestante.toInt())
        }
        
        // Verificar se deve avançar para próximo passo
        if (passoAtual != null && tempoPasso >= passoAtual.duracao) {
            avancarPasso()
            return
        }
        
        // Calcular pace médio
        val paceMedia = if (state.tempoCorridaSegundos > 0 && state.distanciaMetros > 0) {
            val segPorKm = (state.tempoCorridaSegundos.toDouble() / (state.distanciaMetros / 1000.0))
            formatarPace(segPorKm)
        } else "--:--"
        
        // 🔊 Anunciar cada km completo
        val kmAtual = (state.distanciaMetros / 1000).toInt()
        if (kmAtual > ultimoKmAnunciado && kmAtual > 0) {
            audioCoach.anunciarKm(kmAtual.toDouble(), paceMedia)
            ultimoKmAnunciado = kmAtual
        }
        
        // 🔊 Verificar pace vs alvo (a cada 15 segundos)
        if (passoAtual != null && !passoAtual.isDescanso && 
            state.paceAtual != "--:--" &&
            agora - ultimoAlertaPace >= INTERVALO_ALERTA_PACE_MS) {
            
            audioCoach.anunciarPaceFeedback(
                paceAtual = state.paceAtual,
                paceAlvoMin = passoAtual.paceAlvoMin,
                paceAlvoMax = passoAtual.paceAlvoMax
            )
            ultimoAlertaPace = agora
        }
        
        _uiState.value = state.copy(
            tempoTotalSegundos = tempoTotal,
            tempoCorridaSegundos = tempoTotal,
            tempoPassoAtualSegundos = tempoPasso,
            progressoPasso = progresso,
            tempoPassoRestante = tempoRestante.toInt(),
            paceMedia = paceMedia,
            tempoFormatado = formatarTempo(tempoTotal)
        )
    }

    private fun avancarPasso() {
        val state = _uiState.value
        val proximoIndex = state.passoAtualIndex + 1
        
        if (proximoIndex < state.passos.size) {
            timestampPassoAtual = System.currentTimeMillis()
            
            val proximoPasso = state.passos[proximoIndex]
            
            _uiState.value = state.copy(
                passoAtualIndex = proximoIndex,
                passoAtual = proximoPasso,
                tempoPassoAtualSegundos = 0,
                progressoPasso = 0f
            )
            
            android.util.Log.d("CorridaVM", "➡️ Avançou para passo ${proximoIndex + 1}/${state.passos.size}")
            
            // 🔊 Anunciar novo passo
            if (proximoPasso.isDescanso) {
                audioCoach.anunciarDescanso()
            } else {
                audioCoach.anunciarPasso(
                    nomePasso = proximoPasso.nome,
                    paceAlvo = "${proximoPasso.paceAlvoMin} a ${proximoPasso.paceAlvoMax}",
                    duracao = proximoPasso.duracao
                )
            }
        } else {
            android.util.Log.d("CorridaVM", "🏁 Todos os passos completados")
            finalizarCorrida()
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // GPS
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun iniciarGPS() {
        viewModelScope.launch {
            try {
                val fusedClient = LocationServices.getFusedLocationProviderClient(context)
                
                fusedClient.lastLocation.addOnSuccessListener { location ->
                    location?.let { 
                        android.util.Log.d("CorridaVM", """
                            📍 Última localização conhecida obtida:
                               Lat: ${it.latitude}
                               Lng: ${it.longitude}
                               Accuracy: ${it.accuracy}m
                               Tempo: ${Date(it.time)}
                        """.trimIndent())
                    }
                }.addOnFailureListener { e ->
                    android.util.Log.w("CorridaVM", "⚠️ Não foi possível obter última localização: ${e.message}")
                }
                
                android.util.Log.d("CorridaVM", "✅ GPS client inicializado com sucesso")
                
            } catch (e: SecurityException) {
                android.util.Log.e("CorridaVM", "❌ Erro ao iniciar GPS: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    erro = "Permissões de GPS não concedidas. " +
                           "Vá em Configurações > Apps > RunApp > Permissões e ative 'Localização'"
                )
            } catch (e: Exception) {
                android.util.Log.e("CorridaVM", "❌ Erro inesperado ao iniciar GPS", e)
                _uiState.value = _uiState.value.copy(
                    erro = "Erro ao inicializar GPS: ${e.message}"
                )
            }
        }
    }

    fun onNovaLocalizacao(location: Location) {
        val state = _uiState.value
        
        // Log SEMPRE no início para debug
        android.util.Log.d("GPS_DEBUG", """
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            📍 Nova localização recebida
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            Fase: ${state.fase}
            Lat: ${location.latitude}
            Lng: ${location.longitude}
            Accuracy: ${location.accuracy}m
            Speed: ${if (location.hasSpeed()) "${location.speed} m/s" else "N/A"}
            Pontos coletados: ${state.rota.size}
            Distância atual: ${"%.2f".format(state.distanciaMetros / 1000)} km
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        """.trimIndent())
        
        if (state.fase != FaseCorrida.CORRENDO) {
            android.util.Log.w("GPS_DEBUG", "⚠️ Localização ignorada - fase: ${state.fase}")
            return
        }

        // Filtrar pontos com accuracy muito ruim
        // Primeiros 5 pontos: aceita até 50m
        // Depois: apenas 30m
        val limiteAccuracy = if (state.rota.size < 5) 50f else 30f
        
        if (location.accuracy > limiteAccuracy) {
            android.util.Log.w("GPS_DEBUG", "⚠️ Ponto descartado - accuracy ${location.accuracy}m > limite $limiteAccuracy m (${state.rota.size} pontos coletados)")
            return
        }

        android.util.Log.i("GPS_DEBUG", "✅ Ponto ACEITO - accuracy OK (${location.accuracy}m)")

        val novoPonto = LatLngPonto(location.latitude, location.longitude)
        val novaRota = state.rota + novoPonto

        // Calcular distância incremental
        var novaDistancia = state.distanciaMetros
        if (state.rota.isNotEmpty()) {
            val ultimoPonto = state.rota.last()
            val distanciaIncremental = calcularDistancia(
                ultimoPonto.lat, ultimoPonto.lng,
                novoPonto.lat, novoPonto.lng
            )
            novaDistancia += distanciaIncremental
            android.util.Log.d("GPS_DEBUG", "📏 Distância incremental: +${"%.2f".format(distanciaIncremental)}m → Total: ${"%.2f".format(novaDistancia)}m")
        }

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // Auto-pause: TEMPORARIAMENTE DESATIVADO para debug
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        /*
        val estaParado = verificarSeEstaParado(location)
        val deveAutoPausar = estaParado && !state.autoPausado

        if (deveAutoPausar) {
            android.util.Log.d("CorridaVM", "⏸️ Auto-pause ativado (sem movimento)")
            pausar()
            _uiState.value = _uiState.value.copy(autoPausado = true)
            return
        }
        */
        android.util.Log.d("GPS_DEBUG", "⏭️ Auto-pause desativado para debug")

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // Calcular Pace Atual usando Rolling Window (Média Móvel dos últimos 15s)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        // 1. Adiciona a localização atual à janela de tempo
        ultimasLocalizacoes.add(location)
        
        // 2. Remove pontos mais velhos que a janela (15 segundos)
        val tempoCorte = location.time - (JANELA_PACE_SEGUNDOS * 1000)
        ultimasLocalizacoes.removeAll { it.time < tempoCorte }
        
        // 3. Calcula o Pace baseado na janela de tempo
        val paceAtual = calcularPaceNaJanela(ultimasLocalizacoes)
        
        android.util.Log.d("GPS_DEBUG", "📊 Pace Atual (Rolling Window ${ultimasLocalizacoes.size} pontos): $paceAtual")

        _uiState.value = state.copy(
            rota = novaRota,
            posicaoAtual = novoPonto,
            distanciaMetros = novaDistancia,
            paceAtual = paceAtual
        )
        
        android.util.Log.d("GPS_DEBUG", "💾 Estado atualizado: ${novaRota.size} pontos, ${"%.3f".format(novaDistancia / 1000)} km")
    }
    
    /**
     * Calcula o pace atual baseado na média móvel dos últimos X segundos.
     * 
     * Este método implementa um "Rolling Window" que:
     * - Soma a distância percorrida entre todos os pontos na janela
     * - Divide pelo tempo real decorrido
     * - Converte para pace (min/km)
     * 
     * Esta é a mesma técnica usada por relógios esportivos profissionais
     * (Garmin, Apple Watch, etc) para suavizar o pace e evitar oscilações.
     */
    private fun calcularPaceNaJanela(pontos: List<Location>): String {
        // Precisa de pelo menos 3 pontos para calcular
        if (pontos.size < 3) {
            android.util.Log.d("GPS_DEBUG", "⚠️ Pace: aguardando mais pontos (${pontos.size}/3)")
            return "--:--"
        }

        // Soma a distância entre todos os pontos consecutivos na janela
        var distanciaJanelaMetros = 0.0
        for (i in 0 until pontos.size - 1) {
            distanciaJanelaMetros += pontos[i].distanceTo(pontos[i + 1])
        }

        // Tempo real decorrido na janela
        val tempoJanelaSegundos = (pontos.last().time - pontos.first().time) / 1000.0

        // Validações: movimento muito pequeno ou tempo muito curto
        if (distanciaJanelaMetros < 5.0) {
            android.util.Log.d("GPS_DEBUG", "⚠️ Pace: movimento muito pequeno (${"%.1f".format(distanciaJanelaMetros)}m)")
            return "--:--"
        }
        
        if (tempoJanelaSegundos < 1) {
            android.util.Log.d("GPS_DEBUG", "⚠️ Pace: janela de tempo muito curta (${"%.1f".format(tempoJanelaSegundos)}s)")
            return "--:--"
        }

        // Velocidade média na janela (m/s)
        val velocidadeMetrosPorSegundo = distanciaJanelaMetros / tempoJanelaSegundos
        
        // Converter para segundos por quilômetro (pace)
        val segundosPorKm = 1000.0 / velocidadeMetrosPorSegundo
        
        android.util.Log.d("GPS_DEBUG", """
            📊 Cálculo Pace:
               Janela: ${pontos.size} pontos em ${"%.1f".format(tempoJanelaSegundos)}s
               Distância: ${"%.1f".format(distanciaJanelaMetros)}m
               Velocidade: ${"%.2f".format(velocidadeMetrosPorSegundo)} m/s
               Pace: ${formatarPace(segundosPorKm)}
        """.trimIndent())
        
        return formatarPace(segundosPorKm)
    }

    private fun verificarSeEstaParado(location: Location): Boolean {
        // Se não há localização anterior, não está parado
        val ultimaLoc = ultimaLocalizacaoSignificativa
        if (ultimaLoc == null) {
            ultimaLocalizacaoSignificativa = location
            contadorSemMovimento = 0
            return false
        }

        // Calcular distância desde última localização significativa
        val distancia = calcularDistancia(
            ultimaLoc.latitude, ultimaLoc.longitude,
            location.latitude, location.longitude
        )

        if (distancia < DISTANCIA_MINIMA_MOVIMENTO) {
            contadorSemMovimento++
        } else {
            contadorSemMovimento = 0
            ultimaLocalizacaoSignificativa = location
        }

        return contadorSemMovimento >= LIMITE_SEM_MOVIMENTO
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Salvamento
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    fun salvarCorrida() {
        val state = _uiState.value
        
        // Validações
        if (state.rota.isEmpty()) {
            _uiState.value = state.copy(
                salvamentoEstado = SalvamentoEstado.ERRO,
                erroSalvamento = """
                    Nenhum ponto GPS foi coletado durante a corrida.
                    
                    Possíveis causas:
                    • GPS do celular desligado
                    • Permissões de localização não concedidas
                    • Sinal GPS muito fraco (ambiente interno)
                    
                    Solução: Verifique as configurações e tente novamente em área aberta.
                """.trimIndent()
            )
            return
        }
        
        if (state.distanciaMetros < 50) {
            _uiState.value = state.copy(
                salvamentoEstado = SalvamentoEstado.ERRO,
                erroSalvamento = """
                    Distância muito curta: ${state.distanciaMetros.toInt()} metros.
                    
                    Percorra pelo menos 50 metros antes de salvar a corrida.
                    (Foram coletados ${state.rota.size} pontos GPS)
                """.trimIndent()
            )
            return
        }
        
        if (state.tempoTotalSegundos < 30) {
            _uiState.value = state.copy(
                salvamentoEstado = SalvamentoEstado.ERRO,
                erroSalvamento = """
                    Tempo muito curto: ${state.tempoTotalSegundos} segundos.
                    
                    Corra por pelo menos 30 segundos antes de salvar.
                """.trimIndent()
            )
            return
        }
        
        if (state.salvamentoEstado == SalvamentoEstado.SALVANDO) {
            android.util.Log.w("CorridaVM", "⚠️ Salvamento já em andamento")
            return
        }

        android.util.Log.d("CorridaVM", """
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            💾 INICIANDO SALVAMENTO
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            Distância: ${"%.2f".format(state.distanciaMetros / 1000)} km
            Tempo: ${state.tempoFormatado}
            Pace médio: ${state.paceMedia}
            Pontos GPS: ${state.rota.size}
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        """.trimIndent())

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
                        erroSalvamento = "ID do atleta não configurado. Configure em Ajustes."
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
                        android.util.Log.e("CorridaVM", "❌ Erro ao salvar GPX", e)
                        _uiState.value = _uiState.value.copy(
                            salvamentoEstado = SalvamentoEstado.ERRO,
                            erroSalvamento = "Erro ao salvar: ${e.message}"
                        )
                    }
                )
            } catch (e: Exception) {
                android.util.Log.e("CorridaVM", "❌ Erro inesperado no salvamento", e)
                _uiState.value = _uiState.value.copy(
                    salvamentoEstado = SalvamentoEstado.ERRO,
                    erroSalvamento = "Erro inesperado: ${e.message}"
                )
            }
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Upload
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    fun uploadParaIntervals() {
        val arquivo = arquivoGpxSalvo
        if (arquivo == null) {
            android.util.Log.w("CorridaVM", "⚠️ Nenhum arquivo para upload")
            return
        }

        _uiState.value = _uiState.value.copy(
            uploadEstado = UploadEstado.ENVIANDO
        )

        viewModelScope.launch {
            try {
                val apiKey = container.preferencesRepository.apiKey.first()
                val athleteId = container.preferencesRepository.athleteId.first()

                if (athleteId == null) {
                    _uiState.value = _uiState.value.copy(
                        uploadEstado = UploadEstado.ERRO,
                        erroSalvamento = "ID do atleta não configurado"
                    )
                    return@launch
                }

                val repo = workoutRepo ?: container.createWorkoutRepository(apiKey ?: "")
                workoutRepo = repo

                val result = repo.uploadParaIntervals(athleteId, arquivo)
                result.fold(
                    onSuccess = {
                        _uiState.value = _uiState.value.copy(
                            uploadEstado = UploadEstado.ENVIADO
                        )
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
                android.util.Log.e("CorridaVM", "❌ Erro inesperado no upload", e)
                _uiState.value = _uiState.value.copy(
                    uploadEstado = UploadEstado.ERRO,
                    erroSalvamento = "Erro inesperado: ${e.message}"
                )
            }
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Utilitários
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun calcularDistancia(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371000.0 // Raio da Terra em metros
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
        
        val minutos = (segundosPorKm / 60).toInt()
        val segundos = (segundosPorKm % 60).toInt()
        return "%d:%02d".format(minutos, segundos)
    }

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
