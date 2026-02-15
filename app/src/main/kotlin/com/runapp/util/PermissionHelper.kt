package com.runapp.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Helper para gerenciar permissões de localização no app.
 * 
 * Android 10+ requer permissão separada para localização em background.
 * Este helper centraliza toda a lógica de verificação de permissões.
 */
object PermissionHelper {
    
    /**
     * Permissões básicas de localização necessárias.
     * Necessárias para rastreamento durante uso ativo do app.
     */
    val LOCATION_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
    
    /**
     * Permissão de localização em background.
     * Só é necessária no Android 10 (Q) ou superior.
     * Permite rastreamento GPS mesmo com tela bloqueada.
     */
    val BACKGROUND_LOCATION_PERMISSION = 
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            emptyArray()
        }
    
    /**
     * Verifica se o app tem as permissões básicas de localização.
     * 
     * @return true se todas as permissões básicas foram concedidas
     */
    fun hasLocationPermissions(context: Context): Boolean {
        return LOCATION_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == 
                PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Verifica se o app tem permissão de localização em background.
     * No Android 9 ou inferior, sempre retorna true.
     * 
     * @return true se a permissão foi concedida (ou não é necessária)
     */
    fun hasBackgroundLocationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context, 
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Android < 10 não precisa desta permissão
        }
    }
    
    /**
     * Verifica se TODAS as permissões necessárias foram concedidas,
     * incluindo background location para Android 10+.
     * 
     * @return true se todas as permissões estão OK
     */
    fun hasAllPermissions(context: Context): Boolean {
        return hasLocationPermissions(context) && hasBackgroundLocationPermission(context)
    }
}
