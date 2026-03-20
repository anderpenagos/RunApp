package com.runapp.util

/**
 * Filtro de Kalman para suavizar coordenadas GPS.
 *
 * O GPS do celular "pula" — mesmo parado ele registra micro-deslocamentos.
 * Este filtro prevê a próxima posição com base na trajetória recente e
 * descarta variações que seriam fisicamente impossíveis para um corredor.
 *
 * Funciona aplicando o algoritmo 1D separadamente em lat e lng.
 *
 * Uso:
 *   val kalman = KalmanFilter()
 *   val (latSmooth, lngSmooth) = kalman.process(lat, lng, accuracy, System.currentTimeMillis())
 */
class KalmanFilter(
    /**
     * Ruído do processo em m/s: o quão rápido a incerteza cresce entre medições.
     * 3f é um bom padrão para pedestres/corredores.
     */
    private val processNoise: Float = 3f
) {
    private var variance = -1f   // variância acumulada (negativa = não inicializado)
    private var lat = 0.0
    private var lng = 0.0
    private var timeMs = 0L

    val isInitialized get() = variance >= 0

    /**
     * Alimenta o filtro com uma nova leitura GPS e retorna as coordenadas suavizadas.
     *
     * @param newLat      latitude bruta do sensor
     * @param newLng      longitude bruta do sensor
     * @param accuracy    precisão em metros (location.accuracy)
     * @param timestampMs timestamp atual em milissegundos
     * @return Par (lat, lng) suavizado pelo filtro
     */
    fun process(
        newLat: Double,
        newLng: Double,
        accuracy: Float,
        timestampMs: Long
    ): Pair<Double, Double> {
        if (!isInitialized) {
            lat = newLat
            lng = newLng
            variance = accuracy * accuracy
            timeMs = timestampMs
            return Pair(lat, lng)
        }

        val dtSeconds = ((timestampMs - timeMs) / 1000.0).coerceAtLeast(0.0)
        timeMs = timestampMs

        // Predição: incerteza cresce com o tempo (o corredor pode ter se movido)
        variance += dtSeconds.toFloat() * processNoise * processNoise

        // Ganho de Kalman: quanto confiamos na nova leitura vs. nossa estimativa atual
        val measurementNoise = accuracy * accuracy
        val gain = variance / (variance + measurementNoise)

        // Atualização: mistura previsão com medição ponderada pelo ganho
        lat += gain * (newLat - lat)
        lng += gain * (newLng - lng)
        variance *= (1f - gain)

        return Pair(lat, lng)
    }

    /** Reseta o filtro. Chame ao pausar e retomar a corrida. */
    fun reset() {
        variance = -1f
    }
}
