package com.fersaiyan.cyanbridge.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.fersaiyan.cyanbridge.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An explicit, foreground-only bridge to the installed ChatGPT app. It never reads other apps. */
object ChatGptUiAutomation {
    const val PACKAGE_NAME = "com.openai.chatgpt"
    private const val TIMEOUT_MS = 90_000L

    private val mutableStatus = MutableStateFlow("Accessibility service is not enabled")
    val status = mutableStatus.asStateFlow()
    private val mutableLastReply = MutableStateFlow<String?>(null)
    val lastReply = mutableLastReply.asStateFlow()
    private var service: ChatGptUiAutomationService? = null
    private var request: Request? = null

    internal data class Request(
        val text: String,
        val startedAt: Long,
        var stage: Stage = Stage.OPENING,
        var baselineReply: String? = null,
        var candidateReply: String? = null,
        var candidateSince: Long = 0L,
        var textSetAt: Long = 0L,
    )

    internal enum class Stage { OPENING, TEXT_SET, SENT }

    fun isAvailable(): Boolean = service != null

    @Synchronized
    fun send(context: Context, text: String): Boolean {
        val active = service ?: run {
            mutableStatus.value = "Enable BV300 ChatGPT accessibility in Android settings"
            return false
        }
        if (request != null) {
            mutableStatus.value = "A ChatGPT message is already in progress"
            return false
        }
        val normalized = text.trim()
        if (normalized.isEmpty() || normalized.length > 4000) {
            mutableStatus.value = "Message must contain 1–4000 characters"
            return false
        }
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (keyguard.isDeviceLocked) {
            mutableStatus.value = "Unlock the phone before sending to ChatGPT"
            return false
        }
        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME) ?: run {
            mutableStatus.value = "Install and sign in to the ChatGPT app"
            return false
        }
        request = Request(normalized, android.os.SystemClock.elapsedRealtime())
        mutableLastReply.value = null
        mutableStatus.value = "Opening ChatGPT…"
        return runCatching {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            active.scheduleInspection()
            true
        }.getOrElse { error ->
            request = null
            mutableStatus.value = "Could not open ChatGPT: ${error.message.orEmpty()}"
            false
        }
    }

    @Synchronized
    internal fun attach(value: ChatGptUiAutomationService) {
        service = value
        mutableStatus.value = "ChatGPT accessibility ready"
        Log.i("ChatGptUiBridge", "Accessibility attached")
    }

    @Synchronized
    internal fun detach(value: ChatGptUiAutomationService) {
        if (service !== value) return
        service = null
        val wasWaiting = request != null
        request = null
        mutableStatus.value = "ChatGPT accessibility disconnected"
        Log.i("ChatGptUiBridge", "Accessibility detached; pending=$wasWaiting")
        if (wasWaiting && AssistantRuntime.snapshot.value.phase == AssistantPhase.WAITING_FOR_ASSISTANT) {
            AssistantRuntime.update { fail("ChatGPT accessibility disconnected") }
        }
    }

    @Synchronized
    internal fun current(): Request? = request

    @Synchronized
    internal fun updateStatus(value: String) {
        mutableStatus.value = value
        Log.i("ChatGptUiBridge", value)
    }

    @Synchronized
    internal fun fail(reason: String) {
        request = null
        mutableStatus.value = reason
        Log.w("ChatGptUiBridge", reason)
        if (AssistantRuntime.snapshot.value.phase == AssistantPhase.WAITING_FOR_ASSISTANT) {
            AssistantRuntime.update { fail(reason) }
        }
    }

    @Synchronized
    internal fun finish(reply: String, context: Context) {
        request = null
        mutableLastReply.value = reply
        mutableStatus.value = "ChatGPT reply received; playing on BV300"
        Log.i("ChatGptUiBridge", "Reply received; starting BV300 playback")
        if (AssistantRuntime.snapshot.value.phase == AssistantPhase.WAITING_FOR_ASSISTANT) {
            AssistantRuntime.update { speaking() }
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_SPEAK_CHATGPT_REPLY
            putExtra(MainActivity.EXTRA_CHATGPT_REPLY, reply)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            mutableStatus.value = "Reply received, but Android could not open BV300 playback"
            AssistantRuntime.update { fail("Could not start BV300 playback") }
        }
    }

    internal fun timedOut(request: Request): Boolean =
        android.os.SystemClock.elapsedRealtime() - request.startedAt >= TIMEOUT_MS
}

class ChatGptUiAutomationService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val inspectRunnable = Runnable { inspect() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ChatGptUiAutomation.attach(this)
    }

    override fun onDestroy() {
        handler.removeCallbacks(inspectRunnable)
        ChatGptUiAutomation.detach(this)
        super.onDestroy()
    }

    override fun onInterrupt() {
        ChatGptUiAutomation.fail("ChatGPT accessibility was interrupted")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() == ChatGptUiAutomation.PACKAGE_NAME &&
            ChatGptUiAutomation.current() != null) scheduleInspection()
    }

    internal fun scheduleInspection() {
        handler.removeCallbacks(inspectRunnable)
        handler.postDelayed(inspectRunnable, 350L)
    }

    private fun inspect() {
        val request = ChatGptUiAutomation.current() ?: return
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (keyguard.isDeviceLocked) {
            ChatGptUiAutomation.fail("Phone locked before ChatGPT exchange finished")
            return
        }
        if (ChatGptUiAutomation.timedOut(request)) {
            ChatGptUiAutomation.fail("Timed out waiting for ChatGPT; check sign-in and network")
            return
        }
        val root = rootInActiveWindow
        if (root?.packageName?.toString() != ChatGptUiAutomation.PACKAGE_NAME) {
            handler.postDelayed(inspectRunnable, 700L)
            return
        }
        root.refresh()
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collect(root, nodes)
        when (request.stage) {
            ChatGptUiAutomation.Stage.OPENING -> prepareComposer(request, nodes)
            ChatGptUiAutomation.Stage.TEXT_SET -> sendPrepared(request, nodes)
            ChatGptUiAutomation.Stage.SENT -> receiveReply(request, nodes)
        }
        if (ChatGptUiAutomation.current() === request) handler.postDelayed(inspectRunnable, 700L)
    }

    private fun prepareComposer(request: ChatGptUiAutomation.Request, nodes: List<AccessibilityNodeInfo>) {
        val composer = nodes.lastOrNull { it.isEditable && it.isVisibleToUser && it.className?.toString() == "android.widget.EditText" }
            ?: return
        val currentText = composer.text?.toString().orEmpty()
        if (currentText.isNotBlank() && currentText != request.text) {
            ChatGptUiAutomation.fail("ChatGPT has an unsent draft; clear it before BV300 sends a message")
            return
        }
        request.baselineReply = latestVisibleReply(nodes, request.text)
        if (currentText == request.text || composer.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, request.text) },
            )) {
            request.stage = ChatGptUiAutomation.Stage.TEXT_SET
            request.textSetAt = android.os.SystemClock.elapsedRealtime()
            ChatGptUiAutomation.updateStatus("Message prepared in ChatGPT")
        } else {
            ChatGptUiAutomation.fail("ChatGPT did not accept accessibility text input")
        }
    }

    private fun sendPrepared(request: ChatGptUiAutomation.Request, nodes: List<AccessibilityNodeInfo>) {
        nodes.filter { it.isEditable }.forEach { it.refresh() }
        val composer = nodes.lastOrNull { it.isEditable && it.isVisibleToUser && it.className?.toString() == "android.widget.EditText" && it.text?.toString() == request.text }
            ?: run {
                Log.d("ChatGptUiBridge", "Composer not verified: " + nodes.filter { it.isEditable }.joinToString { "length=${it.text?.length}, visible=${it.isVisibleToUser}, top=${Rect().also(it::getBoundsInScreen).top}" })
                return
            }
        val send = nodes.lastOrNull { node ->
            val label = node.contentDescription?.toString().orEmpty().lowercase()
            label == "отправить сообщение" || label == "send message"
        }
        var target: AccessibilityNodeInfo? = send
        while (target != null && !target.isClickable) target = target.parent
        val clickedByNode = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        if (!clickedByNode && android.os.SystemClock.elapsedRealtime() - request.textSetAt < 1_500L) return
        val clicked = if (clickedByNode) {
            true
        } else {
            // ChatGPT sometimes exposes a stale node tree after ACTION_SET_TEXT. The text above
            // is verified first; this fallback taps the send affordance beside that composer.
            val bounds = Rect().also(composer::getBoundsInScreen)
            val width = resources.displayMetrics.widthPixels
            val x = width * 0.897f
            val y = bounds.bottom + width * 0.043f
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
                .build()
            Log.i("ChatGptUiBridge", "Using verified-composer send gesture")
            dispatchGesture(gesture, null, null)
        }
        if (clicked) {
            request.stage = ChatGptUiAutomation.Stage.SENT
            ChatGptUiAutomation.updateStatus("Waiting for ChatGPT response…")
            AssistantRuntime.update { waiting() }
        } else {
            ChatGptUiAutomation.fail("ChatGPT send button is unavailable")
        }
    }

    private fun receiveReply(request: ChatGptUiAutomation.Request, nodes: List<AccessibilityNodeInfo>) {
        val anchoredReply = visibleReply(nodes, request.text)
        val reply = anchoredReply ?: latestVisibleReply(nodes, request.text)
            ?.takeIf { request.baselineReply != null && it != request.baselineReply }
            ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (reply != request.candidateReply) {
            request.candidateReply = reply
            request.candidateSince = now
            return
        }
        if (now - request.candidateSince >= 900L) ChatGptUiAutomation.finish(reply, this)
    }

    private fun visibleReply(nodes: List<AccessibilityNodeInfo>, prompt: String): String? {
        val promptIndex = nodes.indexOfLast { node ->
            node.className?.toString() == "android.widget.TextView" &&
                node.text?.toString()?.trim() == prompt
        }
        if (promptIndex < 0) return null
        val copy = nodes.withIndex().lastOrNull { (index, node) ->
            index > promptIndex && isCopyAction(node)
        }?.value ?: return null
        return replyForCopyAction(copy, prompt)
    }

    private fun latestVisibleReply(nodes: List<AccessibilityNodeInfo>, prompt: String): String? =
        nodes.lastOrNull(::isCopyAction)?.let { replyForCopyAction(it, prompt) }

    private fun isCopyAction(node: AccessibilityNodeInfo): Boolean =
        node.contentDescription?.toString()?.lowercase() in listOf("скопировать", "copy")

    private fun replyForCopyAction(copy: AccessibilityNodeInfo, prompt: String): String? {
        var group = copy.parent
        while (group != null) {
            val descendants = mutableListOf<AccessibilityNodeInfo>()
            collect(group, descendants)
            val parts = descendants.mapNotNull { node ->
                if (node.className?.toString() != "android.widget.TextView") return@mapNotNull null
                node.text?.toString()?.trim()?.takeIf { it.isNotBlank() && it != prompt }
            }
            if (parts.isNotEmpty()) return parts.joinToString("\n")
            group = group.parent
        }
        return null
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        out.add(node)
        for (index in 0 until node.childCount) node.getChild(index)?.let { collect(it, out) }
    }
}
