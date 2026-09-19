package com.fersaiyan.cyanbridge.ui

import com.fersaiyan.cyanbridge.shared.devices.DeviceClass

/** The only pairing candidate this personal fork exposes. */
internal object Bv300PairingPolicy {
    fun isCandidate(
        detectedClass: DeviceClass,
        advertisedName: String?,
        address: String,
        rememberedBv300Address: String?,
    ): Boolean = detectedClass == DeviceClass.MOYOUNG_W620 ||
        advertisedName?.contains("BV300", ignoreCase = true) == true ||
        rememberedBv300Address?.equals(address, ignoreCase = true) == true
}
