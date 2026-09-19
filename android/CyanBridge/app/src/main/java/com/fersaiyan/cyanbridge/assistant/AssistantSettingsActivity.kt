package com.fersaiyan.cyanbridge.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AssistantSettingsActivity : AppCompatActivity() {
    private val transport = ChatGPTAccountTransport()
    private var manualMode by mutableStateOf(false)
    private var conversationName by mutableStateOf("BV300 Glasses")
    private var diagnosticsExpanded by mutableStateOf(false)
    private var browserOpened by mutableStateOf(false)
    private var copiedText by mutableStateOf<String?>(null)
    private var voiceTestArmed by mutableStateOf(false)
    private var requestsModel by mutableStateOf("auto")
    private var questionsModel by mutableStateOf("auto")
    private var tasksModel by mutableStateOf("auto")
    private var systemPrompt by mutableStateOf("")
    private var availableModels by mutableStateOf(listOf("auto", "google/gemini-3.1-flash-live-preview"))
    private var modelCatalogStatus by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manualMode = AssistantPreferences.manualMode(this)
        conversationName = AssistantPreferences.conversationName(this)
        requestsModel = ProSubscriptionAiPrefs.getRequestsModel(this)
        questionsModel = ProSubscriptionAiPrefs.getQuestionsModel(this)
        tasksModel = ProSubscriptionAiPrefs.getTasksModel(this)
        systemPrompt = ProSubscriptionAiPrefs.getSystemPrompt(this)
        availableModels = (availableModels + requestsModel + questionsModel + tasksModel).distinct()
        browserOpened = savedInstanceState?.getBoolean("browser_opened") ?: false
        val appearancePreferences = AppearancePreferences(this)
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            CyanBridgeTheme(appearance) { Screen() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("browser_opened", browserOpened)
        super.onSaveInstanceState(outState)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen() {
        val assistant by AssistantRuntime.snapshot.collectAsState()
        val device by DiagnosticsStore.state.collectAsState()
        Scaffold(topBar = { TopAppBar(title = { Text("BV300 Assistant") }) }) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                    .padding(PaddingValues(horizontal = 20.dp, vertical = 12.dp)),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("ChatGPT account", style = MaterialTheme.typography.titleMedium)
                Text(if (browserOpened) "ChatGPT opened · account verification unavailable" else "Not connected")
                Text("Automatic account messaging is not supported. Your password and browser session stay outside CyanBridge.")
                Button(onClick = ::openChatGpt) { Text("Connect ChatGPT · open browser") }

                OutlinedTextField(
                    value = conversationName,
                    onValueChange = {
                        conversationName = it.take(80)
                        AssistantPreferences.setConversationName(this@AssistantSettingsActivity, conversationName)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Desired conversation") },
                    supportingText = { Text("Local preference only · remote conversation not verified") },
                    singleLine = true,
                )
                Text("Remote conversation: Not verified")
                Text("Glasses: ${if (device.connectionStatus == DiagnosticsConnectionStatus.CONNECTED) "Connected" else "Not confirmed connected"}")
                Text("Input trigger: BV300 assistant button (observed)")
                Text("Microphone: active only after BV300 trigger")
                Text("Speech recognition: ${assistant.lastStt} · local model required")
                Text("Speech output: local TTS to BV300 · ${assistant.lastTts}")
                Text("Assistant: ${assistant.phase.name}")

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

                Text("Manual ChatGPT handoff", style = MaterialTheme.typography.titleMedium)
                androidx.compose.foundation.layout.Row {
                    Text("Prepare BV300 speech for review", modifier = Modifier.weight(1f))
                    Switch(checked = manualMode, onCheckedChange = {
                        manualMode = it
                        AssistantPreferences.setManualMode(this@AssistantSettingsActivity, it)
                    })
                }
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

                assistant.utterance?.takeIf { assistant.phase == AssistantPhase.READY_TO_SEND }?.let { utterance ->
                    Text("Message prepared · review before sharing", style = MaterialTheme.typography.titleMedium)
                    Text(utterance.text)
                    Button(onClick = { shareText(utterance.text) }) { Text("Share prepared text") }
                    OutlinedButton(onClick = { copyAndOpenChatGpt(utterance.text) }) {
                        Text("Copy text and open ChatGPT")
                    }
                    copiedText?.let {
                        Text("Copied text remains on the Android clipboard until replaced or cleared.")
                        TextButton(onClick = ::clearCopiedText) { Text("Clear copied text") }
                    }
                    Text("Select the BV300 Glasses chat yourself. CyanBridge cannot verify or read its response.")
                    TextButton(onClick = { AssistantRuntime.update { reset() } }) { Text("Discard prepared message") }
                }
                assistant.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { diagnosticsExpanded = !diagnosticsExpanded }) {
                    Text(if (diagnosticsExpanded) "Hide assistant diagnostics" else "Assistant diagnostics")
                }
                if (diagnosticsExpanded) {
                    Text("Transport: ChatGPTAccountTransport")
                    Text("Login handoff: YES · Open ChatGPT: YES")
                    Text("Automatic send: NO · Automatic receive: NO")
                    Text("Last trigger: ${assistant.lastTrigger}")
                    Text("Last STT: ${assistant.lastStt} · Last TTS: ${assistant.lastTts}")
                }
            }
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
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/"))
        runCatching { startActivity(intent) }
            .onSuccess {
                transport.connect()
                browserOpened = true
            }
            .onFailure { Toast.makeText(this, "No browser is available", Toast.LENGTH_LONG).show() }
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
