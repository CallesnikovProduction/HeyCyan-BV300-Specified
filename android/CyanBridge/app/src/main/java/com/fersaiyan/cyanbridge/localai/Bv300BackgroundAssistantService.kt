package com.fersaiyan.cyanbridge.localai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.moyoung.MoyoungW620Manager
import com.fersaiyan.cyanbridge.assistant.Bv300AudioPreprocessor
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelsProvider
import com.fersaiyan.cyanbridge.localai.stt.VoskRussianSpeechRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Explicitly armed while the app is visible; owns BV300 turns when its UI is stopped. */
class Bv300BackgroundAssistantService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var manager: MoyoungW620Manager
    private var turn: Job? = null
    @Volatile private var serviceRequestId: String? = null
    @Volatile private var turnRequestId: String? = null
    private val playerLock = Any()
    private var player: MediaPlayer? = null
    private var playbackRequestId: String? = null
    private val orchestrator by lazy {
        LocalAiOrchestrator(
            context = applicationContext,
            stt = VoskRussianSpeechRecognizer(applicationContext),
            captureFreshPhoto = { requestId, started -> manager.captureFreshAiPhoto(requestId, started) },
        )
    }

    override fun onCreate() {
        super.onCreate()
        manager = MoyoungW620Manager.getInstance(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "BV300 background assistant", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "start action=${intent?.action} connected=${manager.isConnected()} selected=${DeviceProfileStore.isMoyoungW620Selected(this)} state=${manager.state.value.protocolState}")
        if (intent?.action != ACTION_START || !manager.isConnected() || !DeviceProfileStore.isMoyoungW620Selected(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (active.value) return START_NOT_STICKY
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, javaClass).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("BV300 assistant is ready")
            .setContentText("Glasses button listens; tap Stop to turn off")
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .build()
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                else 0,
            )
        } catch (error: Throwable) {
            Log.e(TAG, "Cannot start BV300 foreground assistant", error)
            stopSelf()
            return START_NOT_STICKY
        }
        active.value = true
        scope.launch {
            manager.aiDialogueStarted.collect { event ->
                val requestId = event.requestId
                val previous = event.preemptedRequestId
                serviceRequestId = requestId
                if (previous != null) {
                    val oldTurn = turn.takeIf { turnRequestId == previous }
                    if (oldTurn != null) scope.launch { LocalModelsProvider().cancelGeneration() }
                    fadeOutPlayback(previous)
                    oldTurn?.cancel(CancellationException("BV300 button preempted $previous"))
                }
                Log.i(TAG, "Listening requestId=$requestId preempted=${previous != null}")
            }
        }
        scope.launch {
            manager.aiSpeechDetected.collect { detected ->
                if (detected) manager.activeDialogueRequestId?.let { requestId ->
                    bv300TurnOwnership.updateIfCurrent(requestId) { it.copy(phase = LocalAiPhase.SPEECH_DETECTED) }
                }
            }
        }
        scope.launch {
            manager.state.collect { state ->
                if (state.protocolState != "CONNECTED") {
                    Log.i(TAG, "BV300 state changed to ${state.protocolState}; stopping background assistant")
                    turn?.cancel(CancellationException("BV300 disconnected"))
                    stopPlayback()
                    stopSelf()
                }
            }
        }
        scope.launch {
            manager.aiDialogueAudio.collect { audio ->
                if (!bv300TurnOwnership.isCurrent(audio.requestId)) return@collect
                turnRequestId = audio.requestId
                turn = launch { runTurn(audio.pcm16, audio.sampleRateHz, audio.requestId, audio.startedAtElapsedNanos) }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runTurn(pcm16: ByteArray, sampleRateHz: Int, requestId: String, startedAtNanos: Long) {
        val timings = Bv300StreamingTimings(requestId, startedAtNanos)
        val wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:bv300-turn")
        try {
            wakeLock.acquire(MAX_TURN_MS)
            withTimeout(MAX_TURN_MS) {
                val prepared = Bv300AudioPreprocessor.prepare(pcm16, sampleRateHz, compactSilence = false)
                val result = Bv300StreamingPipeline(
                    requestId = requestId,
                    ownership = bv300TurnOwnership,
                    synthesize = { sentence ->
                        bv300TurnOwnership.requireCurrent(requestId)
                        bv300TurnOwnership.updateIfCurrent(requestId) {
                            it.copy(phase = LocalAiPhase.SPEAKING, ttsSynthesizing = true)
                        }
                        timings.synthesisStarted()
                        try {
                            val file = File(cacheDir, "bv300_${requestId}_${sentence.index}.wav")
                            try {
                                SupertonicTts.synthesize(this@Bv300BackgroundAssistantService, sentence.text, file)
                                bv300TurnOwnership.requireCurrent(requestId)
                                file
                            } catch (error: Throwable) {
                                file.delete()
                                throw error
                            }
                        } finally {
                            bv300TurnOwnership.updateIfCurrent(requestId) { state -> state.copy(ttsSynthesizing = false) }
                        }
                    },
                    play = { audio -> playThroughGlasses(audio.file, requestId, timings) },
                    onSpeechError = { error -> Log.e(TAG, "BV300 streaming speech chunk failed", error) },
                ).run { emit -> orchestrator.process(prepared.bytes, sampleRateHz, requestId, timings, emit) }
                bv300TurnOwnership.requireCurrent(requestId)
                timings.finalAudioFinished()
                if (result.speechFailures == 0) {
                    bv300TurnOwnership.finish(requestId, resetState = true)
                } else {
                    bv300TurnOwnership.updateIfCurrent(requestId) {
                        it.copy(phase = LocalAiPhase.ERROR, error = "Some BV300 speech chunks could not be played")
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "BV300 background turn failed", error)
            bv300TurnOwnership.updateIfCurrent(requestId) {
                it.copy(phase = LocalAiPhase.ERROR, error = error.message ?: "Background assistant failed")
            }
        } finally {
            bv300TurnOwnership.finish(requestId)
            if (turnRequestId == requestId) turnRequestId = null
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private suspend fun playThroughGlasses(file: File, requestId: String, timings: Bv300StreamingTimings) {
        val output = bv300Output() ?: error("BV300 audio output is unavailable")
        var playback: MediaPlayer? = null
        try {
            currentCoroutineContext().ensureActive()
            bv300TurnOwnership.requireCurrent(requestId)
            withTimeout(90_000) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val current = MediaPlayer()
                    playback = current
                    synchronized(playerLock) {
                        player = current
                        playbackRequestId = requestId
                    }
                    try {
                        current.setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        current.setDataSource(file.absolutePath)
                        check(current.setPreferredDevice(output)) { "BV300 audio route rejected" }
                        current.setOnCompletionListener {
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                        current.setOnErrorListener { _, _, _ ->
                            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("BV300 playback failed"))
                            true
                        }
                        current.prepare()
                        bv300TurnOwnership.requireCurrent(requestId)
                        current.start()
                        timings.firstAudio()
                        bv300TurnOwnership.updateIfCurrent(requestId) {
                            it.copy(audioPlaying = true)
                        }
                        current.routedDevice?.let { routed ->
                            check(routed.id == output.id) { "Audio routed away from BV300" }
                        }
                        Log.i(TAG, "Playing through ${current.routedDevice?.productName}")
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                    continuation.invokeOnCancellation { runCatching { current.release() } }
                }
            }
        } finally {
            synchronized(playerLock) {
                if (player === playback) {
                    player = null
                    playbackRequestId = null
                }
            }
            playback?.let { runCatching { it.release() } }
            bv300TurnOwnership.updateIfCurrent(requestId) { it.copy(audioPlaying = false) }
        }
    }

    private suspend fun fadeOutPlayback(requestId: String) {
        val current = synchronized(playerLock) { player.takeIf { playbackRequestId == requestId } }
        if (current != null) fadeOutBv300Playback(current)
        stopPlayback(requestId)
    }

    private fun stopPlayback(requestId: String? = null) {
        synchronized(playerLock) {
            if (requestId != null && playbackRequestId != requestId) return
            player?.let { runCatching { it.release() } }
            player = null
            playbackRequestId = null
        }
    }

    private fun bv300Output(): AudioDeviceInfo? {
        val selectedAddress = DeviceProfileStore.loadLastSelected(this)?.macAddress.orEmpty()
        val outputs = (getSystemService(AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outputs.firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP &&
                (device.productName?.toString()?.equals("BV300", ignoreCase = true) == true ||
                    (selectedAddress.isNotBlank() && device.address.equals(selectedAddress, ignoreCase = true)))
        } ?: outputs.firstOrNull { device ->
            (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)) &&
                device.productName?.toString()?.equals("BV300", ignoreCase = true) == true
        }
    }

    override fun onDestroy() {
        active.value = false
        turn?.cancel()
        stopPlayback()
        scope.cancel()
        serviceRequestId?.let { bv300TurnOwnership.finish(it, resetState = true) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "BV300BackgroundAI"
        private const val CHANNEL = "bv300_background_assistant"
        private const val NOTIFICATION_ID = 7044
        private const val MAX_TURN_MS = 2L * 60_000L
        private const val ACTION_START = "com.fersaiyan.cyanbridge.bv300.BACKGROUND_START"
        private const val ACTION_STOP = "com.fersaiyan.cyanbridge.bv300.BACKGROUND_STOP"
        private val active = MutableStateFlow(false)
        val isRunning = active.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, Bv300BackgroundAssistantService::class.java).setAction(ACTION_START),
            )
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, Bv300BackgroundAssistantService::class.java))
        }
    }
}
