package com.matiasnl.hakiosk.data.ha

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * Real-time view of Home Assistant for the UI. Implementations hide HTTP/WebSocket details:
 * the UI only observes flows and calls services.
 */
interface HaRepository {
    val connectionState: StateFlow<HaConnectionState>

    /** Latest state of every entity, keyed by entity id. Empty until the first sync completes. */
    val entities: StateFlow<Map<String, HaEntity>>

    /** Connects using the stored config and keeps the connection alive (reconnecting with backoff). */
    fun start()

    fun stop()

    /**
     * Calls a Home Assistant service, e.g. `callService("light", "toggle", "light.kitchen")`.
     * State changes arrive through [entities]; the result only reports whether the call was accepted.
     */
    suspend fun callService(
        domain: String,
        service: String,
        entityId: String,
        data: JsonObject = JsonObject(emptyMap()),
    ): Result<Unit>

    /** Validates a config without storing it (used by the setup screen). */
    suspend fun testConnection(config: HaServerConfig): HaConnectionTestResult
}

/** Persists the server config. The token must be stored encrypted and never logged. */
interface HaConfigStore {
    val config: Flow<HaServerConfig?>

    suspend fun save(config: HaServerConfig)

    suspend fun clear()
}
