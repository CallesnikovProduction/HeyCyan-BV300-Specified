package com.fersaiyan.cyanbridge.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryResponseParserTest {
    private class VendorResponse(
        private val battery: Int,
        private val charging: Boolean,
    )

    @Test
    fun parsesVendorFieldsWithoutActivityState() {
        val result = BatteryResponseParser.parse(VendorResponse(64, true))

        assertEquals(64, result.battery)
        assertEquals(true, result.charging)
        assertEquals("Battery: 64% (charging)", result.message)
    }

    @Test
    fun nullResponsePreservesUnknownBattery() {
        val result = BatteryResponseParser.parse(null)

        assertNull(result.battery)
        assertNull(result.charging)
        assertEquals("Battery callback: null response", result.message)
    }
}
