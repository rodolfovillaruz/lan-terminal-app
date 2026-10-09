package com.lanterminal.remote.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "connection")

const val DEFAULT_PORT = 8765

class SettingsStore(private val context: Context) {
    private val hostKey = stringPreferencesKey("host")
    private val portKey = intPreferencesKey("port")
    private val tokenKey = stringPreferencesKey("token")

    val connection: Flow<Connection> = context.dataStore.data.map { prefs ->
        Connection(
            host = prefs[hostKey].orEmpty(),
            port = prefs[portKey] ?: DEFAULT_PORT,
            token = prefs[tokenKey].orEmpty(),
        )
    }

    suspend fun save(connection: Connection) {
        context.dataStore.edit { prefs ->
            prefs[hostKey] = connection.host
            prefs[portKey] = connection.port
            prefs[tokenKey] = connection.token
        }
    }
}
