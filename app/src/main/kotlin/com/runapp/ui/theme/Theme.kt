package com.runapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Cores principais
val RunBlue = Color(0xFF1565C0)
val RunBlueDark = Color(0xFF0D47A1)
val RunAccent = Color(0xFF00BCD4)
val RunGreen = Color(0xFF4CAF50)
val RunOrange = Color(0xFFFF9800)
val RunRed = Color(0xFFF44336)

// Cores das zonas de pace (Z1-Z7) — padrão internacional de esforço
fun corZona(zona: Int): Color {
    return when (zona) {
        1    -> Color(0xFF9E9E9E) // Cinza    — Recuperação
        2    -> Color(0xFF2196F3) // Azul     — Leve
        3    -> Color(0xFF4CAF50) // Verde    — Aeróbico
        4    -> Color(0xFFFFEB3B) // Amarelo  — Limiar
        5    -> Color(0xFFFF9800) // Laranja  — Esforço Forte
        6    -> Color(0xFFF44336) // Vermelho — VO2 Máximo
        7    -> Color(0xFF9C27B0) // Roxo     — Anaeróbico / Sprint
        else -> Color(0xFF4CAF50) // Padrão Verde
    }
}

private val DarkColorScheme = darkColorScheme(
    primary = RunAccent,
    secondary = RunBlue,
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onPrimary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = RunBlue,
    secondary = RunAccent,
    background = Color(0xFFF5F5F5),
    surface = Color.White,
    onPrimary = Color.White,
    onBackground = Color(0xFF1A1A1A),
    onSurface = Color(0xFF1A1A1A)
)

@Composable
fun RunAppTheme(
    darkTheme: Boolean = true, // App de corrida fica melhor dark
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
