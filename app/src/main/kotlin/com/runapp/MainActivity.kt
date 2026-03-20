package com.runapp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.runapp.ui.navigation.AppNavigation
import com.runapp.ui.theme.RunAppTheme

class MainActivity : ComponentActivity() {

    // mutableStateOf garante que o Compose recomponha quando o intent mudar via
    // onNewIntent (app já aberto, usuário clica na notificação novamente).
    // Sem isso, o AppNavigation receberia o intent antigo e não navegaria para a corrida.
    private val intentState = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intentState.value = intent
        enableEdgeToEdge()
        setContent {
            RunAppTheme {
                // Fundo preto no container raiz — elimina o flash branco/cinza que aparece
                // nos 1-2 frames antes do Compose renderizar a primeira tela.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF121212)
                ) {
                    AppNavigation(notificationIntent = intentState.value)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentState.value = intent  // dispara recomposição do AppNavigation
    }
}
