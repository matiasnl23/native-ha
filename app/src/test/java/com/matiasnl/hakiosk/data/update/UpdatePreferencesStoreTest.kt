package com.matiasnl.hakiosk.data.update

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdatePreferencesStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun TestScope.dataStoreStore(file: File = File(tempFolder.root, "update.preferences_pb")) =
        DataStoreUpdatePreferencesStore(
            PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file }),
        )

    @Test
    fun `defaults to a daily check that never ran`() = runTest {
        val store = dataStoreStore()

        assertEquals(UpdatePreferences(DEFAULT_CHECK_INTERVAL_HOURS, null), store.preferences.first())
    }

    @Test
    fun `persists the interval and the last check`() = runTest {
        val store = dataStoreStore()

        store.setCheckIntervalHours(6)
        store.setLastCheckEpochMillis(1_700_000_000_000)

        assertEquals(UpdatePreferences(6, 1_700_000_000_000), store.preferences.first())
    }

    @Test
    fun `zero hours is kept as the off switch`() = runTest {
        val store = dataStoreStore()

        store.setCheckIntervalHours(0)

        assertEquals(0, store.preferences.first().checkIntervalHours)
    }

    @Test
    fun `the interval is clamped to a sane range`() = runTest {
        val store = dataStoreStore()

        store.setCheckIntervalHours(-5)
        assertEquals(0, store.preferences.first().checkIntervalHours)

        store.setCheckIntervalHours(MAX_CHECK_INTERVAL_HOURS + 100)
        assertEquals(MAX_CHECK_INTERVAL_HOURS, store.preferences.first().checkIntervalHours)
    }

    @Test
    fun `a non-positive last check reads as never checked`() = runTest {
        val store = dataStoreStore()

        store.setLastCheckEpochMillis(0)

        assertNull(store.preferences.first().lastCheckEpochMillis)
    }

    @Test
    fun `in-memory store behaves the same`() = runTest {
        val store = InMemoryUpdatePreferencesStore()

        store.setCheckIntervalHours(MAX_CHECK_INTERVAL_HOURS + 1)
        store.setLastCheckEpochMillis(0)

        assertEquals(MAX_CHECK_INTERVAL_HOURS, store.preferences.value.checkIntervalHours)
        assertNull(store.preferences.value.lastCheckEpochMillis)
    }
}
