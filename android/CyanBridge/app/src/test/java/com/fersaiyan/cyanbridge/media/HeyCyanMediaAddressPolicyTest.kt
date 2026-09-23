package com.fersaiyan.cyanbridge.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeyCyanMediaAddressPolicyTest {
    @Test
    fun phoneGroupOwnerAddressIsExcludedOnlyWhenPhoneIsGroupOwner() {
        assertTrue(HeyCyanMediaAddressPolicy.isProbablyPhoneGroupOwnerIp("192.168.49.1", true))
        assertFalse(HeyCyanMediaAddressPolicy.isProbablyPhoneGroupOwnerIp("192.168.49.1", false))
        assertFalse(HeyCyanMediaAddressPolicy.isProbablyPhoneGroupOwnerIp("192.168.49.1", null))
        assertFalse(HeyCyanMediaAddressPolicy.isProbablyPhoneGroupOwnerIp("192.168.49.2", true))
    }

    @Test
    fun candidatesPreferReportedAddressesAndKeepSubnetFallbacksUnique() {
        assertEquals(
            listOf("192.168.49.79", "192.168.49.2", "192.168.49.1", "192.168.49.3"),
            HeyCyanMediaAddressPolicy.candidateIps(
                bleIp = "192.168.49.79",
                bridgeIp = "192.168.49.79",
                wifiIp = "192.168.49.2",
                subnetPrefix = "192.168.49.",
            ),
        )
    }

    @Test
    fun subnetPrefixMatchesExistingFourPartAddressRule() {
        assertEquals("192.168.49.", HeyCyanMediaAddressPolicy.ipv4Prefix24("192.168.49.79"))
        assertNull(HeyCyanMediaAddressPolicy.ipv4Prefix24("192.168.49"))
    }
}
