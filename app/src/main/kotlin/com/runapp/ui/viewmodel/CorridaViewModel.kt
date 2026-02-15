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

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Lifecycle
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    init {
        android.util.Log.d("CorridaVM", "✅ ViewModel inicializado")
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        android.util.Log.d("CorridaVM", "🧹 ViewModel limpo")
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Carregar Treino
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    fun carregarTreino(eventId: Long) {
        viewModelScope.launch {
            try {
                val apiKey = container.preferencesRepository.apiKey.first()
                val repo = container.createWorkoutRepository(apiKey ?: "")
                workoutRepo = repo
                
                val evento = repo.buscarEvento(eventId)
                val passosProcessados = evento.workoutDoc?.steps?.flatMap { processarPasso(it) } ?: emptyList()
                
                _uiState.value = _uiState.value.copy(
                    treino = evento,
                    passos = passosProcessados,
                    passoAtual = passosProcessados.firstOrNull()
                )
                
                android.util.Log.d("CorridaVM", "✅ Treino carregado: ${evento.name} (${passosProcessados.size} passos)")
            } catch (e: Exception) {
                android.util.Log.e("CorridaVM", "❌ Erro ao carregar treino", e)
                _uiState.value = _uiState.value.copy(
                    erro = "Erro ao carregar treino: ${e.message}"
                )
            }
        }
    }

    private fun processarPasso(step: com.runapp.data.model.WorkoutStep): List<PassoExecucao> {
        return when (step.type) {
            "IntervalsT" -> {
                val subPassos = step.steps?.flatMap { processarPasso(it) } ?: emptyList()
                List(step.reps ?: 1) { subPassos }.flatten()
            }
            else -> {
                val isDescanso = step.type == "Rest"
                val zona = step.pace?.value?.toInt() ?: 3
                val (min, max) = calcularPaceLimites(step)
                
                listOf(
                    PassoExecucao(
                        nome = step.text ?: step.type,
                        duracao = step.duration,
                        paceAlvoMin = min,
                        paceAlvoMax = max,
                        zona = zona,
                        instrucao = step.text ?: "",
                        isDescanso = isDescanso
                    )
                )
            }
        }
    }

    private fun calcularPaceLimites(step: com.runapp.data.model.WorkoutStep): Pair<String, String> {
        val paceTarget = step.pace ?: return "--:--" to "--:--"
        
        return when (paceTarget.type) {
            "zone" -> {
                val zona = paceTarget.value.toInt()
                val limites = mapOf(
                    1 to (360.0 to 420.0),  // Z1: 6:00-7:00
                    2 to (300.0 to 360.0),  // Z2: 5:00-6:00
                    3 to (270.0 to 300.0),  // Z3: 4:30-5:00
                    4 to (240.0 to 270.0),  // Z4: 4:00-4:30
                    5 to (180.0 to 240.0)   // Z5: 3:00-4:00
                )
                val (min, max) = limites[zona] ?: (300.0 to 360.0)
                formatarPace(min) to formatarPace(max)
            }
            "pace" -> {
                val segPorMetro = paceTarget.value
                val segPorMetro2 = paceTarget.value2 ?: segPorMetro
                formatarPace(segPorMetro * 1000) to formatarPace(segPorMetro2 * 1000)
            }
            else -> "--:--" to "--:--"
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Controle de Corrida
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    fun iniciarCorrida() {
        android.util.Log.d("CorridaVM", "▶️ Iniciando corrida")
        
        timestampInicio = System.currentTimeMillis()
        timestampPassoAtual = timestampInicio
        tempoPausadoTotal = 0
        
        _uiState.value = _uiState.value.copy(
            fase = FaseCorrida.CORRENDO
        )
        
        iniciarTimer()
        iniciarGPS()
    }

    fun pausar() {
        android.util.Log.d("CorridaVM", "⏸️ Corrida pausada")
        
        timestampPausaInicio = System.currentTimeMillis()
        
        _uiState.value = _uiState.value.copy(
            fase = FaseCorrida.PAUSADO,
            autoPausado = false
        )
        
        timerJob?.cancel()
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
        
        _uiState.value = _uiState.value.copy(
            fase = FaseCorrida.FINALIZADO
        )
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Timer
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun iniciarTimer() {
        timerJob?.cancel()
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
        
        // Atualizar progresso do passo
        val progresso = if (passoAtual != null && passoAtual.duracao > 0) {
            (tempoPasso.toFloat() / passoAtual.duracao).coerceIn(0f, 1f)
        } else 0f
        
        val tempoRestante = if (passoAtual != null) {
            (passoAtual.duracao - tempoPasso).coerceAtLeast(0)
        } else 0
        
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
            
            _uiState.value = state.copy(
                passoAtualIndex = proximoIndex,
                passoAtual = state.passos[proximoIndex],
                tempoPassoAtualSegundos = 0,
                progressoPasso = 0f
            )
            
            android.util.Log.d("CorridaVM", "➡️ Avançou para passo ${proximoIndex + 1}/${state.passos.size}")
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
        if (state.fase != FaseCorrida.CORRENDO) return

        // Log detalhado a cada 10 pontos
        if (state.rota.size % 10 == 0) {
            android.util.Log.d("GPS_DEBUG", """
                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                📍 Ponto GPS #${state.rota.size}
                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                Lat: ${location.latitude}
                Lng: ${location.longitude}
                Accuracy: ${location.accuracy}m
                Speed: ${if (location.hasSpeed()) "${location.speed} m/s" else "N/A"}
                Time: ${Date(location.time)}
                Distância total: ${"%.2f".format(state.distanciaMetros / 1000)} km
                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            """.trimIndent())
        }

        // Filtrar pontos com accuracy ruim
        if (location.accuracy > 30) {
            android.util.Log.w("GPS_DEBUG", "⚠️ Ponto descartado - accuracy muito baixa: ${location.accuracy}m")
            return
        }

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
        }

        // Auto-pause: detectar se está parado
        val estaParado = verificarSeEstaParado(location)
        val deveAutoPausar = estaParado && !state.autoPausado

        if (deveAutoPausar) {
            android.util.Log.d("CorridaVM", "⏸️ Auto-pause ativado (sem movimento)")
            pausar()
            _uiState.value = _uiState.value.copy(autoPausado = true)
            return
        }

        // Calcular pace atual
        val paceAtual = if (location.hasSpeed() && location.speed > 0.5) {
            val segPorKm = 1000.0 / location.speed
            formatarPace(segPorKm)
        } else "--:--"

        _uiState.value = state.copy(
            rota = novaRota,
            posicaoAtual = novoPonto,
            distanciaMetros = novaDistancia,
            paceAtual = paceAtual
        )
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

                val result = repo.uploadAtividade(athleteId, arquivo)
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
