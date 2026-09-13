package com.matiasnl.hakiosk.data.ha.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.matiasnl.hakiosk.data.ha.HaConfigStore
import com.matiasnl.hakiosk.data.ha.HaServerConfig
import com.matiasnl.hakiosk.data.ha.HaUrls
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * [HaConfigStore] backed by DataStore Preferences. The base URL is stored as-is (normalized); the
 * token is only ever persisted encrypted through [cipher]. The token is never logged.
 */
class DataStoreHaConfigStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: TokenCipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HaConfigStore {

    override val config: Flow<HaServerConfig?> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { prefs -> prefs.toConfig() }
        .distinctUntilChanged()
        .flowOn(ioDispatcher)

    override suspend fun save(config: HaServerConfig) {
        val encryptedToken = withContext(ioDispatcher) { cipher.encrypt(config.token.trim()) }
        dataStore.edit { prefs ->
            prefs[KEY_BASE_URL] = HaUrls.normalizeBaseUrl(config.baseUrl)
            prefs[KEY_TOKEN] = encryptedToken
        }
    }

    override suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_BASE_URL)
            prefs.remove(KEY_TOKEN)
        }
    }

    private fun Preferences.toConfig(): HaServerConfig? {
        val baseUrl = this[KEY_BASE_URL] ?: return null
        val encryptedToken = this[KEY_TOKEN] ?: return null
        // A token that can't be decrypted (e.g. restored from backup without its Keystore key)
        // is treated as "not configured" so the user is sent back to setup.
        val token = try {
            cipher.decrypt(encryptedToken)
        } catch (_: Exception) {
            return null
        }
        return HaServerConfig(baseUrl = baseUrl, token = token)
    }

    companion object {
        const val DATASTORE_NAME = "ha_server_config"
        private val KEY_BASE_URL = stringPreferencesKey("base_url")
        private val KEY_TOKEN = stringPreferencesKey("token_encrypted")
    }
}
