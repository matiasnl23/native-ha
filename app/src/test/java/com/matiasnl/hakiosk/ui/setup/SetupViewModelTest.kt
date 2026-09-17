package com.matiasnl.hakiosk.ui.setup

import com.matiasnl.hakiosk.data.ha.HaConfigStore
import com.matiasnl.hakiosk.data.ha.HaConnectionState
import com.matiasnl.hakiosk.data.ha.HaConnectionTestResult
import com.matiasnl.hakiosk.data.ha.HaEntity
import com.matiasnl.hakiosk.data.ha.HaRegistry
import com.matiasnl.hakiosk.data.ha.HaRepository
import com.matiasnl.hakiosk.data.ha.HaServerConfig
import com.matiasnl.hakiosk.data.ha.fake.InMemoryHaConfigStore
import com.matiasnl.hakiosk.ui.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * [HaConfigStore] whose [config] flow only emits once [release] is called, to reproduce the race
 * between the async load in [SetupViewModel]'s init and the user editing fields in the meantime.
 */
private class GatedHaConfigStore(private val stored: HaServerConfig?) : HaConfigStore {
    private val gate = CompletableDeferred<Unit>()

    override val config: Flow<HaServerConfig?> = flow {
        gate.await()
        emit(stored)
    }

    override val baseUrl: Flow<String?> = flow {
        gate.await()
        emit(stored?.baseUrl)
    }

    override suspend fun save(config: HaServerConfig) {}
    override suspend fun saveBaseUrl(baseUrl: String) {}
    override suspend fun clear() {}

    fun release() {
        gate.complete(Unit)
    }
}

/** Test double with a scriptable [testConnection] result, since [FakeHaRepository] always succeeds. */
private class ScriptedHaRepository(
    private var testResult: HaConnectionTestResult = HaConnectionTestResult.Success("2026.1.0"),
) : HaRepository {
    private val _connectionState = MutableStateFlow<HaConnectionState>(HaConnectionState.Idle)
    override val connectionState: StateFlow<HaConnectionState> = _connectionState.asStateFlow()

    private val _entities = MutableStateFlow<Map<String, HaEntity>>(emptyMap())
    override val entities: StateFlow<Map<String, HaEntity>> = _entities.asStateFlow()

    override val registry: StateFlow<HaRegistry> = MutableStateFlow(HaRegistry()).asStateFlow()

    var lastTestedConfig: HaServerConfig? = null
        private set

    override fun start() {}
    override fun stop() {}

    override suspend fun callService(
        domain: String,
        service: String,
        entityId: String,
        data: JsonObject,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun testConnection(config: HaServerConfig): HaConnectionTestResult {
        lastTestedConfig = config
        return testResult
    }

    fun setNextTestResult(result: HaConnectionTestResult) {
        testResult = result
    }
}

class SetupViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `blank fields cannot be saved`() = runTest {
        val viewModel = SetupViewModel(ScriptedHaRepository(), InMemoryHaConfigStore())

        assertFalse(viewModel.uiState.value.canSave)
    }

    @Test
    fun `url without scheme is rejected on save`() = runTest {
        val viewModel = SetupViewModel(ScriptedHaRepository(), InMemoryHaConfigStore())

        viewModel.onBaseUrlChange("192.168.1.50:8123")
        viewModel.onTokenChange("token")
        var saved = false
        viewModel.save { saved = true }

        assertFalse(saved)
        assertEquals(UrlValidationError.INVALID_SCHEME, viewModel.uiState.value.urlError)
    }

    @Test
    fun `https url with port is accepted`() = runTest {
        val repository = ScriptedHaRepository()
        val configStore = InMemoryHaConfigStore()
        val viewModel = SetupViewModel(repository, configStore)

        viewModel.onBaseUrlChange("https://homeassistant.local:8443")
        viewModel.onTokenChange("token")
        var saved = false
        viewModel.save { saved = true }

        assertTrue(saved)
        assertNull(viewModel.uiState.value.urlError)
        assertEquals("https://homeassistant.local:8443", configStore.config.value?.baseUrl)
    }

    @Test
    fun `test connection reports invalid token`() = runTest {
        val repository = ScriptedHaRepository()
        repository.setNextTestResult(HaConnectionTestResult.InvalidToken)
        val viewModel = SetupViewModel(repository, InMemoryHaConfigStore())

        viewModel.onBaseUrlChange("https://ha.local")
        viewModel.onTokenChange("bad-token")
        viewModel.testConnection()

        val status = viewModel.uiState.value.testStatus
        check(status is ConnectionTestStatus.Done)
        assertEquals(HaConnectionTestResult.InvalidToken, status.result)
        assertEquals("https://ha.local", repository.lastTestedConfig?.baseUrl)
    }

    @Test
    fun `test connection surfaces the full unreachable message`() = runTest {
        val repository = ScriptedHaRepository()
        val message = "Unable to resolve host \"ha.local\": No address associated with hostname"
        repository.setNextTestResult(HaConnectionTestResult.Unreachable(message))
        val viewModel = SetupViewModel(repository, InMemoryHaConfigStore())

        viewModel.onBaseUrlChange("https://ha.local")
        viewModel.onTokenChange("token")
        viewModel.testConnection()

        val status = viewModel.uiState.value.testStatus
        check(status is ConnectionTestStatus.Done)
        val result = status.result
        check(result is HaConnectionTestResult.Unreachable)
        assertEquals(message, result.message)
    }

    @Test
    fun `save trims trailing slash`() = runTest {
        val configStore = InMemoryHaConfigStore()
        val viewModel = SetupViewModel(ScriptedHaRepository(), configStore)

        viewModel.onBaseUrlChange("https://ha.local:8123/")
        viewModel.onTokenChange("token")
        viewModel.save {}

        assertEquals("https://ha.local:8123", configStore.config.value?.baseUrl)
    }

    @Test
    fun `disconnect clears the stored config`() = runTest {
        val configStore = InMemoryHaConfigStore(HaServerConfig("https://ha.local", "token"))
        val viewModel = SetupViewModel(ScriptedHaRepository(), configStore)

        var disconnected = false
        viewModel.disconnect { disconnected = true }

        assertTrue(disconnected)
        assertNull(configStore.config.value)
    }

    @Test
    fun `a base url restored by a config import is prefilled even without a token`() = runTest {
        val configStore = InMemoryHaConfigStore()
        configStore.saveBaseUrl("https://imported.local:8123")

        val viewModel = SetupViewModel(ScriptedHaRepository(), configStore)

        val state = viewModel.uiState.value
        assertEquals("https://imported.local:8123", state.baseUrl)
        assertEquals("", state.token)
        // There is no usable config until the token is pasted, so nothing to disconnect from yet.
        assertFalse(state.isEditingExisting)
        assertFalse(state.canSave)
    }

    @Test
    fun `existing config is loaded for editing`() = runTest {
        val configStore = InMemoryHaConfigStore(HaServerConfig("https://ha.local", "existing-token"))
        val viewModel = SetupViewModel(ScriptedHaRepository(), configStore)

        val state = viewModel.uiState.value
        assertEquals("https://ha.local", state.baseUrl)
        assertEquals("existing-token", state.token)
        assertTrue(state.isEditingExisting)
    }

    @Test
    fun `late-arriving stored config does not overwrite fields the user already typed`() = runTest {
        val configStore = GatedHaConfigStore(HaServerConfig("https://stored.local", "stored-token"))
        val viewModel = SetupViewModel(ScriptedHaRepository(), configStore)

        // The user starts typing before the store's load (gated) resolves.
        viewModel.onBaseUrlChange("https://typed.local")
        viewModel.onTokenChange("typed-token")

        configStore.release()

        val state = viewModel.uiState.value
        assertEquals("https://typed.local", state.baseUrl)
        assertEquals("typed-token", state.token)
        // We still learned a config exists, so disconnect should be offered.
        assertTrue(state.isEditingExisting)
    }

    @Test
    fun `stored config is applied when it arrives before the user types anything`() = runTest {
        val configStore = GatedHaConfigStore(HaServerConfig("https://stored.local", "stored-token"))
        val viewModel = SetupViewModel(ScriptedHaRepository(), configStore)

        configStore.release()

        val state = viewModel.uiState.value
        assertEquals("https://stored.local", state.baseUrl)
        assertEquals("stored-token", state.token)
        assertTrue(state.isEditingExisting)
    }
}
