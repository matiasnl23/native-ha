package com.matiasnl.hakiosk.data.device.discovery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * A command from the JSON topic (`hakiosk/<deviceId>/command`), for Home Assistant automations that
 * don't want to drive one of the per-entity `.../set` topics. [ShowView.viewIdOrName] is resolved
 * against the current view catalog by `RemoteControlPublisher` (accepts either, see `docs/MVP3-MQTT.md`).
 */
sealed interface JsonCommand {
    data class OpenCamera(val entityId: String, val closeAfterSeconds: Int?) : JsonCommand
    data object CloseCamera : JsonCommand
    data class ShowView(val viewIdOrName: String) : JsonCommand
    data object MainView : JsonCommand
    data class Screen(val on: Boolean) : JsonCommand
    data class Brightness(val percent: Int) : JsonCommand
    data object Reload : JsonCommand
}

/** Parses [JsonCommand]s. Never throws: malformed or unknown payloads parse to null. */
object JsonCommandParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(payload: String): JsonCommand? = runCatching {
        val obj = json.parseToJsonElement(payload) as? JsonObject ?: return@runCatching null
        when (obj.stringOrNull("command")) {
            "open_camera" -> {
                val entityId = obj.stringOrNull("entity_id")?.takeIf { it.isNotBlank() } ?: return@runCatching null
                // A present but non-numeric (or non-primitive) close_after is treated as absent —
                // falls back to the "cerrar cámara tras" default — instead of discarding the whole command.
                JsonCommand.OpenCamera(entityId, obj.intOrNull("close_after"))
            }
            "close_camera" -> JsonCommand.CloseCamera
            "show_view" -> obj.stringOrNull("view")?.takeIf { it.isNotBlank() }?.let(JsonCommand::ShowView)
            "main_view" -> JsonCommand.MainView
            "screen" -> obj["on"]?.jsonPrimitive?.booleanOrNull?.let(JsonCommand::Screen)
            "brightness" -> obj["value"]?.jsonPrimitive?.intOrNull?.let(JsonCommand::Brightness)
            "reload" -> JsonCommand.Reload
            else -> null
        }
    }.getOrNull()

    private fun JsonObject.stringOrNull(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    /** Null both when [key] is absent and when its value isn't a number (including a non-primitive). */
    private fun JsonObject.intOrNull(key: String): Int? = runCatching { this[key]?.jsonPrimitive?.intOrNull }.getOrNull()
}
