package com.runapp.data.repository

import android.content.Context
import android.os.Environment
import android.util.Log
import com.google.gson.Gson
import com.runapp.data.model.CorridaHistorico
import com.runapp.data.model.LatLngPonto
import com.runapp.data.model.PaceZone
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Gerencia backup e restore do histórico de corridas.
 *
 * Backup: copia JSON + GPX de cada corrida para Downloads/RunApp/backup/
 * Restore: lê GPX (dados brutos) + JSON (metadados do treino) e
 *          recalcula tudo via WorkoutRepository.salvarAtividade() para
 *          aplicar as implementações mais recentes do código.
 */
class BackupRepository(
    private val context: Context,
    private val workoutRepository: WorkoutRepository
) {

    private val TAG = "BackupRepository"
    private val gson = Gson()

    private fun pastaOrigem() = File(context.getExternalFilesDir(null), "gpx")

    private fun pastaBackup(): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(downloads, "RunApp/backup").also { it.mkdirs() }
    }

    // ── EXPORTAR ─────────────────────────────────────────────────────────────

    /**
     * Copia todos os arquivos JSON e GPX para Downloads/RunApp/backup/.
     * Retorna o número de corridas exportadas.
     */
    suspend fun exportarTudo(): Result<Int> = runCatching {
        val origem = pastaOrigem()
        val destino = pastaBackup()
        var count = 0

        origem.listFiles()?.forEach { arquivo ->
            if (arquivo.extension == "gpx" || arquivo.extension == "json") {
                arquivo.copyTo(File(destino, arquivo.name), overwrite = true)
                if (arquivo.extension == "gpx") count++
            }
        }

        Log.d(TAG, "✅ Exportadas $count corridas para ${destino.absolutePath}")
        count
    }

    // ── IMPORTAR VIA SAF (Storage Access Framework) ──────────────────────────

    /**
     * Importa corridas de uma pasta selecionada pelo usuário via file picker.
     *
     * Usa DocumentFile (SAF) em vez de File direto — funciona no Android 10+
     * sem precisar de permissão READ_EXTERNAL_STORAGE, inclusive para pastas
     * em Downloads de uma instalação anterior do app.
     */
    suspend fun importarDePasta(
        pastaUri: android.net.Uri,
        athleteId: String,
        paceZones: List<PaceZone> = emptyList()
    ): Result<Pair<Int, Int>> = runCatching {
        val pasta = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, pastaUri)
            ?: return@runCatching Pair(0, 0)

        var importadas = 0
        var erros = 0

        val gpxFiles = pasta.listFiles().filter {
            it.isFile && it.name?.endsWith(".gpx", ignoreCase = true) == true
        }

        for (gpxDoc in gpxFiles) {
            runCatching {
                val nomeBase = gpxDoc.name?.removeSuffix(".gpx") ?: ""

                // Lê metadados do JSON correspondente (se existir na mesma pasta)
                val meta = pasta.listFiles()
                    .firstOrNull { it.name == "$nomeBase.json" }
                    ?.let { jsonDoc ->
                        runCatching {
                            val json = context.contentResolver
                                .openInputStream(jsonDoc.uri)?.bufferedReader()?.readText()
                            if (json != null) gson.fromJson(json, CorridaHistorico::class.java)
                            else null
                        }.getOrNull()
                    }

                // Reconstrói rota a partir do GPX via ContentResolver
                val inputStream = context.contentResolver.openInputStream(gpxDoc.uri)
                    ?: run { erros++; return@runCatching }
                val rota = parsearGpxStream(inputStream)
                if (rota.isEmpty()) {
                    Log.w(TAG, "⚠️ GPX sem pontos: ${gpxDoc.name}")
                    erros++
                    return@runCatching
                }

                val distancia = rota.zipWithNext().sumOf { (a, b) ->
                    haversine(a.lat, a.lng, b.lat, b.lng)
                }
                val tempoSegundos = if (rota.size >= 2)
                    (rota.last().tempo - rota.first().tempo) / 1000L else 0L
                val paceSegKm = if (distancia > 0)
                    (tempoSegundos.toDouble() / distancia * 1000) else 0.0
                val paceMedia = if (paceSegKm in 60.0..1200.0)
                    "%d:%02d".format((paceSegKm / 60).toInt(), (paceSegKm % 60).toInt())
                else "--:--"

                workoutRepository.salvarAtividade(
                    context            = context,
                    athleteId          = athleteId,
                    nomeAtividade      = meta?.nome ?: nomeBase,
                    distanciaMetros    = distancia,
                    tempoSegundos      = tempoSegundos,
                    paceMedia          = paceMedia,
                    rota               = rota,
                    dataHora           = meta?.data ?: LocalDateTime.now().toString(),
                    paceZones          = paceZones,
                    stepLengthBaseline = meta?.stepLengthBaseline ?: 0.0,
                    treinoNome         = meta?.treinoNome,
                    treinoPassosJson   = meta?.treinoPassosJson
                ).onSuccess {
                    importadas++
                    Log.d(TAG, "✅ Importada: ${gpxDoc.name}")
                }.onFailure { e ->
                    erros++
                    Log.e(TAG, "❌ Falha ao importar ${gpxDoc.name}: ${e.message}")
                }
            }.onFailure { e ->
                erros++
                Log.e(TAG, "❌ Erro ao processar ${gpxDoc.name}: ${e.message}")
            }
        }

        Log.d(TAG, "📦 Import SAF concluído: $importadas ok, $erros erros")
        Pair(importadas, erros)
    }

    // ── IMPORTAR (caminho fixo — mantido para compatibilidade interna) ────────

    /**
     * Lê os arquivos de backup e reimporta cada corrida recalculando
     * todos os dados com o código atual (splits, voltas, GAP, biomecânica).
     *
     * Fluxo por corrida:
     *   1. Lê .json → extrai metadados (nome, data, treinoNome, treinoPassosJson)
     *   2. Lê .gpx  → reconstrói List<LatLngPonto> com coords, altitude, cadência, pace
     *   3. Chama salvarAtividade() → recalcula tudo com implementação atual
     *
     * Retorna par (importadas, erros).
     */
    suspend fun importarTudo(
        athleteId: String,
        paceZones: List<PaceZone> = emptyList()
    ): Result<Pair<Int, Int>> = runCatching {
        val backup = pastaBackup()
        var importadas = 0
        var erros = 0

        val gpxFiles = backup.listFiles { f -> f.extension == "gpx" } ?: emptyArray()

        for (gpxFile in gpxFiles) {
            runCatching {
                val jsonFile = File(backup, gpxFile.name.replace(".gpx", ".json"))

                // 1. Lê metadados do JSON
                val meta = if (jsonFile.exists()) {
                    runCatching {
                        gson.fromJson(jsonFile.readText(), CorridaHistorico::class.java)
                    }.getOrNull()
                } else null

                // 2. Reconstrói rota a partir do GPX
                val rota = parsearGpx(gpxFile)
                if (rota.isEmpty()) {
                    Log.w(TAG, "⚠️ GPX sem pontos: ${gpxFile.name}")
                    erros++
                    return@runCatching
                }

                // Calcula distância e tempo a partir dos pontos
                val distancia = rota.zipWithNext().sumOf { (a, b) ->
                    haversine(a.lat, a.lng, b.lat, b.lng)
                }
                val tempoSegundos = if (rota.size >= 2)
                    (rota.last().tempo - rota.first().tempo) / 1000L else 0L

                val paceSegKm = if (distancia > 0)
                    (tempoSegundos.toDouble() / distancia * 1000) else 0.0
                val paceMedia = if (paceSegKm in 60.0..1200.0)
                    "%d:%02d".format((paceSegKm / 60).toInt(), (paceSegKm % 60).toInt())
                else "--:--"

                // 3. Recalcula tudo via salvarAtividade
                workoutRepository.salvarAtividade(
                    context          = context,
                    athleteId        = athleteId,
                    nomeAtividade    = meta?.nome ?: gpxFile.nameWithoutExtension,
                    distanciaMetros  = distancia,
                    tempoSegundos    = tempoSegundos,
                    paceMedia        = paceMedia,
                    rota             = rota,
                    dataHora         = meta?.data ?: LocalDateTime.now().toString(),
                    paceZones        = paceZones,
                    stepLengthBaseline = meta?.stepLengthBaseline ?: 0.0,
                    treinoNome       = meta?.treinoNome,
                    treinoPassosJson = meta?.treinoPassosJson
                ).onSuccess {
                    importadas++
                    Log.d(TAG, "✅ Importada: ${gpxFile.name}")
                }.onFailure { e ->
                    erros++
                    Log.e(TAG, "❌ Falha ao importar ${gpxFile.name}: ${e.message}")
                }
            }.onFailure { e ->
                erros++
                Log.e(TAG, "❌ Erro ao processar ${gpxFile.name}: ${e.message}")
            }
        }

        Log.d(TAG, "📦 Import concluído: $importadas ok, $erros erros")
        Pair(importadas, erros)
    }

    // ── PARSER GPX ───────────────────────────────────────────────────────────

    /**
     * Parseia um arquivo GPX a partir de um File (mantido para importarTudo).
     */
    private fun parsearGpx(file: File): List<LatLngPonto> =
        parsearGpxStream(file.inputStream())

    /**
     * Parseia um GPX a partir de qualquer InputStream — usado pelo SAF
     * (ContentResolver.openInputStream) e pelo parsearGpx de File acima.
     */
    private fun parsearGpxStream(stream: java.io.InputStream): List<LatLngPonto> {
        val pontos = mutableListOf<LatLngPonto>()
        runCatching {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(stream, "UTF-8")

            var lat = 0.0
            var lng = 0.0
            var alt = 0.0
            var tempo = 0L
            var cad = 0
            var pace = 0.0
            var emTrkpt = false
            var emEle = false
            var emTime = false
            var emCad = false
            var emPace = false

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "trkpt" -> {
                                emTrkpt = true
                                lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                                lng = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                                alt = 0.0; tempo = 0L; cad = 0; pace = 0.0
                            }
                            "ele"  -> if (emTrkpt) emEle = true
                            "time" -> if (emTrkpt) emTime = true
                            "gpxtpx:cad" -> if (emTrkpt) emCad = true
                            "pace" -> if (emTrkpt) emPace = true
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim() ?: ""
                        when {
                            emEle  -> { alt = text.toDoubleOrNull() ?: 0.0; emEle = false }
                            emTime -> {
                                // Formato: "2024-05-15T10:30:00Z"
                                tempo = runCatching {
                                    val ldt = LocalDateTime.parse(
                                        text.removeSuffix("Z"),
                                        DateTimeFormatter.ISO_LOCAL_DATE_TIME
                                    )
                                    ldt.toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
                                }.getOrDefault(0L)
                                emTime = false
                            }
                            emCad  -> { cad = text.toIntOrNull() ?: 0; emCad = false }
                            emPace -> { pace = text.toDoubleOrNull() ?: 0.0; emPace = false }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "trkpt" && emTrkpt && lat != 0.0 && tempo != 0L) {
                            pontos.add(LatLngPonto(
                                lat = lat, lng = lng, alt = alt, tempo = tempo,
                                accuracy = 5f,
                                paceNoPonto = pace,
                                cadenciaNoPonto = cad
                            ))
                            emTrkpt = false
                        }
                    }
                }
                event = parser.next()
            }
        }.onFailure { e ->
            Log.e(TAG, "❌ Erro ao parsear GPX: ${e.message}")
        }
        return pontos
    }

    private fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2).let { it * it } +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2).let { it * it }
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}
