package com.fersaiyan.cyanbridge.agent

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fersaiyan.cyanbridge.ui.OfficialHeyCyanWarningDialog
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReleaseDialogsAndLiveModeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun officialAppWarningOffersSafeAndExplicitSyncActions() {
        var openedAppInfo = false
        var syncAnyway = false
        var warningSuppressed = false
        composeRule.setContent {
            CyanBridgeTheme {
                OfficialHeyCyanWarningDialog(
                    onDismissRequest = {},
                    onOpenAppInfo = { openedAppInfo = true },
                    onSyncAnyway = { suppress ->
                        syncAnyway = true
                        warningSuppressed = suppress
                    },
                )
            }
        }

        composeRule.onNodeWithText("HeyCyan may interrupt sync").assertExists()
        composeRule.onNodeWithText("Open App info").performClick()
        composeRule.runOnIdle { assertTrue(openedAppInfo) }

        composeRule.onNodeWithText("Don't remind me again").performClick()
        composeRule.onNodeWithText("Sync anyway").performClick()
        composeRule.runOnIdle {
            assertTrue(syncAnyway)
            assertTrue(warningSuppressed)
        }
    }

}
