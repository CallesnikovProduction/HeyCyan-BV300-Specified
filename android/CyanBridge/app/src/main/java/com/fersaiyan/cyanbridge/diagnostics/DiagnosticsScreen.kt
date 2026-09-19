package com.fersaiyan.cyanbridge.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    liveState: DiagnosticsState,
    showSimulationControls: Boolean,
    onBack: () -> Unit,
    onClear: () -> Unit,
    onSimulate: () -> Unit,
) {
    var paused by remember { mutableStateOf(false) }
    var displayedState by remember { mutableStateOf(liveState) }
    LaunchedEffect(liveState, paused) {
        if (!paused) displayedState = liveState
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Glasses diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Passive, in-memory diagnostics. No microphone, camera, storage, network upload, or extra transport listener is started.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                StatusSection("Connection") {
                    StatusRow("Bluetooth adapter", displayedState.bluetoothStatus)
                    StatusRow("Permissions", displayedState.permissionStatus)
                    StatusRow("Scanner", displayedState.scannerStatus)
                    StatusRow("Detected glasses", if (displayedState.detectedGlasses) "YES" else "NO")
                    StatusRow("Selected device", displayedState.selectedDevice)
                    StatusRow("Device class", displayedState.deviceClass)
                    StatusRow("Vendor SDK", displayedState.sdkStatus)
                    StatusRow("Connection", displayedState.connectionStatus.name)
                    StatusRow("Wi-Fi Direct", displayedState.wifiP2pStatus)
                }
            }
            item {
                StatusSection("Transport") {
                    StatusRow("BLE RX", displayedState.bleRxStatus)
                    StatusRow("Raw packets", displayedState.rawEventCount.toString())
                    StatusRow("Semantic callbacks", displayedState.semanticEventCount.toString())
                    StatusRow("Unknown raw events", displayedState.unknownRawEventCount.toString())
                    StatusRow("Last raw packet", displayedState.lastRawEventAtMs?.let(::formatTimestamp) ?: "None")
                    StatusRow("Last event", displayedState.lastEventAtMs?.let(::formatTimestamp) ?: "None")
                }
            }
            item {
                StatusSection("Glasses input diagnostics") {
                    val realInputs = displayedState.events.count { it.category == DiagnosticsCategory.INPUT && !it.simulated }
                    StatusRow("Observed input events", realInputs.toString())
                    StatusRow("Hardware validation", "REQUIRES HARDWARE VALIDATION")
                    Text(
                        "Controls are named only when the current SDK/raw protocol identifies them. Everything else remains Unknown raw event.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            onClear()
                            displayedState = displayedState.copy(
                                events = emptyList(),
                                lastEventAtMs = null,
                                lastRawEventAtMs = null,
                                rawEventCount = 0,
                                semanticEventCount = 0,
                                unknownRawEventCount = 0,
                            )
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text("Clear") }
                    OutlinedButton(
                        onClick = {
                            if (paused) displayedState = liveState
                            paused = !paused
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text(if (paused) "Resume" else "Pause") }
                }
            }
            if (showSimulationControls) {
                item {
                    TextButton(onClick = onSimulate, modifier = Modifier.fillMaxWidth()) {
                        Text("SIMULATE RAW EVENT (debug only)")
                    }
                }
            }
            item {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("Live events", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${displayedState.events.size}/${liveState.events.size}${if (paused) " · PAUSED" else ""}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (displayedState.events.isEmpty()) {
                item {
                    Text(
                        "No events observed yet. Connect the glasses or start scanning; the observer stays passive.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(displayedState.events, key = { it.id }) { event -> EventCard(event) }
            }
        }
    }
}

@Composable
private fun StatusSection(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EventCard(event: DiagnosticsEvent) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (event.simulated) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "${formatTimestamp(event.timestampMs)}  ${event.category}  ${event.source}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(event.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            event.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            event.observability?.let { Text("Observation: $it", style = MaterialTheme.typography.labelSmall) }
            if (event.rawLength != null) Text("Length: ${event.rawLength} bytes", style = MaterialTheme.typography.labelSmall)
            event.rawHex?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

private fun formatTimestamp(timestampMs: Long): String =
    SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestampMs))
