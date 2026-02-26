package com.runapp.data.model

/**
 * Representa uma corrida salva localmente no dispositivo.
 *
 * Os campos são lidos do arquivo de metadados (.json) que é gravado
 * junto com o arquivo GPX em cada corrida concluída.
 *
 * @param nome         nome da corrida (ex: "Corrida RunApp - 14/02 10:30")
 * @param data         data/hora de início no formato "yyyy-MM-dd'T'HH:mm:ss"
 * @param distanciaKm  distância total em quilômetros
 * @param tempoFormatado tempo no formato "HH:mm:ss" ou "mm:ss"
 * @param paceMedia    pace médio no formato "M:SS"
 * @param pontosGps    número de pontos GPS gravados na rota
 * @param arquivoGpx   nome do arquivo .gpx (sem caminho — relativo à pasta gpx/)
 * @param enviadoIntervals se já foi enviado para o Intervals.icu
 */
data class CorridaHistorico(
    val nome: String,
    val data: String,
    val distanciaKm: Double,
    val tempoFormatado: String,
    val paceMedia: String,
    val pontosGps: Int,
    val arquivoGpx: String,
    val enviadoIntervals: Boolean = false,
    // Métricas avançadas para o dashboard
    val cadenciaMedia: Int = 0,             // SPM médio da corrida
    val ganhoElevacaoM: Int = 0,            // D+ total em metros
    val splitsParciais: List<SplitParcial> = emptyList(), // pace por km completo
    // Fronteiras de pace do perfil do atleta no Intervals.icu, capturadas no momento do save.
    // Usadas pelo gráfico de zonas de ritmo para mostrar dados reais em vez de heurísticas.
    val zonasFronteira: List<ZonaFronteira> = emptyList(),
    // Voltas/laps detectados automaticamente para "Análise do Treino" na DetalheAtividadeScreen.
    // Vazio  = corrida uniforme (sem intervalos claros) → o card usará splitsParciais por km.
    // Preenchido = intervalos detectados → mostra barra por volta com destaque rápido/lento.
    val voltasAnalise: List<VoltaAnalise> = emptyList()
)

/**
 * Fronteira de uma zona de pace do perfil do atleta no Intervals.icu.
 * Paces em seg/km. [paceMaxSegKm] null significa "sem teto" (zona mais lenta).
 */
data class ZonaFronteira(
    val nome: String,
    val cor: String = "",
    val paceMinSegKm: Double,   // pace mais RÁPIDO da zona (seg/km) — número menor
    val paceMaxSegKm: Double?   // pace mais LENTO da zona (seg/km) — null = sem teto
)

/** Pace de um quilômetro fechado da corrida */
data class SplitParcial(
    val km: Int,            // qual km (1, 2, 3...)
    val paceSegKm: Double,  // pace em seg/km
    val paceFormatado: String // "5:30"
)

/**
 * Representa uma volta/lap detectado automaticamente na análise do treino.
 *
 * Gerado por [WorkoutRepository.calcularVoltasAnalise] a partir das variações
 * de pace da rota GPS. Laps de corrida uniforme não são detectados (lista vazia).
 *
 * @param numero        número sequencial (1, 2, 3…)
 * @param distanciaKm   distância percorrida nesta volta em km
 * @param tempoSegundos duração em segundos
 * @param paceSegKm     pace médio em seg/km
 * @param paceFormatado pace formatado "M:SS"
 * @param isDescanso    true = recuperação (pace acima do limiar rápido/lento)
 */
data class VoltaAnalise(
    val numero: Int,
    val distanciaKm: Double,
    val tempoSegundos: Long,
    val paceSegKm: Double,
    val paceFormatado: String,
    val isDescanso: Boolean = false
)
