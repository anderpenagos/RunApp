package com.runapp.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

object PermissionHelper {

    val LOCATION_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    val BACKGROUND_LOCATION_PERMISSION =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            emptyArray()
        }

    // Android 13+ exige permissão em runtime para notificações
    val NOTIFICATION_PERMISSION =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        }

    fun hasLocationPermissions(context: Context): Boolean {
        return LOCATION_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasBackgroundLocationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Abaixo do Android 13 não precisa pedir
        }
    }

    /**
     * Verifica se o app já está excluído da otimização de bateria do sistema.
     * Quando retorna false, o Android pode matar o foreground service em corridas longas
     * mesmo com WakeLock ativo — especialmente em ROMs Xiaomi (MIUI), Samsung (One UI)
     * e Huawei, que são mais agressivas do que o AOSP.
     */
    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Retorna o Intent para pedir ao usuário que exclua o app da otimização de bateria.
     * Deve ser lançado via startActivity(). A permissão REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
     * já está no manifesto, então esse Intent funciona sem abrir as configurações manualmente.
     *
     * Uso recomendado: chamar antes de iniciar uma corrida se [isBatteryOptimizationIgnored] = false.
     */
    fun batteryOptimizationIntent(context: Context): Intent {
        return Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    fun hasAllPermissions(context: Context): Boolean {
        return hasLocationPermissions(context) &&
               hasBackgroundLocationPermission(context) &&
               hasNotificationPermission(context)
    }
}

