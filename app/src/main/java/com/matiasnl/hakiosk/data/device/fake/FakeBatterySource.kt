package com.matiasnl.hakiosk.data.device.fake

import com.matiasnl.hakiosk.data.device.discovery.BatterySource
import com.matiasnl.hakiosk.data.device.discovery.BatteryState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeBatterySource(initial: BatteryState = BatteryState(percent = 100, charging = false)) : BatterySource {
    private val _state = MutableStateFlow(initial)
    override val state: Flow<BatteryState> = _state.asStateFlow()

    fun set(value: BatteryState) {
        _state.value = value
    }
}
