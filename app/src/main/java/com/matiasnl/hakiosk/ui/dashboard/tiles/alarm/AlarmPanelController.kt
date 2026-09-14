package com.matiasnl.hakiosk.ui.dashboard.tiles.alarm

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.matiasnl.hakiosk.data.ha.HaEntity
import com.matiasnl.hakiosk.data.ha.domain.AlarmArmMode
import com.matiasnl.hakiosk.data.ha.domain.AlarmCodeFormat
import com.matiasnl.hakiosk.data.ha.domain.AlarmPanelCapabilities
import com.matiasnl.hakiosk.data.ha.domain.AlarmPanelCommands
import com.matiasnl.hakiosk.data.ha.domain.AlarmPanelState
import com.matiasnl.hakiosk.data.ha.domain.ServiceCall
import com.matiasnl.hakiosk.ui.dashboard.tiles.EntityControlSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Longest code the panel accepts; generous for text codes, far beyond any keypad PIN. */
const val ALARM_CODE_MAX_LENGTH = 32

/** States from which "Desarmar" is offered. */
private val DISARMABLE_STATES = setOf(
    AlarmPanelState.ARMED_HOME,
    AlarmPanelState.ARMED_AWAY,
    AlarmPanelState.ARMED_NIGHT,
    AlarmPanelState.ARMED_VACATION,
    AlarmPanelState.ARMED_CUSTOM_BYPASS,
    AlarmPanelState.PENDING,
    AlarmPanelState.ARMING,
    AlarmPanelState.TRIGGERED,
)

/**
 * State holder of the alarm panel. Collects [entityId] from [source] while [scope] lives (the panel's
 * composition).
 *
 * **The code** lives only in [code], in memory, for as long as this holder exists: it's cleared as soon
 * as an attempt is sent (whatever the outcome) and by [close]. It is never logged, never saved (no
 * saveable state) and never part of any `toString`. A blank code is sent as no `code` key at all.
 */
@Stable
class AlarmPanelController(
    private val entityId: String,
    private val source: EntityControlSource,
    private val scope: CoroutineScope,
    initialEntity: HaEntity? = null,
) {
    var entity: HaEntity? by mutableStateOf(null)
        private set

    var capabilities: AlarmPanelCapabilities? by mutableStateOf(null)
        private set

    val state: AlarmPanelState get() = entity?.let(AlarmPanelState::from) ?: AlarmPanelState.UNKNOWN

    val isMissing: Boolean get() = entity == null

    /** Supported arm modes, in a fixed order; empty unless disarmed. */
    var armModes: List<AlarmArmMode> by mutableStateOf(emptyList())
        private set

    val canDisarm: Boolean get() = state in DISARMABLE_STATES

    /** Arming needs a code: `code_arm_required` and a declared code format. */
    val armCodeRequired: Boolean
        get() = capabilities?.let { it.codeArmRequired && it.codeFormat != AlarmCodeFormat.NONE } == true

    val disarmCodeRequired: Boolean get() = capabilities?.codeRequiredForDisarm == true

    /** The code entry to show for the actions on offer: NONE hides it. */
    val codeInput: AlarmCodeFormat
        get() {
            val needed = (armModes.isNotEmpty() && armCodeRequired) || (canDisarm && disarmCodeRequired)
            return if (needed) capabilities?.codeFormat ?: AlarmCodeFormat.NONE else AlarmCodeFormat.NONE
        }

    var code: String by mutableStateOf("")
        private set

    /** True while an arm/disarm call is in flight. */
    var isBusy: Boolean by mutableStateOf(false)
        private set

    /** HA's message for the last failed call. */
    var error: String? by mutableStateOf(null)
        private set

    /** True once a call succeeded: the panel should close. */
    var isFinished: Boolean by mutableStateOf(false)
        private set

    init {
        if (initialEntity != null) onEntity(initialEntity)
        scope.launch { source.entity(entityId).collect { onEntity(it) } }
    }

    fun onDigit(digit: Char) {
        if (digit !in '0'..'9' || code.length >= ALARM_CODE_MAX_LENGTH) return
        code += digit
    }

    fun onDeleteDigit() {
        if (code.isNotEmpty()) code = code.dropLast(1)
    }

    fun onClearCode() {
        code = ""
    }

    /** Text-format codes, from a password field. */
    fun onCodeChange(text: String) {
        code = text.take(ALARM_CODE_MAX_LENGTH)
    }

    fun arm(mode: AlarmArmMode) {
        if (mode !in armModes) return
        submit(codeNeeded = armCodeRequired) { code -> AlarmPanelCommands.arm(mode, code) }
    }

    fun disarm() {
        if (!canDisarm) return
        submit(codeNeeded = disarmCodeRequired) { code -> AlarmPanelCommands.disarm(code) }
    }

    /** The panel is closing: forget the code and any error. */
    fun close() {
        code = ""
        error = null
    }

    private inline fun submit(codeNeeded: Boolean, build: (code: String?) -> ServiceCall) {
        if (isBusy) return
        val call = build(if (codeNeeded) code else null)
        code = ""
        error = null
        isBusy = true
        scope.launch {
            val result = source.call(entityId, call)
            isBusy = false
            val failure = result.exceptionOrNull()
            if (failure == null) {
                isFinished = true
            } else {
                error = failure.message ?: "${call.domain}.${call.service}"
            }
        }
    }

    private fun onEntity(new: HaEntity?) {
        if (new != null && new === entity) return
        entity = new
        val capabilities = new?.let(AlarmPanelCapabilities::from)
        this.capabilities = capabilities
        val modes = if (capabilities != null && state == AlarmPanelState.DISARMED) {
            AlarmArmMode.entries.filter { it in capabilities.supportedArmModes }
        } else {
            emptyList()
        }
        if (modes != armModes) armModes = modes
    }
}
