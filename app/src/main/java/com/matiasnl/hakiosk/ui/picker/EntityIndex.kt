package com.matiasnl.hakiosk.ui.picker

import com.matiasnl.hakiosk.data.ha.HaEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Search-relevant fields of one entity, with lowercase keys precomputed once per index build. */
internal class EntityIndexItem(val entityId: String, val friendlyName: String, val domain: String) {
    val nameKey: String = friendlyName.lowercase()
    val idKey: String = entityId.lowercase()
}

/** Entities sorted by name, plus lookups derived from them. Compared by identity, never by content. */
internal class EntityIndex(val items: List<EntityIndexItem>) {
    val byId: Map<String, EntityIndexItem> = items.associateBy { it.entityId }
    val domains: List<String> = items.mapTo(HashSet()) { it.domain }.sorted()
}

/**
 * Maps a raw entity-map flow (real installs have thousands of entities whose states change every
 * second) to an [EntityIndex] flow that only emits a new instance when entities are added, removed
 * or renamed. State-only changes (a light toggling, a sensor value ticking) keep emitting the same
 * (`===`) instance, so a `combine()` built on top of this (and any Compose state derived from it)
 * skips recomputing/recomposing on every unrelated state update.
 *
 * Each call keeps its own private "last index" cursor, so independent callers (e.g. the entity
 * picker and the dashboard editor) each get their own stable stream from the same source flow.
 */
internal fun entityIndexFlow(entities: Flow<Map<String, HaEntity>>): Flow<EntityIndex> {
    var lastIndex = EntityIndex(emptyList())
    return entities
        .map { snapshot ->
            val previous = lastIndex
            val unchanged = previous.items.size == snapshot.size &&
                previous.items.all { item -> snapshot[item.entityId]?.friendlyName == item.friendlyName }
            if (unchanged) {
                previous
            } else {
                val items = snapshot.values
                    .map { EntityIndexItem(it.entityId, it.friendlyName, it.domain) }
                    .sortedBy { it.nameKey }
                EntityIndex(items).also { lastIndex = it }
            }
        }
        .distinctUntilChanged { old, new -> old === new }
}
