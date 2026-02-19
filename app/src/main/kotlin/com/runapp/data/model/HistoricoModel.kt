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
    val splitsParciais: List<SplitParcial> = emptyList() // pace por km completo
)

/** Pace de um quilômetro fechado da corrida */
data class SplitParcial(
    val km: Int,            // qual km (1, 2, 3...)
    val paceSegKm: Double,  // pace em seg/km
    val paceFormatado: String // "5:30"
)
