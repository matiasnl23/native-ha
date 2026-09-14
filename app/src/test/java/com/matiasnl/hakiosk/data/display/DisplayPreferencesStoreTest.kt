package com.matiasnl.hakiosk.data.display

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DisplayPreferencesStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun kotlinx.coroutines.test.TestScope.dataStoreStore(file: File = File(tempFolder.root, "prefs.preferences_pb")) =
        DataStoreDisplayPreferencesStore(
            PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file }),
        )

    @Test
    fun `defaults to the system brightness, no screen-off timeout and 60s camera auto-close`() = runTest {
        val store = dataStoreStore()

        assertEquals(DisplayPreferences(null, 0, 60), store.preferences.first())
    }

    @Test
    fun `persists brightness, timeout and camera auto-close`() = runTest {
        val store = dataStoreStore()

        store.setBrightnessPercent(40)
        store.setScreenOffTimeoutMinutes(10)
        store.setCameraCloseAfterSeconds(30)

        assertEquals(DisplayPreferences(40, 10, 30), store.preferences.first())
    }

    @Test
    fun `brightness is clamped to 0 to 100`() = runTest {
        val store = dataStoreStore()

        store.setBrightnessPercent(-5)
        assertEquals(0, store.preferences.first().brightnessPercent)

        store.setBrightnessPercent(150)
        assertEquals(100, store.preferences.first().brightnessPercent)
    }

    @Test
    fun `timeout and camera auto-close are clamped to a minimum of 0`() = runTest {
        val store = dataStoreStore()

        store.setScreenOffTimeoutMinutes(-1)
        store.setCameraCloseAfterSeconds(-1)

        assertEquals(0, store.preferences.first().screenOffTimeoutMinutes)
        assertEquals(0, store.preferences.first().cameraCloseAfterSeconds)
    }

    @Test
    fun `in-memory store behaves the same`() = runTest {
        val store = InMemoryDisplayPreferencesStore()

        store.setBrightnessPercent(200)
        store.setScreenOffTimeoutMinutes(-3)
        store.setCameraCloseAfterSeconds(15)

        assertEquals(DisplayPreferences(100, 0, 15), store.preferences.value)
    }
}
