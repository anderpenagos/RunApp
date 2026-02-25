package com.runapp.util

import com.runapp.data.model.LatLngPonto
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Implementação do algoritmo Ramer-Douglas-Peucker para simplificação de rotas GPS.
 *
 * PROBLEMA RESOLVIDO:
 * Uma corrida de 40min gera ~2400 pontos GPS. Tentar renderizar todos eles no
 * Google Maps na Main Thread causa ANR (App Not Responding) em ~5 segundos —
 * o mesmo comportamento que o usuário vê ao reabrir o app após uma corrida longa.
 *
 * COMO FUNCIONA:
 * O algoritmo remove pontos que estão "próximos o suficiente" de uma linha reta
 * entre dois pontos vizinhos. Com tolerância de 5 metros, uma corrida de 40min
 * passa de ~2400 para ~200-400 pontos (redução de 85-90%), preservando o formato
 * visual da rota com fidelidade perfeita.
 *
 * USOS:
 * - Map display: simplify(rota, toleranceMeters = 5.0) → renderização rápida
 * - Export GPX: usar rota original completa (sem simplificação) para precisão
 * - Heatmap: toleranceMeters = 2.0 para mais detalhe visual
 *
 * COMPLEXIDADE: O(n log n) amortizado para dados GPS típicos.
 */
object DouglasPeucker {

    /**
     * Simplifica uma rota GPS removendo pontos redundantes.
     *
     * @param points Lista completa de pontos GPS da corrida
     * @param toleranceMeters Distância perpendicular máxima tolerada (metros).
     *   5.0m = boa fidelidade visual em corridas urbanas
     *   2.0m = alta fidelidade para exibição detalhada
     *   10.0m = performance máxima para listas de histórico
     * @return Lista simplificada, sempre incluindo o primeiro e último ponto
     */
    fun simplify(points: List<LatLngPonto>, toleranceMeters: Double = 5.0): List<LatLngPonto> {
        if (points.size <= 2) return points
        return rdp(points, 0, points.size - 1, toleranceMeters, BooleanArray(points.size) { false })
            .let { keep ->
                keep[0] = true
                keep[points.size - 1] = true
                points.filterIndexed { i, _ -> keep[i] }
            }
    }

    /**
     * Versão iterativa usando índices para evitar overhead de sublistas e
     * risco de StackOverflow em rotas muito longas (> 10.000 pontos).
     */
    private fun rdp(
        points: List<LatLngPonto>,
        start: Int,
        end: Int,
        epsilon: Double,
        keep: BooleanArray
    ): BooleanArray {
        // Stack explícito: evita recursão profunda em corridas de maratona
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(start to end)

        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast()
            if (e - s <= 1) continue

            var maxDist = 0.0
            var maxIndex = s

            val first = points[s]
            val last  = points[e]

            for (i in s + 1 until e) {
                val d = perpendicularDistanceMeters(points[i], first, last)
                if (d > maxDist) {
                    maxDist = d
                    maxIndex = i
                }
            }

            if (maxDist > epsilon) {
                keep[maxIndex] = true
                stack.addLast(s to maxIndex)
                stack.addLast(maxIndex to e)
            }
        }

        return keep
    }

    /**
     * Distância perpendicular de um ponto a um segmento de linha, em metros.
     *
     * Usa aproximação de Terra plana (flat-earth), válida para distâncias < 50km.
     * Para corridas (tipicamente < 50km), o erro é irrelevante (~0.01%).
     *
     * Fórmula: projeta o ponto na linha e calcula a distância à projeção.
     */
    private fun perpendicularDistanceMeters(
        point: LatLngPonto,
        lineStart: LatLngPonto,
        lineEnd: LatLngPonto
    ): Double {
        val metersPerDegLat = 111_320.0
        val metersPerDegLng = 111_320.0 * cos(Math.toRadians(point.lat))

        // Converter coordenadas para metros (ponto de referência = lineStart)
        val px = (point.lng    - lineStart.lng) * metersPerDegLng
        val py = (point.lat    - lineStart.lat) * metersPerDegLat
        val dx = (lineEnd.lng  - lineStart.lng) * metersPerDegLng
        val dy = (lineEnd.lat  - lineStart.lat) * metersPerDegLat

        val lineLen = sqrt(dx * dx + dy * dy)

        // Segmento degenerado (dois pontos iguais): retorna distância direta
        if (lineLen < 0.001) return sqrt(px * px + py * py)

        // Parâmetro t da projeção ortogonal (clampado ao segmento)
        val t = ((px * dx + py * dy) / (lineLen * lineLen)).coerceIn(0.0, 1.0)

        // Distância do ponto à projeção
        val projX = px - t * dx
        val projY = py - t * dy
        return sqrt(projX * projX + projY * projY)
    }
}
