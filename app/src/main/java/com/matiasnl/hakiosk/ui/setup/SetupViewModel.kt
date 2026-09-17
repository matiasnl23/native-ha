package com.matiasnl.hakiosk.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.matiasnl.hakiosk.data.ha.HaConfigStore
import com.matiasnl.hakiosk.data.ha.HaConnectionTestResult
import com.matiasnl.hakiosk.data.ha.HaRepository
import com.matiasnl.hakiosk.data.ha.HaServerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why the base URL entered by the user cannot be saved yet. */
enum class UrlValidationError {
    BLANK,
    INVALID_SCHEME,
}

sealed interface ConnectionTestStatus {
    data object Idle : ConnectionTestStatus
    data object Testing : ConnectionTestStatus
    data class Done(val result: HaConnectionTestResult) : ConnectionTestStatus
}

data class SetupUiState(
    val baseUrl: String = "",
    val token: String = "",
    val tokenVisible: Boolean = false,
    val urlError: UrlValidationError? = null,
    val tokenBlankError: Boolean = false,
    val testStatus: ConnectionTestStatus = ConnectionTestStatus.Idle,
    /** True once an existing config was loaded, i.e. this is an edit rather than the first setup. */
    val isEditingExisting: Boolean = false,
) {
    val canSave: Boolean get() = baseUrl.isNotBlank() && token.isNotBlank()
}

/**
 * Drives the setup screen: editing the server URL/token, testing the connection, and
 * saving (or clearing) the stored [HaServerConfig].
 */
class SetupViewModel(
    private val haRepository: HaRepository,
    private val haConfigStore: HaConfigStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    /**
     * True once the user has typed into either field. The stored config loads asynchronously
     * (init below); if it arrives after the user already started typing, we must not clobber
     * what they entered.
     */
    private var fieldsTouched = false

    init {
        viewModelScope.launch {
            val config = haConfigStore.config.first()
            if (config != null) {
                _uiState.update {
                    if (fieldsTouched) {
                        // Keep whatever the user already typed, but we now know a config exists
                        // so e.g. the disconnect option should still be offered.
                        it.copy(isEditingExisting = true)
                    } else {
                        it.copy(baseUrl = config.baseUrl, token = config.token, isEditingExisting = true)
                    }
                }
                return@launch
            }
            // No usable config, yet a base URL may still be stored on its own: a config import
            // restores it without the token, which can't be exported. Prefilling it leaves only the
            // token to paste. Not an "existing" config: there is nothing to disconnect from yet.
            val storedBaseUrl = haConfigStore.baseUrl.first() ?: return@launch
            _uiState.update { if (fieldsTouched) it else it.copy(baseUrl = storedBaseUrl) }
        }
    }

    fun onBaseUrlChange(value: String) {
        fieldsTouched = true
        _uiState.update { it.copy(baseUrl = value, urlError = null, testStatus = ConnectionTestStatus.Idle) }
    }

    fun onTokenChange(value: String) {
        fieldsTouched = true
        _uiState.update { it.copy(token = value, tokenBlankError = false, testStatus = ConnectionTestStatus.Idle) }
    }

    fun toggleTokenVisibility() {
        _uiState.update { it.copy(tokenVisible = !it.tokenVisible) }
    }

    fun testConnection() {
        val state = _uiState.value
        val urlError = validateUrl(state.baseUrl)
        if (urlError != null || state.token.isBlank()) {
            _uiState.update { it.copy(urlError = urlError, tokenBlankError = state.token.isBlank()) }
            return
        }
        _uiState.update { it.copy(testStatus = ConnectionTestStatus.Testing) }
        viewModelScope.launch {
            val result = haRepository.testConnection(HaServerConfig(normalizeUrl(state.baseUrl), state.token))
            _uiState.update { it.copy(testStatus = ConnectionTestStatus.Done(result)) }
        }
    }

    /** Persists the current fields and invokes [onSaved] once the store confirms the write. */
    fun save(onSaved: () -> Unit) {
        val state = _uiState.value
        val urlError = validateUrl(state.baseUrl)
        if (urlError != null || state.token.isBlank()) {
            _uiState.update { it.copy(urlError = urlError, tokenBlankError = state.token.isBlank()) }
            return
        }
        viewModelScope.launch {
            haConfigStore.save(HaServerConfig(normalizeUrl(state.baseUrl), state.token))
            onSaved()
        }
    }

    /** Clears the stored config and invokes [onDisconnected]. */
    fun disconnect(onDisconnected: () -> Unit) {
        viewModelScope.launch {
            haConfigStore.clear()
            onDisconnected()
        }
    }

    companion object {
        fun validateUrl(url: String): UrlValidationError? = when {
            url.isBlank() -> UrlValidationError.BLANK
            !url.startsWith("http://") && !url.startsWith("https://") -> UrlValidationError.INVALID_SCHEME
            else -> null
        }

        private fun normalizeUrl(url: String): String = url.trim().trimEnd('/')

        fun factory(haRepository: HaRepository, haConfigStore: HaConfigStore) = viewModelFactory {
            initializer { SetupViewModel(haRepository, haConfigStore) }
        }
    }
}
