package com.matiasnl.hakiosk.data.dashboard

import kotlinx.coroutines.flow.Flow

interface DashboardLayoutStore {
    val layout: Flow<DashboardLayout>

    /** Atomically replaces the stored layout with `transform(current)`. */
    suspend fun update(transform: (DashboardLayout) -> DashboardLayout)
}
