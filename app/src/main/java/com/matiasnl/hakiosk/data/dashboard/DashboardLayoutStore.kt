package com.matiasnl.hakiosk.data.dashboard

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The stored layout together with whether it actually came from storage.
 *
 * Rendering a default layout when the persisted one can't be decoded is deliberate (the disk is left
 * untouched, in case a later version can still read it), but it makes the two cases indistinguishable
 * to anyone downstream. [isReadable] is that distinction.
 */
data class StoredDashboardLayout(
    val layout: DashboardLayout,
    /**
     * False when persisted data exists but couldn't be decoded, so [layout] is a default stand-in.
     * Writers must refuse while this is false — persisting the stand-in would destroy the data it
     * stands in for. True for a fresh install, where the default layout is the real current state.
     */
    val isReadable: Boolean = true,
)

interface DashboardLayoutStore {
    /** The stored layout and whether it could be read. See [StoredDashboardLayout]. */
    val stored: Flow<StoredDashboardLayout>

    /** Just the layout, for everything that only renders it. */
    val layout: Flow<DashboardLayout> get() = stored.map { it.layout }

    /** Atomically replaces the stored layout with `transform(current)`. */
    suspend fun update(transform: (DashboardLayout) -> DashboardLayout)
}
