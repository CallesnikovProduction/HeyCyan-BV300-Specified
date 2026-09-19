package com.fersaiyan.cyanbridge.ai.router

import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaInferenceRoutingPolicyTest {
    @Test
    fun localSelectionNeverFallsBackToCloudWithoutConsent() {
        assertEquals(
            AgentProviderType.LOCAL_AGENT,
            MediaInferenceRoutingPolicy.resolve(AgentProviderType.LOCAL_AGENT, true),
        )
        assertEquals(
            AgentProviderType.LOCAL_AGENT,
            MediaInferenceRoutingPolicy.resolve(AgentProviderType.LOCAL_AGENT, false),
        )
    }

    @Test
    fun taskerLocalModelsUsesCapableLocalRuntime() {
        assertEquals(
            AgentProviderType.LOCAL_AGENT,
            MediaInferenceRoutingPolicy.resolve(
                preferred = AgentProviderType.TASKER,
                localMediaAvailable = true,
                taskerUsesLocalModels = true,
            ),
        )
    }

    @Test
    fun explicitRelaySelectionStaysOnRelay() {
        assertEquals(
            AgentProviderType.PRO_SUBSCRIPTION,
            MediaInferenceRoutingPolicy.resolve(
                preferred = AgentProviderType.PRO_SUBSCRIPTION,
                localMediaAvailable = false,
            ),
        )
        assertEquals(
            AgentProviderType.PRO_SUBSCRIPTION,
            MediaInferenceRoutingPolicy.resolve(
                preferred = AgentProviderType.PRO_SUBSCRIPTION,
                localMediaAvailable = true,
            ),
        )
    }
}
