package io.bbui.assistant

/** Small execution projection. It never reads the selected chat or serializes history. */
class OverlayPresentation(private val terminalDurationMs: Long = 3000) {
    data class Model(val kind: String, val label: String, val sessionId: String,
        val requestId: String? = null, val canStop: Boolean)
    data class Visibility(val enabled: Boolean, val permission: Boolean, val shizukuReady: Boolean,
        val foreground: Boolean, val locked: Boolean) {
        fun allows() = enabled && permission && shizukuReady && !foreground && !locked
    }
    private data class Owner(val sessionId: String, val submissionId: String, val runId: String)
    private var store: ConversationStore? = null
    private var owner: Owner? = null
    private var previousMode = "idle"
    private var terminal: Model? = null
    private var terminalKey = ""
    private var terminalUntil = 0L
    private var toolOwner: Owner? = null
    private var toolIntent = ""
    val nextUpdateAtMs: Long? get() = terminal?.let { terminalUntil }

    fun project(state: ConversationStore, now: Long, environmentSessionId: String = "", refreshTool: Boolean = true): Model? {
        if (store !== state) {
            store = state; owner = null; previousMode = "idle"; terminal = null; terminalKey = ""
            toolOwner = null; toolIntent = ""
        }
        val running = state.running
        val mode = state.controlMode
        val current = running?.let { Owner(it.optString("sessionId"), it.optString("id"), it.optString("runId")) }
        val stopped = mode == "stopped" || mode == "error"
        if (!stopped && mode in setOf("manual", "taking_over", "resuming")) {
            val sessionId = current?.sessionId ?: state.continuation?.optString("sessionId")?.takeIf { it.isNotBlank() }
                ?: owner?.sessionId ?: environmentSessionId
            owner = current ?: state.continuation?.let { Owner(sessionId, it.optString("id"), it.optString("runId")) }
                ?: owner ?: sessionId.takeIf { it.isNotBlank() }?.let { Owner(it, "manual", "") }
            terminal = null; terminalKey = ""; previousMode = mode
            return Model(mode, when (mode) { "manual" -> "你正在操作"; "taking_over" -> "正在交接"; else -> "正在恢复" }, sessionId, canStop = true)
        }
        if (!stopped && current != null && current.sessionId.isNotBlank()) {
            owner = current; previousMode = "running"; terminal = null; terminalKey = ""
            if (refreshTool || current != toolOwner) {
                toolOwner = current
                toolIntent = state.chats[current.sessionId]?.activeToolSnapshot(current.runId)?.optString("intent").orEmpty()
                    .replace(Regex("\\s+"), " ").trim().take(120)
            }
            val question = state.pendingQuestion?.takeIf { it.optString("status") == "pending" &&
                it.optString("sessionId") == current.sessionId && it.optString("runId") == current.runId &&
                it.optString("requestId").isNotBlank() && it.optString("toolCallId").isNotBlank() }
            return if (question != null) Model("waiting", "需要你回答", current.sessionId, question.optString("requestId"), true)
                else Model("running", toolIntent.ifBlank { "处理中" }, current.sessionId, canStop = true)
        }
        val prior = owner
        val kind = when {
            mode == "error" || (prior != null && "error" in state.pauseReasons) -> "error"
            mode == "stopped" -> "stopped"
            prior != null && previousMode == "running" && mode == "idle" -> "complete"
            else -> ""
        }
        if (prior != null && kind.isNotBlank()) {
            val key = "${prior.sessionId}:${prior.submissionId}:${prior.runId}:$kind"
            if (key != terminalKey) {
                terminalKey = key; terminalUntil = now + terminalDurationMs
                terminal = Model(kind, when (kind) { "error" -> "执行出错"; "stopped" -> "已停止"; else -> "本轮回复结束" }, prior.sessionId, canStop = false)
            }
        } else { terminal = null; terminalKey = ""; owner = null }
        if (terminal != null && now >= terminalUntil) { terminal = null; owner = null }
        return terminal
    }
}
