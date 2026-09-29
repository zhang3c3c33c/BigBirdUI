package io.bbui.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Pure, service-owned session/queue state. Viewing never changes the execution owner. */
class ConversationStore {
    val sessions = linkedMapOf<String, JSONObject>()
    val pinnedSessions = linkedSetOf<String>()
    private fun orderedSessions() = sessions.values.sortedWith(
        compareByDescending<JSONObject> { it.getString("id") in pinnedSessions }.thenByDescending { it.optLong("modified") })
    fun setPinned(id: String, pinned: Boolean) {
        if (!sessions.containsKey(id)) return
        if (pinned) pinnedSessions.add(id) else pinnedSessions.remove(id)
    }
    val chats = linkedMapOf<String, ChatStore>()
    val queue = mutableListOf<JSONObject>()
    val submissions = linkedSetOf<String>()
    val autoTitles = AutoTitleState()
    internal val viewStates = ViewStateStore()
    val drafts: MutableMap<String, JSONObject> get() = viewStates.values
    val modelSelections = linkedMapOf<String, JSONObject>()
    val modelChoices = linkedMapOf<String, JSONObject>()
    var modelOptions = JSONArray()
    var modelSelectionResult: JSONObject? = null
    val limits = mutableMapOf<String, Int>()
    val interrupted = mutableListOf<JSONObject>()
    var selected = ""
    var running: JSONObject? = null
    /** Even adding an existing reason is a new protection decision during an async save. */
    class PauseReasons : LinkedHashSet<String>() {
        var revision = 0L
            private set
        override fun add(element: String): Boolean { revision++; return super.add(element) }
        override fun clear() { revision++; super.clear() }
        override fun remove(element: String): Boolean { revision++; return super.remove(element) }
    }
    val pauseReasons = PauseReasons()
    class FreshSubmissionPause internal constructor(val submissionId: String, val controlId: String,
        val pauseRevision: Long, val reasons: Set<String>)
    fun hasProtectedWork(): Boolean = queue.isNotEmpty() || running != null || continuation != null ||
        pendingQuestion?.optString("status") == "pending" || controlMode in setOf("manual", "taking_over", "resuming", "running")
    fun prepareFreshSubmission(submissionId: String): FreshSubmissionPause? {
        if (submissionId.isBlank() || submissionId in submissions || hasProtectedWork() || pauseReasons.isEmpty()) return null
        if (pauseReasons.any { it !in setOf("stop", "environment", "lock", "error", "restart", "shizuku") }) return null
        return FreshSubmissionPause(submissionId, controlId, pauseReasons.revision, pauseReasons.toSet())
    }
    /** Called only after the new submission is durable; old queue items can never obtain this ticket. */
    fun releaseFreshSubmissionPause(ticket: FreshSubmissionPause?): Boolean {
        if (ticket == null || ticket.controlId != controlId || ticket.pauseRevision != pauseReasons.revision) return false
        if (ticket.submissionId !in submissions || queue.firstOrNull()?.optString("id") != ticket.submissionId ||
            running != null || continuation != null || pendingQuestion?.optString("status") == "pending" ||
            controlMode in setOf("manual", "taking_over", "resuming", "running")) return false
        if (pauseReasons != ticket.reasons) return false
        pauseReasons.clear(); error = ""; transition("idle")
        return true
    }
    fun restoreFreshSubmissionPause(ticket: FreshSubmissionPause) { pauseReasons.addAll(ticket.reasons) }
    /** A later durable submission must never skip an earlier submission still being saved. */
    fun nextReadySubmission(pendingSubmissions: Set<String>): JSONObject? =
        queue.firstOrNull()?.takeUnless { it.optString("id") in pendingSubmissions }
    var paused: Boolean
        get() = pauseReasons.isNotEmpty()
        set(value) { if (value) pauseReasons.add("user") else pauseReasons.clear() }
    var continuation: JSONObject? = null
    var controlId = UUID.randomUUID().toString()
    var controlMode = "idle"
    fun transition(mode: String) { controlMode = mode; controlId = UUID.randomUUID().toString() }
    fun controlSnapshot(): JSONObject = JSONObject().put("id", controlId).put("mode", controlMode)
        .put("sessionId", running?.optString("sessionId") ?: continuation?.optString("sessionId") ?: "")
        .put("runId", running?.optString("runId") ?: "")
        .put("canResume", continuation != null && controlMode in setOf("manual", "stopped", "error"))
        .put("canSteer", running != null && controlMode == "running" && pendingQuestion?.optString("status") != "pending")
    fun retainTask() { running?.let { continuation = JSONObject(it.toString()) } }

    var ready = false
    var error = ""
    var submissionResult: JSONObject? = null
    var pendingQuestion: JSONObject? = null
    var questionResult: JSONObject? = null
    val questionHistory = mutableListOf<JSONObject>()
    private var revision = 0L
    private val stateId = UUID.randomUUID().toString()

    fun chat(id: String): ChatStore = chats.getOrPut(id) {
        ChatStore().also { it.accept(JSONObject().put("type", "history_snapshot").put("sessionId", id).put("messages", JSONArray())) }
    }
    fun submit(id: String, sessionId: String, text: String): Boolean {
        require(sessions.containsKey(sessionId)) { "会话不存在" }
        require(id.isNotBlank() && text.isNotBlank()) { "任务不能为空" }
        if (!submissions.add(id)) return false
        queue.add(JSONObject().put("id", id).put("sessionId", sessionId).put("text", text).put("submittedAt", System.currentTimeMillis()))
        sessions[sessionId]?.let { item ->
            if (item.optString("title") == "新会话" && !autoTitles.isManual(sessionId)) item.put("title", text.codePoints().limit(24).toArray().let { String(it, 0, it.size) })
        }
        viewStates.clear(sessionId)
        return true
    }
    fun accept(event: JSONObject) {
        if (event.optBoolean("gate") || event.optBoolean("retired")) return
        val id = event.optString("sessionId")
        val type = event.optString("type")
        val eventRun = event.optString("runId").toLongOrNull()
        val activeRun = running?.optString("runId")?.toLongOrNull()
        if (activeRun != null && eventRun != null && eventRun < activeRun) return
        if (type == "question_requested" && running?.optString("sessionId") == id && running?.optString("runId") == event.optString("runId")) {
            pendingQuestion = JSONObject(event.toString()).apply { remove("type"); put("messageSourceKey", chat(id).questionSourceKey(event.optString("toolCallId"))) }
            questionHistory.add(pendingQuestion!!)
        }
        if (type == "question_resolved" && pendingQuestion?.optString("requestId") == event.optString("requestId")) {
            pendingQuestion?.put("status", event.optString("status"))?.put("answers", event.optJSONArray("answers") ?: JSONArray())
        }
        if (type == "run_stopped" && pendingQuestion?.optString("status") == "pending") pendingQuestion?.put("status", "interrupted")
        if (type == "run_started" && running?.optString("sessionId") == id) {
            running?.put("started", true)?.put("runId", event.optString("runId")); transition("running")
        }
        if (type == "run_stopped") { retainTask(); if (event.optString("reason") != "takeover") { pauseReasons.add("stop"); transition("stopped") } }
        if (type == "handoff_ready" && controlMode == "taking_over") { error = ""; transition("manual") }
        if (event.optString("type") == "run_stopped") running?.takeIf { !it.optBoolean("started") }?.let { interrupted.add(JSONObject(it.toString())) }
        if (id.isNotBlank() && sessions.containsKey(id)) {
            chat(id).accept(event)
            if (event.optString("type") in setOf("message_end", "run_started")) sessions[id]?.put("modified", System.currentTimeMillis())
        }
        if (type == "status" && event.optString("status") == "error") {
            retainTask(); pauseReasons.add("error"); transition("error")
        }
        val channel = event.optJSONObject("details")?.optJSONObject("通道")
        if (event.optString("type") == "execution_channel_error" || (event.optString("type") == "phone_action_result" && channel != null &&
            (channel.optString("控制") == "不可用" || channel.optString("输入故障").isNotBlank()))) {
            retainTask(); pauseReasons.add("error"); transition("error"); error = "手机执行通道异常，队列已暂停；请检查画面和连接后继续"
        }
        if (type == "agent_settled" && running?.optString("sessionId") == id && controlMode == "running") {
            continuation = null; transition("idle")
        }
        if (event.optString("type") == "run_stopped" || event.optString("type") == "agent_settled" ||
            (event.optString("type") == "status" && event.optString("status") == "error")) {
            if (event.optString("type") == "run_stopped" || running?.optString("sessionId") == id) running = null
        }
    }
    fun snapshot(): JSONObject {
        val value = chat(selected).snapshot(limits[selected] ?: 40)
        val visibleQuestions = mutableSetOf<String>()
        val questions = questionHistory.filter { it.optString("sessionId") == selected && it.optString("messageSourceKey").isNotBlank() }
            .associateBy { it.optString("messageSourceKey") to it.optString("toolCallId") }
        value.optJSONArray("messages")?.let { messages -> for (i in 0 until messages.length()) {
            val message = messages.getJSONObject(i)
            message.optJSONArray("parts")?.let { parts -> for (j in 0 until parts.length()) {
                val part = parts.getJSONObject(j)
                questions[message.optString("sourceKey") to part.optString("toolCallId")]?.let { question ->
                    part.put("questionRequestId", question.getString("requestId")); visibleQuestions.add(question.getString("requestId"))
                }
            } }
        } }
        return value.put("revision", ++revision).put("stateId", stateId).put("sessionsReady", ready).put("sessionError", error)
            .put("sessions", JSONArray(orderedSessions().map { item ->
                JSONObject(item.toString()).put("running", running?.optString("sessionId") == item.getString("id"))
                    .put("pinned", item.getString("id") in pinnedSessions)
                    .put("queued", queue.count { it.optString("sessionId") == item.getString("id") })
                    .put("state", chats[item.getString("id")]?.taskState() ?: "idle")
            })).put("queue", JSONArray(queue)).put("queuePaused", paused)
            .put("queuePauseReasons", JSONArray(pauseReasons.toList())).put("control", controlSnapshot())
            .put("modelOptions", modelOptions).put("modelSelection", modelSelections[selected] ?: JSONObject())
            .put("modelSelectionResult", modelSelectionResult ?: JSONObject.NULL)
            .put("submissionResult", submissionResult ?: JSONObject.NULL)
            .put("pendingQuestion", pendingQuestion ?: JSONObject.NULL)
            .put("questionResult", questionResult ?: JSONObject.NULL)
            .put("questionHistory", JSONArray(questionHistory.filter { it.optString("requestId") in visibleQuestions }))
            .put("runningSessionId", running?.optString("sessionId") ?: "")
            .put("execution", if (controlMode == "running") running?.let { item ->
                chats[item.optString("sessionId")]?.activeToolSnapshot(item.optString("runId"))
            } ?: JSONObject() else JSONObject())
            .put("viewState", drafts[selected] ?: JSONObject().put("text", "").put("scrollTop", 0))
            .put("interruptedTasks", JSONArray(interrupted.filter { it.optString("sessionId") == selected }))
    }
    fun save(excludingSubmissions: Set<String> = emptySet(), retainingPauseReasons: Set<String> = emptySet()): JSONObject =
        JSONObject().put("selected", selected).put("paused", paused || retainingPauseReasons.isNotEmpty())
        .put("autoTitles", autoTitles.snapshot(excludingSubmissions))
        .put("pinnedSessions", JSONArray(pinnedSessions.toList()))
        .put("pauseReasons", JSONArray((pauseReasons + retainingPauseReasons).toList())).put("continuation", continuation ?: JSONObject.NULL)
        .put("controlMode", controlMode)
        .put("pendingQuestion", pendingQuestion ?: JSONObject.NULL).put("questionHistory", JSONArray(questionHistory))
        .put("modelSelections", JSONObject(modelSelections as Map<*, *>)).put("modelChoices", JSONObject(modelChoices as Map<*, *>))
        .put("running", running ?: JSONObject.NULL).put("queue", JSONArray(queue.filter { it.optString("id") !in excludingSubmissions }))
        .put("submissions", JSONArray(submissions.filter { it !in excludingSubmissions }))
        .put("sessions", JSONArray(sessions.values.toList())).put("drafts", JSONObject(drafts as Map<*, *>))
        .put("viewStateClock", viewStates.clock).put("viewStateRevisions", viewStates.revisionsSnapshot())
        .put("chats", JSONObject(chats.mapValues { it.value.snapshot(Int.MAX_VALUE) }))
        .put("limits", JSONObject(limits as Map<*, *>)).put("interrupted", JSONArray(interrupted))

    fun restore(value: JSONObject) {
        autoTitles.restore(value.optJSONObject("autoTitles"))
        for (key in listOf("modelSelections", "modelChoices")) value.optJSONObject(key)?.let { rows -> rows.keys().forEach { (if (key == "modelSelections") modelSelections else modelChoices)[it] = rows.getJSONObject(it) } }
        selected = value.optString("selected"); running = null
        value.optJSONArray("questionHistory")?.let { rows -> for (i in 0 until rows.length()) questionHistory.add(rows.getJSONObject(i).apply { if (optString("status") == "pending") put("status", "interrupted") }) }
        pendingQuestion = value.optJSONObject("pendingQuestion")?.let { saved -> questionHistory.find { it.optString("requestId") == saved.optString("requestId") } ?: saved }.apply { if (this?.optString("status") == "pending") put("status", "interrupted") }
        value.optJSONArray("pauseReasons")?.let { rows -> for (i in 0 until rows.length()) pauseReasons.add(rows.getString(i)) }
        if (!value.has("pauseReasons") && value.optBoolean("paused") &&
            ((value.optJSONArray("queue")?.length() ?: 0) > 0 || value.optJSONObject("running") != null || value.optJSONObject("continuation") != null)) pauseReasons.add("restart")
        continuation = value.optJSONObject("running") ?: value.optJSONObject("continuation")
        if (continuation != null) transition("stopped")
        val metadata = value.optJSONArray("sessions") ?: JSONArray()
        for (i in 0 until metadata.length()) metadata.getJSONObject(i).let { sessions[it.getString("id")] = it }
        value.optJSONArray("pinnedSessions")?.let { rows -> for (i in 0 until rows.length()) setPinned(rows.getString(i), true) }
        val pending = value.optJSONArray("queue") ?: JSONArray()
        for (i in 0 until pending.length()) queue.add(pending.getJSONObject(i))
        if (queue.isNotEmpty() || continuation != null) pauseReasons.add("restart")
        val seen = value.optJSONArray("submissions") ?: JSONArray()
        for (i in 0 until seen.length()) submissions.add(seen.getString(i))
        value.optJSONArray("interrupted")?.let { rows -> for (i in 0 until rows.length()) interrupted.add(rows.getJSONObject(i)) }
        value.optJSONObject("running")?.takeIf { !it.optBoolean("started") }?.let { interrupted.add(JSONObject(it.toString())) }
        value.optJSONObject("limits")?.let { rows -> rows.keys().forEach { limits[it] = rows.optInt(it, 40).coerceAtLeast(40) } }
        viewStates.restoreMain(value.optJSONObject("drafts"), value.optJSONObject("viewStateRevisions"), value.optLong("viewStateClock", 0))
        value.optJSONObject("chats")?.let { rows -> rows.keys().forEach { id -> chats[id] = ChatStore().also { it.restore(rows.getJSONObject(id)) } } }
        value.optJSONObject("running")?.optString("sessionId")?.takeIf { it.isNotBlank() }?.let {
            chat(it).accept(JSONObject().put("type", "run_stopped"))
        }
    }
    fun remove(id: String) {
        autoTitles.remove(id)
        val ordered = orderedSessions().map { it.getString("id") }
        val index = ordered.indexOf(id)
        val neighbor = ordered.getOrNull(index + 1) ?: ordered.getOrNull(index - 1).orEmpty()
        questionHistory.removeAll { it.optString("sessionId") == id }; if (pendingQuestion?.optString("sessionId") == id) pendingQuestion = null
        sessions.remove(id); pinnedSessions.remove(id); modelSelections.remove(id); modelChoices.remove(id); chats.remove(id); viewStates.clear(id); limits.remove(id)
        queue.removeAll { it.optString("sessionId") == id }
        interrupted.removeAll { it.optString("sessionId") == id }
        if (continuation?.optString("sessionId") == id) { continuation = null; transition("idle") }
        if (selected == id) selected = neighbor
    }
}
