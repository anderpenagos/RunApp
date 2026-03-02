package com.runapp.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.runapp.data.model.ZonaFronteira
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "runapp_prefs")

class PreferencesRepository(private val context: Context) {

    companion object {
        val API_KEY                   = stringPreferencesKey("api_key")
        val ATHLETE_ID                = stringPreferencesKey("athlete_id")
        val AUTO_PAUSE_ENABLED        = booleanPreferencesKey("auto_pause_enabled")
        // Modo de Telemetria Reduzida: quando ativo, o AudioCoach só menciona o GAP
        // no fechamento de km quando o terreno for desafiador (subida real ou descida
        // técnica). Trechos planos e descidas suaves recebem apenas o pace, sem análise
        // de esforço ajustado. Ideal para quem prefere menos informação em corridas simples.
        val GAP_TELEMETRIA_REDUZIDA   = booleanPreferencesKey("gap_telemetria_reduzida")

        // Cache das zonas de pace do Intervals.icu — persistido em JSON para sobreviver
        // a process death. Atualizado toda vez que zonas são buscadas com sucesso.
        // Formato: JSON de List<ZonaFronteira> (s/km), pronto para uso no dashboard.
        val ZONAS_FRONTEIRA_JSON      = stringPreferencesKey("zonas_fronteira_json")
    }

    private val gson = Gson()

    val apiKey: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[API_KEY]
    }

    val athleteId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[ATHLETE_ID]
    }

    val autoPauseEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[AUTO_PAUSE_ENABLED] ?: true  // Ativado por padrão
    }

    // Desativado por padrão: novos usuários recebem o feedback completo e podem
    // reduzir quando ficarem confortáveis com o GAP. Inverter o default seria confuso
    // pois o usuário não saberia por que o app "silenciou".
    val gapTelemetriaReduzida: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[GAP_TELEMETRIA_REDUZIDA] ?: false
    }

    suspend fun setAutoPauseEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[AUTO_PAUSE_ENABLED] = enabled
        }
    }

    suspend fun setGapTelemetriaReduzida(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[GAP_TELEMETRIA_REDUZIDA] = enabled
        }
    }

    suspend fun saveCredentials(apiKey: String, athleteId: String) {
        context.dataStore.edit { prefs ->
            prefs[API_KEY] = apiKey
            prefs[ATHLETE_ID] = athleteId
        }
    }

    suspend fun clearCredentials() {
        context.dataStore.edit { prefs ->
            prefs.remove(API_KEY)
            prefs.remove(ATHLETE_ID)
        }
    }

    /**
     * Persiste as zonas de pace no DataStore para uso offline.
     * Chamado toda vez que as zonas são buscadas com sucesso do Intervals.icu.
     */
    suspend fun salvarZonasFronteira(zonas: List<ZonaFronteira>) {
        if (zonas.isEmpty()) return
        val json = gson.toJson(zonas)
        context.dataStore.edit { prefs ->
            prefs[ZONAS_FRONTEIRA_JSON] = json
        }
        android.util.Log.d("PrefsRepo", "✅ ${zonas.size} zonas salvas no cache")
    }

    /**
     * Lê as zonas em cache. Retorna lista vazia se nunca foram salvas.
     */
    suspend fun getZonasFronteiraCached(): List<ZonaFronteira> {
        val json = context.dataStore.data.first()[ZONAS_FRONTEIRA_JSON] ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<ZonaFronteira>>() {}.type
            gson.fromJson<List<ZonaFronteira>>(json, type) ?: emptyList()
        }.getOrDefault(emptyList())
    }
}
