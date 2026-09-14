package com.matiasnl.hakiosk.data.device

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.matiasnl.hakiosk.data.device.store.DataStoreMqttConfigStore
import com.matiasnl.hakiosk.data.ha.store.TokenCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.GeneralSecurityException

class DataStoreMqttConfigStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore by lazy {
        PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("mqtt.preferences_pb") }
    }

    private class FakeCipher : TokenCipher {
        var failDecrypt = false
        override fun encrypt(plaintext: String) = "enc:" + plaintext.reversed()
        override fun decrypt(ciphertext: String): String {
            if (failDecrypt) throw GeneralSecurityException("key lost")
            return ciphertext.removePrefix("enc:").reversed()
        }
    }

    private val cipher = FakeCipher()
    private var generatedIds = 0
    private val store by lazy {
        DataStoreMqttConfigStore(dataStore, cipher, Dispatchers.Unconfined) { "id${++generatedIds}" }
    }

    private val config = MqttConfig(
        host = "192.168.1.10",
        port = 8883,
        username = "kiosk",
        password = "broker-secret",
        useTls = true,
        deviceName = "Tablet cocina",
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun emptyStoreEmitsNull() = runTest {
        assertNull(store.config.first())
    }

    @Test
    fun saveRoundTripsAndStoresPasswordEncrypted() = runTest {
        store.save(config)

        assertEquals(config, store.config.first())
        val prefs = dataStore.data.first()
        assertEquals("enc:terces-rekorb", prefs[stringPreferencesKey("password_encrypted")])
        assertFalse(prefs.asMap().values.any { it.toString().contains("broker-secret") })
    }

    @Test
    fun blankCredentialsAreStoredAsAbsent() = runTest {
        store.save(config.copy(username = "  ", password = ""))
        val saved = store.config.first()!!
        assertNull(saved.username)
        assertNull(saved.password)
    }

    @Test
    fun savingWithoutPasswordRemovesPreviousOne() = runTest {
        store.save(config)
        store.save(config.copy(username = null, password = null))
        assertNull(store.config.first()!!.password)
        assertNull(dataStore.data.first()[stringPreferencesKey("password_encrypted")])
    }

    @Test
    fun clearRemovesConfigButKeepsDeviceId() = runTest {
        val id = store.deviceId()
        store.save(config)
        store.clear()
        assertNull(store.config.first())
        assertEquals(id, store.deviceId())
    }

    @Test
    fun undecryptablePasswordIsTreatedAsNotConfigured() = runTest {
        store.save(config)
        cipher.failDecrypt = true
        dataStore.edit { it[stringPreferencesKey("touch")] = "1" }
        assertNull(store.config.first())
    }

    @Test
    fun deviceIdIsGeneratedOnceAndStable() = runTest {
        val ids = List(8) { async(Dispatchers.IO) { store.deviceId() } }.awaitAll()
        assertEquals(1, ids.toSet().size)
        assertEquals(ids.first(), store.deviceId())
        assertEquals(1, generatedIds)
    }

    @Test
    fun deviceIdSurvivesANewStoreInstanceOnTheSameFile() = runTest {
        val id = store.deviceId()
        val reopened = DataStoreMqttConfigStore(dataStore, cipher, Dispatchers.Unconfined) { "other" }
        assertEquals(id, reopened.deviceId())
    }

    @Test
    fun defaultDeviceIdIsTopicSafe() = runTest {
        val id = DataStoreMqttConfigStore(dataStore, cipher, Dispatchers.Unconfined).deviceId()
        assertTrue(id, id.matches(Regex("[0-9a-f]{16}")))
    }
}
