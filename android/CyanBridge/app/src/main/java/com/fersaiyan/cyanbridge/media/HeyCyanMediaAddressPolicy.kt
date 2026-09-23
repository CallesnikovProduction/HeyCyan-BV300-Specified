package com.fersaiyan.cyanbridge.media

/** Pure address rules for the BV300 BLE + Wi-Fi Direct media transfer. */
internal object HeyCyanMediaAddressPolicy {
    fun isProbablyPhoneGroupOwnerIp(ip: String?, phoneIsGroupOwner: Boolean?): Boolean {
        if (ip.isNullOrBlank()) return false

        // When the phone is not GO, .1 can belong to the glasses and must remain eligible.
        if (phoneIsGroupOwner != true) return false

        return ip == "192.168.49.1"
    }

    fun ipv4Prefix24(ip: String?): String? {
        if (ip.isNullOrBlank()) return null
        val parts = ip.split(".")
        if (parts.size != 4) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}."
    }

    fun candidateIps(
        bleIp: String?,
        bridgeIp: String?,
        wifiIp: String?,
        subnetPrefix: String?,
    ): List<String> {
        val addresses = LinkedHashSet<String>()
        bleIp?.let(addresses::add)
        bridgeIp?.let(addresses::add)
        wifiIp?.let(addresses::add)

        subnetPrefix?.let { prefix ->
            addresses.add("${prefix}1") // Glasses might be the group owner.
            addresses.add("${prefix}79")
            addresses.add("${prefix}2")
            addresses.add("${prefix}3")
        }
        return addresses.toList()
    }
}
