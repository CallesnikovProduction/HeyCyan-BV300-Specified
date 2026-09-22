package com.fersaiyan.cyanbridge.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.diagnostics.DiagnosticsConnectionStatus
import com.fersaiyan.cyanbridge.diagnostics.DiagnosticsStore
import com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionRelayClient
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import com.fersaiyan.cyanbridge.localai.Bv300BackgroundAssistantService
import com.fersaiyan.cyanbridge.devices.moyoung.MoyoungW620Manager
import com.fersaiyan.cyanbridge.localai.LocalAiPhase
import com.fersaiyan.cyanbridge.localai.LocalAiRuntime
import com.fersaiyan.cyanbridge.localai.SupertonicVoicePrefs
import com.fersaiyan.cyanbridge.localai.model.LocalModelManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AssistantSettingsActivity : AppCompatActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startBackgroundAssistantIfReady()
        else Toast.makeText(this, "Notifications are required for the visible BV300 background control", Toast.LENGTH_LONG).show()
    }
    private var manualMode by mutableStateOf(false)
    private var autoSendMode by mutableStateOf(false)
    private var testPrompt by mutableStateOf("Answer with one word: ready")
    private var diagnosticsExpanded by mutableStateOf(false)
    private var copiedText by mutableStateOf<String?>(null)
    private var voiceTestArmed by mutableStateOf(false)
    private var requestsModel by mutableStateOf("auto")
    private var questionsModel by mutableStateOf("auto")
    private var tasksModel by mutableStateOf("auto")
    private var systemPrompt by mutableStateOf("")
    private var availableModels by mutableStateOf(listOf("auto", "google/gemini-3.1-flash-live-preview"))
    private var modelCatalogStatus by mutableStateOf("")
    private var localVoiceId by mutableStateOf(0)
    private var localVoiceSpeed by mutableStateOf(1.0f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manualMode = AssistantPreferences.manualMode(this)
        autoSendMode = AssistantPreferences.autoSendMode(this)
        requestsModel = ProSubscriptionAiPrefs.getRequestsModel(this)
        questionsModel = ProSubscriptionAiPrefs.getQuestionsModel(this)
        tasksModel = ProSubscriptionAiPrefs.getTasksModel(this)
        systemPrompt = ProSubscriptionAiPrefs.getSystemPrompt(this)
        availableModels = (availableModels + requestsModel + questionsModel + tasksModel).distinct()
        localVoiceId = SupertonicVoicePrefs.speakerId(this)
        localVoiceSpeed = SupertonicVoicePrefs.speed(this)
        val appearancePreferences = AppearancePreferences(this)
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            CyanBridgeTheme(appearance) { Screen() }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen() {
        val assistant by AssistantRuntime.snapshot.collectAsState()
        val chatGptStatus by ChatGptUiAutomation.status.collectAsState()
        val lastChatGptReply by ChatGptUiAutomation.lastReply.collectAsState()
        val device by DiagnosticsStore.state.collectAsState()
        val backgroundAssistantRunning by Bv300BackgroundAssistantService.isRunning.collectAsState()
        Scaffold(topBar = { TopAppBar(title = { Text("BV300 Assistant") }) }) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                    .padding(PaddingValues(horizontal = 20.dp, vertical = 12.dp)),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("ChatGPT app", style = MaterialTheme.typography.titleMedium)
                Text("The installed ChatGPT app handles your sign-in. BV300 never receives your account credentials.")
                Button(onClick = ::openChatGpt) { Text("Open ChatGPT app") }
                Text("UI bridge: $chatGptStatus")
                lastChatGptReply?.let { Text("Last ChatGPT reply: $it") }
                OutlinedButton(onClick = {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text("Enable BV300 ChatGPT accessibility") }

                Text("Messages go to the current visible ChatGPT conversation; there is no chat ID binding.")
                Text("Glasses: ${if (device.connectionStatus == DiagnosticsConnectionStatus.CONNECTED) "Connected" else "Not confirmed connected"}")
                Text("Input trigger: BV300 assistant button (observed)")
                Text("Microphone: active only after BV300 trigger")
                Text("Speech recognition: ${assistant.lastStt} · local model required")
                Text("Speech output: local TTS to BV300 · ${assistant.lastTts}")
                Text("Assistant: ${assistant.phase.name}")

                Text("BV300 local assistant in background", style = MaterialTheme.typography.titleMedium)
                Text("Enable while BV300 is connected. The glasses button then works over other apps and with the screen off. The ongoing notification has a Stop action. Voice and photos stay local.")
                androidx.compose.foundation.layout.Row {
                    Text("Keep BV300 assistant ready", modifier = Modifier.weight(1f))
                    Switch(checked = backgroundAssistantRunning, onCheckedChange = { enabled ->
                        if (enabled) {
                            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                                    this@AssistantSettingsActivity, Manifest.permission.POST_NOTIFICATIONS,
                                ) != PackageManager.PERMISSION_GRANTED) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else startBackgroundAssistantIfReady()
                        } else Bv300BackgroundAssistantService.stop(this@AssistantSettingsActivity)
                    })
                }

                Text("Offline BV300 voice", style = MaterialTheme.typography.titleMedium)
                Text(if (LocalModelManager.hasSupertonic(this@AssistantSettingsActivity))
                    "Supertonic 3 is installed. Russian and English use the same selected voice."
                    else "Supertonic 3 is missing; import it in Local Models before using the local assistant.")
                var voiceMenuExpanded by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { voiceMenuExpanded = true }) {
                    Text("Voice: ${SupertonicVoicePrefs.femaleVoices[localVoiceId]}")
                }
                DropdownMenu(expanded = voiceMenuExpanded, onDismissRequest = { voiceMenuExpanded = false }) {
                    SupertonicVoicePrefs.femaleVoices.forEachIndexed { id, name ->
                        DropdownMenuItem(text = { Text(name) }, onClick = {
                            voiceMenuExpanded = false
                            localVoiceId = id
                            SupertonicVoicePrefs.setSpeakerId(this@AssistantSettingsActivity, id)
                        })
                    }
                }
                var speedMenuExpanded by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { speedMenuExpanded = true }) { Text("Voice speed: ${localVoiceSpeed}×") }
                DropdownMenu(expanded = speedMenuExpanded, onDismissRequest = { speedMenuExpanded = false }) {
                    listOf(0.8f, 1.0f, 1.2f, 1.4f).forEach { speed ->
                        DropdownMenuItem(text = { Text("${speed}×") }, onClick = {
                            speedMenuExpanded = false
                            localVoiceSpeed = speed
                            SupertonicVoicePrefs.setSpeed(this@AssistantSettingsActivity, speed)
                        })
                    }
                }

                Text("AI model settings", style = MaterialTheme.typography.titleMedium)
                Text("These preferences control the configured AI relay; they do not change the local STT or TTS models.")
                ModelChoice("Requests model", requestsModel) {
                    requestsModel = it
                    ProSubscriptionAiPrefs.setRequestsModel(this@AssistantSettingsActivity, it)
                }
                ModelChoice("Image and voice questions model", questionsModel) {
                    questionsModel = it
                    ProSubscriptionAiPrefs.setQuestionsModel(this@AssistantSettingsActivity, it)
                }
                ModelChoice("Tasks model", tasksModel) {
                    tasksModel = it
                    ProSubscriptionAiPrefs.setTasksModel(this@AssistantSettingsActivity, it)
                }
                OutlinedButton(onClick = ::refreshModels) { Text("Refresh available models") }
                if (modelCatalogStatus.isNotBlank()) Text(modelCatalogStatus)
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = {
                        systemPrompt = it.take(4000)
                        ProSubscriptionAiPrefs.setSystemPrompt(this@AssistantSettingsActivity, systemPrompt)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("AI system prompt") },
                    minLines = 3,
                )
                TextButton(onClick = {
                    ProSubscriptionAiPrefs.resetSystemPrompt(this@AssistantSettingsActivity)
                    systemPrompt = ProSubscriptionAiPrefs.getSystemPrompt(this@AssistantSettingsActivity)
                }) { Text("Reset system prompt") }

                Text("BV300 voice to ChatGPT", style = MaterialTheme.typography.titleMedium)
                androidx.compose.foundation.layout.Row {
                    Text("Capture BV300 speech for review", modifier = Modifier.weight(1f))
                    Switch(checked = manualMode, onCheckedChange = {
                        manualMode = it
                        AssistantPreferences.setManualMode(this@AssistantSettingsActivity, it)
                        if (!it) {
                            autoSendMode = false
                            AssistantPreferences.setAutoSendMode(this@AssistantSettingsActivity, false)
                        }
                    })
                }
                androidx.compose.foundation.layout.Row {
                    Text("Send automatically after BV300 transcription", modifier = Modifier.weight(1f))
                    Switch(checked = autoSendMode, onCheckedChange = {
                        autoSendMode = it
                        AssistantPreferences.setAutoSendMode(this@AssistantSettingsActivity, it)
                        if (it) manualMode = true
                    })
                }
                Text("The phone must be unlocked. Automation stops if ChatGPT already has an unsent draft.")
                OutlinedButton(enabled = manualMode, onClick = {
                    voiceTestArmed = true
                    Toast.makeText(this@AssistantSettingsActivity,
                        "Press the BV300 assistant button and speak. Return here to review the text.",
                        Toast.LENGTH_LONG).show()
                }) { Text("Test voice input") }
                if (voiceTestArmed) {
                    Text(if (assistant.phase == AssistantPhase.READY_TO_SEND && assistant.lastTrigger == "BV300") {
                        "Voice test: transcription ready for review"
                    } else if (assistant.phase == AssistantPhase.ERROR) {
                        "Voice test: failed · ${assistant.error.orEmpty()}"
                    } else {
                        "Voice test: waiting for BV300 assistant button"
                    })
                }
                OutlinedButton(onClick = ::testSpeechOutput) { Text("Test speech output") }
                OutlinedTextField(
                    value = testPrompt,
                    onValueChange = { testPrompt = it.take(4000) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("ChatGPT round-trip test message") },
                )
                OutlinedButton(onClick = { sendToChatGpt(testPrompt, UtteranceSource.PHONE) }) {
                    Text("Test send → reply → BV300 audio")
                }

                assistant.utterance?.takeIf {
                    assistant.phase == AssistantPhase.READY_TO_SEND || assistant.phase == AssistantPhase.ERROR
                }?.let { utterance ->
                    Text("Message prepared · review before sending", style = MaterialTheme.typography.titleMedium)
                    Text(utterance.text)
                    Button(onClick = { shareText(utterance.text) }) { Text("Share prepared text") }
                    Button(onClick = { sendToChatGpt(utterance.text, utterance.source) }) {
                        Text("Send to ChatGPT and speak reply")
                    }
                    OutlinedButton(onClick = { copyAndOpenChatGpt(utterance.text) }) {
                        Text("Copy text and open ChatGPT")
                    }
                    copiedText?.let {
                        Text("Copied text remains on the Android clipboard until replaced or cleared.")
                        TextButton(onClick = ::clearCopiedText) { Text("Clear copied text") }
                    }
                    Text("Automatic UI mode uses the currently visible ChatGPT conversation.")
                    TextButton(onClick = { AssistantRuntime.update { reset() } }) { Text("Discard prepared message") }
                }
                assistant.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { diagnosticsExpanded = !diagnosticsExpanded }) {
                    Text(if (diagnosticsExpanded) "Hide assistant diagnostics" else "Assistant diagnostics")
                }
                if (diagnosticsExpanded) {
                    Text("Transport: ChatGPT accessibility UI bridge")
                    Text("Automatic send/receive: ${if (ChatGptUiAutomation.isAvailable()) "READY" else "OFF"}")
                    Text("Last trigger: ${assistant.lastTrigger}")
                    Text("Last STT: ${assistant.lastStt} · Last TTS: ${assistant.lastTts}")
                }
            }
        }
    }

    private fun startBackgroundAssistantIfReady() {
        if (MoyoungW620Manager.getInstance(this).isConnected() &&
            LocalAiRuntime.state.value.phase in listOf(LocalAiPhase.IDLE, LocalAiPhase.ERROR)) {
            runCatching { Bv300BackgroundAssistantService.start(this) }
                .onFailure { Toast.makeText(this, it.message ?: "Could not start background assistant", Toast.LENGTH_LONG).show() }
        } else {
            Toast.makeText(this, "Connect BV300 and finish the current request first", Toast.LENGTH_LONG).show()
        }
    }

    @Composable
    private fun ModelChoice(label: String, selected: String, onSelect: (String) -> Unit) {
        var expanded by remember { mutableStateOf(false) }
        Text(label)
        OutlinedButton(onClick = { expanded = true }) { Text(selected) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            availableModels.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model) },
                    onClick = {
                        expanded = false
                        onSelect(model)
                    },
                )
            }
        }
    }

    private fun refreshModels() {
        modelCatalogStatus = "Loading models…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ProSubscriptionRelayClient.fetchModelCatalog(this@AssistantSettingsActivity)
            }
            result.onSuccess { catalog ->
                availableModels = (listOf("auto") + catalog.models.map { it.id } +
                    requestsModel + questionsModel + tasksModel).distinct()
                modelCatalogStatus = "Loaded ${catalog.models.size} models"
            }.onFailure {
                modelCatalogStatus = "Could not load models: ${it.message ?: "unknown error"}"
            }
        }
    }

    private fun openChatGpt() {
        val intent = packageManager.getLaunchIntentForPackage(ChatGptUiAutomation.PACKAGE_NAME)
            ?: run {
                Toast.makeText(this, "ChatGPT app is not installed", Toast.LENGTH_LONG).show()
                return
            }
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, "Could not open ChatGPT", Toast.LENGTH_LONG).show() }
    }

    private fun sendToChatGpt(text: String, source: UtteranceSource) {
        if (text.isBlank()) return
        if (AssistantRuntime.snapshot.value.phase != AssistantPhase.READY_TO_SEND) {
            AssistantRuntime.update {
                reset()
                startListening(source)
                transcribing()
                prepare(text, System.currentTimeMillis(), source)
            }
        }
        if (!ChatGptUiAutomation.send(this, text)) {
            Toast.makeText(this, ChatGptUiAutomation.status.value, Toast.LENGTH_LONG).show()
        }
    }

    private fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { startActivity(Intent.createChooser(intent, "Share prepared message")) }
            .onFailure { Toast.makeText(this, "No sharing app is available", Toast.LENGTH_LONG).show() }
    }

    private fun copyAndOpenChatGpt(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("BV300 message", text))
        copiedText = text
        openChatGpt()
    }

    private fun clearCopiedText() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val current = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()
        if (current == copiedText) {
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
        copiedText = null
    }

    private fun testSpeechOutput() {
        val intent = Intent(this, com.fersaiyan.cyanbridge.MainActivity::class.java).apply {
            action = com.fersaiyan.cyanbridge.MainActivity.ACTION_TEST_BV300_SPEECH
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }
}
