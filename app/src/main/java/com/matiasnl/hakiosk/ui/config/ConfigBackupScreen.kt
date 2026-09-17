package com.matiasnl.hakiosk.ui.config

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.matiasnl.hakiosk.R
import com.matiasnl.hakiosk.data.config.ConfigBackupSummary
import com.matiasnl.hakiosk.ui.theme.HAKioskTheme

/** What the picker offers to create. Imports accept any type: see [ConfigBackupScreen]. */
private const val CONFIG_BACKUP_MIME_TYPE = "application/json"

@Composable
fun ConfigBackupScreen(
    viewModel: ConfigBackupViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Storage Access Framework: the system picker hands back a single document, so the app needs no
    // storage permission of its own.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(CONFIG_BACKUP_MIME_TYPE),
    ) { uri -> uri?.let { viewModel.export(it.toString()) } }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.onImportFileChosen(it.toString()) }
    }

    ConfigBackupContent(
        uiState = uiState,
        onExport = { exportLauncher.launch(viewModel.suggestedFileName()) },
        // Any type on purpose: providers report a .json file as anything from application/json to
        // application/octet-stream, and a filter that hides the user's own backup would be worse
        // than letting them pick a wrong file, which is validated and refused anyway.
        onImport = { importLauncher.launch(arrayOf("*/*")) },
        onConfirmImport = viewModel::confirmImport,
        onCancelImport = viewModel::cancelImport,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigBackupContent(
    uiState: ConfigBackupUiState,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onConfirmImport: () -> Unit,
    onCancelImport: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.config_backup_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.config_backup_back)) }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.config_backup_intro), style = MaterialTheme.typography.bodyMedium)

            Text(stringResource(R.string.config_backup_includes_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.config_backup_includes), style = MaterialTheme.typography.bodySmall)

            Text(stringResource(R.string.config_backup_excludes_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.config_backup_excludes), style = MaterialTheme.typography.bodySmall)

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Button(onClick = onExport, enabled = !uiState.isBusy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.config_backup_export))
            }

            OutlinedButton(onClick = onImport, enabled = !uiState.isBusy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.config_backup_import))
            }

            StatusText(uiState.status)
        }
    }

    val pendingImport = uiState.pendingImport
    if (pendingImport != null) {
        ImportConfirmDialog(
            summary = pendingImport,
            onConfirm = onConfirmImport,
            onDismiss = onCancelImport,
        )
    }
}

@Composable
private fun StatusText(status: ConfigBackupStatus) {
    val text = when (status) {
        ConfigBackupStatus.Idle -> return
        ConfigBackupStatus.Working -> stringResource(R.string.config_backup_working)
        ConfigBackupStatus.ExportDone -> stringResource(R.string.config_backup_export_done)
        is ConfigBackupStatus.ExportFailed -> when (status.failure) {
            ConfigExportFailure.NotWritten -> stringResource(R.string.config_backup_export_failed)
            ConfigExportFailure.UnreadableLayout -> stringResource(R.string.config_backup_export_failed_layout)
        }
        ConfigBackupStatus.ImportDone -> stringResource(R.string.config_backup_import_done)
        is ConfigBackupStatus.ImportFailed -> when (val failure = status.failure) {
            ConfigImportFailure.Unreadable -> stringResource(R.string.config_backup_import_failed_unreadable)
            ConfigImportFailure.TooLarge -> stringResource(R.string.config_backup_import_failed_too_large)
            ConfigImportFailure.NotABackup -> stringResource(R.string.config_backup_import_failed_not_a_backup)
            ConfigImportFailure.Corrupt -> stringResource(R.string.config_backup_import_failed_corrupt)
            ConfigImportFailure.NotWritten -> stringResource(R.string.config_backup_import_failed_write)
            is ConfigImportFailure.FutureVersion ->
                stringResource(R.string.config_backup_import_failed_future, failure.version)
        }
    }
    val isError = status is ConfigBackupStatus.ImportFailed || status is ConfigBackupStatus.ExportFailed
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

/** Shown once the file has been validated and before anything is written. */
@Composable
private fun ImportConfirmDialog(
    summary: ConfigBackupSummary,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.config_backup_import_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.config_backup_import_confirm_summary,
                        summary.viewCount,
                        summary.tileCount,
                    ),
                )
                Text(
                    stringResource(
                        if (summary.hasBroker) {
                            R.string.config_backup_import_confirm_broker
                        } else {
                            R.string.config_backup_import_confirm_no_broker
                        },
                    ),
                )
                Text(stringResource(R.string.config_backup_import_confirm_origin, summary.appVersionName))
                Text(stringResource(R.string.config_backup_import_confirm_message))
                // The two secrets no file can carry, said before the user commits to the import.
                Text(stringResource(R.string.config_backup_excludes))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.config_backup_import_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.config_backup_cancel)) }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun ConfigBackupScreenPreview() {
    HAKioskTheme {
        ConfigBackupContent(
            uiState = ConfigBackupUiState(),
            onExport = {},
            onImport = {},
            onConfirmImport = {},
            onCancelImport = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ConfigBackupScreenImportedPreview() {
    HAKioskTheme {
        ConfigBackupContent(
            uiState = ConfigBackupUiState(status = ConfigBackupStatus.ImportDone),
            onExport = {},
            onImport = {},
            onConfirmImport = {},
            onCancelImport = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ConfigBackupScreenFailedPreview() {
    HAKioskTheme {
        ConfigBackupContent(
            uiState = ConfigBackupUiState(
                status = ConfigBackupStatus.ImportFailed(ConfigImportFailure.FutureVersion(2)),
            ),
            onExport = {},
            onImport = {},
            onConfirmImport = {},
            onCancelImport = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ConfigBackupScreenConfirmPreview() {
    HAKioskTheme {
        ConfigBackupContent(
            uiState = ConfigBackupUiState(
                pendingImport = ConfigBackupSummary(
                    viewCount = 3,
                    tileCount = 24,
                    hasBroker = true,
                    appVersionName = "1.0.1",
                    exportedAt = "2026-09-17T12:00:00Z",
                ),
            ),
            onExport = {},
            onImport = {},
            onConfirmImport = {},
            onCancelImport = {},
            onBack = {},
        )
    }
}
