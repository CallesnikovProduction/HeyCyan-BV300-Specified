package com.fersaiyan.cyanbridge.diagnostics

enum class DiagnosticsSource {
    ANDROID_BLUETOOTH,
    BLE_GATT,
    VENDOR_SDK,
    WIFI_P2P,
    APPLICATION,
    SIMULATED,
}

enum class DiagnosticsCategory {
    CONNECTION,
    SCANNER,
    TRANSPORT,
    CALLBACK,
    INPUT,
    DEVICE,
    PERMISSION,
    ERROR,
}

enum class InputObservability {
    HIGH_LEVEL_CALLBACK,
    RAW_TRANSPORT_EVENT,
    INDIRECT_EFFECT,
    BLOCKED_BY_VENDOR_SDK,
    NOT_OBSERVABLE,
    UNKNOWN,
}

enum class DiagnosticsConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    FAILED,
    CONNECTION_LOST,
}

data class DiagnosticsEvent(
    val id: Long,
    val timestampMs: Long,
    val source: DiagnosticsSource,
    val category: DiagnosticsCategory,
    val name: String,
    val description: String? = null,
    val rawLength: Int? = null,
    val rawHex: String? = null,
    val observability: InputObservability? = null,
    val simulated: Boolean = false,
)

data class DiagnosticsState(
    val bluetoothStatus: String = "UNKNOWN",
    val permissionStatus: String = "UNKNOWN",
    val scannerStatus: String = "IDLE",
    val detectedGlasses: Boolean = false,
    val selectedDevice: String = "None",
    val deviceClass: String = "UNKNOWN",
    val sdkStatus: String = "NOT INITIALIZED",
    val connectionStatus: DiagnosticsConnectionStatus = DiagnosticsConnectionStatus.DISCONNECTED,
    val wifiP2pStatus: String = "IDLE",
    val bleRxStatus: String = "SILENT",
    val lastEventAtMs: Long? = null,
    val lastRawEventAtMs: Long? = null,
    val rawEventCount: Long = 0,
    val semanticEventCount: Long = 0,
    val unknownRawEventCount: Long = 0,
    val events: List<DiagnosticsEvent> = emptyList(),
)

sealed interface DiagnosticsSignal {
    data object ConnectRequested : DiagnosticsSignal
    data object Connected : DiagnosticsSignal
    data object Disconnected : DiagnosticsSignal
    data object UnexpectedDisconnect : DiagnosticsSignal
    data object ConnectionFailed : DiagnosticsSignal
}

object DiagnosticsStateAggregator {
    fun reduce(state: DiagnosticsState, signal: DiagnosticsSignal): DiagnosticsState = when (signal) {
        DiagnosticsSignal.ConnectRequested -> state.copy(connectionStatus = DiagnosticsConnectionStatus.CONNECTING)
        DiagnosticsSignal.Connected -> state.copy(connectionStatus = DiagnosticsConnectionStatus.CONNECTED)
        DiagnosticsSignal.Disconnected -> state.copy(connectionStatus = DiagnosticsConnectionStatus.DISCONNECTED)
        DiagnosticsSignal.UnexpectedDisconnect -> state.copy(connectionStatus = DiagnosticsConnectionStatus.CONNECTION_LOST)
        DiagnosticsSignal.ConnectionFailed -> state.copy(connectionStatus = DiagnosticsConnectionStatus.FAILED)
    }
}

class BoundedDiagnosticsBuffer(private val capacity: Int = 200) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val items = ArrayDeque<DiagnosticsEvent>(capacity)

    fun add(event: DiagnosticsEvent): List<DiagnosticsEvent> {
        items.addFirst(event)
        while (items.size > capacity) items.removeLast()
        return items.toList()
    }

    fun clear(): List<DiagnosticsEvent> {
        items.clear()
        return emptyList()
    }
}

object DiagnosticsRawFormatter {
    const val MAX_DISPLAY_BYTES = 64
    const val MAX_SAFE_CONTROL_FRAME_BYTES = 128

    fun toSafeHex(bytes: ByteArray): String =
        if (bytes.size > MAX_SAFE_CONTROL_FRAME_BYTES) {
            "[payload omitted: ${bytes.size} bytes; possible media/user data]"
        } else {
            toHex(bytes)
        }

    fun toHex(bytes: ByteArray, maxBytes: Int = MAX_DISPLAY_BYTES): String {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val displayed = bytes.take(maxBytes).joinToString(" ") { byte ->
            "%02X".format(byte.toInt() and 0xFF)
        }
        return if (bytes.size > maxBytes) "$displayed … (+${bytes.size - maxBytes} bytes)" else displayed
    }
}

data class RawEventClassification(
    val name: String,
    val observability: InputObservability,
    val known: Boolean,
)

object DiagnosticsRawClassifier {
    private val vendorNotifyNames = mapOf(
        0x02 to "AI photo ready",
        0x03 to "AI voice activation",
        0x04 to "OTA progress",
        0x05 to "Battery report",
        0x08 to "Wi-Fi IP report",
        0x09 to "Wi-Fi/P2P error",
        0x0C to "Pause/voice broadcast",
        0x0D to "App unbind event",
        0x0E to "Low memory event",
        0x10 to "Translation pause event",
        0x12 to "Volume change event",
    )

    fun classify(bytes: ByteArray): RawEventClassification {
        val eventType = bytes.getOrNull(6)?.toInt()?.and(0xFF)
        val name = eventType?.let(vendorNotifyNames::get)
        return if (name != null) {
            RawEventClassification(name, InputObservability.RAW_TRANSPORT_EVENT, known = true)
        } else {
            RawEventClassification("Unknown raw event", InputObservability.UNKNOWN, known = false)
        }
    }
}
