package com.runapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Foreground Service que mantém o GPS ativo durante a corrida,
 * mesmo com a tela bloqueada.
 */
class RunningService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // Expõe a localização atual para o ViewModel
    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { loc ->
                _location.value = loc
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> iniciar()
            ACTION_STOP -> parar()
        }
        return START_STICKY
    }

    private fun iniciar() {
        criarCanalNotificacao()
        startForeground(NOTIFICATION_ID, criarNotificacao())
        iniciarRastreamento()
    }

    private fun iniciarRastreamento() {
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000L // a cada 1 segundo
        )
            .setMinUpdateDistanceMeters(2f) // mínimo 2 metros de diferença
            .setGranularity(Granularity.GRANULARITY_FINE)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            // Permissão não concedida — o ViewModel deve verificar antes
        }
    }

    private fun parar() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun criarCanalNotificacao() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Corrida em Andamento",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mostra informações da sua corrida atual"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun criarNotificacao(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RunApp — Corrida Ativa 🏃")
            .setContentText("GPS registrando sua corrida...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "running_channel"
        const val NOTIFICATION_ID = 42
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"

        // Instância compartilhada (simples, sem Hilt)
        private var instance: RunningService? = null
        fun getInstance(): RunningService? = instance

        // Pace calculado a partir da velocidade GPS
        fun calcularPace(speedMs: Float): String {
            if (speedMs < 0.3f) return "--:--"
            val minPerKm = 1000f / (speedMs * 60f)
            val min = minPerKm.toInt()
            val seg = ((minPerKm - min) * 60).toInt()
            return "%d:%02d".format(min, seg)
        }

        // Distância entre dois pontos em metros
        fun distanciaMetros(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
            val result = FloatArray(1)
            Location.distanceBetween(lat1, lng1, lat2, lng2, result)
            return result[0]
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
