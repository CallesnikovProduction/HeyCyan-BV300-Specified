package com.fersaiyan.cyanbridge.localai

import android.media.MediaPlayer
import kotlinx.coroutines.delay

/** Short gain ramp on the already-buffered WAV; the new microphone is active before this runs. */
internal suspend fun fadeOutBv300Playback(player: MediaPlayer) {
    for (level in listOf(0.75f, 0.5f, 0.25f, 0f)) {
        if (runCatching { player.setVolume(level, level) }.isFailure) break
        delay(20L)
    }
}
