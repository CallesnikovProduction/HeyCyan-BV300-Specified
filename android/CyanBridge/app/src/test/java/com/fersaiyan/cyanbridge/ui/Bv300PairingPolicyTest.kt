package com.fersaiyan.cyanbridge.ui

import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Bv300PairingPolicyTest {
    @Test fun acceptsBv300NameProtocolAndRememberedAddress() {
        assertTrue(Bv300PairingPolicy.isCandidate(DeviceClass.UNKNOWN, "BV300", "AA", null))
        assertTrue(Bv300PairingPolicy.isCandidate(DeviceClass.MOYOUNG_W620, null, "AA", null))
        assertTrue(Bv300PairingPolicy.isCandidate(DeviceClass.UNKNOWN, null, "AA", "aa"))
    }

    @Test fun rejectsOtherGlassesAndHeadphones() {
        assertFalse(Bv300PairingPolicy.isCandidate(DeviceClass.EYEVUE, "EyeVue", "AA", null))
        assertFalse(Bv300PairingPolicy.isCandidate(DeviceClass.GENERIC_AUDIO, "JBL", "AA", "BB"))
    }
}
