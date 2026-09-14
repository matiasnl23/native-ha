package com.matiasnl.hakiosk.data.device.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.matiasnl.hakiosk.data.device.MqttConfig
import com.matiasnl.hakiosk.data.device.MqttConfigStore
import com.matiasnl.hakiosk.data.ha.store.TokenCipher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/** Stable identifier of this app install, used in MQTT topics, the client id and Home Assistant unique ids. */
fun interface MqttDeviceIdProvider {
    /** Returns the same id for the whole life of the install, generating it on first use. */
    suspend fun deviceId(): String
}

/**
 * [MqttConfigStore] backed by DataStore Preferences in its own file. The password is only ever
 * persisted encrypted through [cipher] and never logged. Also owns the install's device id, which
 * survives [clear] so Home Assistant keeps seeing the same device when the broker is reconfigured.
 */
class DataStoreMqttConfigStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: TokenCipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val newDeviceId: () -> String = ::randomDeviceId,
) : MqttConfigStore, MqttDeviceIdProvider {

    override val config: Flow<MqttConfig?> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { prefs -> prefs.toConfig() }
        .distinctUntilChanged()
        .flowOn(ioDispatcher)

    override suspend fun save(config: MqttConfig) {
        val password = config.password?.takeIf { it.isNotEmpty() }
        val encryptedPassword = password?.let { withContext(ioDispatcher) { cipher.encrypt(it) } }
        val username = config.username?.trim()?.takeIf { it.isNotEmpty() }
        dataStore.edit { prefs ->
            prefs[KEY_HOST] = config.host.trim()
            prefs[KEY_PORT] = config.port
            prefs[KEY_USE_TLS] = config.useTls
            prefs[KEY_DEVICE_NAME] = config.deviceName.trim()
            if (username != null) prefs[KEY_USERNAME] = username else prefs.remove(KEY_USERNAME)
            if (encryptedPassword != null) prefs[KEY_PASSWORD] = encryptedPassword else prefs.remove(KEY_PASSWORD)
        }
    }

    override suspend fun clear() {
        dataStore.edit { prefs ->
            CONFIG_KEYS.forEach { prefs.remove(it) }
        }
    }

    override suspend fun deviceId(): String {
        dataStore.data.first()[KEY_DEVICE_ID]?.let { return it }
        // Generated inside edit: DataStore serializes edits, so concurrent first calls agree on one id.
        var id = ""
        dataStore.edit { prefs ->
            id = prefs[KEY_DEVICE_ID] ?: newDeviceId().also { prefs[KEY_DEVICE_ID] = it }
        }
        return id
    }

    private fun Preferences.toConfig(): MqttConfig? {
        val host = this[KEY_HOST] ?: return null
        val encryptedPassword = this[KEY_PASSWORD]
        // A password that can't be decrypted (e.g. restored without its Keystore key) makes the whole
        // config "not configured", so the user is asked to enter it again instead of failing auth forever.
        val password = try {
            encryptedPassword?.let(cipher::decrypt)
        } catch (_: Exception) {
            return null
        }
        return MqttConfig(
            host = host,
            port = this[KEY_PORT] ?: MqttConfig.DEFAULT_PORT,
            username = this[KEY_USERNAME],
            password = password,
            useTls = this[KEY_USE_TLS] ?: false,
            deviceName = this[KEY_DEVICE_NAME].orEmpty(),
        )
    }

    companion object {
        /** Excluded from backups in `res/xml/backup_rules.xml` and `data_extraction_rules.xml`. */
        const val DATASTORE_NAME = "mqtt_config"

        /** Keystore alias for the broker password, separate from the Home Assistant token key. */
        const val PASSWORD_KEY_ALIAS = "ha_kiosk_mqtt_password_key"

        private val KEY_HOST = stringPreferencesKey("host")
        private val KEY_PORT = intPreferencesKey("port")
        private val KEY_USERNAME = stringPreferencesKey("username")
        private val KEY_PASSWORD = stringPreferencesKey("password_encrypted")
        private val KEY_USE_TLS = booleanPreferencesKey("use_tls")
        private val KEY_DEVICE_NAME = stringPreferencesKey("device_name")
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        private val CONFIG_KEYS = listOf(KEY_HOST, KEY_PORT, KEY_USERNAME, KEY_PASSWORD, KEY_USE_TLS, KEY_DEVICE_NAME)

        /** 16 lowercase hex chars: valid in MQTT topics and Home Assistant object ids. */
        private fun randomDeviceId(): String = UUID.randomUUID().toString().replace("-", "").take(16)
    }
}
