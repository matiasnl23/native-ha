package com.matiasnl.hakiosk.ui.remote

import com.matiasnl.hakiosk.data.dashboard.DashboardLayout
import com.matiasnl.hakiosk.data.dashboard.TileContent
import com.matiasnl.hakiosk.data.device.CameraOption
import com.matiasnl.hakiosk.data.device.ViewOption
import com.matiasnl.hakiosk.data.ha.HaEntity

private const val CAMERA_DOMAIN = "camera"

/** One [ViewOption] per view of [layout], in order, for the "Vista" select entity. */
fun viewOptionsFrom(layout: DashboardLayout): List<ViewOption> =
    layout.views.map { view -> ViewOption(view.id, view.name) }

/**
 * Camera-domain entity tiles across every view of [layout], de-duplicated by entity id (first
 * occurrence wins) and named from the tile's label override, falling back to [entities]' friendly
 * name and then the entity id. For the "Cámara en pantalla" select entity.
 */
fun cameraOptionsFrom(layout: DashboardLayout, entities: Map<String, HaEntity>): List<CameraOption> {
    val seen = HashSet<String>()
    val result = mutableListOf<CameraOption>()
    for (view in layout.views) {
        for (tile in view.tiles) {
            val content = tile.content
            if (content !is TileContent.Entity) continue
            if (content.entityId.substringBefore('.') != CAMERA_DOMAIN) continue
            if (!seen.add(content.entityId)) continue
            val name = content.label ?: entities[content.entityId]?.friendlyName ?: content.entityId
            result += CameraOption(content.entityId, name)
        }
    }
    return result
}
