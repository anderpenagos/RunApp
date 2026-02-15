// CorridaScreen.kt - MODIFICAÇÕES NECESSÁRIAS
// Adicione estas importações no topo do arquivo:

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.runapp.util.PermissionHelper
import android.widget.Toast

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// MODIFICAÇÃO 1: Adicione estas variáveis no início da função CorridaScreen,
// logo após a linha: val context = LocalContext.current
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

var permissaoGps by remember { 
    mutableStateOf(PermissionHelper.hasLocationPermissions(context)) 
}

var statusGps by remember { mutableStateOf("Buscando GPS...") }
var pontosColetados by remember { mutableStateOf(0) }

// Launcher para solicitar permissões
val permissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
) { permissions ->
    permissaoGps = permissions.values.all { it }
    if (!permissaoGps) {
        Toast.makeText(
            context,
            "⚠️ Permissões de GPS são necessárias para rastrear sua corrida",
            Toast.LENGTH_LONG
        ).show()
    } else {
        Toast.makeText(
            context,
            "✅ Permissões concedidas! Aguarde o sinal GPS...",
            Toast.LENGTH_SHORT
        ).show()
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// MODIFICAÇÃO 2: Adicione este LaunchedEffect para solicitar permissões
// Coloque logo após o LaunchedEffect(eventId) existente
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

// Solicitar permissões ao iniciar a tela
LaunchedEffect(Unit) {
    if (!permissaoGps) {
        permissionLauncher.launch(PermissionHelper.LOCATION_PERMISSIONS)
    }
}

// Atualizar status do GPS baseado nos pontos coletados
LaunchedEffect(state.rota.size) {
    pontosColetados = state.rota.size
    statusGps = when {
        !permissaoGps -> "⚠️ Sem permissão GPS"
        pontosColetados == 0 -> "🔍 Buscando sinal GPS..."
        pontosColetados < 10 -> "📡 Sinal GPS fraco (${pontosColetados} pontos)"
        else -> "✅ GPS OK (${pontosColetados} pontos)"
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// MODIFICAÇÃO 3: Adicione este indicador de status no topo da UI
// Substitua a primeira Box/Column pelo código abaixo
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Column(modifier = Modifier.fillMaxSize()) {
    // ✨ NOVO: Indicador de status GPS no topo
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = when {
            !permissaoGps -> Color(0xFFFF6B6B)  // Vermelho - sem permissão
            pontosColetados < 10 -> Color(0xFFFFBE0B)  // Amarelo - sinal fraco
            else -> Color(0xFF4ECDC4)  // Verde - GPS OK
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = statusGps,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            
            // Mostrar botão para reabrir permissões se negadas
            if (!permissaoGps) {
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = { 
                        permissionLauncher.launch(PermissionHelper.LOCATION_PERMISSIONS)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Color.White
                    )
                ) {
                    Text("PERMITIR", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
    
    // ... resto do conteúdo da tela (Box com mapa, etc)
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// MODIFICAÇÃO 4: Atualize a condição do DisposableEffect
// Encontre o DisposableEffect(state.fase) e modifique a condição:
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

DisposableEffect(state.fase, permissaoGps) {  // ← Adicione permissaoGps aqui
    if (state.fase == FaseCorrida.CORRENDO && permissaoGps) {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateDistanceMeters(0f)
            .build()
        try {
            fusedLocationClient.requestLocationUpdates(
                request, 
                locationCallback, 
                Looper.getMainLooper()
            )
            android.util.Log.d("CorridaScreen", "✅ GPS iniciado com sucesso")
        } catch (e: SecurityException) {
            android.util.Log.e("CorridaScreen", "❌ Erro GPS: ${e.message}")
            Toast.makeText(
                context,
                "Erro ao acessar GPS. Verifique as permissões.",
                Toast.LENGTH_LONG
            ).show()
        }
    } else {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        if (!permissaoGps && state.fase == FaseCorrida.CORRENDO) {
            android.util.Log.w("CorridaScreen", "⚠️ GPS não iniciado - sem permissão")
        }
    }
    onDispose {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
}
