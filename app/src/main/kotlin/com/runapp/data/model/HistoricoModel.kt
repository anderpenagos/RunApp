package com.runapp.data.model

/**
 * Representa uma corrida salva localmente no dispositivo.
 *
 * Os campos são lidos do arquivo de metadados (.json) que é gravado
 * junto com o arquivo GPX em cada corrida concluída.
 *
 * @param nome              nome da corrida (ex: "Corrida RunApp - 14/02 10:30")
 * @param data              data/hora de início no formato "yyyy-MM-dd'T'HH:mm:ss"
 * @param distanciaKm       distância total em quilômetros
 * @param tempoFormatado    tempo no formato "HH:mm:ss" ou "mm:ss"
 * @param paceMedia         pace médio no formato "M:SS"
 * @param pontosGps         número de pontos GPS gravados na rota
 * @param arquivoGpx        nome do arquivo .gpx (sem caminho — relativo à pasta gpx/)
 * @param enviadoIntervals  se já foi enviado para o Intervals.icu
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
    val splitsParciais: List<SplitParcial> = emptyList(),
    val zonasFronteira: List<ZonaFronteira> = emptyList(),
    val voltasAnalise: List<VoltaAnalise> = emptyList(),

    // ── Auto-Learner — biomecânica ──────────────────────────────────────────
    // stepLengthBaseline: passada EMA aprendida pelo Auto-Learner AO INÍCIO da corrida.
    //   Representa o "baseline" histórico do atleta — como ele corre normalmente.
    // stepLengthTreino:   passada calculada neste treino (distância / passos totais).
    //   Diferença > 5% indica fadiga mecânica (queda) ou evolução técnica (subida).
    val stepLengthBaseline: Double = 0.0,
    val stepLengthTreino: Double = 0.0,

    // ── Treino planeado associado ───────────────────────────────────────────
    // Preenchidos quando a corrida foi feita com um WorkoutEvent ativo.
    // treinoPassosJson: JSON de List<PassoResumo> — permite ao Coach comparar
    //   o plano (pace alvo, duração) com a execução real.
    val treinoNome: String? = null,
    val treinoPassosJson: String? = null,

    // ── Feedback do Coach ───────────────────────────────────────────────────
    // Gerado uma única vez pelo Gemini ao abrir o detalhe da corrida.
    // Persistido aqui para não gastar tokens nas próximas visualizações.
    val feedbackCoach: String? = null
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

/**
 * Pace de um quilômetro fechado da corrida.
 * Inclui GAP (Grade-Adjusted Pace) quando há dados de altitude confiáveis.
 *
 * @param gapSegKm       pace equivalente em terreno plano em seg/km. Null se sem altitude.
 * @param gapFormatado   pace formatado "M:SS". Null se sem altitude.
 * @param gradienteMedio inclinação média do km em % (positivo = subida, negativo = descida).
 */
data class SplitParcial(
    val km: Int,
    val paceSegKm: Double,
    val paceFormatado: String,
    val gapSegKm: Double? = null,
    val gapFormatado: String? = null,
    val gradienteMedio: Double? = null
)

/**
 * Representa uma volta/lap detectado automaticamente na análise do treino.
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

/**
 * Versão compacta de PassoExecucao para persistência no JSON da corrida.
 * Contém apenas os campos que o Coach precisa para comparar plano vs execução.
 */
data class PassoResumo(
    val nome: String,
    val duracaoSegundos: Int,
    val paceAlvoMin: String,   // ex: "5:00"
    val paceAlvoMax: String    // ex: "5:30"
)
