package com.matiasnl.hakiosk.data.dashboard

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Legacy (pre-multi-view) persisted tile: entityId + optional label, order = position in the list. */
@Serializable
private data class LegacyDashboardTile(val entityId: String, val label: String? = null)

/** Persisted JSON envelope for [DashboardLayout]. [version] lets a future app version recognize and
 * migrate a format it no longer writes by default; there's only one format so far. */
@Serializable
private data class PersistedDashboardLayout(
    val version: Int = CURRENT_VERSION,
    val views: List<DashboardView>,
)

private const val CURRENT_VERSION = 1

/** What [DashboardLayoutJsonMapper.decode] found, and whether the store should persist it. */
data class DecodedDashboardLayout(
    val layout: DashboardLayout,
    /**
     * True only when a legacy-format migration happened: the store should write [layout] back and
     * drop the legacy key. Never true for corrupt/unreadable new-format data — that is left on disk
     * untouched (falls back to a default layout in memory only) until the user saves something, in
     * case it's actually recoverable by a future version of the app.
     */
    val shouldPersist: Boolean,
)

/**
 * Pure JSON <-> [DashboardLayout] mapping: encoding, decoding, the migration from the pre-multi-view
 * single tile list, and the corrupt-data fallback. Kept free of Android/DataStore types so it can be
 * unit-tested on the plain JVM; [DataStoreDashboardLayoutStore] is a thin wrapper around it.
 */
object DashboardLayoutJsonMapper {
    // encodeDefaults stays off (the library default), so default-valued fields such as a DEFAULT tap
    // action are never written. coerceInputValues makes an enum value this version doesn't know (written
    // by a newer app) fall back to the field's default instead of failing the whole layout.
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun encode(layout: DashboardLayout): String =
        json.encodeToString(PersistedDashboardLayout(views = layout.views))

    /**
     * @param newFormatJson the current `dashboard_layout_json` value, if any.
     * @param legacyTilesJson the pre-multi-view `dashboard_tiles_json` value, if any.
     */
    fun decode(
        newFormatJson: String?,
        legacyTilesJson: String?,
        idProvider: DashboardIdProvider = UuidDashboardIdProvider,
    ): DecodedDashboardLayout {
        if (newFormatJson != null) {
            val layout = runCatching { json.decodeFromString<PersistedDashboardLayout>(newFormatJson) }
                .getOrNull()
                ?.takeIf { it.views.isNotEmpty() }
                ?.let { DashboardLayout(it.views) }
            return DecodedDashboardLayout(layout ?: defaultDashboardLayout(), shouldPersist = false)
        }
        if (legacyTilesJson != null) {
            val legacyTiles = runCatching { json.decodeFromString<List<LegacyDashboardTile>>(legacyTilesJson) }.getOrNull()
            if (legacyTiles != null) {
                val migratedTiles = legacyTiles.map { legacy ->
                    newDashboardTile(TileContent.Entity(legacy.entityId, legacy.label), idProvider = idProvider)
                }
                val view = DashboardView(id = idProvider.newId(), name = PRINCIPAL_VIEW_NAME, tiles = migratedTiles)
                return DecodedDashboardLayout(DashboardLayout(views = listOf(view)), shouldPersist = true)
            }
        }
        return DecodedDashboardLayout(defaultDashboardLayout(), shouldPersist = false)
    }
}
