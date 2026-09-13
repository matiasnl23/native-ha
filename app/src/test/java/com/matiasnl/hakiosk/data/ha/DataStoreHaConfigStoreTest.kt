package com.matiasnl.hakiosk.data.ha

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.matiasnl.hakiosk.data.ha.store.DataStoreHaConfigStore
import com.matiasnl.hakiosk.data.ha.store.TokenCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.GeneralSecurityException

class DataStoreHaConfigStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore by lazy {
        PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("test.preferences_pb") }
    }

    /** Reversible fake: never returns the plaintext as-is so we can check nothing is stored in clear. */
    private class FakeCipher : TokenCipher {
        var failDecrypt = false
        override fun encrypt(plaintext: String) = "enc:" + plaintext.reversed()
        override fun decrypt(ciphertext: String): String {
            if (failDecrypt) throw GeneralSecurityException("key lost")
            return ciphertext.removePrefix("enc:").reversed()
        }
    }

    private val cipher = FakeCipher()
    private val store by lazy { DataStoreHaConfigStore(dataStore, cipher, Dispatchers.Unconfined) }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun emptyStoreEmitsNull() = runTest {
        assertNull(store.config.first())
    }

    @Test
    fun saveNormalizesUrlAndRoundTripsToken() = runTest {
        store.save(HaServerConfig(baseUrl = "  http://192.168.1.50:8123/ ", token = "secret-token"))

        assertEquals(
            HaServerConfig("http://192.168.1.50:8123", "secret-token"),
            store.config.first(),
        )
        val raw = dataStore.data.first()[stringPreferencesKey("token_encrypted")]
        assertEquals("enc:nekot-terces", raw)
        assertFalse(dataStore.data.first().asMap().values.any { it == "secret-token" })
    }

    @Test
    fun clearRemovesConfig() = runTest {
        store.save(HaServerConfig("http://ha.local:8123", "t"))
        store.clear()
        assertNull(store.config.first())
    }

    @Test
    fun undecryptableTokenIsTreatedAsNotConfigured() = runTest {
        store.save(HaServerConfig("http://ha.local:8123", "t"))
        cipher.failDecrypt = true
        dataStore.edit { it[stringPreferencesKey("touch")] = "1" }
        assertNull(store.config.first())
    }
}
