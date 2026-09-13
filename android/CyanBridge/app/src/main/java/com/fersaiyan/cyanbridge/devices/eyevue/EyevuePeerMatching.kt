package com.fersaiyan.cyanbridge.devices.eyevue

internal fun matchesEyevuePeerName(target: String, deviceName: String?): Boolean {
    val name = deviceName?.trim().orEmpty()
    return target.isNotBlank() && name.isNotBlank() &&
        (name.contains(target, ignoreCase = true) || target.contains(name, ignoreCase = true))
}
