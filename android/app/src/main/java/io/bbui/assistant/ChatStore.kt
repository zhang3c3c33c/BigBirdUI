package io.bbui.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Pure projection of Pi events. Never feeds projected/sanitized content back to the model. */
class ChatStore(private val now: () -> Long = System::currentTimeMillis) {
    private data class Part(val id: String, val type: String, var state: String = "streaming",
        val text: StringBuilder = StringBuilder(), var toolCallId: String = "", var toolName: String = "",
        var title: String = "", var summary: String = "", var error: String = "",
        var started: Long = 0, var duration: Long = 0,
        var executionState: String = "", var observationState: String = "", var taskAction: String = "")
    private data class Message(val id: String, val role: String, var status: String,
        var sourceKey: String = "", var timestamp: Long = 0,
        val parts: MutableList<Part> = mutableListOf(), var sourceDigest: String = "")
    private val messages = mutableListOf<Message>()
    private var active: Message? = null
    private var pendingUser: Message? = null
    private var runId = ""
    private var sessionId = ""
    private var sequence = 0L
    private val instanceId = UUID.randomUUID().toString()
    private var revision = 0L
    private var running = false
    private var phase = "idle"
    private var statusText = "准备好后，告诉我需要做什么"
    private var receivedAt = 0L
    private var runtimeAt = 0L
    private var piAt = 0L
    private var task: JSONObject? = null
    private var taskUpdatedThisRun = false
    private var liveTaskReceived = false
    private var runOutcome = ""

    /** Returns false for non-chat events or events belonging to retired executions. */
    @Synchronized fun accept(event: JSONObject): Boolean {
        val type = event.optString("type")
        val eventRun = event.optString("runId")
        if (event.optBoolean("gate") && type != "status") return false
        if (type !in setOf("run_started", "run_stopped", "status") && eventRun.isNotEmpty() && eventRun != runId) return false
        receivedAt = now()
        runtimeAt = event.optLong("runtimeReceivedAtMs", runtimeAt)
        piAt = event.optLong("piEmittedAtMs", piAt)
        when (type) {
            "run_started" -> {
                settle("stopped")
                runId = eventRun; running = true; phase = "starting"; statusText = "正在准备"
                taskUpdatedThisRun = false; liveTaskReceived = false; runOutcome = ""
                active = null
                val text = event.optString("prompt")
                pendingUser = if (text.isNotBlank()) Message(nextId(), "user", "complete", timestamp = now(),
                    parts = mutableListOf(Part("0", "text", "complete", StringBuilder(ContinuationPrompt.project(text)))), sourceDigest = digest(text)).also { messages.add(it) } else null
            }
            "run_stopped" -> { runId = eventRun; settle("stopped"); phase = "stopped"; statusText = "已停止"; runOutcome = "stopped" }
            "history_snapshot" -> {
                val restoredSession = event.optString("sessionId", sessionId)
                if (sessionId.isNotEmpty() && restoredSession != sessionId) { messages.clear(); task = null; taskUpdatedThisRun = false }
                sessionId = restoredSession
                val pending = pendingUser
                messages.remove(pending); active = null
                val history = event.optJSONArray("messages") ?: JSONArray()
                val restored = mutableListOf<Message>()
                var historicalTaskUpdated = false
                var historicalOutcome = ""
                val pendingPhoneCalls = mutableSetOf<String>()
                for (i in 0 until history.length()) {
                    val item = history.optJSONObject(i) ?: continue
                    when (item.optString("role")) {
                        "user", "assistant" -> {
                            if (item.optString("role") == "user") {
                                historicalTaskUpdated = false; historicalOutcome = ""; pendingPhoneCalls.clear()
                            }
                            if (item.optString("role") == "assistant") {
                                if (item.optString("stopReason") == "error") historicalOutcome = "error"
                                if (item.optString("stopReason") == "aborted") historicalOutcome = "stopped"
                            }
                            val key = sourceKey(item).ifBlank { "$sessionId:h:$i" }
                            val msg = messages.find { it.sourceKey == key } ?: unacknowledgedUser(item)
                                ?: Message("$sessionId:$key", item.getString("role"), "complete").also { messages.add(it) }
                            populate(msg, item)
                            msg.parts.filter { it.toolName == "phone_action" }.forEach { pendingPhoneCalls.add(it.toolCallId) }
                            msg.sourceKey = key
                            restored.add(msg)
                        }
                        "toolResult" -> {
                            pendingPhoneCalls.remove(item.optString("toolCallId"))
                            if (task?.optString("status") in terminalTaskStates &&
                                (item.optString("toolName") == "phone_action" || findTool(item.optString("toolCallId"))?.toolName == "phone_action")) historicalTaskUpdated = false
                            val detail = item.optJSONObject("details")
                            if (!item.optBoolean("isError") && detail?.has("bbuiTask") == true && detail.isNull("bbuiTask")) historicalTaskUpdated = false
                            if (toolResult(item.optString("toolCallId"), item, item.optBoolean("isError"), fromHistory = true)) historicalTaskUpdated = true
                        }
                    }
                }
                // get_messages is Pi's current context; keep display history and unflushed partial replies.
                if (task?.optString("status") in terminalTaskStates && pendingPhoneCalls.isNotEmpty()) historicalTaskUpdated = false
                restored.flatMap { it.parts }.filter { it.state in setOf("streaming", "running") }.forEach { complete(it, "stopped") }
                messages.sortBy { it.timestamp }
                if (pending != null) messages.add(pending)
                if (!running && pending == null) { taskUpdatedThisRun = historicalTaskUpdated; runOutcome = historicalOutcome }
            }
            "message_start" -> {
                val item = event.optJSONObject("message") ?: return false
                when (item.optString("role")) {
                    "user" -> {
                        if (pendingUser != null) { identify(pendingUser!!, item); pendingUser = null }
                        else Message(nextId(), "user", "complete").also { populate(it, item); messages.add(it) }
                    }
                    "assistant" -> {
                        active = Message(nextId(), "assistant", "streaming").also { populate(it, item); messages.add(it) }
                        active?.status = "streaming"
                        active?.parts?.forEach { it.state = "streaming"; it.started = now() }
                    }
                    else -> return false
                }
            }
            "message_update" -> {
                val delta = event.optJSONObject("assistantMessageEvent") ?: return false
                val kind = delta.optString("type")
                if (kind !in setOf("text_start", "text_delta", "text_end", "thinking_start", "thinking_delta", "thinking_end", "toolcall_start", "toolcall_delta", "toolcall_end")) return false
                val msg = assistant()
                val index = delta.optInt("contentIndex", 0).toString()
                val partType = when { kind.startsWith("thinking") -> "reasoning"; kind.startsWith("toolcall") -> "tool"; else -> "text" }
                val part = msg.parts.find { it.id == index } ?: Part(index, partType, started = now()).also { msg.parts.add(it) }
                when {
                    kind == "toolcall_delta" -> Unit // Argument fragments are not conversational text.
                    kind == "toolcall_start" -> {
                        part.toolCallId = delta.optString("id"); part.toolName = delta.optString("toolName")
                        part.title = if (part.toolName == "task_state") "任务记录" else "准备调用工具"; part.state = "streaming"
                    }
                    kind == "toolcall_end" -> delta.optJSONObject("toolCall")?.let { updateTool(part, it) }
                    kind.endsWith("_delta") -> { part.state = "streaming"; part.text.append(delta.optString("delta")) }
                    kind.endsWith("_end") -> { replace(part.text, delta.optString("content")); complete(part, "complete") }
                }
            }
            "message_end" -> {
                val item = event.optJSONObject("message") ?: return false
                when (item.optString("role")) {
                    "assistant" -> { val msg = assistant(); populate(msg, item); active = null
                        if (msg.status == "error") runOutcome = "error"
                    }
                    "toolResult" -> toolResult(item.optString("toolCallId"), item, item.optBoolean("isError"))
                    else -> return false
                }
            }
            "tool_execution_start", "tool_execution_update", "tool_execution_end" -> {
                val id = event.optString("toolCallId")
                if (id.isEmpty()) return false
                val part = findTool(id) ?: Part("tool:$id", "tool", toolCallId = id).also { assistant().parts.add(it) }
                part.toolName = event.optString("toolName", part.toolName)
                event.optJSONObject("args")?.let { describeTool(part, it) }
                if (type == "tool_execution_start") {
                    part.state = "running"; part.started = now()
                    if (part.toolName == "phone_action" && task?.optString("status") in terminalTaskStates) taskUpdatedThisRun = false
                }
                if (type == "tool_execution_end") toolResult(id, event.optJSONObject("result") ?: JSONObject(), event.optBoolean("isError"))
            }
            "agent_settled" -> settle(if (phase == "error") "error" else "complete")
            "status" -> {
                // Status events are already generation-checked by AppCoordinator.
                phase = event.optString("status", phase); statusText = safe(event.optString("message"))
                if (phase == "error") { runOutcome = "error"; settle("error", clearActive = false) }
                if (phase in setOf("stopping", "stopped", "manual")) { runOutcome = "stopped"; settle("stopped") }
            }
            else -> return false
        }
        revision++
        return true
    }

    private fun digest(text: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun nextId() = "$instanceId:r$runId:m${++sequence}"
    private fun assistant(): Message = active ?: Message(nextId(), "assistant", "streaming", timestamp = now()).also { messages.add(it); active = it }
    private fun sourceKey(item: JSONObject) = if (item.has("timestamp")) "${item.optString("role")}:${item.optLong("timestamp")}" else ""
    private fun identify(msg: Message, item: JSONObject) {
        if (sourceKey(item).isNotEmpty()) msg.sourceKey = sourceKey(item)
        msg.timestamp = item.optLong("timestamp", msg.timestamp.takeIf { it > 0 } ?: now())
    }
    private fun unacknowledgedUser(item: JSONObject): Message? {
        if (item.optString("role") != "user" || !item.has("timestamp")) return null
        val content = item.opt("content")
        val text = if (content is String) content else if (content is JSONArray)
            (0 until content.length()).mapNotNull { content.optJSONObject(it)?.takeIf { b -> b.optString("type") == "text" }?.optString("text") }.joinToString("") else ""
        // STOP may retire the generation after Pi received a prompt but before its user event arrived.
        return messages.firstOrNull { it.role == "user" && it.sourceKey.isEmpty() && it.timestamp <= item.optLong("timestamp") &&
            (if (it.sourceDigest.isNotBlank()) it.sourceDigest == digest(text) else
                it.parts.filter { p -> p.type == "text" }.joinToString("") { p -> p.text.toString() } == text) }
    }
    private fun replace(target: StringBuilder, value: String) { target.setLength(0); target.append(value) }
    private fun complete(part: Part, state: String) {
        part.state = state
        if (part.started > 0) part.duration = maxOf(part.duration, now() - part.started)
    }
    private fun settle(state: String, clearActive: Boolean = true) {
        running = false
        messages.filter { it.status == "streaming" }.forEach { it.status = state }
        messages.flatMap { it.parts }.filter { it.state in setOf("streaming", "running") }.forEach { complete(it, state) }
        if (clearActive) active = null
    }
    private fun findTool(id: String): Part? = messages.asReversed().asSequence().flatMap { it.parts.asSequence() }
        .firstOrNull { it.type == "tool" && it.toolCallId == id }

    private fun populate(msg: Message, item: JSONObject) {
        identify(msg, item)
        val old = msg.parts.associateBy { it.id }
        val content = item.opt("content")
        val parts = if (content is JSONArray) content else JSONArray().put(JSONObject().put("type", "text").put("text", content as? String ?: ""))
        val rebuilt = mutableListOf<Part>()
        for (i in 0 until parts.length()) {
            val block = parts.optJSONObject(i) ?: continue
            val type = when (block.optString("type")) { "text" -> "text"; "thinking" -> "reasoning"; "toolCall" -> "tool"; else -> continue }
            val part = old[i.toString()]?.takeIf { it.type == type } ?: Part(i.toString(), type)
            if (type == "tool") updateTool(part, block)
            else {
                val text = block.optString(if (type == "reasoning") "thinking" else "text")
                replace(part.text, if (msg.role == "user" && type == "text") ContinuationPrompt.project(text) else text)
                complete(part, "complete")
            }
            rebuilt.add(part)
        }
        msg.status = when (item.optString("stopReason")) { "error" -> "error"; "aborted" -> "stopped"; else -> "complete" }
        // Some failed transports finish with empty content after having emitted useful deltas.
        if (rebuilt.isNotEmpty() || msg.status == "complete") { msg.parts.clear(); msg.parts.addAll(rebuilt) }
        if (msg.status != "complete") msg.parts.filter { it.state in setOf("streaming", "running") }.forEach { complete(it, msg.status) }
        if (msg.status == "error") {
            val reason = safe(item.optString("errorMessage", "模型请求失败"))
            msg.parts.add(Part("error", "text", "error", StringBuilder(reason)))
        }
    }
    private fun updateTool(part: Part, block: JSONObject) {
        part.toolCallId = block.optString("id", part.toolCallId)
        part.toolName = block.optString("name", part.toolName)
        block.optJSONObject("arguments")?.let { describeTool(part, it) }
        if (part.title.isBlank()) part.title = if (part.toolName == "task_state") "任务记录" else "工具调用"
    }
    private fun describeTool(part: Part, args: JSONObject) {
        if (part.toolName == "read") {
            part.title = "读取手机操作指南"
            part.summary = ""
            return
        }
        if (part.toolName == "phone_history") {
            part.title = "回看历史画面"
            part.summary = "只读历史截图；操作前仍需查看当前画面"
            return
        }
        if (part.toolName == "task_state") {
            part.taskAction = args.optString("action").takeIf { it in setOf("read", "update") }.orEmpty()
            part.title = when (part.taskAction) { "read" -> "读取任务记录"; "update" -> "更新任务记录"; else -> "任务记录" }
            return // The requested task is not authoritative until the tool returns successfully.
        }
        if (part.toolName != "phone_action") {
            val fallback = when {
                part.toolName == "web_search" -> "搜索网络"
                part.toolName == "fetch_content" || part.toolName == "get_search_results" -> "读取网页"
                part.toolName.startsWith("memory_") -> "用户记忆"
                part.toolName == "ask_user_question" -> "向你提问"
                part.toolName == "system_calendar" -> "管理日程"
                part.toolName == "system_contacts" -> "管理联系人"
                part.toolName == "system_sms" -> "查询短信"
                part.toolName == "system_call_log" -> "查询通话记录"
                part.toolName == "system_media" -> "检索媒体"
                part.toolName == "system_clock" -> "设置闹钟与倒计时"
                part.toolName.startsWith("system_") -> "系统操作"
                else -> "工具调用"
            }
            part.title = safe(args.optString("intent").ifBlank { args.optString("意图").ifBlank { fallback } }).take(80)
            part.summary = if (part.toolName == "web_search") safe(args.optString("query")).take(240) else ""
            return
        }
        val operation = args.optString("操作")
        val fallback = when (operation) {
            "查看" -> "查看屏幕"; "打开应用" -> "打开应用"; "输入内容" -> "输入文字"
            "点击", "双击", "长按", "滑动", "拖拽", "放大", "缩小", "等待", "按键", "列出应用", "列出屏幕", "创建屏幕", "关闭屏幕", "全选", "删除内容" -> operation
            else -> "手机操作"
        }
        // Model-authored display metadata only; it never changes the executable operation.
        val intent = safe((args.opt("意图") as? String).orEmpty()).replace(Regex("\\s+"), " ").trim()
        part.title = if (intent.isBlank()) fallback else intent.take(80).trimEnd { Character.isHighSurrogate(it) }
        val params = args.optJSONObject("参数")
        if (operation == "打开应用") part.summary = safe(params?.optString("包名").orEmpty()).take(200)
        if (operation == "输入内容") part.summary = "向当前输入框输入文字"
    }
    private fun toolResult(id: String, result: JSONObject, failed: Boolean, fromHistory: Boolean = false): Boolean {
        val part = findTool(id)
        val details = result.optJSONObject("details") ?: result
        val isTask = (part?.toolName ?: result.optString("toolName")) == "task_state"
        val success = !failed && !result.optBoolean("isError")
        var updated = false
        if (isTask && success && details.has("bbuiTask")) {
            val raw = details.opt("bbuiTask")
            val projected = projectTask(raw as? JSONObject)
            if (raw == JSONObject.NULL || projected != null) {
                if (!fromHistory || !(running && liveTaskReceived)) task = projected
                updated = projected != null && part?.taskAction == "update"
                if (!fromHistory) {
                    liveTaskReceived = true
                    if (updated) taskUpdatedThisRun = true
                    if (projected == null) taskUpdatedThisRun = false
                }
            }
        }
        if (part == null) return updated
        if (!fromHistory && part.toolName == "phone_action" && task?.optString("status") in terminalTaskStates) taskUpdatedThisRun = false
        complete(part, if (failed || result.optBoolean("isError")) "error" else "complete")
        if (part.toolName == "read") {
            part.title = "读取手机操作指南"
            part.summary = ""
            part.error = if (success) "" else "操作指南读取失败"
            return updated
        }
        if (isTask) {
            part.error = if (success) "" else safe(details.optString("错误", "任务记录调用失败"))
            part.summary = if (!success) "" else if (details.isNull("bbuiTask")) "当前没有任务记录"
                else if (part.taskAction == "update") "任务记录已更新" else "任务记录已读取"
            return updated
        }
        details.optJSONObject("bbuiTool")?.let { display ->
            display.optString("title").takeIf { it.isNotBlank() && part.title in setOf("", "工具调用", "系统操作", "搜索网络", "用户记忆", "读取网页") }?.let { part.title = safe(it).take(80) }
            part.summary = safe(display.optString("summary")).take(2000)
        }
        val execution = details.optJSONObject("执行")
        val observation = details.optJSONObject("观察")
        // A returned call is not proof of a successful action. Persist only the
        // factual state enums; screenshots and raw device diagnostics stay out.
        part.executionState = execution?.optString("状态").orEmpty()
            .takeIf { it in executionStates }.orEmpty()
        part.observationState = observation?.optString("状态").orEmpty()
            .takeIf { it in observationStates }.orEmpty()
        part.error = ""
        if (part.state == "error") {
            part.error = safe(details.optString("错误").ifBlank { "工具调用失败" }).take(1000)
        } else if (part.executionState.isEmpty() && part.observationState.isEmpty() && part.summary.isBlank()) {
            part.summary = "工具已返回结果"
        }
        return false
    }

    /** Whitelisted display copy only. Never replay this projection into Pi or phone input. */
    private fun projectTask(value: JSONObject?): JSONObject? {
        if (value == null || value.optInt("version") != 1 || value.optString("status") !in taskStates ||
            listOf("id", "goal", "summary").any { value.opt(it) !is String } || value.optString("id").isBlank()) return null
        val result = JSONObject().put("version", 1).put("status", value.getString("status"))
        for (key in listOf("id", "goal", "summary", "question", "completionEvidence"))
            (value.opt(key) as? String)?.let { result.put(key, safe(it)) }
        for (key in listOf("constraints", "facts", "unknowns", "steps")) {
            val entries = value.optJSONArray(key) ?: continue
            val clean = JSONArray()
            for (i in 0 until entries.length()) {
                if (key == "facts") { (entries.opt(i) as? String)?.let { clean.put(safe(it)) }; continue }
                val entry = entries.optJSONObject(i) ?: continue
                val choice = when (key) { "constraints" -> "source"; "unknowns" -> "resolveBy"; else -> "status" }
                val allowed = when (key) { "constraints" -> setOf("user", "preference", "assumption")
                    "unknowns" -> setOf("observe", "user"); else -> setOf("pending", "in_progress", "completed") }
                if (entry.optString(choice) !in allowed) continue
                val row = JSONObject().put(choice, entry.getString(choice))
                for (field in if (key == "steps") listOf("id", "title") else listOf("text"))
                    (entry.opt(field) as? String)?.let { row.put(field, safe(it)) }
                clean.put(row)
            }
            result.put(key, clean)
        }
        return result
    }

    @Synchronized fun taskState(): String = when {
            runOutcome == "error" -> "error"
            runOutcome == "stopped" -> "stopped"
            running -> "running"
            taskUpdatedThisRun && task?.optString("status") in taskStates -> task!!.getString("status")
            messages.isNotEmpty() || task != null -> "unconfirmed"
            else -> "idle"
    }

    @Synchronized fun currentRunId(): String = runId

    private fun taskDisplay(): JSONObject {
        val state = taskState()
        val label = when (state) {
            "running" -> "进行中"; "waiting_user" -> "等待你补充"; "completed" -> "已完成"; "blocked" -> "遇到阻碍"
            "active" -> "本轮回复结束 · 任务尚未完成"
            "stopped" -> if (task != null) "已停止 · 任务记录已保留" else "已停止"
            "error" -> if (task != null) "回复出错 · 任务记录已保留" else "回复出错"
            "unconfirmed" -> "本轮回复结束 · 任务状态未确认"; else -> "准备开始"
        }
        return JSONObject().put("state", state).put("label", label)
    }

    /** Only a dispatched live tool from this run; never expose partial arguments or restored history. */
    @Synchronized fun activeToolSnapshot(expectedRun: String): JSONObject? {
        if (!running || expectedRun.isBlank() || runId != expectedRun) return null
        val part = messages.asReversed().asSequence().flatMap { it.parts.asReversed().asSequence() }
            .firstOrNull { it.type == "tool" && it.state == "running" } ?: return null
        return JSONObject().put("toolCallId", part.toolCallId).put("intent", safe(part.title))
    }

    @Synchronized fun questionSourceKey(toolCallId: String): String = messages.asReversed().firstOrNull { message -> message.parts.any { it.toolCallId == toolCallId } }?.sourceKey.orEmpty()

    @Synchronized fun snapshot(limit: Int = 40): JSONObject {
        val visible = messages.takeLast(limit.coerceAtLeast(1))
        return JSONObject().put("type", "snapshot").put("revision", revision).put("runId", runId).put("sessionId", sessionId)
            .put("status", JSONObject().put("phase", phase).put("message", safe(statusText))).put("isRunning", running)
            .put("task", task?.let { JSONObject(it.toString()) } ?: JSONObject.NULL)
            .put("taskUpdatedThisRun", taskUpdatedThisRun).put("taskDisplay", taskDisplay()).put("runOutcome", runOutcome)
            .put("messages", JSONArray(visible.map { message ->
                JSONObject().put("id", message.id).put("role", message.role).put("status", message.status)
                    .put("sourceKey", message.sourceKey).put("sourceDigest", message.sourceDigest).put("timestamp", message.timestamp)
                    .put("parts", JSONArray(message.parts.map { part ->
                        JSONObject().put("id", part.id).put("type", part.type).put("state", part.state).apply {
                            if (part.type == "tool") {
                                put("toolCallId", part.toolCallId); put("toolName", part.toolName)
                                put("title", safe(part.title)); put("summary", safe(part.summary)); put("error", safe(part.error))
                                if (part.executionState.isNotEmpty()) put("executionState", part.executionState)
                                if (part.observationState.isNotEmpty()) put("observationState", part.observationState)
                                if (part.taskAction.isNotEmpty()) put("taskAction", part.taskAction)
                            } else put("text", safe(part.text.toString()))
                            if (part.type != "text") put("durationMs", if (part.started > 0 && part.state in setOf("streaming", "running")) now() - part.started else part.duration)
                        }
                    }))
            })).put("hasOlder", messages.size > visible.size)
            .put("timing", JSONObject().put("nativeReceivedAtMs", receivedAt).put("runtimeReceivedAtMs", runtimeAt).put("piEmittedAtMs", piAt).put("projectionAtMs", now()))
    }

    /** Cache is a display projection; restoring it never sends prompts or phone input. */
    @Synchronized fun restore(cache: JSONObject) {
        messages.clear(); active = null; pendingUser = null
        sessionId = cache.optString("sessionId"); runId = ""; revision = cache.optLong("revision") + 1
        task = projectTask(cache.optJSONObject("task")); taskUpdatedThisRun = cache.optBoolean("taskUpdatedThisRun")
        liveTaskReceived = false
        runOutcome = cache.optString("runOutcome").takeIf { it in setOf("stopped", "error") }.orEmpty()
        val rows = cache.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val role = row.optString("role")
            if (role !in setOf("user", "assistant")) continue
            val msg = Message(row.optString("id"), role, row.optString("status", "complete"), row.optString("sourceKey"), row.optLong("timestamp"))
            msg.sourceDigest = row.optString("sourceDigest")
            val parts = row.optJSONArray("parts") ?: JSONArray()
            for (j in 0 until parts.length()) {
                val p = parts.optJSONObject(j) ?: continue
                val type = p.optString("type")
                if (type !in setOf("text", "reasoning", "tool")) continue
                msg.parts.add(Part(p.optString("id"), type, p.optString("state", "complete"), StringBuilder(safe(p.optString("text"))),
                    p.optString("toolCallId"), p.optString("toolName"), safe(p.optString("title")), safe(p.optString("summary")),
                    safe(p.optString("error")), duration = p.optLong("durationMs"),
                    executionState = p.optString("executionState").takeIf { it in executionStates }.orEmpty(),
                    observationState = p.optString("observationState").takeIf { it in observationStates }.orEmpty(),
                    taskAction = p.optString("taskAction").takeIf { it in setOf("read", "update") }.orEmpty()))
            }
            messages.add(msg)
        }
        val interrupted = cache.optBoolean("isRunning")
        if (interrupted) runOutcome = "stopped"
        settle("stopped")
        phase = if (interrupted) "stopped" else "idle"
        statusText = if (interrupted) "上次执行已中断；请检查画面后发送新指令" else "会话已恢复"
    }

    companion object {
        private val taskStates = setOf("active", "waiting_user", "completed", "blocked")
        private val terminalTaskStates = setOf("waiting_user", "completed", "blocked")
        private val executionStates = setOf("未派发", "已派发", "部分派发", "未知", "无需派发")
        private val observationStates = setOf("已取得", "失败", "未请求")
        private val dataUrl = Regex("data:[^\\s,]{1,100};base64,[A-Za-z0-9+/=_-]+")
        private val bearer = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*")
        private val apiKey = Regex("(?i)(api[ _-]?key(?:\\s+provided)?[\\s\"']*[:=][\\s\"']*)[^\\s\"',}]+")
        private val providerKey = Regex("sk-[A-Za-z0-9_-]{8,}")
        fun safe(value: String): String = providerKey.replace(apiKey.replace(bearer.replace(dataUrl.replace(value,
            "[图片已省略]"), "Bearer [已隐藏]"), "$1[已隐藏]"), "[已隐藏]")
    }
}
