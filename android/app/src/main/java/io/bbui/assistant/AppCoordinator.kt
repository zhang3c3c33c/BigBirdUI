package io.bbui.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Surface
import fi.iki.elonen.NanoHTTPD
import io.bbui.core.AgentRuntime
import io.bbui.core.PhoneDevice
import io.bbui.device.ShizukuPhoneDevice
import io.bbui.runtime.EmbeddedPiRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID

/** Owns the one agent/device lease. Android UI never performs blocking phone work. */
class AppCoordinator(context: Context, private val onEvent: (JSONObject) -> Unit) {
    private val app = context.applicationContext
    private val ui = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private val control = Executors.newSingleThreadExecutor()
    private val phone: ShizukuPhoneDevice = ShizukuPhoneDevice(app, onUnavailable = { unavailable ->
        ui.post {
            if (EnvironmentFailurePolicy.accepts(unavailable, phone.connectionEpoch(), resourceState, releasingPhone.get(), closed.get())) {
                resourceState = "invalid"
                onEvent(JSONObject().put("type", "environment_lost").put("message", unavailable.reason))
            }
        }
    }, onVideoSizeChanged = { id, width, height ->
        ui.post {
            if (!closed.get() && environmentId == id && resourceState == "ready") {
                videoWidth = width; videoHeight = height
                resourceChanged("ready")
            }
        }
    })
    private val busy = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val manual = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val releasingPhone = AtomicBoolean(false)
    @Volatile private var resourceState = "absent"
    @Volatile private var environmentId = ""
    @Volatile private var environmentOwner = ""
    @Volatile private var videoWidth = 0
    @Volatile private var videoHeight = 0
    private var priorPromptEnvironment = ""
    private val token = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
    private val bridge = PhoneHttpBridge(token, phone, app.getString(R.string.resume_from_app), { operation, generation ->
        check(valid(generation)) { "执行会话已失效" }
        val gui = operation !in setOf("列出应用", "列出屏幕", "__system")
        val created = gui && resourceState != "ready"
        if (created) connectPhone()
        check(valid(generation)) { "执行会话已失效" }
        if (gui) environmentOwner = runSessionId
        if (phoneInputGeneration != generation || created) {
            if (gui) phone.retireManualInput()
            check(enableInput(generation)) { "执行会话已失效" }
            phoneInputGeneration = generation
        }
    }) { event ->
        val generation = event.optLong("runId", -1)
        ui.post {
            if (!closed.get()) {
                if (generation == runGeneration) onEvent(event)
                else if (event.optString("type") == "phone_action_result") onEvent(event.put("retired", true))
            }
        }
    }
    @Volatile private var phoneInputGeneration = -1L
    @Volatile private var runtime: AgentRuntime? = null
    @Volatile private var pendingPrompt: String? = null
    @Volatile private var runtimeReady = false
    @Volatile private var gate = false
    private var runFailed = false
    @Volatile private var runGeneration = 0L
    @Volatile private var settled = CountDownLatch(0)
    private var runSessionId = ""
    private var catalogStarting = false
    private val catalogRequests = linkedMapOf<String, Pair<JSONObject, (JSONObject) -> Unit>>()
    private val catalogSent = mutableSetOf<String>()
    private data class TitleRequest(val command: JSONObject, val allowCatalog: Boolean, val done: (String?) -> Unit, var sent: Boolean = false)
    private val titleRequests = linkedMapOf<String, TitleRequest>()
    private val steerReplies = linkedMapOf<String, SteeringReply>()
    private var probeReply: ((JSONObject) -> Unit)? = null
    private fun failSteering() {
        // Keep correlation until runtime retirement: an ACK in transit still reports a fact
        // about the original run, even after STOP or agent_settled retired that run.
        val replies = steerReplies.values.toList()
        ui.post { replies.forEach { it.uncertain() } }
    }
    @Synchronized private fun retireSteering(owner: AgentRuntime?) {
        val ids = steerReplies.filterValues { it.owner === owner }.keys.toList()
        val retired = ids.mapNotNull { steerReplies.remove(it) }
        ui.post { retired.forEach { it.finish(SteeringOutcome.UNCONFIRMED) } }
    }

    @Synchronized fun isBusy(): Boolean = busy.get() || releasingPhone.get() || catalogRequests.isNotEmpty()

    fun environmentSnapshot(): JSONObject = JSONObject().put("state", resourceState)
        .put("id", environmentId).put("sessionId", environmentOwner).put("videoWidth", videoWidth).put("videoHeight", videoHeight)
    fun isReleasingEnvironment(): Boolean = releasingPhone.get()

    private fun resourceChanged(state: String) {
        resourceState = state
        ui.post { if (!closed.get()) onEvent(JSONObject().put("type", "environment_state").put("environment", environmentSnapshot())) }
    }

    private fun connectPhone(): JSONObject {
        if (resourceState != "ready") resourceChanged("creating")
        return try {
            phone.connect().also {
                environmentId = it.getJSONObject("details").getString("环境编号")
                videoWidth = it.getJSONObject("details").getInt("宽")
                videoHeight = it.getJSONObject("details").getInt("高")
                resourceChanged("ready")
            }
        } catch (error: Exception) { resourceChanged("invalid"); throw error }
    }

    /** Reusable resource release, serialized after STOP/action retirement and before later connects. */
    @Synchronized fun releasePhone(onlyIfIdle: Boolean = false): Boolean {
        if (closed.get() || releasingPhone.get()) return false
        if (onlyIfIdle && (isBusy() || manual.get() || resourceState != "ready")) return false
        releasingPhone.set(true)
        phone.setStopped(true)
        resourceChanged("releasing")
        work.execute {
            try {
                bridge.awaitRetired()
                phone.close()
                environmentOwner = ""
                resourceChanged("absent")
            } catch (error: Exception) {
                resourceChanged("invalid")
                android.util.Log.w("BBUI", "Execution environment cleanup failed", error)
            } finally {
                releasingPhone.set(false)
                emit(JSONObject().put("type", "executor_idle"))
            }
        }
        return true
    }

    /** Reuse Pi's adapters in an isolated process, without a phone lease or session. */
    @Synchronized fun probeModel(config: JSONObject, done: (JSONObject) -> Unit) {
        if (closed.get() || isBusy() || manual.get() || catalogStarting) {
            done(JSONObject().put("ok", false).put("message", "请在执行环境空闲后测试连接")); return
        }
        busy.set(true)
        runSessionId = ""
        gate = false
        runtimeReady = false
        retireTitleRequests()
        runtime?.let { it.close(); retireSteering(it) }
        val instance = EmbeddedPiRuntime(app)
        runtime = instance
        probeReply = done
        fun finish(result: JSONObject) { ui.post { synchronized(this) {
            if (runtime !== instance) return@synchronized
            val reply = probeReply
            probeReply = null; runtime = null; runtimeReady = false
            instance.close(); busy.set(false)
            reply?.invoke(result)
            if (catalogRequests.isNotEmpty()) ensureCatalog()
            emit(JSONObject().put("type", "executor_idle"))
        } } }
        instance.start(JSONObject(config.toString()).put("probeOnly", true), { event ->
            when (event.optString("type")) {
                "model_probe_result" -> finish(JSONObject().put("ok", event.optBoolean("ok"))
                    .put("message", event.optString("message")).put("checks", event.optJSONObject("checks") ?: JSONObject()))
                "runtime_error" -> finish(JSONObject().put("ok", false).put("message", "连接测试启动失败，请检查模型配置"))
            }
        }, { finish(JSONObject().put("ok", false).put("message", "连接测试未完成或超时")) })
    }

    @Synchronized fun sessionCommand(command: JSONObject, done: (JSONObject) -> Unit) {
        managementCommand("bbui_sessions", command, done)
    }

    /** Auxiliary model calls share Node, but never hold the task/device busy flag. */
    @Synchronized fun generateTitle(text: String, config: JSONObject, allowCatalog: Boolean, done: (String?) -> Unit) {
        if (closed.get()) { done(null); return }
        val id = "bbui-title-${UUID.randomUUID()}"
        titleRequests[id] = TitleRequest(JSONObject().put("id", id).put("type", "bbui_title")
            .put("text", text).put("config", JSONObject(config.toString())), allowCatalog, done)
        ui.postDelayed({ synchronized(this) { titleRequests.remove(id)?.done?.invoke(null) } }, 45000)
        flushTitleRequests()
        if (allowCatalog && runtime == null) ensureCatalog()
    }

    @Synchronized private fun flushTitleRequests() {
        if (!runtimeReady || runtime == null || closed.get()) return
        titleRequests.values.forEach { request ->
            // Idle catalogue is about to be replaced for a newly submitted task. Wait for
            // that run's ready event instead of spending the only attempt in the old Node.
            if (!request.sent && (busy.get() || request.allowCatalog)) {
                request.sent = true
                runCatching { runtime!!.send(request.command) }.onFailure { ui.post { synchronized(this) {
                    titleRequests.remove(request.command.getString("id"))?.done?.invoke(null)
                } } }
            }
        }
    }

    @Synchronized private fun titleResponse(event: JSONObject): Boolean {
        val id = event.optString("id")
        if (!id.startsWith("bbui-title-")) return false
        val callback = titleRequests.remove(id)?.done
        if (callback != null) ui.post {
            callback(event.optJSONObject("data")?.takeIf { event.optBoolean("success") }?.opt("title") as? String)
        }
        return true
    }

    @Synchronized private fun retireTitleRequests(all: Boolean = false) {
        val retired = titleRequests.filterValues { all || it.sent }.keys.toList().mapNotNull { titleRequests.remove(it)?.done }
        ui.post { retired.forEach { it(null) } }
    }

    @Synchronized fun memoryCommand(command: JSONObject, done: (JSONObject) -> Unit) = managementCommand("bbui_memory", command, done)

    @Synchronized private fun managementCommand(type: String, command: JSONObject, done: (JSONObject) -> Unit) {
        val id = "bbui-session-${UUID.randomUUID()}"
        catalogRequests[id] = JSONObject(command.toString()).put("id", id).put("type", type) to done
        if (BuildConfig.DEBUG) android.util.Log.d("BBUI-Sessions", "request ${command.optString("action")} pending=${catalogRequests.size} ready=$runtimeReady generation=$runGeneration")
        ui.postDelayed({ synchronized(this) {
            catalogRequests.remove(id)?.second?.invoke(JSONObject().put("success", false).put("error", "会话操作超时，请刷新列表确认结果"))
            catalogSent.remove(id)
        } }, 45000)
        if (runtimeReady) flushSessionCommands() else ensureCatalog()
    }

    @Synchronized private fun flushSessionCommands() {
        catalogRequests.forEach { (id, value) ->
            if (catalogSent.add(id)) {
                if (BuildConfig.DEBUG) android.util.Log.d("BBUI-Sessions", "dispatch ${value.first.optString("action")} generation=$runGeneration")
                runtime?.send(value.first)
            }
        }
    }

    @Synchronized private fun sessionResponse(event: JSONObject): Boolean {
        val id = event.optString("id")
        if (!id.startsWith("bbui-session-")) return false
        val callback = catalogRequests.remove(id)?.second
        catalogSent.remove(id)
        if (BuildConfig.DEBUG) android.util.Log.d("BBUI-Sessions", "response pending=${catalogRequests.size} success=${event.optBoolean("success")} generation=$runGeneration")
        if (callback != null) ui.post { callback(event) }
        return true
    }

    @Synchronized private fun ensureCatalog() {
        if (closed.get() || busy.get() || catalogStarting || runtime != null) return
        catalogStarting = true
        val generation = runGeneration
        val instance = EmbeddedPiRuntime(app)
        runtime = instance
        instance.start(JSONObject().put("catalogOnly", true), { event ->
            synchronized(this) {
                if (runtime === instance) {
                    if (!titleResponse(event) && !sessionResponse(event) && event.optString("type") == "runtime_ready") {
                        catalogStarting = false; runtimeReady = true; flushSessionCommands(); flushTitleRequests()
                    }
                }
            }
        }, { reason -> synchronized(this) {
            if (runtime === instance) {
                catalogStarting = false; runtimeReady = false; runtime = null; instance.close()
                retireTitleRequests(all = true)
                val callbacks = catalogRequests.values.map { it.second }; catalogRequests.clear(); catalogSent.clear()
                ui.post { callbacks.forEach { it(JSONObject().put("success", false).put("error", reason)) } }
            }
        } })
    }

    init { bridge.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }

    private fun emit(event: JSONObject) {
        val generation = runGeneration
        event.put("runId", generation.toString()).put("gate", gate)
        if (runSessionId.isNotBlank()) event.put("sessionId", runSessionId)
        ui.post { if (!closed.get() && generation == runGeneration) onEvent(event) }
    }
    private fun status(message: String, state: String = "idle") = emit(
        JSONObject().put("type", "status").put("status", state).put("message", message)
    )

    @Synchronized fun connect() {
        if (closed.get()) return
        if (releasingPhone.get()) return
        if (!busy.compareAndSet(false, true)) { status("任务执行中，请先停止"); return }
        val generation = runGeneration
        work.execute {
            try {
                status("正在连接 Shizuku 并创建虚拟屏…", "connecting")
                val result = connectPhone()
                phone.setStopped(true)
                if (generation == runGeneration) {
                    emit(JSONObject().put("type", "device_connected").put("details", result))
                    status("虚拟屏已连接；可开始任务或人工接管", "connected")
                }
            } catch (error: Exception) { failure(generation, "连接失败", error) }
            finally { synchronized(this) { if (generation == runGeneration) { busy.set(false); emit(JSONObject().put("type", "executor_idle")) } } }
        }
    }

    fun start(config: JSONObject, prompt: String) {
        if (closed.get()) return
        if (prompt.isBlank()) { status("请输入任务"); return }
        if (config.optString("model").isBlank() || config.optString("baseUrl").isBlank()) {
            status("请先填写模型名称和 API 地址"); return
        }
        begin(config, prompt, false)
    }

    fun runRuntimeGate(config: JSONObject) {
        if (closed.get()) return
        val local = JSONObject().put("provider", "bbui-gate").put("model", "bbui-gate-model").put("input", org.json.JSONArray(listOf("text", "image")))
            .put("baseUrl", "http://127.0.0.1:${bridge.listeningPort}/v1").put("apiKey", token)
        begin(local, "运行本地模拟测试：调用两次 phone_action 查看模拟图片，然后报告完成。", true)
    }

    internal fun localSessionTestConfig(scenario: String = ""): JSONObject {
        check(BuildConfig.DEBUG)
        return JSONObject().put("provider", "bbui-gate").put("model", "bbui-gate-model").put("input", org.json.JSONArray(listOf("text", "image")))
            .put("baseUrl", "http://127.0.0.1:${bridge.listeningPort}/v1").put("apiKey", token).put("mockPhone", true).put("mockScenario", scenario)
    }

    @Synchronized private fun begin(config: JSONObject, prompt: String, isGate: Boolean) {
        if (closed.get()) return
        if (releasingPhone.get()) return
        if (!busy.compareAndSet(false, true)) { status("已有任务执行中，请先停止"); return }
        val generation = ++runGeneration
        val runSettled = CountDownLatch(1)
        settled = runSettled
        cancelled.set(false)
        manual.set(false)
        phone.setManual(false, generation)
        pendingPrompt = prompt
        gate = isGate
        runSessionId = config.optString("sessionId")
        catalogStarting = false
        // Directory commands issued after run_started must wait for the replacement
        // runtime, even before the worker has closed the previous Pi process.
        runtimeReady = false
        runFailed = false
        retireTitleRequests()
        emit(JSONObject().put("type", "run_started").put("prompt", prompt))
        phone.setStopped(true)
        val mocked = isGate || (BuildConfig.DEBUG && config.optBoolean("mockPhone"))
        val runToken = bridge.begin(mocked, generation, runSessionId, config.optString("mockScenario"))
        work.execute {
            try {
                if (!valid(generation)) return@execute
                runtime?.let { it.close(); retireSteering(it) }
                runtimeReady = false
                if (!mocked) {
                    phone.bindSystem()
                }
                val settings = JSONObject(config.toString())
                    .put("gate", isGate)
                    .put("bridgeUrl", "http://127.0.0.1:${bridge.listeningPort}")
                    .put("bridgeToken", runToken)
                if (!mocked) {
                    settings.put("tools", SettingsStore(app).toolsConfig())
                    settings.put("environmentId", environmentId)
                        .put("environmentRebuilt", priorPromptEnvironment != environmentId)
                        .put("previousEnvironmentId", priorPromptEnvironment)
                    priorPromptEnvironment = environmentId
                }
                if (mocked) settings.put("apiKey", runToken)
                if (!valid(generation)) return@execute
                val instance = EmbeddedPiRuntime(app)
                synchronized(this) {
                    if (!valid(generation)) return@execute
                    runtime = instance
                }
                status(if (isGate) "正在验证本地 Pi、图片和工具循环…" else "正在启动手机本地 Pi…", "starting")
                instance.start(settings, { event ->
                    if (event.optString("type") == "agent_settled") runSettled.countDown()
                    handleRuntimeEvent(event, generation)
                }, { reason ->
                    failure(generation, "Pi 已停止", IllegalStateException(reason))
                })
            } catch (error: Exception) { failure(generation, "启动失败", error) }
        }
    }

    private fun valid(generation: Long) = generation == runGeneration && !cancelled.get() && !closed.get()
    @Synchronized private fun enableInput(generation: Long): Boolean {
        if (!valid(generation)) return false
        phone.setStopped(false)
        return true
    }

    @Synchronized private fun handleRuntimeEvent(event: JSONObject, generation: Long) {
        steerReplies.remove(event.optString("id"))?.let { reply ->
            ui.post { reply.finish(if (event.optBoolean("success")) SteeringOutcome.ACCEPTED else SteeringOutcome.NOT_SENT) }; return
        }
        if (generation != runGeneration || closed.get()) return
        if (titleResponse(event) || sessionResponse(event)) return
        when (event.optString("type")) {
            "message_end" -> {
                val message = event.optJSONObject("message")
                if (message?.optString("stopReason") == "error") {
                    runFailed = true
                    phone.setStopped(true)
                    status("模型请求失败：${message.optString("errorMessage", "请检查模型配置与网络")}", "error")
                }
            }
            "runtime_error" -> {
                stop()
                status("Pi 初始化失败：${event.optString("message")}", "error")
            }
            "runtime_ready" -> {
                runtimeReady = true
                flushSessionCommands()
                flushTitleRequests()
                val prompt = pendingPrompt
                pendingPrompt = null
                if (prompt != null && !cancelled.get()) {
                    runtime?.send(JSONObject().put("type", "prompt").put("message", prompt))
                    status(if (gate) "Pi 正在运行模拟工具测试" else "正在执行任务", "running")
                }
            }
            "agent_settled" -> {
                // Pi accepts steer even when idle. Retire unacknowledged steering at
                // the round boundary so it cannot become a hidden future prompt.
                failSteering()
                runtime?.send(JSONObject().put("type", "clear_queue"))
                busy.set(false)
                phone.setStopped(true)
                bridge.revoke()
                if (gate) {
                    val passed = !runFailed && !cancelled.get() && bridge.mockCalls.get() >= 2 && bridge.imagesSeen.get() >= 2
                    emit(JSONObject().put("type", "runtime_gate_result").put("passed", passed)
                        .put("toolCalls", bridge.mockCalls.get()).put("imagesSeen", bridge.imagesSeen.get()))
                    status(if (passed) "本地 Pi 门槛通过：两轮图片与工具调用完成" else "本地 Pi 门槛未通过，请查看诊断", if (passed) "passed" else "error")
                } else if (runFailed) status("本轮回复出错，请查看模型错误", "error")
                else status(if (cancelled.get()) "执行已停止" else "本轮回复结束", "settled")
            }
        }
        emit(event)
    }

    @Synchronized fun stop(reason: String = "stop") {
        if (closed.get()) return
        retireTitleRequests(all = true)
        probeReply?.let { reply -> ui.post { reply(JSONObject().put("ok", false).put("message", "连接测试已取消")) } }
        probeReply = null
        busy.set(true) // Hold replacement catalogue startup until the previous process is closed.
        val generation = ++runGeneration
        val previous = runtime
        val previousSettled = settled
        runtime = null
        runtimeReady = false
        catalogStarting = false
        val interruptedQueries = catalogSent.mapNotNull { catalogRequests.remove(it)?.second }
        catalogSent.clear()
        ui.post { interruptedQueries.forEach { it(JSONObject().put("success", false).put("error", "会话操作被停止，请刷新确认结果")) } }
        cancelled.set(true)
        failSteering()
        emit(JSONObject().put("type", "run_stopped").put("reason", reason))
        pendingPrompt = null
        manual.set(false)
        phone.setManual(false, generation)
        phone.setStopped(true) // Never queue STOP behind an action or model request.
        bridge.revoke()
        work.execute {
            try {
                if (previous != null) {
                    previous.send(JSONObject().put("type", "clear_queue"))
                    previous.send(JSONObject().put("type", "abort"))
                    previousSettled.await(3, TimeUnit.SECONDS)
                }
            } catch (_: Exception) { }
            finally {
                previous?.close()
                retireSteering(previous)
                bridge.awaitRetired()
                if (generation == runGeneration) {
                    busy.set(false)
                    if (!manual.get()) status("执行已停止；可输入新任务", "settled")
                    synchronized(this) { if (catalogRequests.isNotEmpty()) ensureCatalog() }
                }
            }
        }
        status("已禁止新输入，正在停止 Pi", "stopping")
    }

    @Synchronized fun takeOver(mockPhone: Boolean = false) {
        if (closed.get() || releasingPhone.get()) return
        stop("takeover")
        val generation = runGeneration
        // Retirement is queued behind STOP cleanup. Never enable manual input while an
        // already-dispatched agent action can still touch the device.
        work.execute {
            try {
                if (generation != runGeneration || closed.get()) return@execute
                if (!mockPhone) connectPhone()
                synchronized(this) {
                    if (generation != runGeneration || closed.get()) return@synchronized
                    manual.set(true)
                    phone.setManual(true, generation)
                    emit(JSONObject().put("type", "handoff_ready"))
                    status("人工操作中", "manual")
                }
            } catch (error: Exception) { failure(generation, "接管失败", error) }
        }
    }

    @Synchronized fun resume() {
        if (closed.get()) return
        manual.set(false)
        phone.setManual(false, runGeneration)
    }

    fun answerQuestion(command: JSONObject): Boolean = bridge.answerQuestion(command)

    @Synchronized fun steer(sessionId: String, submissionId: String, text: String, done: (Boolean) -> Unit) {
        steerQueued(sessionId, submissionId, text, runGeneration.toString()) { done(it == SteeringOutcome.ACCEPTED) }
    }

    @Synchronized internal fun steerQueued(sessionId: String, submissionId: String, text: String, expectedRunId: String,
        done: (SteeringOutcome) -> Unit) {
        if (closed.get() || cancelled.get() || manual.get() || !busy.get() || !runtimeReady ||
            sessionId != runSessionId || expectedRunId != runGeneration.toString() || text.isBlank()) { done(SteeringOutcome.NOT_SENT); return }
        val id = "bbui-steer-$submissionId"
        if (id in steerReplies) { done(SteeringOutcome.NOT_SENT); return }
        steerReplies[id] = SteeringReply(runtime!!, done)
        runCatching { runtime?.send(JSONObject().put("type", "steer").put("id", id).put("message", text)) }
            .onFailure { steerReplies[id]?.uncertain() }
        ui.postDelayed({ synchronized(this) { steerReplies[id]?.uncertain() } }, 15000)
    }

    fun attachPreview(surface: Surface?, width: Int, height: Int) {
        if (!closed.get()) phone.attachPreview(surface, width, height)
    }

    @Synchronized fun touch(action: Int, x: Float, y: Float) {
        val generation = runGeneration
        if (manual.get() && !closed.get()) {
            control.execute {
                if (generation != runGeneration || !manual.get() || closed.get()) return@execute
                try { phone.touch(action, x, y, generation) }
                catch (e: Exception) { failure(generation, "人工输入失败", e) }
            }
        }
    }

    @Synchronized private fun failure(generation: Long, prefix: String, error: Exception) {
        if (generation != runGeneration || closed.get()) return
        retireTitleRequests(all = true)
        phone.setStopped(true)
        bridge.revoke()
        busy.set(false)
        runFailed = true
        failSteering()
        manual.set(false); phone.setManual(false, runGeneration)
        pendingPrompt = null
        val failed = runtime
        runtime = null; runtimeReady = false; catalogStarting = false
        failed?.close()
        retireSteering(failed)
        val callbacks = catalogRequests.values.map { it.second }
        catalogRequests.clear(); catalogSent.clear()
        ui.post { callbacks.forEach { it(JSONObject().put("success", false).put("error", "Pi 已退出，请刷新会话列表")) } }
        status("$prefix：${error.message ?: error.javaClass.simpleName}", "error")
        if (resourceState == "invalid") releasePhone()
    }

    @Synchronized fun close() {
        if (closed.get()) return
        stop()
        if (!closed.compareAndSet(false, true)) return
        steerReplies.clear()
        runGeneration++
        bridge.stop()
        work.execute { runtime?.close(); phone.close() }
        work.shutdown()
        control.shutdown()
    }
}

/** A library supplies HTTP framing; this bridge exposes no arbitrary shell or filesystem API. */
private class PhoneHttpBridge(
    @Volatile private var token: String,
    private val device: PhoneDevice,
    private val resumeFromApp: String,
    private val prepare: (String, Long) -> Unit,
    private val event: (JSONObject) -> Unit
) : NanoHTTPD("127.0.0.1", 0) {
    @Volatile private var gate = false
    val mockCalls = AtomicInteger()
    val imagesSeen = AtomicInteger()
    private val mockRequests = AtomicInteger()
    private var mockScenario = ""
    @Volatile private var inputQuarantined = false
    private val actionLock = Any()
    private val authLock = Any()
    private val questions = QuestionBroker(event)
    fun answerQuestion(command: JSONObject): Boolean = questions.answer(command)
    @Volatile private var eventContext = JSONObject()
    private fun receipt(value: JSONObject, context: JSONObject) {
        event(value.put("runId", context.optString("runId")).put("sessionId", context.optString("sessionId")).put("gate", context.optBoolean("gate")))
    }

    fun awaitRetired() { synchronized(actionLock) { /* Wait for the old dispatch receipt. */ } }
    fun revoke() { synchronized(authLock) { questions.cancel(); token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) } } }
    fun begin(mock: Boolean, generation: Long, sessionId: String, scenario: String = ""): String { revoke(); gate = mock; mockScenario = scenario; inputQuarantined = false;
        eventContext = JSONObject().put("runId", generation.toString()).put("sessionId", sessionId).put("gate", mock && sessionId.isBlank()); mockCalls.set(0); imagesSeen.set(0); mockRequests.set(0); return token }

    override fun serve(session: IHTTPSession): Response {
        val context = eventContext
        val supplied = session.headers["authorization"] ?: ""
        if (!MessageDigest.isEqual(supplied.toByteArray(), "Bearer $token".toByteArray())) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", "{}")
        }
        if (session.method != Method.POST) return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "application/json", "{}")
        val length = session.headers["content-length"]?.toLongOrNull() ?: -1L
        if (length !in 0..(8L * 1024 * 1024)) return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{}")
        return try {
            val files = mutableMapOf<String, String>()
            require(session.headers["content-type"]?.substringBefore(';')?.trim() == "application/json") { "Expected JSON" }
            // JSON is UTF-8 even when a client omits charset. NanoHTTPD otherwise defaults to ASCII.
            session.headers["content-type"] = "application/json; charset=utf-8"
            session.parseBody(files)
            require(MessageDigest.isEqual(supplied.toByteArray(), "Bearer $token".toByteArray())) { "执行会话已失效" }
            val body = JSONObject(files["postData"] ?: "{}")
            when (session.uri) {
                "/action" -> synchronized(actionLock) {
                    require(MessageDigest.isEqual(supplied.toByteArray(), "Bearer $token".toByteArray())) { "执行会话已失效" }
                    val operation = body.getString("操作")
                    val params = body.getJSONObject("参数")
                    check(!inputQuarantined || operation in setOf("查看", "等待", "列出应用", "列出屏幕")) { "原操作派发情况待确认，禁止新输入" }
                    val response = if (gate) mockAction(operation) else {
                        prepare(operation, context.getString("runId").toLong())
                        require(MessageDigest.isEqual(supplied.toByteArray(), "Bearer $token".toByteArray())) { "执行会话已失效" }
                        device.action(operation, params)
                    }
                    receipt(JSONObject().put("type", "phone_action_result").put("operation", operation)
                        .put("details", response.optJSONObject("details") ?: JSONObject()), context)
                    json(response)
                }
                "/system" -> synchronized(actionLock) {
                    require(MessageDigest.isEqual(supplied.toByteArray(), "Bearer $token".toByteArray())) { "执行会话已失效" }
                    check(!gate) { "本地模拟模式不执行系统操作" }
                    val group = body.getString("group"); val operation = body.getString("operation")
                    val readonly = operation in setOf("calendars", "capabilities", "list", "details", "launch_entries", "permissions", "read", "stat", "search", "read_text")
                    check(!inputQuarantined || readonly) { "原操作派发情况待确认，禁止新输入" }
                    prepare(if (group == "apps" && operation == "launch") "打开应用" else "__system", context.getString("runId").toLong())
                    require(MessageDigest.isEqual(supplied.toByteArray(), "Bearer $token".toByteArray())) { "执行会话已失效" }
                    val response = device.systemAction(group, operation, body.getJSONObject("params"), body.getString("actionId"))
                    receipt(JSONObject().put("type", "system_action_result").put("operation", "$group.$operation").put("details", response.optJSONObject("details") ?: JSONObject()), context)
                    json(response)
                }
                "/questions" -> {
                    val pending = synchronized(authLock) {
                        require(MessageDigest.isEqual(supplied.toByteArray(), "Bearer $token".toByteArray())) { "执行会话已失效" }
                        questions.open(body, context)
                    }
                    json(pending.await())
                }
                "/stop" -> synchronized(authLock) {
                    require(MessageDigest.isEqual(supplied.toByteArray(), "Bearer $token".toByteArray())) { "执行会话已失效" }
                    // Re-enabling input is a UI operation, never a network cancellation side effect.
                    if (!body.optBoolean("stopped", true)) throw IllegalArgumentException(resumeFromApp)
                    if (body.optString("source") == "transport") {
                        inputQuarantined = true
                        device.quarantineInput("调用传输中断，原动作派发情况待确认")
                        receipt(JSONObject().put("type", "execution_channel_error"), context)
                    }
                    else { questions.cancel(); device.setStopped(true) }
                    json(JSONObject().put("ok", true))
                }
                "/v1/chat/completions" -> if (gate) mockCompletion(body) else json(JSONObject(), Response.Status.NOT_FOUND)
                else -> json(JSONObject(), Response.Status.NOT_FOUND)
            }
        } catch (error: Exception) {
            json(JSONObject().put("isError", true).put("content", JSONArray().put(
                JSONObject().put("type", "text").put("text", error.message ?: "手机操作失败")
            )).put("details", JSONObject().put("错误", error.message ?: "手机操作失败")))
        }
    }

    private fun mockAction(operation: String): JSONObject {
        require(operation == "查看") { "模拟门槛仅允许查看" }
        val count = mockCalls.incrementAndGet()
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(if (count % 2 == 0) Color.BLUE else Color.GREEN)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        val details = JSONObject().put("截图编号", "mock-$count").put("屏幕会话", "virtual")
            .put("宽度", 128).put("高度", 128).put("模拟", true)
        return JSONObject().put("details", details).put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", details.toString()))
            .put(JSONObject().put("type", "image").put("mimeType", "image/png")
                .put("data", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP))))
    }

    private fun mockCompletion(body: JSONObject): Response {
        val messages = body.optJSONArray("messages") ?: JSONArray()
        var imageCount = 0
        for (i in 0 until messages.length()) {
            val content = messages.optJSONObject(i)?.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                if (content.optJSONObject(j)?.optString("type") == "image_url") imageCount++
            }
        }
        imagesSeen.set(maxOf(imagesSeen.get(), imageCount))
        val count = mockCalls.get()
        val requestNumber = mockRequests.incrementAndGet()
        val delta = JSONObject().put("role", "assistant")
        val finish: String
        val skillQuestion = mockScenario == "skill-question"
        if (skillQuestion && requestNumber == 1) {
            delta.put("tool_calls", JSONArray().put(JSONObject().put("index", 0).put("id", "skill-fixture").put("type", "function")
                .put("function", JSONObject().put("name", "read").put("arguments", JSONObject()
                    .put("path", LocalQuestionFixture.skillPath(messages)).toString()))))
            finish = "tool_calls"
        } else if ((mockScenario == "question" && requestNumber == 1) || (skillQuestion && requestNumber == 2)) {
            if (skillQuestion) check(LocalQuestionFixture.receivedSkill(messages)) { "Skill read did not reach the model" }
            delta.put("tool_calls", JSONArray().put(JSONObject().put("index", 0).put("id", "question-fixture").put("type", "function")
                .put("function", JSONObject().put("name", "ask_user_question").put("arguments", JSONObject().put("questions", JSONArray().put(JSONObject()
                    .put("id", "choice").put("header", if (skillQuestion) "收件人" else "选择").put("question", if (skillQuestion) "发给谁？" else "请选择测试选项")
                    .put("options", if (skillQuestion) JSONArray() else JSONArray().put(JSONObject().put("label", "甲").put("description", "测试甲")).put(JSONObject().put("label", "乙").put("description", "测试乙")))
                    .put("multiSelect", false))).toString()))))
            finish = "tool_calls"
        } else if (mockScenario == "question" || skillQuestion) {
            delta.put("content", if (LocalQuestionFixture.receivedAnswer(messages, skillQuestion)) "已收到测试答案。" else "QUESTION_FIXTURE_FAILED：未收到当前问题的有效工具答案。")
            finish = "stop"
        } else if (count < 2 && requestNumber <= 3) {
            delta.put("tool_calls", JSONArray().put(JSONObject().put("index", 0)
                .put("id", "gate-call-$count").put("type", "function")
                .put("function", JSONObject().put("name", "phone_action")
                    .put("arguments", JSONObject().put("操作", "查看").put("意图", "查看模拟测试画面")
                        .put("参数", JSONObject().put("执行后等待毫秒", 0).put("屏幕会话", "virtual")).toString()))))
            finish = "tool_calls"
        } else if (count == 2 && requestNumber == 3) {
            val task = JSONObject().put("version", 1).put("id", "local-gate").put("goal", "验证本地图片与工具循环")
                .put("status", "completed").put("summary", "已观察两张模拟图片")
                .put("completionEvidence", "两次查看工具均返回模拟画面")
            delta.put("tool_calls", JSONArray().put(JSONObject().put("index", 0)
                .put("id", "gate-task-state").put("type", "function")
                .put("function", JSONObject().put("name", "task_state")
                    .put("arguments", JSONObject().put("action", "update").put("task", task).toString()))))
            finish = "tool_calls"
        } else { delta.put("content", "模拟测试完成，已观察两张图片。"); finish = "stop" }
        fun chunk(value: JSONObject, reason: String?): String = JSONObject()
            .put("id", "gate-$count").put("object", "chat.completion.chunk")
            .put("created", System.currentTimeMillis() / 1000).put("model", "bbui-gate-model").put("input", org.json.JSONArray(listOf("text", "image")))
            .put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", value)
                .put("finish_reason", reason ?: JSONObject.NULL))).toString()
        val stream = "data: ${chunk(delta, null)}\n\ndata: ${chunk(JSONObject(), finish)}\n\ndata: [DONE]\n\n"
        return newFixedLengthResponse(Response.Status.OK, "text/event-stream; charset=utf-8", stream)
    }

    private fun json(body: JSONObject, status: Response.Status = Response.Status.OK) =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body.toString())
}
