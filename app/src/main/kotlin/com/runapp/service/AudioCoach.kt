package com.runapp.service

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Audio Coach — usa Text-to-Speech para dar feedback de voz em português
 * durante a corrida.
 */
class AudioCoach(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var lastAnnouncementTime = 0L
    private val MIN_INTERVAL_MS = 8000L // mínimo 8s entre anúncios

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("pt", "BR"))
                isReady = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
    }

    /**
     * Fala uma mensagem imediatamente (interrompe a atual).
     */
    fun falarUrgente(mensagem: String) {
        tts?.speak(mensagem, TextToSpeech.QUEUE_FLUSH, null, "urgente")
        lastAnnouncementTime = System.currentTimeMillis()
    }

    /**
     * Adiciona à fila (não interrompe).
     */
    fun falar(mensagem: String, respeitarIntervalo: Boolean = true) {
        if (!isReady) return
        if (respeitarIntervalo) {
            val agora = System.currentTimeMillis()
            if (agora - lastAnnouncementTime < MIN_INTERVAL_MS) return
        }
        tts?.speak(mensagem, TextToSpeech.QUEUE_ADD, null, "msg_${System.currentTimeMillis()}")
        lastAnnouncementTime = System.currentTimeMillis()
    }

    // ---- Mensagens específicas ----

    fun anunciarInicioCorrida() {
        falarUrgente("Corrida iniciada. Boa sorte!")
    }

    fun anunciarPasso(nomePasso: String, paceAlvo: String, duracao: Int) {
        val paceTexto = formatarPaceParaFala(paceAlvo)
        if (duracao < 45) {
            // Tiro curto: frase seca e rápida — libera o canal de áudio antes do esforço começar
            val duracaoTexto = if (duracao >= 60) "${duracao / 60} minutos" else "$duracao segundos"
            falarUrgente("$nomePasso, $duracaoTexto. Alvo: $paceTexto!")
        } else {
            // Passo longo: anúncio completo com contexto
            val duracaoTexto = if (duracao >= 60) "${duracao / 60} minutos" else "$duracao segundos"
            falarUrgente("$nomePasso por $duracaoTexto. Ritmo alvo: $paceTexto por quilômetro.")
        }
    }

    fun anunciarKm(distanciaKm: Double, paceMedia: String) {
        val km = "%.1f".format(distanciaKm)
        val paceTexto = formatarPaceParaFala(paceMedia)
        falar("$km quilômetros. Ritmo médio: $paceTexto.", respeitarIntervalo = false)
    }

    fun anunciarPaceFeedback(paceAtual: String, paceAlvoMin: String, paceAlvoMax: String): Boolean {
        val atualSecs = paceParaSegundos(paceAtual)
        val minSecs = paceParaSegundos(paceAlvoMin)
        val maxSecs = paceParaSegundos(paceAlvoMax)

        android.util.Log.d("AudioCoach", "=== FEEDBACK DE PACE ===")
        android.util.Log.d("AudioCoach", "Pace atual: $paceAtual ($atualSecs s/km)")
        android.util.Log.d("AudioCoach", "Alvo min: $paceAlvoMin ($minSecs s/km)")
        android.util.Log.d("AudioCoach", "Alvo max: $paceAlvoMax ($maxSecs s/km)")

        if (paceAlvoMin == "--:--") {
            android.util.Log.d("AudioCoach", "❌ Sem pace alvo definido")
            return false
        }

        if (minSecs <= 0) {
            android.util.Log.d("AudioCoach", "❌ Pace alvo inválido")
            return false
        }

        val mensagem = when {
            paceAtual == "--:--" || atualSecs <= 0 -> {
                android.util.Log.d("AudioCoach", "⚠️ PARADO OU MUITO DEVAGAR (pace --:--)")
                "Você está parado ou muito devagar. Acelere para ${formatarPaceParaFala(paceAlvoMax)}."
            }
            atualSecs < minSecs - 10 -> {
                android.util.Log.d("AudioCoach", "⚠️ MUITO RÁPIDO!")
                "Você está muito rápido. Reduza o ritmo para ${formatarPaceParaFala(paceAlvoMin)}."
            }
            atualSecs > maxSecs + 10 -> {
                android.util.Log.d("AudioCoach", "⚠️ MUITO DEVAGAR!")
                "Você está devagar demais. Acelere para ${formatarPaceParaFala(paceAlvoMax)}."
            }
            else -> {
                android.util.Log.d("AudioCoach", "✅ Dentro do alvo")
                return false  // Dentro do alvo, não fala e retorna false
            }
        }

        android.util.Log.d("AudioCoach", "🔊 Vai falar: $mensagem")
        falar(mensagem, respeitarIntervalo = false)
        return true
    }

    fun anunciarUltimosSegundos(segundos: Int, duracaoPasso: Int) {
        // Hierarquia de countdown baseada na duração do passo:
        // > 60s  → avisa em 30s, 10s, 5s, 3s, 2s, 1s
        // 30–60s → avisa em 10s, 5s, 3s, 2s, 1s
        // < 30s  → só 3s, 2s, 1s (corredor está em esforço máximo, silêncio é respeito)
        val pontosAviso = when {
            duracaoPasso > 60  -> setOf(30, 10, 5, 3, 2, 1)
            duracaoPasso >= 30 -> setOf(10, 5, 3, 2, 1)
            else               -> setOf(3, 2, 1)
        }
        if (segundos in pontosAviso) {
            falar("$segundos", respeitarIntervalo = false)
        }
    }

    fun anunciarFimCorrida(distanciaKm: Double, tempoTotal: String, paceMedia: String) {
        val km = "%.2f".format(distanciaKm)
        falarUrgente(
            "Corrida finalizada! Parabéns! " +
            "Você correu $km quilômetros em $tempoTotal " +
            "com ritmo médio de $paceMedia por quilômetro."
        )
    }

    fun anunciarDescanso() {
        falarUrgente("Intervalo de descanso. Respire e recupere o ritmo.")
    }

    // ---- Helpers ----

    /**
     * Converte pace de "5:30" para "cinco minutos e trinta segundos"
     */
    private fun formatarPaceParaFala(pace: String): String {
        if (pace == "--:--") return "sem ritmo definido"
        val partes = pace.split(":")
        if (partes.size != 2) return pace
        
        val minutos = partes[0].toIntOrNull() ?: return pace
        val segundos = partes[1].toIntOrNull() ?: return pace
        
        return if (segundos == 0) {
            "$minutos minutos"
        } else {
            "$minutos minutos e $segundos segundos"
        }
    }

    private fun paceParaSegundos(pace: String): Int {
        if (pace == "--:--") return 0
        val partes = pace.split(":")
        if (partes.size != 2) return 0
        return (partes[0].toIntOrNull() ?: 0) * 60 + (partes[1].toIntOrNull() ?: 0)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
