package com.runapp.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logger de debug GPS — grava eventos críticos em arquivo de texto durante a corrida.
 *
 * Uso:
 *   GpsDebugLogger.log(context, "GPS", "speed=1.2 accuracy=18m")
 *
 * Leitura:
 *   GpsDebugLogger.ler(context)  → String com todo o conteúdo
 *   GpsDebugLogger.limpar(context)  → apaga o arquivo
 */
object GpsDebugLogger {

    private const val NOME_ARQUIVO = "gps_debug.txt"
    private const val MAX_LINHAS   = 2000  // evita arquivo gigante em corridas longas

    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private fun arquivo(context: Context) =
        File(context.filesDir, NOME_ARQUIVO)

    fun log(context: Context, tag: String, mensagem: String) {
        try {
            val linha = "${fmt.format(Date())} [$tag] $mensagem\n"
            val f = arquivo(context)

            // Proteção de tamanho: se passou do limite, descarta as primeiras 200 linhas
            if (f.exists()) {
                val linhas = f.readLines().toMutableList()
                if (linhas.size >= MAX_LINHAS) {
                    val truncado = linhas.drop(200).joinToString("\n")
                    f.writeText("... (linhas antigas removidas) ...\n$truncado\n")
                }
            }

            f.appendText(linha)
        } catch (_: Exception) { /* nunca deixar o logger quebrar a corrida */ }
    }

    fun ler(context: Context): String {
        return try {
            val f = arquivo(context)
            if (f.exists()) f.readText() else "Nenhum log disponível."
        } catch (_: Exception) { "Erro ao ler o arquivo de log." }
    }

    fun limpar(context: Context) {
        try { arquivo(context).delete() } catch (_: Exception) {}
    }

    fun existe(context: Context) = arquivo(context).exists()
}
