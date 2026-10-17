package com.airbridge.touch.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val darkTheme: Boolean = true,
    val useSystemTheme: Boolean = true,
    val maxFileBytes: Long = com.airbridge.touch.core.TransferLimits.DEFAULT_MAX_FILE_BYTES,
    val confirmIncoming: Boolean = true,
    val receiveTreeUri: String? = null
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val dark = booleanPreferencesKey("dark")
        val system = booleanPreferencesKey("system_theme")
        val maxBytes = longPreferencesKey("max_file_bytes")
        val confirm = booleanPreferencesKey("confirm_incoming")
        val tree = stringPreferencesKey("receive_tree_uri")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            darkTheme = p[Keys.dark] ?: true,
            useSystemTheme = p[Keys.system] ?: true,
            maxFileBytes = p[Keys.maxBytes] ?: com.airbridge.touch.core.TransferLimits.DEFAULT_MAX_FILE_BYTES,
            confirmIncoming = p[Keys.confirm] ?: true,
            receiveTreeUri = p[Keys.tree]
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setDark(v: Boolean) = context.dataStore.edit { it[Keys.dark] = v }
    suspend fun setUseSystem(v: Boolean) = context.dataStore.edit { it[Keys.system] = v }
    suspend fun setMaxFileBytes(v: Long) = context.dataStore.edit { it[Keys.maxBytes] = v.coerceAtLeast(1_000_000L) }
    suspend fun setConfirmIncoming(v: Boolean) = context.dataStore.edit { it[Keys.confirm] = v }
    suspend fun setReceiveTree(uri: String?) = context.dataStore.edit {
        if (uri == null) it.remove(Keys.tree) else it[Keys.tree] = uri
    }
}
