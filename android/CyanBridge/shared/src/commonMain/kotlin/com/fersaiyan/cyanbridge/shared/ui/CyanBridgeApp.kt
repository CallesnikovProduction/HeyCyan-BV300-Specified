package com.fersaiyan.cyanbridge.shared.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardAction
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardUiState
import com.fersaiyan.cyanbridge.shared.glasses.GlassesSyncFlow
import com.fersaiyan.cyanbridge.shared.generated.resources.Res
import com.fersaiyan.cyanbridge.shared.generated.resources.action_open
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.ui.glasses.GlassesDashboardScreen
import com.fersaiyan.cyanbridge.shared.ui.glasses.GlassesSyncFlowPickerDialog

/**
 * Root composable for the CyanBridge app.
 *
 * Renders the shared bottom navigation shell and routes to the appropriate
 * screen for each [AppDestination].
 *
 * Non-dashboard destinations open their Android Activity presenters.
 */
@Composable
fun CyanBridgeApp(
    initialDestination: AppDestination = AppDestination.GLASSES,
    dashboardState: GlassesDashboardUiState = GlassesDashboardUiState(),
    onDashboardAction: (GlassesDashboardAction) -> Unit = {},
    showSyncFlowPicker: Boolean = false,
    onSyncFlowPickerDismiss: () -> Unit = {},
    onSyncFlowSelected: (GlassesSyncFlow) -> Unit = {},
    onNavigateToActivity: (AppDestination) -> Unit = {},
) {
    var currentDestination by remember(initialDestination) { mutableStateOf(initialDestination) }
    CyanBridgeNavShell(
        currentDestination = currentDestination,
        onNavigate = { destination ->
            if (destination == AppDestination.GLASSES) {
                currentDestination = destination
            } else {
                onNavigateToActivity(destination)
            }
        },
    ) { destination ->
        when (destination) {
            AppDestination.GLASSES -> {
                GlassesDashboardScreen(
                    state = dashboardState,
                    onAction = onDashboardAction,
                )
                if (showSyncFlowPicker) {
                    GlassesSyncFlowPickerDialog(
                        onDismissRequest = onSyncFlowPickerDismiss,
                        onFlowSelected = onSyncFlowSelected,
                    )
                }
            }

            AppDestination.CHATS,
            AppDestination.MEDIA,
            AppDestination.PLUGINS,
            AppDestination.SETTINGS,
            -> {
                ActivityLaunchPlaceholder(
                    title = localizedDestinationLabel(destination),
                    subtitle = localizedDestinationSubtitle(destination),
                    onOpen = { onNavigateToActivity(destination) },
                )
            }
        }
    }
}

@Composable
private fun ActivityLaunchPlaceholder(
    title: String,
    subtitle: String,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(48.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onOpen) {
            Text(org.jetbrains.compose.resources.stringResource(Res.string.action_open))
        }
    }
}
