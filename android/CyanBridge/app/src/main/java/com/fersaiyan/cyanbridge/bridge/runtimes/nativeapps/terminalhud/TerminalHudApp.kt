package com.fersaiyan.cyanbridge.bridge.runtimes.nativeapps.terminalhud

import android.util.Log
import com.fersaiyan.cyanbridge.bridge.core.DisplayCommand
import com.fersaiyan.cyanbridge.bridge.core.GlassesBridge
import com.fersaiyan.cyanbridge.bridge.core.GestureType
import com.fersaiyan.cyanbridge.bridge.core.InputEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Native app runtime that displays agent status on the glasses.
 *
 * Receives agent state updates and renders them as DisplayCommands
 * through the GlassesBridge.
 *
 * Display format:
 * ```
 * CLAUDE · CyanBridge
 * Working on bridge/core/GlassesBridge.kt
 * ─────────────────
 * > Adding error handling
 * > Fixed import paths
 * > Building...
 * ```
 *
 * Permission format:
 * ```
 * Permission needed
 * Edit device adapter
 * [ALLOW] [DENY]
 * ```
 */
object TerminalHudApp {

    private const val TAG = "TerminalHudApp"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(TerminalHudState())
    val state: StateFlow<TerminalHudState> = _state.asStateFlow()

    private var isActive = false

    /**
     * Update the terminal HUD state and push to glasses.
     */
    fun update(newState: TerminalHudState) {
        _state.value = newState
        if (isActive) {
            scope.launch { render(newState) }
        }
    }

    /**
     * Start the terminal HUD — begin sending state to glasses.
     */
    fun start() {
        isActive = true
        Log.i(TAG, "Terminal HUD started")
        scope.launch { render(_state.value) }
    }

    /**
     * Stop the terminal HUD — clear display and stop sending.
     */
    fun stop() {
        isActive = false
        Log.i(TAG, "Terminal HUD stopped")
        scope.launch {
            GlassesBridge.clearDisplay()
        }
    }

    /**
     * Render the current state to the glasses display.
     */
    private suspend fun render(state: TerminalHudState) {
        val adapter = GlassesBridge.getActiveAdapter() ?: return

        if (state.pendingPermission != null) {
            renderPermission(state)
            return
        }

        renderStatus(state)
    }

    private suspend fun renderStatus(state: TerminalHudState) {
        val lines = mutableListOf<String>()

        // Header line: provider + repo
        val header = buildString {
            append(state.provider.label.uppercase())
            if (state.repoName.isNotEmpty()) {
                append(" \u00B7 ")
                append(state.repoName)
            }
        }
        lines.add(header)

        // Status line
        lines.add(state.status.label)

        // Separator
        lines.add("\u2500".repeat(20))

        // Recent lines (last 3-4)
        val displayLines = state.recentLines.takeLast(4)
        for (line in displayLines) {
            // Truncate long lines to fit glasses display
            val truncated = if (line.length > 40) {
                line.take(37) + "..."
            } else {
                line
            }
            lines.add("> $truncated")
        }

        GlassesBridge.showLines(DisplayCommand.Lines(lines))
    }

    private suspend fun renderPermission(state: TerminalHudState) {
        val perm = state.pendingPermission ?: return

        val title = "Permission needed"
        val body = buildString {
            append(perm.description)
            append("\n\n")
            append("[${perm.allowLabel}]  [${perm.denyLabel}]")
        }

        GlassesBridge.showCard(DisplayCommand.Card(title, body))
    }

    /**
     * Handle a user input event from the glasses.
     * Maps gestures to ALLOW/DENY for permission prompts.
     */
    fun handleInput(event: InputEvent) {
        val state = _state.value
        if (state.pendingPermission == null) return

        when (event) {
            is InputEvent.Touch -> {
                when (event.gesture) {
                    GestureType.SWIPE_RIGHT -> {
                        // ALLOW
                        Log.i(TAG, "Permission ALLOWED via swipe right")
                        _state.value = state.copy(pendingPermission = null)
                        scope.launch { render(_state.value) }
                        // TODO: notify the agent that permission was granted
                    }
                    GestureType.SWIPE_LEFT -> {
                        // DENY
                        Log.i(TAG, "Permission DENIED via swipe left")
                        _state.value = state.copy(pendingPermission = null)
                        scope.launch { render(_state.value) }
                        // TODO: notify the agent that permission was denied
                    }
                    else -> {}
                }
            }
            is InputEvent.Button -> {
                when (event.gesture) {
                    GestureType.SINGLE_TAP -> {
                        // ALLOW on single tap
                        Log.i(TAG, "Permission ALLOWED via button tap")
                        _state.value = state.copy(pendingPermission = null)
                        scope.launch { render(_state.value) }
                    }
                    GestureType.LONG_PRESS -> {
                        // DENY on long press
                        Log.i(TAG, "Permission DENIED via long press")
                        _state.value = state.copy(pendingPermission = null)
                        scope.launch { render(_state.value) }
                    }
                    else -> {}
                }
            }
            else -> {}
        }
    }
}
