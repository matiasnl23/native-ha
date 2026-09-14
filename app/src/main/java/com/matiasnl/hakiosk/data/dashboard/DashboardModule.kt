package com.matiasnl.hakiosk.data.dashboard

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Last opened view and the inactivity setting; a file of its own so writing them never re-emits the layout. */
private val Context.dashboardViewPreferencesDataStore by preferencesDataStore(name = "dashboard_view_prefs")

/** Wires dashboard persistence. Owned by the android-ui work stream. */
class DashboardModule(private val context: Context) {
    val layoutStore: DashboardLayoutStore by lazy { DataStoreDashboardLayoutStore(context) }

    val viewPreferencesStore: DashboardViewPreferencesStore by lazy {
        DataStoreDashboardViewPreferencesStore(context.dashboardViewPreferencesDataStore)
    }
}

class InMemoryDashboardLayoutStore(
    initial: DashboardLayout = defaultDashboardLayout(),
) : DashboardLayoutStore {
    private val _layout = MutableStateFlow(initial)
    override val layout: StateFlow<DashboardLayout> = _layout.asStateFlow()

    override suspend fun update(transform: (DashboardLayout) -> DashboardLayout) {
        _layout.update(transform)
    }
}
