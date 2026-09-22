package com.fersaiyan.cyanbridge.localai

import kotlinx.coroutines.CancellationException

/** One request may publish assistant state, chat output or audio at a time. */
class Bv300TurnOwnership {
    private var currentRequestId: String? = null

    @Synchronized fun begin(requestId: String): String? {
        require(requestId.isNotBlank())
        if (currentRequestId == requestId) return null
        val previous = currentRequestId
        currentRequestId = requestId
        LocalAiRuntime.beginListening()
        return previous
    }

    @Synchronized fun isCurrent(requestId: String): Boolean = currentRequestId == requestId

    @Synchronized fun updateIfCurrent(requestId: String, transform: (LocalAiSnapshot) -> LocalAiSnapshot) {
        if (currentRequestId == requestId) LocalAiRuntime.update(transform)
    }

    /** Linearizes a final chat write with a button press, so an invalidated turn cannot publish. */
    @Synchronized fun publishIfCurrent(requestId: String, publish: () -> Unit): Boolean {
        if (currentRequestId != requestId) return false
        publish()
        return true
    }

    fun requireCurrent(requestId: String) {
        if (!isCurrent(requestId)) throw CancellationException("BV300 request $requestId was preempted")
    }

    @Synchronized fun finish(requestId: String, resetState: Boolean = false): Boolean {
        if (currentRequestId != requestId) return false
        currentRequestId = null
        if (resetState) LocalAiRuntime.reset()
        return true
    }

    @Synchronized fun clear(resetState: Boolean = false) {
        currentRequestId = null
        if (resetState) LocalAiRuntime.reset()
    }
}

/** Shared by the visible Activity and the explicitly armed foreground service. */
internal val bv300TurnOwnership = Bv300TurnOwnership()
