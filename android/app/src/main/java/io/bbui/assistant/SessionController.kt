package io.bbui.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Owns durable submissions and projects Pi history without ever replaying phone actions. */
class SessionController(private val context: Context, private val coordinator: AppCoordinator, private val changed: () -> Unit) {
    val state = ConversationStore()
    private val main = Handler(Looper.getMainLooper())
    private val disk = Executors.newSingleThreadExecutor()
    private val file = AtomicFile(File(context.noBackupFilesDir, "conversations.json"))
    private val viewStateFile = AtomicFile(File(context.noBackupFilesDir, "view-state.json"))
    private val settings = SettingsStore(context)
    private var pendingDelete = ""
    private var closed = false
    private val loaded = mutableSetOf<String>()
    internal var testConfig: JSONObject? = null
    fun environmentWaitingForUser(sessionId: String): Boolean = sessionId.isNotBlank() &&
        state.chats[sessionId]?.taskState() == "waiting_user"

    fun environmentLost(message: String) {
        if (!state.hasProtectedWork()) { state.error = message; publish(); return }
        state.error = "$message；执行环境已失效，请恢复连接后手动继续"
        stop("environment")
    }
    fun permissionChanged(availability: ShizukuAvailability) {
        if (testConfig != null || availability == ShizukuAvailability.READY) return
        if ("shizuku" !in state.pauseReasons) {
            state.error = "${ShizukuMonitor.message(availability)}，执行已停止；恢复后请手动继续"
            stop("shizuku")
        }
    }
    private val pendingSubmissions = mutableSetOf<String>()
    private val pendingQueuedSteering = mutableSetOf<String>()
    private val durablePendingSubmissions = mutableSetOf<String>()
    private val pendingDraftClears = mutableMapOf<String, ViewStateStore.PendingClear>()
    private var pendingPauseRelease: ConversationStore.FreshSubmissionPause? = null
    private fun persistenceSnapshot(includingSubmission: String? = null): ByteArray {
        // Unrelated saves cannot commit a provisional pause release on another send's behalf.
        val retainedPause = pendingPauseRelease?.takeIf { it.submissionId != includingSubmission }?.reasons ?: emptySet()
        val excluded = pendingSubmissions.filterTo(mutableSetOf()) { it !in durablePendingSubmissions && it != includingSubmission }
        val snapshot = state.save(excluded, retainedPause)
        ViewStateStore.restoreExcludedClears(snapshot, excluded.mapNotNull { pendingDraftClears[it] })
        return snapshot.toString().toByteArray(Charsets.UTF_8)
    }
    private val persistence = SessionPersistenceQueue(::persistenceSnapshot) { bytes, done ->
        disk.execute { val ok = writeSnapshot(bytes); main.post { done(ok) } }
    }
    private var viewStateDirty = false
    private val saveViewState = Runnable { flushViewState() }
    private fun persistViewStateSoon(delay: Long = 250) {
        viewStateDirty = true
        main.removeCallbacks(saveViewState); main.postDelayed(saveViewState, delay)
    }
    private fun flushViewState() {
        if (closed || !viewStateDirty) return
        // A pending send clears its draft in memory. Its queue claim must become durable before
        // independently saving that clear; failure restores the draft with a newer revision.
        if (pendingSubmissions.isNotEmpty()) { main.postDelayed(saveViewState, 250); return }
        val smallSnapshot = state.viewStates.snapshot()
        viewStateDirty = false
        disk.execute {
            val ok = writeAtomic(viewStateFile, smallSnapshot.toString().toByteArray(Charsets.UTF_8))
            if (!ok) main.post { if (!closed) {
                viewStateDirty = true
                state.error = "阅读位置与输入草稿保存失败"
                changed() // A cache failure does not pause or mutate the execution queue.
            } }
        }
    }
    private var deferredPersist = false
    private val saveDeferred = Runnable { deferredPersist = false; if (!closed) persist() }
    private fun persistSoon(delay: Long) {
        if (!deferredPersist) { deferredPersist = true; main.postDelayed(saveDeferred, delay) }
    }

    init {
        runCatching { file.openRead().bufferedReader().use { state.restore(JSONObject(it.readText())) } }
        if (state.sessions.isEmpty()) {
            val legacy = File(context.noBackupFilesDir, "chat-display.json")
            runCatching { JSONObject(legacy.readText()) }.getOrNull()?.let { old ->
                val id = old.optString("sessionId")
                if (id.isNotBlank()) { state.selected = id; state.chats[id] = ChatStore().also { it.restore(old) } }
            }
        }
        runCatching { viewStateFile.openRead().bufferedReader().use { state.viewStates.mergeSidecar(JSONObject(it.readText()), state.sessions.keys) } }
    }
    fun settingsChanged() {
        state.modelOptions = settings.modelOptions()
        val default = settings.registry().getJSONObject("defaultSelection")
        for (id in state.sessions.keys) if (state.modelSelections[id]?.optString("connectionId").isNullOrBlank()) state.modelSelections[id] = JSONObject(default.toString())
        for (item in state.queue) if (!item.has("modelBinding") && testConfig == null) runCatching {
            item.put("modelBinding", settings.binding(state.modelSelections[item.getString("sessionId")] ?: default))
        }.onFailure { state.error = it.message.orEmpty(); state.pauseReasons.add("error") }
        publish()
    }
    private fun selectModel(command: JSONObject) {
        val id = command.optString("sessionId")
        val result = JSONObject().put("id", command.optString("requestId")).put("sessionId", id)
        val priorSelection = state.modelSelections[id]?.let { JSONObject(it.toString()) }
        val priorChoices = state.modelChoices[id]?.let { JSONObject(it.toString()) }
        runCatching {
            require(command.optString("requestId").isNotBlank() && state.sessions.containsKey(id)) { "会话不存在" }
            val selection = JSONObject((state.modelSelections[id] ?: JSONObject()).toString())
            if (command.getString("type") == "selectModel") {
                selection.put("connectionId", command.getString("connectionId")).put("modelId", command.getString("modelId"))
                selection.put("thinkingLevel", state.modelChoices[id]?.optString("${selection.getString("connectionId")}/${selection.getString("modelId")}") ?: "")
            } else selection.put("thinkingLevel", command.getString("thinkingLevel"))
            settings.binding(selection)
            val option = (0 until state.modelOptions.length()).map { state.modelOptions.getJSONObject(it) }.firstOrNull {
                it.getString("connectionId") == selection.getString("connectionId") && it.getString("modelId") == selection.getString("modelId")
            } ?: error("模型不可用")
            val level = selection.optString("thinkingLevel")
            require(level.isBlank() || (0 until option.getJSONArray("thinkingLevels").length()).any { option.getJSONArray("thinkingLevels").getJSONObject(it).getString("id") == level }) { "模型不支持该思考档位" }
            state.modelSelections[id] = selection
            state.modelChoices.getOrPut(id) { JSONObject() }.put("${selection.getString("connectionId")}/${selection.getString("modelId")}", level)
            result.put("accepted", true)
        }.onFailure { result.put("accepted", false).put("error", it.message.orEmpty()) }
        if (!result.optBoolean("accepted")) { state.modelSelectionResult = result; changed(); return }
        persist(after = { state.modelSelectionResult = result; changed() }, onFailure = {
            if (priorSelection == null) state.modelSelections.remove(id) else state.modelSelections[id] = priorSelection
            if (priorChoices == null) state.modelChoices.remove(id) else state.modelChoices[id] = priorChoices
            state.modelSelectionResult = result.put("accepted", false).put("error", "模型选择保存失败")
            changed()
        })
    }
    fun refresh(): Unit = request(JSONObject().put("action", "list")) { data ->
        val rows = data.getJSONArray("sessions")
        state.sessions.clear()
        for (i in 0 until rows.length()) rows.getJSONObject(i).let { state.sessions[it.getString("id")] = it }
        state.ready = true
        settingsChanged()
        if (!state.sessions.containsKey(state.selected)) state.selected = state.sessions.values.maxByOrNull { it.optLong("modified") }?.getString("id").orEmpty()
        if (state.selected.isBlank()) create() else { load(state.selected); publish() }
    }
    private fun request(command: JSONObject, done: (JSONObject) -> Unit) {
        coordinator.sessionCommand(command) { response ->
            if (!closed) {
                if (response.optBoolean("success")) {
                    state.error = ""
                    runCatching { done(response.optJSONObject("data") ?: JSONObject()) }.onFailure { state.error = it.message.orEmpty(); publish() }
                    main.post { drain() }
                } else { state.error = response.optString("error", "会话操作失败"); publish { drain() } }
            }
        }
    }
    private fun create(): Unit = request(JSONObject().put("action", "create")) { data ->
        val id = data.getString("sessionId")
        state.sessions[id] = JSONObject().put("id", id).put("title", "新会话").put("modified", System.currentTimeMillis())
        state.autoTitles.created(id)
        state.selected = id; refresh()
    }
    private fun load(id: String) {
        if (id in loaded || state.running?.optString("sessionId") == id) return
        val originalRun = state.chat(id).currentRunId()
        request(JSONObject().put("action", "history").put("sessionId", id)) { data ->
            if (state.sessions.containsKey(id) && state.running?.optString("sessionId") != id && state.chat(id).currentRunId() == originalRun) {
                state.chat(id).accept(JSONObject().put("type", "history_snapshot").put("sessionId", id).put("messages", data.getJSONArray("messages")))
                loaded.add(id); publish()
            }
        }
    }
    fun command(command: JSONObject) {
        val id = command.optString("sessionId")
        when (command.optString("type")) {
            "answerQuestion" -> {
                val result = JSONObject().put("requestId", command.optString("requestId")).put("sessionId", command.optString("sessionId")).put("runId", command.optString("runId"))
                runCatching { check(coordinator.answerQuestion(command)) { "问题已结束或回答已提交" } }
                    .onSuccess { result.put("accepted", true) }
                    .onFailure { result.put("accepted", false).put("error", it.message.orEmpty()) }
                result.put("revision", (state.questionResult?.optLong("revision") ?: 0L) + 1)
                state.questionResult = result; publish()
            }
            "questionDraft" -> {
                val question = state.pendingQuestion ?: return
                if (question.optString("status") != "pending" || listOf("sessionId", "runId", "requestId").any { question.optString(it) != command.optString(it) }) return
                val draft = command.optJSONArray("answers") ?: return
                if (draft.toString().length > 50000) return
                val normalized = runCatching { QuestionBroker.validateAnswers(question.getJSONArray("questions"), draft, complete = false) }.getOrNull() ?: return
                question.put("draft", normalized); changed(); persistSoon(200)
            }
            "settingsChanged" -> settingsChanged()
            "selectModel", "setThinkingLevel" -> selectModel(command)
            "refreshSessions" -> refresh()
            "newSession" -> create()
            "selectSession" -> if (state.sessions.containsKey(id)) { state.selected = id; load(id); publish() }
            "renameSession" -> {
                if (!state.sessions.containsKey(id)) return
                state.autoTitles.revoke(id)
                request(JSONObject().put("action", "rename").put("sessionId", id).put("title", command.getString("title"))) { refresh() }
            }
            "deleteSession" -> {
                if (!state.sessions.containsKey(id)) return
                state.autoTitles.revoke(id)
                if (state.running?.optString("sessionId") == id || state.continuation?.optString("sessionId") == id) { pendingDelete = id; stop("stop") }
                else delete(id)
            }
            "send" -> {
                val submission = command.getString("submissionId")
                fun result(accepted: Boolean, error: String = "") {
                    state.submissionResult = JSONObject().put("id", submission).put("sessionId", id).put("accepted", accepted).put("error", error)
                    if (error.isNotBlank()) state.error = error
                    changed()
                }
                if (submission in pendingSubmissions) return
                if (submission in state.submissions) { result(true); return }
                if (testConfig == null) {
                    val availability = ShizukuMonitor.current()
                    if (availability != ShizukuAvailability.READY) { result(false, ShizukuMonitor.message(availability)); return }
                }
                var needsTitle = state.sessions[id]?.optString("title") == "新会话" && !state.autoTitles.isManual(id)
                val binding = if (testConfig != null) null else runCatching { settings.binding(state.modelSelections[id] ?: settings.registry().getJSONObject("defaultSelection")) }
                    .getOrElse { result(false, it.message.orEmpty()); return }
                val freshPause = state.prepareFreshSubmission(submission)
                if (freshPause != null) {
                    val config = runCatching { testConfig ?: settings.resolve(binding!!) }
                        .getOrElse { result(false, it.message.orEmpty()); return }
                    if (config.optString("model").isBlank() || config.optString("baseUrl").isBlank() || config.optString("apiKey").isBlank()) {
                        result(false, "请先配置模型"); return
                    }
                }
                val priorDraft = state.drafts[id]
                val priorDraftRevision = state.viewStates.revision(id)
                runCatching { state.submit(submission, id, command.getString("text").trim()) }.onFailure { result(false, it.message.orEmpty()); return }
                var autoTitle = false
                // Capture the submitted model now; later settings edits must not change naming.
                val titleConfig = runCatching { JSONObject((testConfig ?: settings.resolve(binding!!)).toString()) }.getOrNull()
                if (binding != null) state.queue.last().put("modelBinding", binding)
                var submittedTitle = state.sessions[id]?.optString("title")
                val clearedDraftRevision = state.viewStates.revision(id)
                pendingDraftClears[submission] = ViewStateStore.PendingClear(id, priorDraft, priorDraftRevision, clearedDraftRevision)
                pendingSubmissions.add(submission)
                fun nameAcceptedSubmission() {
                    if (needsTitle && !state.autoTitles.isManual(id) && state.sessions[id]?.optString("title") == submittedTitle)
                        saveBackgroundTitle(id, submittedTitle.orEmpty())
                    if (autoTitle && titleConfig != null) generateTitle(id, submission, command.getString("text"), titleConfig)
                }
                fun accepted() {
                    pendingSubmissions.remove(submission); durablePendingSubmissions.remove(submission)
                    persistViewStateSoon()
                    result(true)
                    nameAcceptedSubmission()
                    drain()
                }
                fun failed() {
                    pendingSubmissions.remove(submission); durablePendingSubmissions.remove(submission)
                    val failedClear = ViewStateStore.retireFailedClear(pendingDraftClears, submission)
                    state.queue.removeAll { it.optString("id") == submission }; state.submissions.remove(submission)
                    if (state.sessions.containsKey(id)) failedClear?.prior?.let { state.viewStates.restoreIfUnchanged(id, it, failedClear.clearedRevision) }
                    persistViewStateSoon()
                    if (needsTitle && !state.autoTitles.isManual(id) && state.sessions[id]?.optString("title") == submittedTitle) state.sessions[id]?.put("title", "新会话")
                    state.autoTitles.rollback(id, submission)
                    result(false, "任务保存失败，未发送")
                }
                // The first save retains the old pause. Pending submissions cannot drain until
                // both the submission and its narrowly scoped pause release are durable.
                persist(includingSubmission = submission, prepare = {
                    // The preceding write may have failed. Claim only as this snapshot starts,
                    // so the first successful submission receives the one naming attempt.
                    autoTitle = state.autoTitles.claim(id, submission)
                    if (autoTitle) {
                        needsTitle = true
                        submittedTitle = command.getString("text").trim().codePoints().limit(24).toArray().let { String(it, 0, it.size) }
                        state.sessions[id]?.put("title", submittedTitle)
                    }
                }, after = {
                    durablePendingSubmissions.add(submission); pendingDraftClears.remove(submission)
                    if (state.releaseFreshSubmissionPause(freshPause)) {
                        pendingPauseRelease = freshPause
                        persist(includingSubmission = submission, after = { pendingPauseRelease = null; accepted() }, onFailure = {
                            state.restoreFreshSubmissionPause(freshPause!!); pendingPauseRelease = null
                            pendingSubmissions.remove(submission); durablePendingSubmissions.remove(submission)
                            persistViewStateSoon()
                            // The first save committed this task. Keep it queued instead of reporting
                            // rejection while a durable copy could reappear after a restart.
                            result(true, "任务已排队，但解除暂停未能保存；队列保持暂停")
                            nameAcceptedSubmission()
                        })
                    } else accepted()
                }, onFailure = { failed() })
            }
            "pinSession" -> { state.setPinned(id, command.optBoolean("pinned")); publish() }
            "cancelQueued" -> { state.queue.removeAll { it.optString("id") == command.optString("submissionId") }; publish() }
            "pauseQueue" -> { state.pauseReasons.add("user"); publish() }
            "resumeQueue" -> { state.pauseReasons.clear(); publish { drain() } }
            "stop" -> stop(command.optString("reason", "stop"))
            "closeEnvironment" -> if (validControl(command) && command.has("environmentId") &&
                command.optString("environmentId") == coordinator.environmentSnapshot().optString("id")) {
                if (state.hasProtectedWork()) {
                    stop("environment")
                    state.continuation?.put("environmentClosed", true)
                }
                coordinator.releasePhone()
                publish()
            }
            "takeOver" -> if (validControl(command) && state.controlMode !in setOf("manual", "taking_over", "resuming")) {
                if (coordinator.isReleasingEnvironment()) { state.error = "执行环境正在关闭，请稍后操作"; publish(); return }
                state.retainTask(); state.running = null; state.transition("taking_over"); coordinator.takeOver(testConfig?.optBoolean("mockPhone") == true); publish()
            }
            "resumeTask" -> if (validControl(command) && state.controlSnapshot().optBoolean("canResume")) {
                state.transition("resuming"); coordinator.resume(); publish { drain() }
            }
            "endManual" -> if (validControl(command) && state.controlMode == "manual" && state.continuation == null) {
                coordinator.resume(); state.transition("idle"); publish { drain() }
            }
            "steerQueued" -> {
                val submission = command.optString("submissionId")
                if (submission.isBlank() || !pendingQueuedSteering.add(submission)) return
                var pendingClaim: QueuedSteering.Claim? = null
                // Mutate only when this serial write begins: unrelated saves cannot commit
                // a provisional transfer before its rollback callback has run.
                persist(prepare = { pendingClaim = QueuedSteering.claim(state, command, pendingSubmissions); changed() }, after = {
                    pendingQueuedSteering.remove(submission)
                    val claim = pendingClaim
                    if (claim != null) {
                        if (!QueuedSteering.active(state, claim)) {
                            QueuedSteering.resolve(state, claim, SteeringOutcome.NOT_SENT); publish()
                        } else coordinator.steerQueued(id, claim.item.getString("id"), claim.item.getString("text"), claim.runId) { outcome ->
                            if (!closed) { QueuedSteering.resolve(state, claim, outcome); publish() }
                        }
                    }
                }, onFailure = {
                    pendingQueuedSteering.remove(submission)
                    pendingClaim?.let { QueuedSteering.rollback(state, it) }
                    changed()
                })
            }
            "steer" -> {
                val submission = command.getString("submissionId")
                val text = command.getString("text").trim()
                fun result(accepted: Boolean) {
                    state.submissionResult = JSONObject().put("id", submission).put("sessionId", id).put("accepted", accepted)
                    if (!accepted) state.error = "当前执行已结束，补充未发送"
                    publish()
                }
                if (submission in state.submissions) {
                    if (state.submissionResult?.optString("id") == submission) publish() else result(false)
                    return
                }
                if (state.pendingQuestion?.optString("status") == "pending") { command(JSONObject(command.toString()).put("type", "send")); return }
                if (id != state.running?.optString("sessionId") || state.controlMode != "running" || text.isBlank() || submission.isBlank()) {
                    result(false); return
                }
                state.submissions.add(submission)
                val expected = state.controlId
                val item = state.running!!
                val steering = item.optJSONArray("steering") ?: org.json.JSONArray().also { item.put("steering", it) }
                publish {
                    if (state.controlId != expected) result(false)
                    else coordinator.steer(id, submission, text) { accepted ->
                        // Keep accepted direction with the task across abort/handback, including
                        // messages Pi accepted but had not consumed before abort cleared its queue.
                        if (accepted) {
                            steering.put(text)
                            state.continuation?.takeIf { it.optString("id") == item.optString("id") }?.put("steering", org.json.JSONArray(steering.toString()))
                        }
                        result(accepted)
                    }
                }
            }
            "viewState" -> if (state.sessions.containsKey(id)) {
                if (state.viewStates.set(id, command)) persistViewStateSoon()
            }
            "loadOlder" -> if (state.sessions.containsKey(id)) {
                state.limits[id] = ((state.limits[id] ?: 40).toLong() + 40).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(); publish()
            }
        }
    }
    private fun validControl(command: JSONObject): Boolean = command.optString("controlId").isNotBlank() && command.optString("controlId") == state.controlId
    private fun saveBackgroundTitle(id: String, title: String, submission: String? = null) {
        coordinator.sessionCommand(JSONObject().put("action", "rename").put("sessionId", id).put("title", title)) { response ->
            if (!closed && response.optBoolean("success") && state.sessions.containsKey(id) &&
                (submission == null && !state.autoTitles.isManual(id) || submission != null && state.autoTitles.current(id, submission))) {
                state.sessions[id]?.put("title", title)
                changed()
                // A title is a catalogue cache update, never a queue-failure/STOP decision.
                persistence.save(success = {}, failure = {})
            }
            if (!closed) main.post { drain() }
        }
    }
    private fun generateTitle(id: String, submission: String, text: String, config: JSONObject) {
        if (!state.autoTitles.current(id, submission)) return
        coordinator.generateTitle(text, config, allowCatalog = state.paused || state.controlMode in setOf("manual", "taking_over")) { title ->
            if (!closed && !title.isNullOrBlank() && state.sessions.containsKey(id) && state.autoTitles.current(id, submission))
                saveBackgroundTitle(id, title, submission)
        }
    }
    fun stop(reason: String = "stop") {
        state.retainTask(); state.running = null; state.pauseReasons.add(reason); state.transition("stopped")
        coordinator.stop(reason); publish()
    }
    private fun delete(id: String) = request(JSONObject().put("action", "delete").put("sessionId", id)) {
        state.remove(id); loaded.remove(id); persistViewStateSoon()
        publish { state.viewStates.forgetDeleted(setOf(id), state.sessions.keys); persistViewStateSoon() }
        refresh()
    }
    fun accept(event: JSONObject) {
        if (event.optBoolean("retired")) {
            val task = state.continuation
            if (task != null && event.optString("sessionId") == task.optString("sessionId") && event.optString("runId") == task.optString("runId")) {
                val detail = event.optJSONObject("details") ?: JSONObject()
                task.put("lastDispatch", JSONObject().put("operation", event.optString("operation"))
                    .put("execution", detail.optJSONObject("执行") ?: JSONObject())
                    .put("observation", detail.optJSONObject("观察") ?: JSONObject()))
                persist()
            }
            return
        }
        state.accept(event)
        changed()
        val delta = event.optString("type") == "message_update"
        if (!delta) persist() else persistSoon(1000)
        if (event.optString("type") == "agent_settled") {
            main.post { if (state.queue.isEmpty() || state.paused) refresh() else drain() }
        } else if (event.optString("type") == "status" && event.optString("status") == "settled" && pendingDelete.isNotBlank()) {
            val id = pendingDelete; pendingDelete = ""; delete(id)
        } else if (event.optString("type") == "executor_idle" || (event.optString("type") == "status" && event.optString("status") == "settled")) {
            main.post { drain() }
        }
    }
    private fun drain() {
        if (state.controlMode == "resuming") { resumeContinuation(); return }
        if (state.controlMode in setOf("taking_over", "manual")) return
        if (closed || pendingPauseRelease != null || !state.ready || state.paused || state.running != null || coordinator.isBusy() || state.queue.isEmpty()) return
        if (testConfig == null && ShizukuMonitor.current() != ShizukuAvailability.READY) { permissionChanged(ShizukuMonitor.current()); return }
        val item = state.nextReadySubmission(pendingSubmissions) ?: return
        val config = testConfig?.let { JSONObject(it.toString()) } ?: runCatching { settings.resolve(item.getJSONObject("modelBinding")) }
            .getOrElse { state.pauseReasons.add("error"); state.error = it.message.orEmpty(); publish(); return }
        if (config.optString("model").isBlank() || config.optString("baseUrl").isBlank() || config.optString("apiKey").isBlank()) {
            state.pauseReasons.add("error"); state.error = "请先在设置中填写模型、API 地址和密钥，再继续队列"; publish(); return
        }
        state.running = item; state.queue.removeAt(0)
        publish {
            if (!closed && state.running === item) {
                // A catalogue operation may have started while the durable claim was writing.
                // No prompt has been dispatched yet, so returning this claim to the queue is safe.
                if (state.paused || state.controlMode in setOf("manual", "taking_over", "resuming") || coordinator.isBusy()) {
                    state.running = null; state.queue.add(0, item); publish(); return@publish
                }
                // From this point dispatch may occur; a queued save-failure callback
                // must never return the task to the queue before run_started arrives.
                item.put("started", true)
                coordinator.start(config.put("sessionId", item.getString("sessionId")), item.getString("text"))
            }
        }
    }
    private fun resumeContinuation() {
        if (closed || !state.ready || state.running != null || coordinator.isBusy()) return
        val original = state.continuation ?: run { state.transition("idle"); publish(); return }
        if (testConfig == null && ShizukuMonitor.current() != ShizukuAvailability.READY) { permissionChanged(ShizukuMonitor.current()); return }
        val binding = if (testConfig != null) null else runCatching { settings.binding(state.modelSelections[original.getString("sessionId")] ?: settings.registry().getJSONObject("defaultSelection")) }
            .getOrElse { state.transition("stopped"); state.error = it.message.orEmpty(); publish(); return }
        val config = testConfig?.let { JSONObject(it.toString()) } ?: settings.resolve(binding!!)
        if (config.optString("model").isBlank() || config.optString("baseUrl").isBlank() || config.optString("apiKey").isBlank()) {
            state.transition("stopped"); state.error = "请先配置模型"; publish(); return
        }
        val item = JSONObject(original.toString()).put("started", false)
        if (binding != null) item.put("modelBinding", binding)
        item.remove("runId") // Coordinator generations restart with the service process.
        state.running = item
        val expected = state.controlId
        publish {
            if (closed || state.controlId != expected || state.running !== item) return@publish
            if (coordinator.isBusy()) { state.running = null; publish(); return@publish }
            item.put("started", true)
            coordinator.start(config.put("sessionId", item.getString("sessionId")), ContinuationPrompt.create(item))
        }
    }
    private fun publish(after: () -> Unit = {}) { changed(); persist(after) }
    private fun writeSnapshot(bytes: ByteArray): Boolean = writeAtomic(file, bytes)
    private fun writeAtomic(target: AtomicFile, bytes: ByteArray): Boolean {
        val stream = runCatching { target.startWrite() }.getOrNull() ?: return false
        return try { stream.write(bytes); target.finishWrite(stream); true }
        catch (_: Exception) { runCatching { target.failWrite(stream) }; false }
    }
    private fun persist(after: () -> Unit = {}, onFailure: () -> Unit = {}, includingSubmission: String? = null, prepare: () -> Unit = {}) {
        persistence.save(includingSubmission, prepare = prepare, success = { if (!closed) after() }, failure = {
            if (!closed) {
                state.pauseReasons.add("error")
                if (state.controlMode == "resuming") { state.running = null; state.transition("stopped") }
                state.running?.takeIf { !it.optBoolean("started") }?.let {
                    state.running = null; state.queue.add(0, it)
                }
                state.error = "会话保存失败，队列已暂停"; onFailure(); changed()
            }
        })
    }
    fun close() {
        if (closed) return
        main.removeCallbacks(saveDeferred); main.removeCallbacks(saveViewState)
        if (state.running != null || state.queue.isNotEmpty()) state.pauseReasons.add("restart")
        // Retire queued callbacks, then append one final snapshot behind the in-flight disk write.
        val bytes = persistenceSnapshot()
        val viewSnapshot = if (pendingSubmissions.isEmpty()) state.viewStates.snapshot() else null
        persistence.close(); closed = true
        disk.execute {
            writeSnapshot(bytes)
            if (viewSnapshot != null) writeAtomic(viewStateFile, viewSnapshot.toString().toByteArray(Charsets.UTF_8))
        }
        disk.shutdown()
    }
}
