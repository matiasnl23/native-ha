package com.matiasnl.hakiosk.data.device.discovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Battery level (rounded to 1%) and charging state, as published to Home Assistant. */
data class BatteryState(val percent: Int, val charging: Boolean)

/** Abstracted so it can be faked in JVM tests; the real implementation needs no permission. */
interface BatterySource {
    /** Emits the current state right away, and again on every change. Never completes. */
    val state: Flow<BatteryState>
}

/**
 * Reads the sticky `ACTION_BATTERY_CHANGED` broadcast. No manifest-registered receiver or permission
 * is needed: registering for a sticky broadcast immediately redelivers the last one, and Android keeps
 * delivering updates to a runtime-registered receiver for as long as the process is alive.
 */
class AndroidBatterySource(context: Context) : BatterySource {
    private val appContext = context.applicationContext

    override val state: Flow<BatteryState> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(intent.toBatteryState())
            }
        }
        val sticky = appContext.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        sticky?.let { trySend(it.toBatteryState()) }
        awaitClose { appContext.unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    private fun Intent.toBatteryState(): BatteryState {
        val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) (level * 100f / scale).roundToInt() else 0
        val status = getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return BatteryState(percent.coerceIn(0, 100), charging)
    }
}
