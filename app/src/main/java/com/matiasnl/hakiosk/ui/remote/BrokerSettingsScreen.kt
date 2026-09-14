package com.matiasnl.hakiosk.ui.remote

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.matiasnl.hakiosk.data.device.MqttConnectionState
import com.matiasnl.hakiosk.data.device.MqttTestResult
import com.matiasnl.hakiosk.ui.theme.HAKioskTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrokerSettingsScreen(
    viewModel: BrokerSettingsViewModel,
    onSaved: () -> Unit,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val notificationPermissionLauncher = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) viewModel.onNotificationPermissionDenied()
        }
    } else {
        null
    }

    BrokerSettingsContent(
        uiState = uiState,
        onHostChange = viewModel::onHostChange,
        onPortChange = viewModel::onPortChange,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onTogglePasswordVisibility = viewModel::togglePasswordVisibility,
        onUseTlsChange = viewModel::onUseTlsChange,
        onDeviceNameChange = viewModel::onDeviceNameChange,
        onTestConnection = viewModel::testConnection,
        onSave = {
            notificationPermissionLauncher?.launch(Manifest.permission.POST_NOTIFICATIONS)
            viewModel.save(onSaved)
        },
        onDisable = { viewModel.clear(onSaved) },
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrokerSettingsContent(
    uiState: BrokerSettingsUiState,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onUseTlsChange: (Boolean) -> Unit,
    onDeviceNameChange: (String) -> Unit,
    onTestConnection: () -> Unit,
    onSave: () -> Unit,
    onDisable: () -> Unit,
    onBack: () -> Unit,
) {
    var showDisableConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.remote_broker_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.remote_broker_back)) }
                },
                // Always reachable, even when the form doesn't fit (tablet in landscape, keyboard open).
                actions = {
                    TextButton(onClick = onSave, enabled = uiState.canSave) {
                        Text(stringResource(R.string.remote_broker_save))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.remote_broker_status_label, connectionStateText(uiState.connectionState)),
                style = MaterialTheme.typography.bodyMedium,
            )

            OutlinedTextField(
                value = uiState.host,
                onValueChange = onHostChange,
                label = { Text(stringResource(R.string.remote_broker_host_label)) },
                placeholder = { Text(stringResource(R.string.remote_broker_host_placeholder)) },
                singleLine = true,
                isError = uiState.hostError,
                supportingText = { if (uiState.hostError) Text(stringResource(R.string.remote_broker_host_error)) },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = uiState.port,
                onValueChange = onPortChange,
                label = { Text(stringResource(R.string.remote_broker_port_label)) },
                singleLine = true,
                isError = uiState.portError,
                supportingText = { if (uiState.portError) Text(stringResource(R.string.remote_broker_port_error)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = uiState.username,
                onValueChange = onUsernameChange,
                label = { Text(stringResource(R.string.remote_broker_username_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = uiState.password,
                onValueChange = onPasswordChange,
                label = { Text(stringResource(R.string.remote_broker_password_label)) },
                singleLine = true,
                visualTransformation = if (uiState.passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    TextButton(onClick = onTogglePasswordVisibility) {
                        Text(
                            stringResource(
                                if (uiState.passwordVisible) R.string.remote_broker_hide_password else R.string.remote_broker_show_password,
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.remote_broker_tls_label), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = uiState.useTls, onCheckedChange = onUseTlsChange)
            }

            OutlinedTextField(
                value = uiState.deviceName,
                onValueChange = onDeviceNameChange,
                label = { Text(stringResource(R.string.remote_broker_device_name_label)) },
                singleLine = true,
                isError = uiState.deviceNameError,
                supportingText = { if (uiState.deviceNameError) Text(stringResource(R.string.remote_broker_device_name_error)) },
                modifier = Modifier.fillMaxWidth(),
            )

            BrokerTestStatusText(uiState.testStatus)

            if (uiState.notificationPermissionDenied) {
                Text(
                    text = stringResource(R.string.remote_broker_notification_permission_denied),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            OutlinedButton(onClick = onTestConnection, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.remote_broker_test_connection))
            }

            Button(onClick = onSave, enabled = uiState.canSave, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.remote_broker_save))
            }

            if (uiState.isConfigured) {
                OutlinedButton(
                    onClick = { showDisableConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.remote_broker_disable))
                }
            }
        }
    }

    if (showDisableConfirm) {
        AlertDialog(
            onDismissRequest = { showDisableConfirm = false },
            title = { Text(stringResource(R.string.remote_broker_disable_confirm_title)) },
            text = { Text(stringResource(R.string.remote_broker_disable_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDisableConfirm = false
                        onDisable()
                    },
                ) {
                    Text(stringResource(R.string.remote_broker_disable_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisableConfirm = false }) {
                    Text(stringResource(R.string.remote_broker_cancel))
                }
            },
        )
    }
}

@Composable
private fun connectionStateText(state: MqttConnectionState): String = when (state) {
    MqttConnectionState.Disabled -> stringResource(R.string.remote_broker_status_disabled)
    MqttConnectionState.Connecting -> stringResource(R.string.remote_broker_status_connecting)
    MqttConnectionState.Connected -> stringResource(R.string.remote_broker_status_connected)
    is MqttConnectionState.AuthFailed -> stringResource(R.string.remote_broker_status_auth_failed, state.message)
    is MqttConnectionState.Disconnected -> stringResource(R.string.remote_broker_status_disconnected, state.message)
}

@Composable
private fun BrokerTestStatusText(status: BrokerTestStatus) {
    val text = when (status) {
        BrokerTestStatus.Idle -> return
        BrokerTestStatus.Testing -> stringResource(R.string.remote_broker_testing)
        is BrokerTestStatus.Done -> when (val result = status.result) {
            MqttTestResult.Success -> stringResource(R.string.remote_broker_test_success)
            is MqttTestResult.AuthFailed -> stringResource(R.string.remote_broker_test_auth_failed, result.message)
            is MqttTestResult.Unreachable -> stringResource(R.string.remote_broker_test_unreachable, result.message)
        }
    }
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
}

@Preview(showBackground = true)
@Composable
private fun BrokerSettingsScreenPreview() {
    HAKioskTheme {
        BrokerSettingsContent(
            uiState = BrokerSettingsUiState(host = "192.168.1.10", deviceName = "Tablet cocina"),
            onHostChange = {},
            onPortChange = {},
            onUsernameChange = {},
            onPasswordChange = {},
            onTogglePasswordVisibility = {},
            onUseTlsChange = {},
            onDeviceNameChange = {},
            onTestConnection = {},
            onSave = {},
            onDisable = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun BrokerSettingsScreenConnectedPreview() {
    HAKioskTheme {
        BrokerSettingsContent(
            uiState = BrokerSettingsUiState(
                host = "192.168.1.10",
                deviceName = "Tablet cocina",
                isConfigured = true,
                connectionState = MqttConnectionState.Connected,
                testStatus = BrokerTestStatus.Done(MqttTestResult.Success),
            ),
            onHostChange = {},
            onPortChange = {},
            onUsernameChange = {},
            onPasswordChange = {},
            onTogglePasswordVisibility = {},
            onUseTlsChange = {},
            onDeviceNameChange = {},
            onTestConnection = {},
            onSave = {},
            onDisable = {},
            onBack = {},
        )
    }
}
