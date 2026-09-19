package com.fersaiyan.cyanbridge.ui

import com.fersaiyan.cyanbridge.shared.devices.DeviceProfile
import com.fersaiyan.cyanbridge.shared.devices.ScannedDevice as SharedScannedDevice

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.devices.DeviceClassifier
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.moyoung.MoyoungW620Manager
import com.fersaiyan.cyanbridge.devices.ScannedDevice
import com.fersaiyan.cyanbridge.diagnostics.DiagnosticsSignal
import com.fersaiyan.cyanbridge.diagnostics.DiagnosticsStore
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.shared.ui.DeviceBindScreen
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.oudmon.ble.base.scan.BleScannerHelper
import com.oudmon.ble.base.scan.ScanRecord
import com.oudmon.ble.base.scan.ScanWrapperCallback
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class DeviceBindActivity : BaseActivity() {
    private val scanTimeout = ScanTimeout()
    private val handler = Handler(Looper.getMainLooper())
    private val deviceList = mutableListOf<ScannedDevice>()
    private val bleScanCallback = BleCallback()

    private var scannedDevices by mutableStateOf<List<ScannedDevice>>(emptyList())
    private var isScanning by mutableStateOf(false)
    private var connectingDevice by mutableStateOf<ScannedDevice?>(null)
    private var initialScanStarted = false
    private var lastDeviceListPublishAtMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventBus.getDefault().register(this)
        val appearancePreferences = AppearancePreferences(this)
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            CyanBridgeTheme(appearance) {
                DeviceBindScreen(
                    devices = scannedDevices.map { it.toShared() },
                    isScanning = isScanning,
                    connectingDevice = connectingDevice?.toShared(),
                    onScan = ::startScan,
                    onSelectDevice = { sharedDevice ->
                        val device = deviceList.firstOrNull {
                            it.macAddress.equals(sharedDevice.macAddress, ignoreCase = true)
                        }
                        if (device != null) {
                            connectingDevice = device
                        }
                    },
                    onConfirmConnection = ::confirmConnection,
                    onDismissConnection = { connectingDevice = null },
                    onBack = ::finish,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!initialScanStarted) {
            initialScanStarted = true
            startScan()
        }
    }

    // BaseActivity invokes this after Compose installs its host view; no ViewBinding remains.
    override fun setupViews() = Unit

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(messageEvent: BluetoothEvent) {
        Log.i(TAG, "onMessageEvent: ${messageEvent.connect}")
        if (messageEvent.connect) finish()
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        handler.removeCallbacks(scanTimeout)
        deviceList.clear()
        scannedDevices = emptyList()
        lastDeviceListPublishAtMs = 0L
        if (!hasBluetooth(this)) {
            isScanning = false
            DiagnosticsStore.scanner("ERROR", "Bluetooth permission missing")
            requestBluetoothPermission(this, PermissionCallback())
            return
        }
        BleScannerHelper.getInstance().reSetCallback()
        if (!BluetoothUtils.isEnabledBluetooth(this)) {
            DiagnosticsStore.scanner("ERROR", "Bluetooth adapter off")
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BLUETOOTH)
            return
        }
        isScanning = true
        DiagnosticsStore.scanner("SCANNING")
        BleScannerHelper.getInstance().scanDevice(this, null, bleScanCallback)
        handler.postDelayed(scanTimeout, 15_000)
    }

    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        BleScannerHelper.getInstance().stopScan(this)
        isScanning = false
        DiagnosticsStore.scanner("IDLE")
    }

    private fun confirmConnection() {
        val device = connectingDevice ?: return
        if (!hasBluetooth(this)) {
            Toast.makeText(this, "Bluetooth permission is required to connect", Toast.LENGTH_SHORT).show()
            requestBluetoothPermission(this, PermissionCallback())
            return
        }
        val rememberedAddress = DeviceProfileStore.loadLastSelected(this)
            ?.takeIf { it.selectedClass == DeviceClass.MOYOUNG_W620 }?.macAddress
        if (!Bv300PairingPolicy.isCandidate(
                device.detectedClass, device.advertisedName, device.connectionAddress, rememberedAddress,
            )
        ) {
            Toast.makeText(this, "This is not a recognized BV300 device", Toast.LENGTH_LONG).show()
            connectingDevice = null
            return
        }

        connectingDevice = null
        stopScan()
        DiagnosticsStore.connection(DiagnosticsSignal.ConnectRequested, "BV300 connection requested")
        AutoPairManager.setAutoReconnectSuppressed(false, reason = "user_manual_pair")
        DeviceProfileStore.saveLastSelected(
            this,
            DeviceProfile(
                macAddress = device.connectionAddress,
                advertisedName = device.advertisedName,
                detectedClass = DeviceClass.MOYOUNG_W620,
                selectedClass = DeviceClass.MOYOUNG_W620,
                userOverridden = true,
            ),
        )
        MoyoungW620Manager.getInstance(this).connect(device.macAddress, device.advertisedName)
        Toast.makeText(this, "Connecting to BV300", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun upsertDevice(
        mac: String,
        name: String?,
        rssi: Int,
        scanRecord: ScanRecord? = null,
        manufacturerCompanyIds: Set<Int> = emptySet(),
    ) {
        val sanitizedName = name?.trim()?.takeIf { it.isNotEmpty() }
        val existingIndex = deviceList.indexOfFirst { it.macAddress.equals(mac, ignoreCase = true) }
        if (existingIndex >= 0) {
            val existing = deviceList[existingIndex]
            val previousName = existing.advertisedName
            val previousClass = existing.detectedClass
            existing.rssi = rssi
            if (existing.advertisedName.isNullOrBlank() && sanitizedName != null) {
                existing.advertisedName = sanitizedName
            }
            scanRecord?.serviceUuids?.takeIf { it.isNotEmpty() }?.let { existing.serviceUuids = it }
            existing.setDetectedClass(
                DeviceClassifier.guessDeviceClass(
                    existing.advertisedName,
                    existing.serviceUuids,
                    manufacturerCompanyIds,
                    existing.macAddress,
                ),
            )
            publishDevices(
                force = previousName != existing.advertisedName || previousClass != existing.detectedClass,
            )
            return
        }
        val detectedClass = DeviceClassifier.guessDeviceClass(
            sanitizedName,
            scanRecord?.serviceUuids.orEmpty(),
            manufacturerCompanyIds,
            mac,
        )
        val rememberedAddress = DeviceProfileStore.loadLastSelected(this)
            ?.takeIf { it.selectedClass == DeviceClass.MOYOUNG_W620 }?.macAddress
        if (sanitizedName == null && !Bv300PairingPolicy.isCandidate(
                detectedClass, null, mac, rememberedAddress,
            )
        ) return

        val newDevice = ScannedDevice(
            macAddress = mac,
            advertisedName = sanitizedName ?: "BV300",
            rssi = rssi,
            serviceUuids = scanRecord?.serviceUuids.orEmpty(),
        )
        newDevice.setDetectedClass(detectedClass)
        DeviceProfileStore.getUserOverrideForMac(this, newDevice.connectionAddress)?.let { override ->
            if (override != newDevice.detectedClass) newDevice.userSelectedClass = override
        }
        deviceList += newDevice
        DiagnosticsStore.deviceDiscovered(
            newDevice.advertisedName,
            newDevice.macAddress,
            recognized = detectedClass != DeviceClass.UNKNOWN,
        )
        publishDevices(force = true)
    }

    /** Avoid repeatedly recreating scan rows while TalkBack is navigating them. */
    private fun publishDevices(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastDeviceListPublishAtMs < DEVICE_LIST_PUBLISH_INTERVAL_MS) return
        lastDeviceListPublishAtMs = now
        val rememberedBv300 = DeviceProfileStore.loadLastSelected(this)
            ?.takeIf { it.selectedClass == DeviceClass.MOYOUNG_W620 }
        scannedDevices = deviceList.filter { device ->
            Bv300PairingPolicy.isCandidate(
                device.detectedClass, device.advertisedName, device.macAddress,
                rememberedBv300?.macAddress,
            )
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(scanTimeout)
        if (EventBus.getDefault().isRegistered(this)) EventBus.getDefault().unregister(this)
        super.onDestroy()
    }

    @Deprecated("Deprecated in AndroidX Activity; retained for the vendor scanner flow.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BLUETOOTH && BluetoothUtils.isEnabledBluetooth(this)) {
            startScan()
        }
    }

    private inner class ScanTimeout : Runnable {
        override fun run() {
            BleScannerHelper.getInstance().stopScan(this@DeviceBindActivity)
            isScanning = false
            DiagnosticsStore.scanner("IDLE", "Scan timeout after 15 seconds")
        }
    }

    private inner class PermissionCallback : OnPermissionCallback {
        override fun onGranted(permissions: MutableList<String>, all: Boolean) {
            if (all) startScan()
        }

        override fun onDenied(permissions: MutableList<String>, never: Boolean) {
            super.onDenied(permissions, never)
            Toast.makeText(
                this@DeviceBindActivity,
                "Bluetooth permission is required to find and connect to glasses",
                Toast.LENGTH_LONG,
            ).show()
            if (never) XXPermissions.startPermissionActivity(this@DeviceBindActivity, permissions)
        }
    }

    private inner class BleCallback : ScanWrapperCallback {
        override fun onStart() {
            isScanning = true
            DiagnosticsStore.scanner("SCANNING")
        }

        override fun onStop() {
            isScanning = false
            DiagnosticsStore.scanner("IDLE")
        }

        @SuppressLint("MissingPermission")
        override fun onLeScan(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?) {
            if (!hasBluetooth(this@DeviceBindActivity)) return
            val bluetoothDevice = device ?: return
            val address = bluetoothDevice.address
            val name = runCatching { bluetoothDevice.name }.getOrNull()
            upsertDevice(address, name, rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            DiagnosticsStore.scanner("ERROR", "Vendor scanner error=$errorCode")
            Log.w(TAG, "Scan failed: $errorCode")
        }

        @SuppressLint("MissingPermission")
        override fun onParsedData(device: BluetoothDevice?, scanRecord: ScanRecord?) {
            if (!hasBluetooth(this@DeviceBindActivity)) return
            val bluetoothDevice = device ?: return
            val address = bluetoothDevice.address
            val name = runCatching { scanRecord?.deviceName ?: bluetoothDevice.name }.getOrNull()
            val rssi = deviceList.firstOrNull { it.macAddress.equals(address, true) }?.rssi ?: 0
            val manufacturerData = scanRecord?.manufacturerSpecificData
            val companyIds = buildSet {
                if (manufacturerData != null) {
                    for (index in 0 until manufacturerData.size()) add(manufacturerData.keyAt(index))
                }
            }
            upsertDevice(
                address,
                name,
                rssi,
                scanRecord,
                manufacturerCompanyIds = companyIds,
            )
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) = Unit
    }

    private fun ScannedDevice.toShared(): SharedScannedDevice = SharedScannedDevice(
        macAddress = macAddress,
        advertisedName = advertisedName,
        rssi = rssi,
        detectedClass = detectedClass,
        selectedClass = userSelectedClass,
        userOverridden = userOverridden(),
    )

    private companion object {
        const val TAG = "DeviceBindActivity"
        const val REQUEST_ENABLE_BLUETOOTH = 300
        const val DEVICE_LIST_PUBLISH_INTERVAL_MS = 1_000L
    }
}
