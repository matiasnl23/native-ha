package com.matiasnl.hakiosk.ui.remote

import com.matiasnl.hakiosk.data.device.MqttConfig
import com.matiasnl.hakiosk.data.device.MqttConnectionState
import com.matiasnl.hakiosk.data.device.MqttTestResult
import com.matiasnl.hakiosk.data.device.fake.FakeMqttRemoteControl
import com.matiasnl.hakiosk.data.device.fake.InMemoryMqttConfigStore
import com.matiasnl.hakiosk.ui.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** [com.matiasnl.hakiosk.data.device.MqttConfigStore] that only emits once [release] is called. */
private class GatedMqttConfigStore(private val stored: MqttConfig?) : com.matiasnl.hakiosk.data.device.MqttConfigStore {
    private val gate = CompletableDeferred<Unit>()
    override val config: Flow<MqttConfig?> = flow {
        gate.await()
        emit(stored)
    }

    override suspend fun save(config: MqttConfig) {}
    override suspend fun clear() {}

    fun release() = gate.complete(Unit)
}

class BrokerSettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `blank fields cannot be saved`() = runTest {
        val viewModel = BrokerSettingsViewModel(InMemoryMqttConfigStore(), FakeMqttRemoteControl())

        assertFalse(viewModel.uiState.value.canSave)
    }

    @Test
    fun `blank host is rejected on save`() = runTest {
        val configStore = InMemoryMqttConfigStore()
        val viewModel = BrokerSettingsViewModel(configStore, FakeMqttRemoteControl())

        viewModel.onDeviceNameChange("Tablet")
        var saved = false
        viewModel.save { saved = true }

        assertFalse(saved)
        assertTrue(viewModel.uiState.value.hostError)
    }

    @Test
    fun `port out of range is rejected`() = runTest {
        val viewModel = BrokerSettingsViewModel(InMemoryMqttConfigStore(), FakeMqttRemoteControl())

        viewModel.onHostChange("192.168.1.10")
        viewModel.onDeviceNameChange("Tablet")
        viewModel.onPortChange("70000")
        var saved = false
        viewModel.save { saved = true }

        assertFalse(saved)
        assertTrue(viewModel.uiState.value.portError)
    }

    @Test
    fun `valid config is saved and starts remote control`() = runTest {
        val configStore = InMemoryMqttConfigStore()
        val remoteControl = FakeMqttRemoteControl()
        val viewModel = BrokerSettingsViewModel(configStore, remoteControl)

        viewModel.onHostChange("192.168.1.10")
        viewModel.onDeviceNameChange("Tablet cocina")
        var saved = false
        viewModel.save { saved = true }

        assertTrue(saved)
        val config = configStore.config.value
        assertEquals("192.168.1.10", config?.host)
        assertEquals(MqttConfig.DEFAULT_PORT, config?.port)
        assertEquals("Tablet cocina", config?.deviceName)
        assertEquals(MqttConnectionState.Connected, remoteControl.connectionState.value)
    }

    @Test
    fun `enabling TLS switches the default port, disabling switches it back`() = runTest {
        val viewModel = BrokerSettingsViewModel(InMemoryMqttConfigStore(), FakeMqttRemoteControl())

        assertEquals(MqttConfig.DEFAULT_PORT.toString(), viewModel.uiState.value.port)

        viewModel.onUseTlsChange(true)
        assertEquals(MqttConfig.DEFAULT_TLS_PORT.toString(), viewModel.uiState.value.port)

        viewModel.onUseTlsChange(false)
        assertEquals(MqttConfig.DEFAULT_PORT.toString(), viewModel.uiState.value.port)
    }

    @Test
    fun `a custom port is not overwritten when toggling TLS`() = runTest {
        val viewModel = BrokerSettingsViewModel(InMemoryMqttConfigStore(), FakeMqttRemoteControl())

        viewModel.onPortChange("1900")
        viewModel.onUseTlsChange(true)

        assertEquals("1900", viewModel.uiState.value.port)
    }

    @Test
    fun `test connection reports the full unreachable message`() = runTest {
        val message = "Connection refused"
        val viewModel = BrokerSettingsViewModel(
            InMemoryMqttConfigStore(),
            FakeMqttRemoteControl(testResult = MqttTestResult.Unreachable(message)),
        )
        viewModel.onHostChange("broker.local")
        viewModel.onDeviceNameChange("Tablet")

        viewModel.testConnection()

        val status = viewModel.uiState.value.testStatus
        check(status is BrokerTestStatus.Done)
        val result = status.result
        check(result is MqttTestResult.Unreachable)
        assertEquals(message, result.message)
    }

    @Test
    fun `clear removes the stored config`() = runTest {
        val configStore = InMemoryMqttConfigStore(MqttConfig(host = "broker.local", deviceName = "Tablet"))
        val viewModel = BrokerSettingsViewModel(configStore, FakeMqttRemoteControl())

        var cleared = false
        viewModel.clear { cleared = true }

        assertTrue(cleared)
        assertNull(configStore.config.value)
    }

    @Test
    fun `existing config is loaded for editing`() = runTest {
        val configStore = InMemoryMqttConfigStore(
            MqttConfig(host = "broker.local", port = 8883, username = "user", useTls = true, deviceName = "Tablet cocina"),
        )
        val viewModel = BrokerSettingsViewModel(configStore, FakeMqttRemoteControl())

        val state = viewModel.uiState.value
        assertEquals("broker.local", state.host)
        assertEquals("8883", state.port)
        assertEquals("user", state.username)
        assertTrue(state.useTls)
        assertEquals("Tablet cocina", state.deviceName)
        assertTrue(state.isConfigured)
    }

    @Test
    fun `late-arriving stored config does not overwrite fields the user already typed`() = runTest {
        val configStore = GatedMqttConfigStore(MqttConfig(host = "stored.local", deviceName = "Stored"))
        val viewModel = BrokerSettingsViewModel(configStore, FakeMqttRemoteControl())

        viewModel.onHostChange("typed.local")
        viewModel.onDeviceNameChange("Typed")

        configStore.release()

        val state = viewModel.uiState.value
        assertEquals("typed.local", state.host)
        assertEquals("Typed", state.deviceName)
        assertTrue(state.isConfigured)
    }
}
