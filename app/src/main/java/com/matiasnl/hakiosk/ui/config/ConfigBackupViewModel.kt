package com.matiasnl.hakiosk.ui.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.matiasnl.hakiosk.data.config.ConfigBackup
import com.matiasnl.hakiosk.data.config.ConfigBackupDecodeResult
import com.matiasnl.hakiosk.data.config.ConfigBackupFiles
import com.matiasnl.hakiosk.data.config.ConfigBackupJsonMapper
import com.matiasnl.hakiosk.data.config.ConfigBackupReadResult
import com.matiasnl.hakiosk.data.config.ConfigBackupRepository
import com.matiasnl.hakiosk.data.config.ConfigBackupSummary
import com.matiasnl.hakiosk.data.config.suggestedBackupFileName
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why a chosen file was refused. The stored configuration is untouched in every one of these. */
sealed interface ConfigImportFailure {
    data object Unreadable : ConfigImportFailure
    data object TooLarge : ConfigImportFailure
    data object NotABackup : ConfigImportFailure
    data class FutureVersion(val version: Int) : ConfigImportFailure
    data object Corrupt : ConfigImportFailure

    /** The file was fine, but writing the configuration back failed partway. */
    data object NotWritten : ConfigImportFailure
}

sealed interface ConfigBackupStatus {
    data object Idle : ConfigBackupStatus

    /** Reading or writing. Both actions stay disabled meanwhile. */
    data object Working : ConfigBackupStatus
    data object ExportDone : ConfigBackupStatus
    data object ExportFailed : ConfigBackupStatus
    data object ImportDone : ConfigBackupStatus
    data class ImportFailed(val failure: ConfigImportFailure) : ConfigBackupStatus
}

data class ConfigBackupUiState(
    val status: ConfigBackupStatus = ConfigBackupStatus.Idle,
    /**
     * Set once a chosen file has been read and fully validated, and cleared when the user answers.
     * Nothing has been written while this is non-null: it's what the confirmation dialog describes.
     */
    val pendingImport: ConfigBackupSummary? = null,
) {
    val isBusy: Boolean get() = status == ConfigBackupStatus.Working
}

/**
 * Drives the export/import screen. The file itself is picked by the system file picker, so this only
 * ever sees the address the picker returned (as a string, see [ConfigBackupFiles]).
 *
 * The import is two steps on purpose: choosing a file only reads and validates it, and nothing is
 * written until [confirmImport]. A file that is unreadable, from another app, from a newer version
 * or broken never reaches [ConfigBackupRepository.apply] at all.
 */
class ConfigBackupViewModel(
    private val repository: ConfigBackupRepository,
    private val files: ConfigBackupFiles,
    private val today: () -> LocalDate = LocalDate::now,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConfigBackupUiState())
    val uiState: StateFlow<ConfigBackupUiState> = _uiState.asStateFlow()

    /** Validated content behind [ConfigBackupUiState.pendingImport]; never shown, only applied. */
    private var pendingBackup: ConfigBackup? = null

    /** Name the system file picker offers for a new file, e.g. `hakiosk-config-2026-09-17.json`. */
    fun suggestedFileName(): String = suggestedBackupFileName(today())

    fun export(uri: String) {
        if (_uiState.value.isBusy) return
        startWorking()
        viewModelScope.launch {
            // read() suspends until the stores have really loaded, so this can never write a
            // placeholder over the user's configuration into the file.
            val content = runCatching { ConfigBackupJsonMapper.encode(repository.read()) }.getOrNull()
            val written = content != null && files.write(uri, content)
            _uiState.update {
                it.copy(status = if (written) ConfigBackupStatus.ExportDone else ConfigBackupStatus.ExportFailed)
            }
        }
    }

    /** Reads and validates the chosen file. On success the user still has to [confirmImport]. */
    fun onImportFileChosen(uri: String) {
        if (_uiState.value.isBusy) return
        startWorking()
        viewModelScope.launch {
            when (val read = files.read(uri)) {
                ConfigBackupReadResult.TooLarge -> failImport(ConfigImportFailure.TooLarge)
                ConfigBackupReadResult.Unreadable -> failImport(ConfigImportFailure.Unreadable)
                is ConfigBackupReadResult.Success -> when (val decoded = ConfigBackupJsonMapper.decode(read.text)) {
                    is ConfigBackupDecodeResult.Success -> {
                        pendingBackup = decoded.backup
                        _uiState.update {
                            it.copy(status = ConfigBackupStatus.Idle, pendingImport = decoded.backup.summary())
                        }
                    }

                    ConfigBackupDecodeResult.NotABackup -> failImport(ConfigImportFailure.NotABackup)
                    ConfigBackupDecodeResult.Corrupt -> failImport(ConfigImportFailure.Corrupt)
                    is ConfigBackupDecodeResult.FutureVersion ->
                        failImport(ConfigImportFailure.FutureVersion(decoded.version))
                }
            }
        }
    }

    /** Applies the file the user just confirmed. Overwrites the stored configuration. */
    fun confirmImport() {
        val backup = pendingBackup ?: return
        startWorking()
        viewModelScope.launch {
            val applied = runCatching { repository.apply(backup) }.isSuccess
            pendingBackup = null
            _uiState.update {
                it.copy(
                    status = if (applied) {
                        ConfigBackupStatus.ImportDone
                    } else {
                        ConfigBackupStatus.ImportFailed(ConfigImportFailure.NotWritten)
                    },
                )
            }
        }
    }

    /** Drops the validated file without writing any of it. */
    fun cancelImport() {
        pendingBackup = null
        _uiState.update { it.copy(status = ConfigBackupStatus.Idle, pendingImport = null) }
    }

    private fun startWorking() {
        _uiState.update { it.copy(status = ConfigBackupStatus.Working, pendingImport = null) }
    }

    private fun failImport(failure: ConfigImportFailure) {
        pendingBackup = null
        _uiState.update { it.copy(status = ConfigBackupStatus.ImportFailed(failure), pendingImport = null) }
    }

    companion object {
        fun factory(repository: ConfigBackupRepository, files: ConfigBackupFiles) = viewModelFactory {
            initializer { ConfigBackupViewModel(repository, files) }
        }
    }
}
