package com.matiasnl.hakiosk.data.dashboard

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DashboardViewPreferencesStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun kotlinx.coroutines.test.TestScope.dataStoreStore(file: File = File(tempFolder.root, "prefs.preferences_pb")) =
        DataStoreDashboardViewPreferencesStore(
            PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file }),
        )

    @Test
    fun `defaults to no last view and inactivity return disabled`() = runTest {
        val store = dataStoreStore()

        assertEquals(DashboardViewPreferences(lastViewId = null, inactivityReturnMinutes = 0), store.preferences.first())
    }

    @Test
    fun `persists the last view id and the inactivity minutes`() = runTest {
        val store = dataStoreStore()

        store.setLastViewId("view-2")
        store.setInactivityReturnMinutes(5)

        assertEquals(DashboardViewPreferences(lastViewId = "view-2", inactivityReturnMinutes = 5), store.preferences.first())
    }

    @Test
    fun `unsupported inactivity minutes are stored as disabled`() = runTest {
        val store = dataStoreStore()

        store.setInactivityReturnMinutes(7)

        assertEquals(0, store.preferences.first().inactivityReturnMinutes)
    }

    @Test
    fun `in-memory store behaves the same and counts writes`() = runTest {
        val store = InMemoryDashboardViewPreferencesStore()

        store.setLastViewId("a")
        store.setInactivityReturnMinutes(99)

        assertEquals(DashboardViewPreferences("a", 0), store.preferences.value)
        assertEquals(2, store.writes)
    }

    @Test
    fun `resolveViewId keeps an existing last view and falls back to the first view otherwise`() {
        val layout = DashboardLayout(listOf(DashboardView("a", "A"), DashboardView("b", "B")))

        assertEquals("b", resolveViewId(layout, "b"))
        assertEquals("a", resolveViewId(layout, "gone"))
        assertEquals("a", resolveViewId(layout, null))
        assertNull(resolveViewId(DashboardLayout(emptyList()), "a"))
    }
}
