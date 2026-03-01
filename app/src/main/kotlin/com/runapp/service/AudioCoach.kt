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
     * Adiciona à fila TTS sem interromper o que está tocando (QUEUE_ADD).
     *
     * ⚠️ DECISÃO ARQUITETURAL — NÃO mudar para QUEUE_FLUSH sem motivo explícito:
     * Quando o km fecha exatamente numa descida técnica, dois anúncios chegam
     * quase simultaneamente: o aviso de descida + o split do km. Com QUEUE_ADD,
     * o TTS enfileira e fala um após o outro. Com QUEUE_FLUSH, o segundo cortaria
     * o primeiro no meio da frase — experiência terrível em fone de ouvido.
     * Apenas `falarUrgente()` usa QUEUE_FLUSH, para mudanças de passo estruturais
     * que precisam interromper qualquer coisa (ex: "Intervalo iniciado!").
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

    /**
     * Anúncio de km enriquecido com GAP quando houver relevância.
     *
     * Só menciona o GAP se a diferença for > 15s/km (perceptível e significativa).
     * Abaixo disso, o percurso era essencialmente plano e o GAP não acrescenta informação.
     *
     * O tom e a mensagem variam pela direção do gradiente E pela eficiência:
     *
     *  ┌──────────────────────┬───────────────────────────┬────────────────────────────────┐
     *  │ Cenário              │ Critério                  │ Mensagem (modo normal)         │
     *  ├──────────────────────┼───────────────────────────┼────────────────────────────────┤
     *  │ Plano (dif. < 15s)   │ GAP ≈ Pace               │ Anúncio simples                │
     *  │ Subida eficiente     │ GAP < paceMediaGeral      │ "Excelente subida!"            │
     *  │ Subida regular       │ GAP ≥ paceMediaGeral      │ "Continue firme"               │
     *  │ Descida suave        │ 0% a -15%, dif. > 15s    │ "Controle o impacto"           │
     *  │ Descida técnica      │ < -15%                    │ "Cuidado com articulações"     │
     *  └──────────────────────┴───────────────────────────┴────────────────────────────────┘
     *
     *  Modo Telemetria Reduzida (telemetriaReduzida = true):
     *  O GAP comparativo só aparece para subidas e descidas técnicas.
     *  Plano e descida suave recebem apenas o split de pace — sem análise de esforço ajustado.
     *  O aviso de descida técnica nunca é suprimido (é de segurança, não de telemetria).
     *
     * @param gradienteMedio      inclinação média ponderada do km (fração, ex: 0.05 = 5%)
     * @param paceMediaGeralSegKm pace médio da corrida inteira em s/km (baseline de eficiência)
     * @param telemetriaReduzida  quando true, omite GAP em terrenos planos/descidas suaves
     */
    fun anunciarKmComGap(distanciaKm: Double, paceMedia: String,
                         paceRealSegKm: Double, gapMedioSegKm: Double,
                         gradienteMedio: Double = 0.0,
                         paceMediaGeralSegKm: Double = 0.0,
                         telemetriaReduzida: Boolean = false) {
        val km        = distanciaKm.toInt().toString()
        val paceTexto = formatarPaceParaFala(paceMedia)

        val ehSubida         = gradienteMedio >   0.02   // > 2% líquido = subida real
        val ehDescidaTecnica = gradienteMedio <  -0.15   // < -15% = paradoxo de Minetti
        val diferencaGap     = gapMedioSegKm - paceRealSegKm  // positivo = GAP mais lento = subida

        // Eficiência: o corredor manteve a intensidade se o esforço ajustado (GAP)
        // ficou abaixo do pace médio geral da corrida. Ou seja: subiu sem desacelerar
        // em termos de esforço fisiológico.
        val gapTexto              = formatarPaceParaFala(formatarPaceDeSegundos(gapMedioSegKm))
        val subiuEficientemente   = ehSubida &&
                gapMedioSegKm > 0 &&
                paceMediaGeralSegKm > 0 &&
                gapMedioSegKm < paceMediaGeralSegKm  // esforço ajustado abaixo da média — subida excelente

        val mensagem = when {
            // Descida técnica: sempre anuncia — é aviso de segurança, não telemetria.
            // Nunca suprimido pelo modo de telemetria reduzida.
            ehDescidaTecnica -> {
                "Quilômetro $km concluído. Ritmo real: $paceTexto. Cuidado com o impacto nas articulações."
            }

            // Subida eficiente: GAP ficou abaixo do pace médio geral → corredor manteve intensidade
            subiuEficientemente && diferencaGap > 15.0 -> {
                "Quilômetro $km concluído. Ritmo real: $paceTexto. Esforço ajustado: $gapTexto. Excelente subida, você manteve a intensidade!"
            }

            // Subida regular com GAP relevante → tom motivacional
            ehSubida && gapMedioSegKm > 0 && diferencaGap > 15.0 -> {
                "Quilômetro $km concluído. Ritmo real: $paceTexto. Esforço equivale a $gapTexto no plano. Continue firme!"
            }

            // Descida suave em modo de telemetria reduzida → omite GAP, apenas pace
            !ehSubida && telemetriaReduzida -> {
                "Quilômetro $km concluído. Ritmo médio: $paceTexto."
            }

            // Descida suave com GAP relevante → tom neutro/cauteloso (modo completo)
            !ehSubida && gapMedioSegKm > 0 && kotlin.math.abs(diferencaGap) > 15.0 -> {
                "Quilômetro $km concluído. Ritmo real: $paceTexto. Ritmo ajustado: $gapTexto. Controle o impacto."
            }

            // Terreno plano ou diferença irrelevante → anúncio simples
            else -> "Quilômetro $km concluído. Ritmo médio: $paceTexto."
        }

        falar(mensagem, respeitarIntervalo = false)
    }

    /**
     * Modo Montanha — subida íngreme detectada (> 4% por ≥ 100m).
     * Ativa motivação contextual com o GAP para mostrar que o esforço é real.
     */
    fun anunciarModoMontanha(paceAtualStr: String, gapAtualSegKm: Double) {
        if (!isReady) return
        if (paceAtualStr == "--:--" || gapAtualSegKm <= 0) return

        val agora = System.currentTimeMillis()
        if (agora - lastAnnouncementTime < MIN_INTERVAL_MS * 2) return  // 16s entre alertas de subida

        val paceTexto = formatarPaceParaFala(paceAtualStr)
        val gapTexto  = formatarPaceParaFala(formatarPaceDeSegundos(gapAtualSegKm))

        falar(
            "Subida detectada. Ritmo atual: $paceTexto. Mantenha o esforço, equivale a $gapTexto no plano. Vai!",
            respeitarIntervalo = false
        )
    }

    /**
     * Descida técnica — grade < -15%.
     * Minetti demonstra que o custo metabólico volta a subir nessa faixa (frenagem ativa).
     * NÃO compara GAP aqui — seria contraintuitivo. Foco no aviso de proteção articular.
     */
    fun anunciarDescidaTecnica() {
        if (!isReady) return
        val agora = System.currentTimeMillis()
        if (agora - lastAnnouncementTime < MIN_INTERVAL_MS * 3) return  // máximo 1 aviso a cada 24s

        falar(
            "Descida íngreme. Reduza a passada e proteja os joelhos.",
            respeitarIntervalo = false
        )
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

    /**
     * Converte segundos/km (Double) para string "M:SS" para usar em fala.
     */
    private fun formatarPaceDeSegundos(segKm: Double): String {
        if (segKm <= 0 || segKm.isNaN() || segKm.isInfinite()) return "--:--"
        val min = (segKm / 60).toInt()
        val seg = (segKm % 60).toInt()
        return "%d:%02d".format(min, seg)
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
