package com.matiasnl.hakiosk.ui.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks the epoch millis of the last user interaction for [com.matiasnl.hakiosk.data.device.DeviceUiState]
 * reporting, throttled so frequent touches don't spam MQTT publishes: at most one update every
 * [throttleMillis], except the very first touch ever and any touch reported with `forceImmediate`
 * (used for the first touch after the screen was off/idle), which always update right away.
 */
class LastInteractionThrottle(
    private val clock: () -> Long,
    private val throttleMillis: Long = DEFAULT_THROTTLE_MILLIS,
) {
    private val _lastInteractionEpochMillis = MutableStateFlow<Long?>(null)
    val lastInteractionEpochMillis: StateFlow<Long?> = _lastInteractionEpochMillis.asStateFlow()

    private var lastReportedAt: Long? = null

    fun onActivity(forceImmediate: Boolean = false) {
        val now = clock()
        val last = lastReportedAt
        if (forceImmediate || last == null || now - last >= throttleMillis) {
            lastReportedAt = now
            _lastInteractionEpochMillis.value = now
        }
    }

    companion object {
        const val DEFAULT_THROTTLE_MILLIS = 10_000L
    }
}
