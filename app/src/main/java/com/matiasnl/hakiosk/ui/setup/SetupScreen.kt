package com.matiasnl.hakiosk.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.matiasnl.hakiosk.R
import com.matiasnl.hakiosk.data.ha.HaConnectionTestResult
import com.matiasnl.hakiosk.ui.theme.HAKioskTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    viewModel: SetupViewModel,
    canGoBack: Boolean,
    onSaved: () -> Unit,
    onDisconnected: () -> Unit,
    onBack: () -> Unit,
    onOpenRemoteControl: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SetupContent(
        uiState = uiState,
        canGoBack = canGoBack,
        onBaseUrlChange = viewModel::onBaseUrlChange,
        onTokenChange = viewModel::onTokenChange,
        onToggleTokenVisibility = viewModel::toggleTokenVisibility,
        onTestConnection = viewModel::testConnection,
        onSave = { viewModel.save(onSaved) },
        onDisconnect = { viewModel.disconnect(onDisconnected) },
        onBack = onBack,
        onOpenRemoteControl = onOpenRemoteControl,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupContent(
    uiState: SetupUiState,
    canGoBack: Boolean,
    onBaseUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onToggleTokenVisibility: () -> Unit,
    onTestConnection: () -> Unit,
    onSave: () -> Unit,
    onDisconnect: () -> Unit,
    onBack: () -> Unit,
    onOpenRemoteControl: () -> Unit = {},
) {
    var showDisconnectConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setup_title)) },
                navigationIcon = {
                    if (canGoBack) {
                        TextButton(onClick = onBack) { Text(stringResource(R.string.setup_back)) }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = uiState.baseUrl,
                onValueChange = onBaseUrlChange,
                label = { Text(stringResource(R.string.setup_base_url_label)) },
                placeholder = { Text(stringResource(R.string.setup_base_url_placeholder)) },
                singleLine = true,
                isError = uiState.urlError != null,
                supportingText = {
                    val message = when (uiState.urlError) {
                        UrlValidationError.BLANK -> stringResource(R.string.setup_url_error_blank)
                        UrlValidationError.INVALID_SCHEME -> stringResource(R.string.setup_url_error_scheme)
                        null -> null
                    }
                    if (message != null) Text(message)
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = uiState.token,
                onValueChange = onTokenChange,
                label = { Text(stringResource(R.string.setup_token_label)) },
                singleLine = true,
                isError = uiState.tokenBlankError,
                supportingText = {
                    if (uiState.tokenBlankError) Text(stringResource(R.string.setup_token_error_blank))
                },
                visualTransformation = if (uiState.tokenVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    TextButton(onClick = onToggleTokenVisibility) {
                        Text(
                            if (uiState.tokenVisible) {
                                stringResource(R.string.setup_hide_token)
                            } else {
                                stringResource(R.string.setup_show_token)
                            }
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            ConnectionTestStatusText(uiState.testStatus)

            OutlinedButton(onClick = onTestConnection, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.setup_test_connection))
            }

            Button(onClick = onSave, enabled = uiState.canSave, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.setup_save))
            }

            if (uiState.isEditingExisting) {
                OutlinedButton(
                    onClick = { showDisconnectConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.setup_disconnect))
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.setup_remote_control_section), style = MaterialTheme.typography.titleSmall)
            OutlinedButton(onClick = onOpenRemoteControl, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.remote_broker_title))
            }
        }
    }

    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            title = { Text(stringResource(R.string.setup_disconnect_confirm_title)) },
            text = { Text(stringResource(R.string.setup_disconnect_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDisconnectConfirm = false
                        onDisconnect()
                    },
                ) {
                    Text(stringResource(R.string.setup_disconnect_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirm = false }) {
                    Text(stringResource(R.string.setup_cancel))
                }
            },
        )
    }
}

@Composable
private fun ConnectionTestStatusText(status: ConnectionTestStatus) {
    val text = when (status) {
        ConnectionTestStatus.Idle -> return
        ConnectionTestStatus.Testing -> stringResource(R.string.setup_testing)
        is ConnectionTestStatus.Done -> when (val result = status.result) {
            is HaConnectionTestResult.Success -> stringResource(R.string.setup_test_success, result.haVersion)
            HaConnectionTestResult.InvalidToken -> stringResource(R.string.setup_test_invalid_token)
            is HaConnectionTestResult.Unreachable -> stringResource(R.string.setup_test_unreachable, result.message)
        }
    }
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
}

@Preview(showBackground = true)
@Composable
private fun SetupScreenPreview() {
    HAKioskTheme {
        SetupContent(
            uiState = SetupUiState(baseUrl = "http://192.168.1.50:8123", token = "abc123"),
            canGoBack = false,
            onBaseUrlChange = {},
            onTokenChange = {},
            onToggleTokenVisibility = {},
            onTestConnection = {},
            onSave = {},
            onDisconnect = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SetupScreenEditingPreview() {
    HAKioskTheme {
        SetupContent(
            uiState = SetupUiState(
                baseUrl = "http://192.168.1.50:8123",
                token = "abc123",
                isEditingExisting = true,
                testStatus = ConnectionTestStatus.Done(HaConnectionTestResult.Success("2026.1.0")),
            ),
            canGoBack = true,
            onBaseUrlChange = {},
            onTokenChange = {},
            onToggleTokenVisibility = {},
            onTestConnection = {},
            onSave = {},
            onDisconnect = {},
            onBack = {},
        )
    }
}
