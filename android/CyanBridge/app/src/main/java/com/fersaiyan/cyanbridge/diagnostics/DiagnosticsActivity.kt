package com.fersaiyan.cyanbridge.diagnostics

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.BuildConfig
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DiagnosticsActivity : AppCompatActivity() {
    private var transportStatusJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticsStore.refreshPlatformSnapshot(this)
        val appearancePreferences = AppearancePreferences(this)
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            val state by DiagnosticsStore.state.collectAsState()
            CyanBridgeTheme(appearance) {
                DiagnosticsScreen(
                    liveState = state,
                    showSimulationControls = BuildConfig.DEBUG,
                    onBack = ::finish,
                    onClear = DiagnosticsStore::clearEvents,
                    onSimulate = {
                        DiagnosticsStore.simulate("Unknown raw event", byteArrayOf(0x55, 0x01, 0x7F, 0x00))
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        DiagnosticsStore.refreshPlatformSnapshot(this)
    }

    override fun onStart() {
        super.onStart()
        transportStatusJob = lifecycleScope.launch {
            while (isActive) {
                DiagnosticsStore.refreshTransportStatus()
                delay(2_000L)
            }
        }
    }

    override fun onStop() {
        transportStatusJob?.cancel()
        transportStatusJob = null
        super.onStop()
    }
}
