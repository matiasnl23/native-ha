package com.matiasnl.hakiosk.data.dashboard

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Wires dashboard persistence. Owned by the android-ui work stream. */
class DashboardModule(private val context: Context) {
    val layoutStore: DashboardLayoutStore by lazy { DataStoreDashboardLayoutStore(context) }
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
