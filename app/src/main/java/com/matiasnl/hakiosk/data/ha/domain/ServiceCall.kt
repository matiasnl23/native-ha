package com.matiasnl.hakiosk.data.ha.domain

import com.matiasnl.hakiosk.data.ha.HaRepository
import kotlinx.serialization.json.JsonObject

/**
 * A Home Assistant service to call (`domain.service`) with its data payload, not yet bound to a
 * target entity. Built by the per-domain command objects (e.g. [LightCommands], [AlarmPanelCommands])
 * and dispatched with [HaRepository.call].
 */
data class ServiceCall(
    val domain: String,
    val service: String,
    val data: JsonObject = JsonObject(emptyMap()),
)

/** Calls [call] against [entityId]. Thin wrapper so callers don't spell out domain/service by hand. */
suspend fun HaRepository.call(entityId: String, call: ServiceCall): Result<Unit> =
    callService(call.domain, call.service, entityId, call.data)
