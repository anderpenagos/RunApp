package com.runapp.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "runapp_prefs")

class PreferencesRepository(private val context: Context) {

    companion object {
        val API_KEY = stringPreferencesKey("api_key")
        val ATHLETE_ID = stringPreferencesKey("athlete_id")
    }

    val apiKey: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[API_KEY]
    }

    val athleteId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[ATHLETE_ID]
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
}
