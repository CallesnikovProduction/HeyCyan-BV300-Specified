package com.fersaiyan.cyanbridge.diagnostics

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.oudmon.ble.base.bluetooth.BleOperateManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object DiagnosticsStore {
    private const val HISTORY_LIMIT = 200
    private val buffer = BoundedDiagnosticsBuffer(HISTORY_LIMIT)
    private val mutableState = MutableStateFlow(DiagnosticsState())
    val state: StateFlow<DiagnosticsState> = mutableState.asStateFlow()
    private var nextId = 1L
    private var lastExternalConnectionLabel: String? = null

    @Synchronized
    fun refreshPlatformSnapshot(context: Context) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        val bluetooth = when {
            adapter == null -> "UNSUPPORTED"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                Manifest.permission.BLUETOOTH_CONNECT in missing -> "PERMISSION REQUIRED"
            !runCatching { adapter.isEnabled }.getOrDefault(false) -> "OFF"
            else -> "READY"
        }
        val profile = DeviceProfileStore.loadLastSelected(context)
        val current = mutableState.value
        val connectionStatus = if (profile == null || profile.selectedClass == DeviceClass.HEY_CYAN) {
            val connected = runCatching { BleOperateManager.getInstance().isConnected }.getOrDefault(false)
            if (connected) {
                DiagnosticsConnectionStatus.CONNECTED
            } else if (current.connectionStatus == DiagnosticsConnectionStatus.CONNECTED) {
                DiagnosticsConnectionStatus.DISCONNECTED
            } else {
                current.connectionStatus
            }
        } else {
            // Other device families have their own managers. Do not overwrite their observed
            // callback state with the unrelated HeyCyan vendor manager snapshot.
            current.connectionStatus
        }
        mutableState.value = current.copy(
            bluetoothStatus = bluetooth,
            permissionStatus = if (missing.isEmpty()) "OK" else "MISSING (${missing.size})",
            selectedDevice = profile?.let { "${it.advertisedName.orEmpty().ifBlank { "Unnamed glasses" }} · ${safeDeviceId(it.macAddress)}" }
                ?: "None",
            deviceClass = profile?.selectedClass?.name ?: "UNKNOWN",
            connectionStatus = connectionStatus,
        )
    }

    @Synchronized
    fun refreshTransportStatus(nowMs: Long = System.currentTimeMillis()) {
        val current = mutableState.value
        val active = current.lastRawEventAtMs?.let { nowMs - it <= 10_000L } == true
        mutableState.value = current.copy(bleRxStatus = if (active) "ACTIVE" else "SILENT")
    }

    @Synchronized
    fun markSdkReady() {
        mutableState.value = mutableState.value.copy(sdkStatus = "INITIALIZED")
        recordEvent(DiagnosticsSource.VENDOR_SDK, DiagnosticsCategory.CONNECTION, "Vendor SDK initialized")
    }

    @Synchronized
    fun markSdkError(message: String) {
        mutableState.value = mutableState.value.copy(sdkStatus = "ERROR")
        recordEvent(DiagnosticsSource.VENDOR_SDK, DiagnosticsCategory.ERROR, "Vendor SDK error", message)
    }

    @Synchronized
    fun scanner(status: String, description: String? = null) {
        mutableState.value = mutableState.value.copy(
            scannerStatus = status,
            detectedGlasses = if (status == "SCANNING") false else mutableState.value.detectedGlasses,
        )
        recordEvent(DiagnosticsSource.VENDOR_SDK, DiagnosticsCategory.SCANNER, "Scanner $status", description)
    }

    @Synchronized
    fun deviceDiscovered(name: String?, address: String?, recognized: Boolean) {
        if (recognized) mutableState.value = mutableState.value.copy(detectedGlasses = true)
        recordEvent(
            DiagnosticsSource.VENDOR_SDK,
            DiagnosticsCategory.DEVICE,
            if (recognized) "Glasses candidate discovered" else "Unknown Bluetooth candidate",
            "${name.orEmpty().ifBlank { "Unnamed" }} · ${safeDeviceId(address)}",
        )
    }

    @Synchronized
    fun connection(signal: DiagnosticsSignal, name: String, description: String? = null) {
        mutableState.value = DiagnosticsStateAggregator.reduce(mutableState.value, signal)
        recordEvent(DiagnosticsSource.ANDROID_BLUETOOTH, DiagnosticsCategory.CONNECTION, name, description)
    }

    @Synchronized
    fun connectionEnded(name: String) {
        val signal = if (mutableState.value.connectionStatus == DiagnosticsConnectionStatus.CONNECTING) {
            DiagnosticsSignal.ConnectionFailed
        } else {
            DiagnosticsSignal.UnexpectedDisconnect
        }
        connection(signal, name)
    }

    @Synchronized
    fun connectionFromManager(label: String, source: String) {
        val sourceAndLabel = "$source\u0000$label"
        if (sourceAndLabel == lastExternalConnectionLabel) return
        lastExternalConnectionLabel = sourceAndLabel
        val normalized = label.lowercase()
        val signal = when {
            "error" in normalized || "fail" in normalized -> DiagnosticsSignal.ConnectionFailed
            "connecting" in normalized || "starting" in normalized -> DiagnosticsSignal.ConnectRequested
            "disconnected" in normalized || "idle" in normalized -> DiagnosticsSignal.Disconnected
            "connected" in normalized || "ready" in normalized -> DiagnosticsSignal.Connected
            else -> null
        }
        if (signal != null) {
            mutableState.value = DiagnosticsStateAggregator.reduce(mutableState.value, signal)
        }
        recordEvent(DiagnosticsSource.VENDOR_SDK, DiagnosticsCategory.CONNECTION, "$source state", label)
    }

    @Synchronized
    fun wifiP2p(status: String, description: String? = null) {
        mutableState.value = mutableState.value.copy(wifiP2pStatus = status)
        recordEvent(DiagnosticsSource.WIFI_P2P, DiagnosticsCategory.CONNECTION, "Wi-Fi Direct $status", description)
    }

    @Synchronized
    fun vendorFrame(sourceLabel: String, bytes: ByteArray) {
        val classification = DiagnosticsRawClassifier.classify(bytes)
        val event = newEvent(
            source = DiagnosticsSource.BLE_GATT,
            category = if (classification.known && bytes.getOrNull(6)?.toInt()?.and(0xFF) == 0x03) {
                DiagnosticsCategory.INPUT
            } else {
                DiagnosticsCategory.TRANSPORT
            },
            name = classification.name,
            description = sourceLabel,
            rawLength = bytes.size,
            rawHex = DiagnosticsRawFormatter.toSafeHex(bytes),
            observability = classification.observability,
        )
        val old = mutableState.value
        mutableState.value = old.copy(
            bleRxStatus = "ACTIVE",
            lastEventAtMs = event.timestampMs,
            lastRawEventAtMs = event.timestampMs,
            rawEventCount = old.rawEventCount + 1,
            unknownRawEventCount = old.unknownRawEventCount + if (classification.known) 0 else 1,
            events = buffer.add(event),
        )
    }

    @Synchronized
    fun unclassifiedTransport(sourceLabel: String, length: Int) {
        val event = newEvent(
            source = DiagnosticsSource.BLE_GATT,
            category = DiagnosticsCategory.TRANSPORT,
            name = "Unclassified GATT traffic",
            description = sourceLabel,
            rawLength = length,
            rawHex = "[payload hidden: unvalidated characteristic]",
            observability = InputObservability.UNKNOWN,
        )
        val old = mutableState.value
        mutableState.value = old.copy(
            bleRxStatus = "ACTIVE",
            lastEventAtMs = event.timestampMs,
            lastRawEventAtMs = event.timestampMs,
            rawEventCount = old.rawEventCount + 1,
            unknownRawEventCount = old.unknownRawEventCount + 1,
            events = buffer.add(event),
        )
    }

    @Synchronized
    fun semantic(
        name: String,
        description: String? = null,
        category: DiagnosticsCategory = DiagnosticsCategory.CALLBACK,
        observability: InputObservability = InputObservability.HIGH_LEVEL_CALLBACK,
    ) {
        val event = newEvent(
            source = DiagnosticsSource.VENDOR_SDK,
            category = category,
            name = name,
            description = description,
            observability = observability,
        )
        val old = mutableState.value
        mutableState.value = old.copy(
            lastEventAtMs = event.timestampMs,
            semanticEventCount = old.semanticEventCount + 1,
            events = buffer.add(event),
        )
    }

    @Synchronized
    fun simulate(name: String, raw: ByteArray? = null) {
        val event = newEvent(
            source = DiagnosticsSource.SIMULATED,
            category = if (raw == null) DiagnosticsCategory.CALLBACK else DiagnosticsCategory.TRANSPORT,
            name = "SIMULATED · $name",
            rawLength = raw?.size,
            rawHex = raw?.let(DiagnosticsRawFormatter::toSafeHex),
            observability = if (raw == null) InputObservability.HIGH_LEVEL_CALLBACK else InputObservability.RAW_TRANSPORT_EVENT,
            simulated = true,
        )
        val old = mutableState.value
        mutableState.value = old.copy(lastEventAtMs = event.timestampMs, events = buffer.add(event))
    }

    @Synchronized
    fun clearEvents() {
        mutableState.value = mutableState.value.copy(
            lastEventAtMs = null,
            lastRawEventAtMs = null,
            rawEventCount = 0,
            semanticEventCount = 0,
            unknownRawEventCount = 0,
            events = buffer.clear(),
        )
    }

    @Synchronized
    private fun recordEvent(
        source: DiagnosticsSource,
        category: DiagnosticsCategory,
        name: String,
        description: String? = null,
    ) {
        val event = newEvent(source, category, name, description)
        val old = mutableState.value
        mutableState.value = old.copy(lastEventAtMs = event.timestampMs, events = buffer.add(event))
    }

    private fun newEvent(
        source: DiagnosticsSource,
        category: DiagnosticsCategory,
        name: String,
        description: String? = null,
        rawLength: Int? = null,
        rawHex: String? = null,
        observability: InputObservability? = null,
        simulated: Boolean = false,
    ) = DiagnosticsEvent(
        id = nextId++,
        timestampMs = System.currentTimeMillis(),
        source = source,
        category = category,
        name = name,
        description = description,
        rawLength = rawLength,
        rawHex = rawHex,
        observability = observability,
        simulated = simulated,
    )

    private fun safeDeviceId(address: String?): String {
        val parts = address?.split(':').orEmpty()
        return if (parts.size >= 2) "••:••:••:••:${parts.takeLast(2).joinToString(":")}" else "ID unavailable"
    }
}
