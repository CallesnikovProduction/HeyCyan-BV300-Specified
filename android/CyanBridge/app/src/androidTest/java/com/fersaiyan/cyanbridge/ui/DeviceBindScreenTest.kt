package com.fersaiyan.cyanbridge.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.ScannedDevice
import com.fersaiyan.cyanbridge.shared.ui.DeviceBindScreen
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DeviceBindScreenTest {
    @get:Rule val composeRule = createComposeRule()

    private val bv300 = ScannedDevice(
        macAddress = "AA:BB:CC:DD:EE:FF",
        advertisedName = "BV300",
        rssi = -54,
        detectedClass = DeviceClass.MOYOUNG_W620,
        selectedClass = null,
        userOverridden = false,
    )

    @Test fun scanShowsOnlyBv300FlowWithoutMacOrOtherProtocolPicker() {
        composeRule.setContent {
            CyanBridgeTheme {
                DeviceBindScreen(
                    devices = listOf(bv300), isScanning = false, connectingDevice = null,
                    onScan = {}, onSelectDevice = {}, onConfirmConnection = {},
                    onDismissConnection = {}, onBack = {},
                )
            }
        }
        composeRule.onNodeWithText("Connect BV300").assertExists()
        composeRule.onNodeWithText("BV300").assertExists()
        composeRule.onAllNodesWithText(bv300.macAddress).assertCountEquals(0)
        composeRule.onAllNodesWithText("Pair Meta Glasses").assertCountEquals(0)
    }

    @Test fun confirmationInvokesBv300Connection() {
        var confirmed = false
        composeRule.setContent {
            CyanBridgeTheme {
                DeviceBindScreen(
                    devices = listOf(bv300), isScanning = false, connectingDevice = bv300,
                    onScan = {}, onSelectDevice = {},
                    onConfirmConnection = { confirmed = true }, onDismissConnection = {}, onBack = {},
                )
            }
        }
        composeRule.onNodeWithText("Connect BV300?").assertExists()
        composeRule.onNodeWithTag("bv300_pair_confirm").performClick()
        composeRule.runOnIdle { assertTrue(confirmed) }
    }
}
