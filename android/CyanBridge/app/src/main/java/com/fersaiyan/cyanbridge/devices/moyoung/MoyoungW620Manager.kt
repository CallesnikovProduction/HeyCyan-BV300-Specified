package com.fersaiyan.cyanbridge.devices.moyoung

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.BuildConfig
import com.fersaiyan.cyanbridge.localai.audio.VoiceActivityDetector
import com.fersaiyan.cyanbridge.localai.model.FreshPhotoGuard
import com.fersaiyan.cyanbridge.localai.bv300TurnOwnership
import com.moyoung.glasses.CRPBleClient
import com.moyoung.glasses.conn.CRPBleConnection
import com.moyoung.glasses.conn.CRPBleDevice
import com.moyoung.glasses.conn.callback.CRPFileDownloadCallback
import com.moyoung.glasses.conn.listener.CRPAiDialogueListener
import com.moyoung.glasses.conn.listener.CRPBleConnectionStateListener
import com.moyoung.glasses.conn.listener.CRPFeatureStateListener
import com.moyoung.glasses.conn.listener.CRPWifiChangeListener
import com.moyoung.glasses.conn.protos.RunningStatus
import com.moyoung.glasses.conn.protos.TakePhoto
import com.moyoung.glasses.conn.type.CRPWifiType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

data class MoyoungW620State(
    val connectionLabel: String = "MoYoung / W620 disconnected",
    val protocolState: String = "DISCONNECTED",
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    val batteryPercent: Int? = null,
    val isCharging: Boolean = false,
    val photoCount: Int? = null,
    val videoCount: Int? = null,
    val audioCount: Int? = null,
    val lastError: String? = null,
)

data class MoyoungWifiCredentials(
    val ssid: String,
    val password: String,
)

data class MoyoungAiDialogueAudio(
    val pcm16: ByteArray,
    val requestId: String,
    val sampleRateHz: Int = 16_000,
    val cancelledByGlasses: Boolean = false,
    val startedAtElapsedNanos: Long = 0L,
)

data class MoyoungAiDialogueStart(
    val requestId: String,
    val preemptedRequestId: String?,
)

/** Thin adapter around the published MoYoung Android SDK. */
class MoyoungW620Manager private constructor(context: Context) {
    companion object {
        private const val TAG = "MoyoungW620"
        private const val WIFI_READY_TIMEOUT_MS = 30_000L
        private const val WIFI_CONNECTION_TIMEOUT_MS = 45_000L
        private const val DOWNLOAD_TIMEOUT_MS = 180_000L
        private const val PROBE_TIMEOUT_MS = 15_000L

        @Volatile
        private var instance: MoyoungW620Manager? = null

        fun getInstance(context: Context): MoyoungW620Manager =
            instance ?: synchronized(this) {
                instance ?: MoyoungW620Manager(context.applicationContext).also { instance = it }
            }
    }

    private data class WifiStateEvent(val type: CRPWifiType, val state: Int)
    private data class DownloadFiles(val sourceDirectory: String, val names: List<String>)

    private val appContext = context.applicationContext
    private val client = CRPBleClient.create(appContext)
    private val mediaMutex = Mutex()
    private val photoMutex = Mutex()
    private val wifiStateEvents = MutableSharedFlow<WifiStateEvent>(extraBufferCapacity = 8)
    private val wifiConnectionEvents = MutableSharedFlow<Boolean>(extraBufferCapacity = 8)
    @Volatile private var pendingMediaBaseUrl: CompletableDeferred<String>? = null
    private val _aiDialogueAudio = MutableSharedFlow<MoyoungAiDialogueAudio>(extraBufferCapacity = 2)
    private val _aiDialogueStarted = MutableSharedFlow<MoyoungAiDialogueStart>(extraBufferCapacity = 2)
    private val _state = MutableStateFlow(MoyoungW620State())
    private val _aiDialogueListening = MutableStateFlow(false)
    private val _aiSpeechDetected = MutableStateFlow(false)

    private var device: CRPBleDevice? = null
    private var connection: CRPBleConnection? = null
    private val aiDialogueAudioLock = Any()
    private var aiDialogueAudioBuffer = ByteArrayOutputStream()
    private var aiDialogueAudioBytes: Long = 0L
    private var aiDialogueAudioFrames: Long = 0L
    private var aiVoiceActivityDetector = VoiceActivityDetector()
    private var aiAutoStopSent = false
    private var dialogueRequestId: String? = null
    private var dialogueStartedAtElapsedNanos: Long = 0L
    private data class PendingAiPhoto(
        val requestId: String,
        val connectionId: String,
        val image: CompletableDeferred<File> = CompletableDeferred(),
        val drained: CompletableDeferred<Unit> = CompletableDeferred(),
        var abandoned: Boolean = false,
    )
    private val pendingAiPhotoLock = Any()
    private var pendingAiPhoto: PendingAiPhoto? = null
    @Volatile var connectionSessionId: String? = null
        private set

    val state: StateFlow<MoyoungW620State> = _state.asStateFlow()
    val aiDialogueAudio: SharedFlow<MoyoungAiDialogueAudio> = _aiDialogueAudio.asSharedFlow()
    val aiDialogueStarted: SharedFlow<MoyoungAiDialogueStart> = _aiDialogueStarted.asSharedFlow()
    val aiDialogueListening: StateFlow<Boolean> = _aiDialogueListening.asStateFlow()
    val aiSpeechDetected: StateFlow<Boolean> = _aiSpeechDetected.asStateFlow()
    val activeDialogueRequestId: String?
        get() = synchronized(aiDialogueAudioLock) { dialogueRequestId }

    @Synchronized
    fun connect(address: String, deviceName: String? = null) {
        val normalizedAddress = address.trim()
        if (normalizedAddress.isBlank()) {
            updateError("No MoYoung Bluetooth address was selected")
            return
        }
        if (
            _state.value.protocolState == "CONNECTING" &&
            _state.value.deviceAddress.equals(normalizedAddress, ignoreCase = true)
        ) {
            Log.d(TAG, "Ignoring duplicate connect while the same device is still connecting")
            return
        }
        if (isConnected() && _state.value.deviceAddress.equals(normalizedAddress, ignoreCase = true)) return

        runCatching { device?.disconnect() }
        _state.value = MoyoungW620State(
            connectionLabel = "Connecting to MoYoung / W620",
            protocolState = "CONNECTING",
            deviceAddress = normalizedAddress,
            deviceName = deviceName,
        )
        try {
            val nextDevice = client.getBleDevice(normalizedAddress)
                ?: throw IOException("MoYoung SDK could not open the selected BLE device")
            val nextConnection = nextDevice.connect()
                ?: throw IOException("MoYoung SDK did not create a BLE connection")
            device = nextDevice
            connection = nextConnection
            installListeners(nextConnection)
            if (nextDevice.isConnected) onConnected(nextConnection)
        } catch (error: Throwable) {
            updateError("MoYoung connection failed: ${error.message}")
        }
    }

    fun disconnect() {
        connectionSessionId = null
        bv300TurnOwnership.clear(resetState = true)
        _aiDialogueListening.value = false
        _aiSpeechDetected.value = false
        synchronized(aiDialogueAudioLock) { dialogueRequestId = null }
        synchronized(pendingAiPhotoLock) {
            pendingAiPhoto?.image?.cancel()
            pendingAiPhoto?.drained?.cancel()
            pendingAiPhoto = null
        }
        runCatching { connection?.disableWifi() }
        runCatching { device?.disconnect() }
        device = null
        connection = null
        _state.value = MoyoungW620State()
    }

    fun isConnected(): Boolean = device?.isConnected == true && _state.value.protocolState == "CONNECTED"

    suspend fun probe(address: String, deviceName: String?): Boolean {
        connect(address, deviceName)
        val identified = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            state.filter { it.protocolState == "CONNECTED" }.first()
            true
        } == true
        if (!identified) disconnect()
        return identified
    }

    fun requestBattery() {
        connectedOrNull()?.queryBattery()
    }

    fun requestMediaCount() {
        connectedOrNull()?.queryNewMediaFile()
    }

    suspend fun captureFreshAiPhoto(requestId: String, requestStartedAtMs: Long): File = photoMutex.withLock {
        require(requestId.isNotBlank() && requestId.all { it.isLetterOrDigit() || it == '-' })
        val activeConnection = connectedOrNull() ?: throw IOException("BV300 disconnected before photo capture")
        val previousDrain = synchronized(pendingAiPhotoLock) {
            pendingAiPhoto?.takeIf { it.abandoned }?.drained
        }
        previousDrain?.let { withTimeout(30_000L) { it.await() } }
        val pending = synchronized(pendingAiPhotoLock) {
            check(pendingAiPhoto == null) {
                "Previous BV300 photo is still pending; reconnect the glasses before another visual request"
            }
            PendingAiPhoto(requestId, connectionSessionId ?: error("BV300 has no connection session")).also {
                pendingAiPhoto = it
            }
        }
        var receivedImage = false
        try {
            activeConnection.takePhoto(TakePhoto.PhotoMode.ModeAIRecognition)
            val source = withTimeout(30_000L) { pending.image.await() }
            receivedImage = true
            check(connectionSessionId == pending.connectionId) { "BV300 reconnected during photo capture" }
            check(FreshPhotoGuard.isFresh(source, requestStartedAtMs)) {
                "BV300 camera returned a stale or empty image"
            }
            val destinationDirectory = File(appContext.filesDir, "local_ai_photos")
            check(destinationDirectory.mkdirs() || destinationDirectory.isDirectory)
            val destination = File(destinationDirectory, "bv300_${requestId}.jpg")
            source.copyTo(destination, overwrite = false)
            destination
        } finally {
            synchronized(pendingAiPhotoLock) {
                if (pendingAiPhoto === pending) {
                    // A timed-out/cancelled SDK command may still deliver its image. Keep a tombstone
                    // until that callback is drained, so the next request can never consume it.
                    if (receivedImage || (pending.image.isCompleted && !pending.image.isCancelled)) {
                        pendingAiPhoto = null
                    } else {
                        pending.abandoned = true
                    }
                }
            }
        }
    }

    fun stopMediaSync() {
        runCatching { connection?.disableWifi() }
    }

    /** Reuses the SDK's FILE Wi-Fi handshake without starting its download-everything callback. */
    suspend fun <T> withMediaCatalogConnection(
        wifiCredentials: MoyoungWifiCredentials,
        block: suspend (baseUrl: String) -> T,
    ): T = mediaMutex.withLock {
        val activeConnection = connectedOrNull()
            ?: throw IOException("Connect BV300 before opening its media library")
        val baseUrl = CompletableDeferred<String>()
        pendingMediaBaseUrl = baseUrl
        try {
            coroutineScope {
                val wifiReady = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(WIFI_READY_TIMEOUT_MS) {
                        wifiStateEvents.filter {
                            it.type == CRPWifiType.FILE && it.state == CRPWifiChangeListener.STATE_SUCCESS
                        }.first()
                    }
                }
                activeConnection.enableWifi(CRPWifiType.FILE, wifiCredentials.ssid, wifiCredentials.password)
                wifiReady.await()
                delay(5_000L)
                val connected = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(WIFI_CONNECTION_TIMEOUT_MS) { wifiConnectionEvents.filter { it }.first() }
                }
                activeConnection.connectWifi()
                connected.await()
            }
            val reportedBaseUrl = withTimeout(WIFI_CONNECTION_TIMEOUT_MS) {
                while (true) {
                    val sdkUrl = sdkMediaBaseUrl()
                    if (sdkUrl != null) return@withTimeout sdkUrl
                    val callbackUrl = withTimeoutOrNull(500L) { baseUrl.await() }
                    if (callbackUrl != null) return@withTimeout callbackUrl
                }
                @Suppress("UNREACHABLE_CODE")
                error("BV300 did not report a media URL")
            }
            block(reportedBaseUrl)
        } finally {
            if (pendingMediaBaseUrl === baseUrl) pendingMediaBaseUrl = null
            runCatching { activeConnection.disableWifi() }
        }
    }

    /** Pinned MoYoung 0.0.7 SDK: its own media.config and file downloads use this URL provider. */
    private fun sdkMediaBaseUrl(): String? = runCatching {
        val providerClass = Class.forName("com.moyoung.x.e")
        val provider = providerClass.getMethod("c").invoke(null)
        providerClass.getMethod("a").invoke(provider) as? String
    }.getOrNull()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }

    suspend fun downloadMedia(
        targetDirectory: File,
        wifiCredentials: MoyoungWifiCredentials,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): List<File> = mediaMutex.withLock {
        val activeConnection = connectedOrNull()
            ?: throw IOException("Connect MoYoung / W620 glasses before syncing media")
        targetDirectory.mkdirs()
        val sdkCacheDirectory = File(appContext.filesDir, "moyoung/wifi/media_res")
        val filesBeforeDownload = sdkCacheDirectory.listFiles()
            ?.filter(File::isFile)
            ?.map(File::getName)
            ?.toSet()
            .orEmpty()
        val downloadStartedAt = System.currentTimeMillis()

        try {
            coroutineScope {
                val wifiReady = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(WIFI_READY_TIMEOUT_MS) {
                        wifiStateEvents
                            .filter { it.type == CRPWifiType.FILE && it.state == CRPWifiChangeListener.STATE_SUCCESS }
                            .first()
                    }
                }
                activeConnection.enableWifi(
                    CRPWifiType.FILE,
                    wifiCredentials.ssid,
                    wifiCredentials.password,
                )
                wifiReady.await()

                // The SDK example waits for file-sync mode to settle before requesting Android's join prompt.
                delay(5_000L)
                val wifiConnected = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(WIFI_CONNECTION_TIMEOUT_MS) {
                        wifiConnectionEvents.filter { it }.first()
                    }
                }
                activeConnection.connectWifi()
                wifiConnected.await()
            }

            val downloaded = awaitSdkDownload(activeConnection, onProgress)
            copyDownloadedFiles(
                downloaded = downloaded,
                targetDirectory = targetDirectory,
                sdkCacheDirectory = sdkCacheDirectory,
                filesBeforeDownload = filesBeforeDownload,
                downloadStartedAt = downloadStartedAt,
            )
        } finally {
            runCatching { activeConnection.disableWifi() }
        }
    }

    private fun installListeners(activeConnection: CRPBleConnection) {
        activeConnection.setConnectionStateListener { newState ->
            when (newState) {
                CRPBleConnectionStateListener.STATE_CONNECTED -> onConnected(activeConnection)
                CRPBleConnectionStateListener.STATE_CONNECTING -> {
                    _state.value = _state.value.copy(
                        connectionLabel = "Connecting to MoYoung / W620",
                        protocolState = "CONNECTING",
                    )
                }
                else -> {
                    connectionSessionId = null
                    bv300TurnOwnership.clear(resetState = true)
                    _aiDialogueListening.value = false
                    _aiSpeechDetected.value = false
                    synchronized(aiDialogueAudioLock) { dialogueRequestId = null }
                    synchronized(pendingAiPhotoLock) {
                        pendingAiPhoto?.image?.cancel()
                        pendingAiPhoto?.drained?.cancel()
                        pendingAiPhoto = null
                    }
                    _state.value = _state.value.copy(
                        connectionLabel = "MoYoung / W620 disconnected",
                        protocolState = "DISCONNECTED",
                    )
                }
            }
        }
        activeConnection.setBatteryListener { battery ->
            _state.value = _state.value.copy(
                batteryPercent = battery.lvl.coerceIn(0, 100),
                isCharging = battery.charging,
                lastError = null,
            )
        }
        activeConnection.setMediaFileChangeListener { files ->
            if (files != null) {
                _state.value = _state.value.copy(
                    photoCount = files.photoCount,
                    videoCount = files.videoCount,
                    audioCount = files.audioCount,
                    lastError = null,
                )
            }
        }
        activeConnection.setAiDialogueListener(object : CRPAiDialogueListener {
            override fun onDialogueStart() {
                val requestId = UUID.randomUUID().toString()
                _aiDialogueListening.value = true
                _aiSpeechDetected.value = false
                synchronized(aiDialogueAudioLock) {
                    dialogueRequestId = requestId
                    dialogueStartedAtElapsedNanos = android.os.SystemClock.elapsedRealtimeNanos()
                    aiDialogueAudioBuffer = ByteArrayOutputStream()
                    aiDialogueAudioBytes = 0L
                    aiDialogueAudioFrames = 0L
                    aiVoiceActivityDetector = VoiceActivityDetector()
                    aiAutoStopSent = false
                }
                val previous = bv300TurnOwnership.begin(requestId)
                _aiDialogueStarted.tryEmit(MoyoungAiDialogueStart(requestId, previous))
                Log.i(TAG, "AI dialogue started from glasses")
            }

            override fun onDialogueAudioChange(audio: ByteArray?) {
                if (audio == null) return
                val snapshot = synchronized(aiDialogueAudioLock) {
                    aiDialogueAudioBuffer.write(audio)
                    aiDialogueAudioFrames += 1L
                    aiDialogueAudioBytes += audio.size
                    val shouldStop = aiVoiceActivityDetector.accept(audio) && !aiAutoStopSent
                    if (shouldStop) aiAutoStopSent = true
                    if (aiVoiceActivityDetector.speechDetected) _aiSpeechDetected.value = true
                    Triple(aiDialogueAudioFrames, aiDialogueAudioBytes, shouldStop)
                }
                if (snapshot.third) {
                    runCatching { activeConnection.exitAIDialogue() }
                        .onFailure { Log.w(TAG, "Could not auto-end AI dialogue", it) }
                }
                if (BuildConfig.DEBUG && (snapshot.first == 1L || snapshot.first % 100L == 0L)) {
                    Log.d(
                        TAG,
                        "AI dialogue audio frames=${snapshot.first} bytes=${snapshot.second} " +
                            "lastFrame=${audio.size} rms=${aiVoiceActivityDetector.currentRms.toInt()} " +
                            "noiseFloor=${aiVoiceActivityDetector.noiseFloor.toInt()} " +
                            "speech=${aiVoiceActivityDetector.speechDetected}",
                    )
                }
            }

            override fun onDialogueImageChange(image: File?) {
                Log.i(
                    TAG,
                    "AI dialogue image path=${image?.absolutePath.orEmpty()} exists=${image?.isFile == true}",
                )
                if (image != null) synchronized(pendingAiPhotoLock) {
                    pendingAiPhoto?.let { pending ->
                        if (pending.abandoned) {
                            Log.i(TAG, "Discarding late photo for cancelled request=${pending.requestId}")
                            pendingAiPhoto = null
                            pending.drained.complete(Unit)
                        } else if (!pending.image.isCompleted) {
                            pending.image.complete(image)
                        }
                    }
                }
            }

            override fun onDialogueStop(cancelled: Boolean) {
                _aiDialogueListening.value = false
                _aiSpeechDetected.value = false
                val completed = synchronized(aiDialogueAudioLock) {
                    val audio = aiDialogueAudioBuffer.toByteArray()
                    aiDialogueAudioBuffer = ByteArrayOutputStream()
                    val requestId = dialogueRequestId
                    val startedAt = dialogueStartedAtElapsedNanos
                    dialogueRequestId = null
                    Triple(requestId, audio, startedAt)
                }
                if (BuildConfig.DEBUG) Log.d(
                    TAG,
                    "AI dialogue stopped cancelled=$cancelled reason=${aiVoiceActivityDetector.endpointReason} " +
                        "durationMs=${aiVoiceActivityDetector.durationMs} frames=$aiDialogueAudioFrames " +
                        "bytes=$aiDialogueAudioBytes speech=${aiVoiceActivityDetector.speechDetected}",
                )
                // The glasses also use AudioCancel for their 30-second capture timeout. The
                // decoded PCM received before that signal is still a valid user utterance.
                // Still let Vosk examine low-level audio: a softly spoken utterance can be
                // below the PCM gate, while Vosk may recover its words from the full buffer.
                if (completed.first != null && completed.second.isNotEmpty()) {
                    _aiDialogueAudio.tryEmit(
                        MoyoungAiDialogueAudio(
                            pcm16 = completed.second,
                            requestId = requireNotNull(completed.first),
                            cancelledByGlasses = cancelled,
                            startedAtElapsedNanos = completed.third,
                        ),
                    )
                }
            }
        })
        activeConnection.setFeatureActiveStateListener(object : CRPFeatureStateListener {
            override fun onFeatureStateChanged(status: RunningStatus) {
                Log.i(
                    TAG,
                    "Feature state " +
                        "takePicture=${status.takePicture} aiVisual=${status.aiVisual} " +
                        "audioRecording=${status.audioRecording} videoRecording=${status.videoRecording} " +
                        "fileSync=${status.fileSync} livingMode=${status.livingMode} " +
                        "slaveActive=${status.slaveActive} " +
                        "simuInterpretation=${status.simuInterpretation} " +
                        "aiDialogue=${status.aiDialogue} slaveOta=${status.slaveOta} " +
                        "jieliOta=${status.jieliOta}",
                )
            }
        })
        activeConnection.setWifiListener(object : CRPWifiChangeListener {
            override fun onWifiStateChange(type: CRPWifiType, state: Int) {
                Log.i(TAG, "Wi-Fi state type=$type state=$state")
                wifiStateEvents.tryEmit(WifiStateEvent(type, state))
            }

            override fun onWifiConnectionStateChanged(connected: Boolean) {
                Log.i(TAG, "Wi-Fi connected=$connected")
                wifiConnectionEvents.tryEmit(connected)
            }

            override fun onLiveUrlChanged(url: String?) {
                Log.d(TAG, "SDK media/live base URL=${url.orEmpty()}")
                if (url?.startsWith("http://", ignoreCase = true) == true ||
                    url?.startsWith("https://", ignoreCase = true) == true
                ) {
                    pendingMediaBaseUrl?.complete(url)
                }
            }
        })
    }

    private fun onConnected(activeConnection: CRPBleConnection) {
        if (_state.value.protocolState != "CONNECTED" || connectionSessionId == null) {
            connectionSessionId = UUID.randomUUID().toString()
        }
        _state.value = _state.value.copy(
            connectionLabel = "MoYoung / W620 connected",
            protocolState = "CONNECTED",
            lastError = null,
        )
        activeConnection.syncTime()
        activeConnection.queryBattery()
        activeConnection.queryNewMediaFile()
        activeConnection.queryFeatureActiveState()
        activeConnection.queryWearCheckState { enabled ->
            Log.i(TAG, "Wear detection enabled=$enabled")
        }
        activeConnection.queryVoiceWakeUpState { enabled ->
            Log.i(TAG, "Voice wake-up enabled=$enabled")
        }
    }

    private suspend fun awaitSdkDownload(
        activeConnection: CRPBleConnection,
        onProgress: (Int, Int) -> Unit,
    ): DownloadFiles {
        val success = CompletableDeferred<Unit>()
        val files = CompletableDeferred<DownloadFiles>()
        activeConnection.downloadMediaFile(object : CRPFileDownloadCallback {
            override fun onStart() = onProgress(0, 100)

            override fun onProgress(current: Int) = onProgress(current, 100)

            override fun onProgress(current: Int, total: Int) = onProgress(current, total)

            override fun onDownloadFile(sourceDirectory: String?, names: MutableList<String>?) {
                if (!files.isCompleted) {
                    files.complete(DownloadFiles(sourceDirectory.orEmpty(), names.orEmpty().toList()))
                }
            }

            override fun onSuccess() {
                if (!success.isCompleted) success.complete(Unit)
            }

            override fun onFail(code: Int) {
                val error = IOException("MoYoung SDK media download failed with code $code")
                if (!success.isCompleted) success.completeExceptionally(error)
                if (!files.isCompleted) files.completeExceptionally(error)
            }
        })
        return withTimeout(DOWNLOAD_TIMEOUT_MS) {
            success.await()
            files.await()
        }
    }

    private fun copyDownloadedFiles(
        downloaded: DownloadFiles,
        targetDirectory: File,
        sdkCacheDirectory: File,
        filesBeforeDownload: Set<String>,
        downloadStartedAt: Long,
    ): List<File> {
        val reportedDirectory = downloaded.sourceDirectory.takeIf(String::isNotBlank)?.let(::File)
        val names = downloaded.names.ifEmpty {
            val scanDirectory = reportedDirectory ?: sdkCacheDirectory
            scanDirectory.listFiles()
                ?.filter { file ->
                    file.isFile && if (reportedDirectory != null) {
                        file.lastModified() >= downloadStartedAt
                    } else {
                        file.name !in filesBeforeDownload || file.lastModified() >= downloadStartedAt
                    }
                }
                ?.map(File::getName)
                .orEmpty()
        }
        return names.mapNotNull { value ->
            val listed = File(value)
            val source = when {
                listed.isAbsolute -> listed
                reportedDirectory != null -> File(reportedDirectory, value)
                else -> File(sdkCacheDirectory, value)
            }
            if (!source.isFile) {
                Log.w(TAG, "SDK-reported media file is missing: ${source.absolutePath}")
                return@mapNotNull null
            }
            val safeName = source.name.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val target = File(targetDirectory, safeName)
            source.copyTo(target, overwrite = true)
            target
        }
    }

    private fun connectedOrNull(): CRPBleConnection? = connection?.takeIf { isConnected() }

    private fun updateError(message: String) {
        Log.w(TAG, message)
        _state.value = _state.value.copy(
            connectionLabel = message,
            protocolState = "ERROR",
            lastError = message,
        )
    }
}
