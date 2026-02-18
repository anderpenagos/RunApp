package com.runapp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.runapp.ui.navigation.AppNavigation
import com.runapp.ui.theme.RunAppTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RunAppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavigation(notificationIntent = intent)
                }
            }
        }
    }

    // Chamado quando o app já está aberto (singleTop) e o usuário clica na notificação.
    // Sem isso, o intent novo seria ignorado e o clique não navegaria para a corrida.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)  // atualiza o intent da Activity para o Compose conseguir ler
    }
}
