package com.fersaiyan.cyanbridge.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.devices.ScannedDevice
import com.fersaiyan.cyanbridge.shared.generated.resources.*
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

/** Pairing surface for this personal BV300 fork. The platform layer owns scanning and connection. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun DeviceBindScreen(
    devices: List<ScannedDevice>,
    isScanning: Boolean,
    connectingDevice: ScannedDevice?,
    onScan: () -> Unit,
    onSelectDevice: (ScannedDevice) -> Unit,
    onConfirmConnection: () -> Unit,
    onDismissConnection: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Connect BV300") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = onScan, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(Res.string.device_bind_scan))
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Turn on BV300 and keep it nearby. Only BV300-compatible devices are shown.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                Button(onClick = onScan, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(if (isScanning) stringResource(Res.string.device_bind_scanning)
                        else stringResource(Res.string.device_bind_scan))
                }
            }
            if (devices.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        Text(
                            if (isScanning) "Searching for BV300…" else "BV300 not found. Check Bluetooth and scan again.",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                        )
                    }
                }
            } else {
                items(devices, key = { it.macAddress }) { device ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    device.advertisedName?.takeIf { it.isNotBlank() } ?: "BV300",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    stringResource(Res.string.device_bind_signal, device.rssi),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                            FilledTonalButton(onClick = { onSelectDevice(device) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(Res.string.action_connect))
                            }
                        }
                    }
                }
            }
        }
    }

    connectingDevice?.let { device ->
        AlertDialog(
            onDismissRequest = onDismissConnection,
            title = { Text("Connect BV300?") },
            text = {
                Text("Connect ${device.advertisedName?.takeIf { it.isNotBlank() } ?: "this device"} as BV300 glasses.")
            },
            confirmButton = {
                FilledTonalButton(onClick = onConfirmConnection, modifier = Modifier.heightIn(min = 48.dp).testTag("bv300_pair_confirm")) {
                    Text(stringResource(Res.string.action_connect))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissConnection, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }
}
