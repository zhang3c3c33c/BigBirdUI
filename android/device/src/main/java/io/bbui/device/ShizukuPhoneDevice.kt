package io.bbui.device

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Binder
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import android.view.Surface
import io.bbui.core.PhoneDevice
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

class ShizukuPhoneDevice(context: Context, private val useTestTarget: Boolean = false,
    private val diagnosticScreenshots: Boolean = false,
    private val onUnavailable: (DeviceUnavailable) -> Unit = {},
    private val onVideoSizeChanged: (String, Int, Int) -> Unit = { _, _, _ -> }) : PhoneDevice {
    private val context = context.applicationContext
    private val stopped = AtomicBoolean(true)
    private val systemEpoch = java.util.concurrent.atomic.AtomicLong()
    // Unlike ConnectionGeneration's active ticket, this survives internal fault cleanup so
    // a real failure can still reach the UI. Explicit close or a replacement binding retires it.
    private val connectionEpoch = java.util.concurrent.atomic.AtomicLong()
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val claims = File(context.filesDir, "device-claims").apply { mkdirs() }
    private val runs = File(context.filesDir, "device-runs").apply { mkdirs() }
    private val main = Handler(Looper.getMainLooper())
    private var service: IDeviceService? = null
    private var connection: ServiceConnection? = null
    private var args: Shizuku.UserServiceArgs? = null
    private var controls: ControlChannel? = null
    private var supportInitialized = false
    private var frames: VideoFrames? = null
    private var videoFd: ParcelFileDescriptor? = null
    private var displayId = -1
    private var boundPackage = "io.bbui.assistant"
    @Volatile private var inputFailure: String? = null
    private var lastFrameContext: String? = null
    private var lastInputAt = 0L
    private val latest = AtomicReference<Observation?>(null)
    private val retired = ConcurrentLinkedQueue<Observation>()
    private val manual = ManualInputGate()
    private val binding = ConnectionGeneration()
    @Volatile private var activeTicket: ConnectionGeneration.Ticket? = null
    private var previewSurface: Surface? = null
    @Volatile private var previewWidth = 0
    @Volatile private var previewHeight = 0
    private var manualDown = false
    private var manualPointerEpoch = -1L
    private val lease = Binder()
    private var binderDead: Shizuku.OnBinderDeadListener? = null
    fun connectionEpoch(): Long = connectionEpoch.get()
    private data class Observation(val id: String, val captured: Long, val frame: CapturedFrame, val identity: String)

    fun bindSystem(): Unit = synchronized(lock) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "connect 必须在工作线程调用" }
        check(DevicePermission.available() && DevicePermission.granted()) { "Shizuku 未就绪" }
        if (!closed.get() && service != null && inputFailure == null && binding.isActive() && binding.failure() == null && Shizuku.pingBinder()) {
            return@synchronized
        }
        release()
        check(DevicePermission.available()) { "Shizuku 未启动，请先启动 Shizuku 服务" }
        check(DevicePermission.granted()) { "请授予 BBUI Shizuku 权限后重新连接" }
        check(Shizuku.getUid() == 2000) { "仅支持 Shizuku adb UID 2000" }
        closed.set(false); stopped.set(true)
        val epoch = connectionEpoch.incrementAndGet()
        val ticket = binding.begin()
        activeTicket = ticket
        val ready = CountDownLatch(1)
        val connectedService = AtomicReference<IDeviceService?>(null)
        val callback = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val remote = IDeviceService.Stub.asInterface(binder)
                if (binding.isCurrent(ticket)) {
                    connectedService.set(remote); ready.countDown()
                } else {
                    // A cancelled startup may publish its Binder after unbind. This process has
                    // a unique tag and cannot be a later session, so explicitly retire it.
                    Thread({ runCatching { remote.destroy() } }, "bbui-retire-binding").apply { isDaemon = true; start() }
                }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                if (binding.fail(ticket, "Shizuku 执行服务已断开")) {
                    stopped.set(true); invalidate()
                    onUnavailable(DeviceUnavailable("Shizuku 执行服务已断开", epoch, DeviceUnavailable.Source.SYSTEM))
                }
            }
        }
        connection = callback
        val options = Shizuku.UserServiceArgs(ComponentName(context, DeviceService::class.java))
            .tag(ticket.tag).daemon(false).processNameSuffix("device_${ticket.tag.takeLast(8)}").version(1)
        args = options
        var bindError: Throwable? = null
        main.post { try {
            check(binding.isCurrent(ticket)) { "Shizuku 绑定请求已取消" }
            Shizuku.bindUserService(options, callback)
        }
            catch (error: Throwable) { bindError = error; ready.countDown() } }
        try {
            check(ready.await(20, TimeUnit.SECONDS)) { "Shizuku UserService 启动超时" }
            bindError?.let { throw it }
            check(binding.isCurrent(ticket)) { "Shizuku 连接已取消" }
            val remote = checkNotNull(connectedService.get()) { "Shizuku UserService 未返回" }
            service = remote
            val death = Shizuku.OnBinderDeadListener {
                if (binding.fail(ticket, "Shizuku 服务已断开")) {
                    stopped.set(true); invalidate()
                    onUnavailable(DeviceUnavailable("Shizuku 服务已断开", epoch, DeviceUnavailable.Source.SYSTEM))
                    Thread { synchronized(lock) { if (activeTicket == ticket) release() } }.start()
                }
            }
            binderDead = death
            Shizuku.addBinderDeadListener(death)
        } catch (error: Throwable) { release(); throw error }
    }

    override fun connect(): JSONObject = synchronized(lock) {
        if (frames != null && inputFailure == null && binding.isActive() && binding.failure() == null) return@synchronized capture("已连接", 0)
        bindSystem()
        val ticket = checkNotNull(activeTicket)
        val epoch = connectionEpoch.get()
        try {
            val pipe = ParcelFileDescriptor.createPipe()
            val writer = Thread({
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                    context.assets.open("scrcpy-server-v4.1").use { it.copyTo(out) }
                }
            }, "bbui-server-resource").apply { start() }
            val started = try { JSONObject(rpc("启动scrcpy", 25000, true) { it.start(pipe[0], lease) }) } finally { pipe[0].close() }
            persist(File(runs, "connection.json"), started.put("serviceTag", ticket.tag))
            writer.join(1000)
            displayId = started.getInt("displayId")
            check(displayId > 0) { "不允许使用主屏幕" }
            controls = ControlChannel(rpc("连接控制通道", 5000) { it.control() })
            val fd = rpc("连接视频通道", 5000) { it.video() }; videoFd = fd
            frames = VideoFrames(ParcelFileDescriptor.AutoCloseInputStream(fd),
                { width, height -> if (binding.isCurrent(ticket)) { invalidate(); onVideoSizeChanged(ticket.tag, width, height) } }, { error ->
                    if (binding.fail(ticket, error.message ?: "视频连接已断开")) {
                        stopped.set(true); invalidate()
                        onUnavailable(DeviceUnavailable(error.message ?: "视频连接已断开", epoch, DeviceUnavailable.Source.DISPLAY))
                    }
                })
                .also { it.start(); it.attach(previewSurface, previewWidth, previewHeight) }
            val ownPackage = context.packageName
            check(ownPackage == "io.bbui.assistant") { "未知的 BBUI 应用包名" }
            val debugTarget = ComponentName(ownPackage, "$ownPackage.TestTargetActivity")
            @Suppress("DEPRECATION")
            val hasDebugTarget = runCatching { context.packageManager.getActivityInfo(debugTarget, 0) }.isSuccess
            val initialActivity = if (useTestTarget && hasDebugTarget) ".TestTargetActivity" else ".WorkspaceActivity"
            val launch = JSONObject(rpc("打开初始页面", 25000, true) { it.launch("$ownPackage/$initialActivity") })
            check(launch.optString("状态") == "已派发") { launch.optString("错误", "初始页面启动请求未确认") }
            boundPackage = ownPackage
            Thread.sleep(300)
            capture("已连接，已暂停", 0)
        } catch (error: Throwable) { release(); throw error }
    }

    override fun action(operation: String, params: JSONObject): JSONObject = synchronized(lock) {
        try { performAction(operation, params) }
        catch (error: Exception) {
            val message = error.message ?: error.javaClass.simpleName
            if (operation in setOf("列出应用", "列出屏幕")) queryResult(JSONObject().put("错误", message))
            else finishResult(if (operation in setOf("查看", "等待")) "无需派发" else "未派发", 0, message,
                JSONObject().put("阶段", "派发前").put("动作编号", params.optString("动作编号")))
        }
    }

    override fun systemAction(group: String, operation: String, params: JSONObject, actionId: String): JSONObject = synchronized(lock) {
        val readonly = operation in setOf("capabilities", "calendars", "list", "details", "launch_entries", "permissions", "read", "stat", "search", "read_text")
        if (!readonly) check(inputFailure == null) { inputFailure ?: "输入通道待确认" }
        val aid = ActionRequirements.actionId(actionId)
        check(DevicePermission.available() && DevicePermission.granted()) { "Shizuku 未就绪" }
        check(!stopped.get() && !manual.snapshot().enabled) { "用户停止或接管已生效" }
        val request = JSONObject().put("group", group).put("operation", operation).put("params", params)
        val serializedRequest = request.toString()
        SystemPayloadLimits.rejection(serializedRequest)?.let { rejected ->
            return@synchronized textResult(rejected.put("动作编号", aid)).put("isError", true)
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(serializedRequest.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val claim = File(claims, "system-$aid.json")
        if (!readonly && claim.exists()) {
            val prior = JSONObject(claim.readText())
            require(prior.optString("requestHash") == digest) { "动作编号已用于其他系统请求" }
            val detail = prior.optJSONObject("result") ?: JSONObject().put("执行", JSONObject().put("状态", "未知"))
            return@synchronized textResult(JSONObject(detail.toString()).put("重复请求", true).put("本次未重放", true)).put("isError", detail.has("错误"))
        }
        if (group in setOf("clipboard", "calendar", "contacts", "sms", "call_log", "media", "clock") && !supportInitialized) {
            val pipe = ParcelFileDescriptor.createPipe()
            val writer = Thread({ runCatching { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out -> context.assets.open("scrcpy-server-v4.1").use { it.copyTo(out) } } } }, "bbui-scrcpy-support").apply { start() }
            try { rpc("加载 scrcpy 兼容库", 15000) { it.initializeSupport(pipe[0]) }; supportInitialized = true }
            finally { pipe[0].close(); writer.join(1000) }
        }
        check(!stopped.get() && !manual.snapshot().enabled) { "用户停止或接管已生效" }
        val journal = JSONObject().put("requestHash", digest)
        if (!readonly) { check(claim.createNewFile()) { "重复动作编号" }; persist(claim, journal) }
        val result = try {
            if (group == "apps" && operation == "list") {
                val original = listApplications(JSONObject().put("关键词", params.optString("query", params.optString("packageName")))).getJSONObject("details")
                val user = original.getInt("用户编号")
                require(!params.has("userId") || params.getInt("userId") == user) { "仅支持当前 Android 用户 $user" }
                val apps = original.getJSONArray("应用")
                val offset = params.optInt("offset", 0).coerceAtLeast(0); val limit = params.optInt("limit", 50).coerceIn(1, 200)
                val page = JSONArray(); for (i in offset until minOf(apps.length().toLong(), offset.toLong() + limit).toInt()) page.put(apps.getJSONObject(i))
                JSONObject().put("data", JSONObject().put("items", page).put("total", apps.length()).put("userId", user)
                    .put("nextOffset", if (offset.toLong() + limit < apps.length()) offset + limit else JSONObject.NULL))
                    .put("执行", JSONObject().put("状态", "无需派发")).put("观察", JSONObject().put("状态", "未请求"))
            } else JSONObject(rpc("系统操作", 65000, false) { it.system(serializedRequest) })
        }
        catch (error: Exception) { if (!readonly && error is DeadlineCall.Uncertain) quarantineInput("系统操作是否完成尚未确认"); JSONObject().put("错误", error.message ?: "系统调用失败").put("执行", JSONObject().put("状态", "未知")) }
        result.put("动作编号", aid).put("bbuiTool", JSONObject().put("title", when (group) { "apps" -> "应用管理"; "notifications" -> "通知"; "clipboard" -> "剪贴板"; "calendar" -> "管理日程"; "contacts" -> "管理联系人"; "sms" -> "读取短信"; "call_log" -> "查询通话记录"; "media" -> "检索媒体"; "clock" -> "管理闹钟与计时器"; else -> "共享文件" }).put("summary", "$operation：${if (result.has("错误")) "调用失败" else "已返回结果"}"))
        if (!readonly) runCatching { persist(claim, journal.put("result", JSONObject(result.toString()).apply { remove("data") })) }.onFailure { result.put("记录错误", it.message) }
        textResult(result).put("isError", result.has("错误"))
    }

    private fun performAction(operation: String, params: JSONObject): JSONObject {
        recycleRetired()
        require(params.optString("屏幕会话", "virtual") == "virtual") { "本机版仅支持 virtual，禁止回退 main" }
        require(!params.optBoolean("读取节点", false)) { "虚拟屏暂不支持读取节点" }
        when (operation) {
            "列出应用" -> return listApplications(params)
            "列出屏幕" -> {
                val screen = JSONObject().put("屏幕会话", "virtual").put("显示屏编号", displayId)
                    .put("最后启动目标", boundPackage).put("连接状态", channelState())
                runCatching { inspect() }.onSuccess { screen.put("当前前台", it.apply { remove("ime") }) }
                    .onFailure { screen.put("前台读取错误", it.message) }
                return queryResult(JSONObject().put("屏幕", JSONArray().apply { if (displayId > 0) put(screen) }))
            }
        }
        require(operation in SUPPORTED) { "当前安卓执行器不支持此操作：$operation" }
        val waitMs = integer(params, "执行后等待毫秒", 0, 30000, 0)
        if (operation == "查看" || operation == "等待") {
            val duration = if (operation == "等待") integer(params, "时间", 0, 30000, waitMs) else waitMs
            return finishResult("无需派发", duration)
        }
        checkRunning()
        val supported = setOf("点击", "双击", "长按", "滑动", "拖拽", "放大", "缩小", "输入内容", "删除内容", "全选", "按键", "打开应用")
        require(operation in supported) { "不支持的虚拟屏操作：$operation；系统面板属于主屏，拒绝执行" }
        val aid = ActionRequirements.actionId(params.opt("动作编号"))
        val claim = File(claims, "$aid.json")
        if (claim.exists()) {
            val original = runCatching { JSONObject(claim.readText()) }.getOrElse { JSONObject().put("状态", "未知") }
            val originalState = original.optString("状态").takeIf { it in setOf("未派发", "已派发", "部分派发") } ?: "未知"
            return finishResult(originalState, 0, extra = JSONObject().put("重复请求", true).put("本次未重放", true)
                .put("动作编号", aid).put("原动作", original))
        }
        // Launch targets a known display, not coordinates in an old screenshot.
        val observation = if (operation == "打开应用") null else checkNotNull(latest.get()) { "当前没有可用于输入的观察，请查看" }
        val currentFrames = checkNotNull(frames)
        val before = inspect()
        if (observation != null) {
            ActionRequirements.observation(params.opt("截图编号"), observation.id,
                SystemClock.elapsedRealtime() - observation.captured, currentFrames.generation, observation.frame.generation)
            check(identity(before) == observation.identity) { "前台或显示屏已变化，本次未派发" }
        }
        val w = observation?.frame?.width ?: currentFrames.width; val h = observation?.frame?.height ?: currentFrames.height
        val time = integer(params, "时间", 0, 5000, if (operation == "长按") 700 else 400)
        var paths: List<Pair<Pair<Int, Int>, Pair<Int, Int>>>? = null
        var hold = 0
        when (operation) {
            "点击", "双击", "长按" -> { val p = point(params, "位置", w, h); paths = listOf(p to p) }
            "滑动", "拖拽" -> { paths = listOf(point(params, "起点", w, h) to point(params, "终点", w, h))
                if (operation == "拖拽") hold = integer(params, "按住时间", 0, 5000, 600) }
            "放大", "缩小" -> {
                val center = point(params, "中心", w, h)
                val first = integer(params, "初始指距", 1, min(w, h), 200)
                val last = integer(params, "结束指距", 1, min(w, h), if (operation == "放大") 400 else 100)
                require(if (operation == "放大") last > first else last < first) { "指距方向错误" }
                paths = listOf(-1, 1).map { side ->
                    checkedPoint(center.first + side * first / 2, center.second, w, h) to
                        checkedPoint(center.first + side * last / 2, center.second, w, h)
                }
            }
            "输入内容" -> {
                require(params.opt("内容") is String && params.getString("内容").length in 1..2000) { "内容必须为1～2000字符" }
                checkTextTarget(checkNotNull(observation), before)
            }
            "按键" -> require(params.optString("键名") in KEYS) { "此按键不属于支持的虚拟屏按键" }
            "删除内容" -> { integer(params, "次数", 1, 100, 1); checkTextTarget(checkNotNull(observation), before) }
            "全选" -> checkTextTarget(checkNotNull(observation), before)
            "打开应用" -> {
                require(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*").matches(params.optString("包名"))) { "包名无效" }
                if (params.has("启动组件")) require(params.getString("启动组件").startsWith(params.getString("包名") + "/")) { "启动组件不属于目标包名" }
            }
        }
        checkRunning()
        if (observation != null) check(identity(inspect()) == observation.identity && currentFrames.generation == observation.frame.generation
            && latest.get() === observation) { "派发前屏幕已变化或观察已作废" }
        checkRunning()
        check(claim.createNewFile()) { "重复动作编号" }
        val record = JSONObject().put("动作编号", aid).put("操作", operation).put("状态", "派发中")
            .put("截图编号", observation?.id).put("显示屏编号", displayId).put("time", System.currentTimeMillis())
        persist(claim, record)
        invalidate()
        var execution = "未派发"
        var failure: String? = null
        val extra = JSONObject().put("动作编号", aid)
        try {
            val control = checkNotNull(controls)
            checkRunning()
            execution = "未知"
            lastInputAt = SystemClock.elapsedRealtime()
            if (paths != null) {
                val millis = if (operation in setOf("点击", "双击")) 35 else time
                gesture(paths, w, h, millis, hold)
                if (operation == "双击") {
                    execution = "部分派发"; extra.put("已完成点击次数", 1)
                    waitFor(100, true); gesture(paths, w, h, 35, 0)
                    extra.put("已完成点击次数", 2)
                }
            } else when (operation) {
                "输入内容" -> {
                    execution = "未派发"
                    control.paste(params.getString("内容"), beforeKey = { checkRunning(); execution = "未知" }) {
                        checkTextTarget(checkNotNull(observation))
                    }
                    extra.put("文字输入", JSONObject().put("方式", "scrcpy定向粘贴").put("显示屏编号", displayId)
                        .put("写入结果", "未自动读回，请根据执行后画面确认"))
                }
                "按键" -> control.key(KEYS.getValue(params.getString("键名")))
                "删除内容" -> repeat(params.optInt("次数", 1)) { index ->
                    checkTextTarget(checkNotNull(observation)); control.key(67)
                    execution = "部分派发"; extra.put("已派发删除次数", index + 1)
                }
                "全选" -> control.key(29, 0x1000)
                "打开应用" -> {
                    val launch = JSONObject(rpc("打开应用", 25000, true) { it.launch(params.optString("启动组件", params.getString("包名"))) })
                    execution = launch.optString("状态", "未知")
                    failure = launch.optString("错误").takeIf { it.isNotBlank() }
                    extra.put("启动", launch).put("目标包名", params.getString("包名"))
                    if (launch.optBoolean("调用未确认")) quarantineInput("启动请求超时，原请求是否完成尚未确认")
                    if (execution == "已派发") boundPackage = params.getString("包名")
                }
            }
            if (operation != "打开应用") execution = "已派发"
        } catch (error: Exception) {
            failure = error.message ?: error.javaClass.simpleName
            if (error is java.io.IOException || error is android.system.ErrnoException || error is ControlChannel.Failure) quarantineInput(failure)
        }
        record.put("状态", execution).put("错误", failure)
        // A journaling or post-action screenshot failure cannot erase a known dispatch result.
        runCatching { persist(claim, record) }.onFailure { extra.put("记录错误", it.message) }
        return finishResult(execution, waitMs, failure, extra).also { recycleRetired() }
    }

    private fun channelState(): JSONObject = JSONObject()
        // System-only queries deliberately leave scrcpy unallocated. Absence is
        // not a channel failure; explicit faults still take precedence.
        .put("控制", when {
            inputFailure != null || binding.failure() != null -> "不可用"
            controls == null -> "未创建"
            else -> "可用"
        })
        .put("视频", if (frames == null) "未创建" else "已连接")
        .put("用户停止", stopped.get()).put("人工接管", manual.snapshot().enabled)
        .put("输入故障", inputFailure ?: binding.failure())

    private fun queryResult(details: JSONObject): JSONObject = textResult(details
        .put("执行", JSONObject().put("状态", "无需派发")).put("观察", JSONObject().put("状态", "未请求"))
        .put("通道", channelState()))

    private fun listApplications(params: JSONObject): JSONObject {
        val keyword = params.optString("关键词", "")
        require(keyword.length <= 200) { "关键词不能超过200字符" }
        require(!params.has("包含系统应用") || params.opt("包含系统应用") is Boolean) { "包含系统应用必须是布尔值" }
        val data = JSONObject(rpc("列出应用", 60000) { it.packages() })
        val all = data.getJSONArray("应用")
        val filtered = JSONArray()
        for (index in 0 until all.length()) {
            val entry = all.getJSONObject(index)
            val pkg = entry.getString("包名")
            @Suppress("DEPRECATION")
            runCatching { context.packageManager.getApplicationInfo(pkg, 0).loadLabel(context.packageManager).toString() }
                .onSuccess { entry.put("名称", it) }
            if ((!pkg.contains(keyword, true) && !entry.optString("名称").contains(keyword, true)) ||
                (!params.optBoolean("包含系统应用", true) && entry.optBoolean("系统应用"))) continue
            filtered.put(entry)
        }
        return queryResult(data.put("应用", filtered).put("已安装总数", all.length()).put("返回数量", filtered.length()))
    }

    private fun finishResult(execution: String, waitMs: Int, error: String? = null, extra: JSONObject = JSONObject()): JSONObject {
        val result = try {
            // Reading remains possible after STOP; a new STOP interrupts an existing wait.
            val wasStopped = stopped.get()
            val until = SystemClock.elapsedRealtime() + waitMs
            while (SystemClock.elapsedRealtime() < until && !closed.get() && (wasStopped || !stopped.get())) {
                Thread.sleep(min(20, maxOf(1, until - SystemClock.elapsedRealtime())))
            }
            capture(if (execution == "无需派发") "已观察" else execution, waitMs, diagnostic = error != null)
        } catch (observationError: Exception) {
            textResult(JSONObject().put("状态", execution).put("观察", JSONObject().put("状态", "失败")
                .put("错误", observationError.message ?: "未取得画面")))
        }
        val details = result.getJSONObject("details")
        extra.keys().forEach { details.put(it, extra.get(it)) }
        details.put("执行", JSONObject().put("状态", execution)).put("通道", channelState())
        if (error != null) details.put("错误", error)
        if (error != null || details.optJSONObject("观察")?.optString("状态") == "失败") runCatching {
            val receipt = JSONObject().put("at", System.currentTimeMillis()).put("execution", execution)
                .put("observation", details.optJSONObject("观察")?.optString("状态"))
                .put("actionId", extra.optString("动作编号"))
            DiagnosticStorage.save(File(context.cacheDir, "bbui-diagnostics"), "${UUID.randomUUID()}.json", receipt.toString().toByteArray())
        }
        if (extra.has("目标包名")) {
            val foreground = details.optJSONObject("屏幕")?.optString("package").orEmpty()
            if (foreground.isNotBlank() && details.optJSONObject("观察")?.optBoolean("元数据已确认") == true)
                details.put("目标应用在前台", foreground == extra.getString("目标包名"))
        }
        // Keep the model-visible text and native details identical after adding action facts.
        result.getJSONArray("content").put(0, JSONObject().put("type", "text").put("text", details.toString()))
        return result
    }

    private fun capture(status: String, waitMs: Int, diagnostic: Boolean = false): JSONObject {
        recycleRetired()
        check(!closed.get()) { "设备会话已关闭" }
        val before = runCatching { inspect() }
        val frame = checkNotNull(frames).capture()
        val after = runCatching { inspect() }
        val screen = after.getOrNull() ?: before.getOrNull() ?: JSONObject().put("displayId", displayId)
        val stable = before.isSuccess && after.isSuccess && identity(before.getOrThrow()) == identity(after.getOrThrow())
            && screen.optString("package").isNotBlank() && screen.optInt("userId", -1) >= 0
            && screen.optString("powerState") !in setOf("OFF", "DOZE", "DOZE_SUSPEND", "ON_SUSPEND")
            && frames?.generation == frame.generation
            && (lastFrameContext == identity(screen) || frame.timestamp >= lastInputAt)
        val id = UUID.randomUUID().toString()
        val encoded = ScreenshotEncoder.encode(frame.bitmap)
        // The model image is returned below and persisted by Pi. An extra full-size
        // PNG is disposable evidence, only needed for failures or explicit test diagnostics.
        if (diagnosticScreenshots || diagnostic || !stable) runCatching {
            DiagnosticStorage.save(File(context.cacheDir, "bbui-diagnostics"), "$id.png", encoded.originalPng)
        }
        val detail = JSONObject().put("状态", status).put("屏幕会话", "virtual").put("显示屏编号", displayId)
            .put("环境编号", activeTicket?.tag)
            .put("截图编号", id).put("视频帧编号", frame.sequence).put("generation", frame.generation)
            .put("视频帧接收时间", frame.timestamp).put("视频帧年龄毫秒", SystemClock.elapsedRealtime() - frame.timestamp)
            .put("宽", frame.width).put("高", frame.height).put("执行后等待毫秒", waitMs)
            .put("屏幕", JSONObject(screen.toString()).put("width", frame.width).put("height", frame.height).apply { remove("ime") })
            .put("观察", JSONObject().put("状态", "已取得").put("截图编号", id).put("可用于输入", stable)
                .put("元数据错误", after.exceptionOrNull()?.message ?: before.exceptionOrNull()?.message)
                .put("元数据已确认", after.isSuccess)
                .put("画面上下文一致", stable))
            .put("支持操作", JSONArray(SUPPORTED)).put("支持按键", JSONArray(KEYS.keys.toList()))
        if (screen.optString("ime").isNotBlank() && screen.optInt("userId", -1) >= 0) {
            val diagnostic = JSONObject().put("用于派发校验", false)
                .put("说明", "scrcpy 向目标显示屏发送按键和粘贴；全局输入法匹配不是前提")
            runCatching { InputFocus.assess(screen.getString("ime"), screen.getInt("userId"), displayId, screen.optString("package")) }
                .onSuccess { diagnostic.put("匹配目标", it.ready).put("状态", it.reason).put("类型", it.inputType) }
                .onFailure { diagnostic.put("匹配目标", false).put("状态", it.message) }
            detail.put("全局输入法", diagnostic)
        }
        // scrcpy emits frames on change. A fresh observation of a static screen may
        // legitimately reuse an older frame; expire the observation, not the encoded frame.
        latest.getAndSet(if (stable) Observation(id, SystemClock.elapsedRealtime(), frame, identity(screen)) else null)?.let { retired.add(it) }
        if (stable) lastFrameContext = identity(screen)
        if (!stable) frame.bitmap.recycle()
        recycleRetired()
        persist(File(runs, "latest.json"), detail)
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", detail.toString()))
            .put(JSONObject().put("type", "image").put("mimeType", encoded.mimeType)
                .put("data", Base64.encodeToString(encoded.modelBytes, Base64.NO_WRAP)))
        return JSONObject().put("content", content).put("details", detail)
    }

    private fun textResult(detail: JSONObject): JSONObject = JSONObject().put("details", detail)
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", detail.toString())))
    private fun invalidate() { latest.getAndSet(null)?.let { retired.add(it) } }
    /** Only called while the action lock is held and no older observation is still being used. */
    private fun recycleRetired() { while (true) { val old = retired.poll() ?: break; old.frame.bitmap.recycle() } }

    private fun inspect(timeoutMillis: Long = 12000): JSONObject {
        val result = JSONObject(rpc("读取屏幕状态", timeoutMillis) { it.inspect() })
        check(result.getInt("displayId") == displayId && displayId > 0) { "虚拟屏身份变化" }
        val state = context.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(displayId)?.state
        result.put("powerState", when (state) {
            android.view.Display.STATE_ON -> "ON"; android.view.Display.STATE_OFF -> "OFF"
            android.view.Display.STATE_DOZE -> "DOZE"; android.view.Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
            android.view.Display.STATE_ON_SUSPEND -> "ON_SUSPEND"; else -> "UNKNOWN"
        })
        return result
    }
    private fun identity(value: JSONObject): String = "${activeTicket?.tag}:${value.optInt("displayId")}:${value.optInt("userId", -1)}:${value.optString("package")}:${value.optString("activity")}:${value.optInt("rotation")}:${value.optString("focusedWindow")}:${value.optInt("focusedWindowUser", -1)}:${value.optString("powerState")}"
    private fun checkTextTarget(observation: Observation, initial: JSONObject? = null) {
        checkRunning()
        val state = initial ?: inspect()
        check(identity(state) == observation.identity && frames?.generation == observation.frame.generation) {
            "文字派发前目标窗口或屏幕发生变化，请查看后继续"
        }
        DisplayInputTarget.requireTarget(state.getInt("displayId"), state.optInt("userId", -1),
            state.optString("focusedWindow"), state.optInt("focusedWindowUser", -1))
        checkRunning()
    }
    private fun gesture(paths: List<Pair<Pair<Int, Int>, Pair<Int, Int>>>, w: Int, h: Int, duration: Int, hold: Int) {
        val control = checkNotNull(controls)
        val active = mutableMapOf<Long, Pair<Int, Int>>()
        var originalFailure: Throwable? = null
        try {
            paths.forEachIndexed { index, path -> checkRunning(); active[index.toLong()] = path.first
                control.touch(0, path.first.first, path.first.second, w, h, index.toLong()) }
            waitFor(hold.toLong(), true)
            val steps = maxOf(1, ceil(duration / 33.0).toInt())
            val started = SystemClock.elapsedRealtime()
            for (step in 1..steps) {
                checkRunning()
                paths.forEachIndexed { index, path ->
                    val x = (path.first.first + (path.second.first - path.first.first) * step.toDouble() / steps).roundToInt()
                    val y = (path.first.second + (path.second.second - path.first.second) * step.toDouble() / steps).roundToInt()
                    active[index.toLong()] = x to y
                    if (path.first != path.second) control.touch(2, x, y, w, h, index.toLong())
                }
                waitFor(maxOf(0, started + duration * step / steps - SystemClock.elapsedRealtime()), true)
            }
        } catch (error: Throwable) { originalFailure = error; throw error }
        finally {
            var releaseFailure: Throwable? = null
            active.entries.reversed().forEach { (id, p) ->
                try { control.touch(1, p.first, p.second, w, h, id) }
                catch (error: Throwable) { if (releaseFailure == null) releaseFailure = error }
            }
            releaseFailure?.let {
                quarantineInput(it.message ?: "抬手派发未确认")
                if (originalFailure == null) throw it else originalFailure.addSuppressed(it)
            }
        }
    }
    /** Called on the coordinator worker before opening the AI input gate. STOP stays atomic. */
    fun retireManualInput() = synchronized(lock) {
        check(!manual.snapshot().enabled) { "人工操作尚未结束" }
        if (manualDown) {
            val video = checkNotNull(frames) { "触摸通道已断开" }
            checkNotNull(controls).touch(1, 0, 0, video.width, video.height)
            manualDown = false
        }
    }
    override fun setStopped(stopped: Boolean) { val epoch = systemEpoch.incrementAndGet(); this.stopped.set(stopped); invalidate(); runCatching { if (stopped) service?.setSystemStopped(epoch) else service?.beginSystemActions(epoch) } }
    override fun quarantineInput(reason: String) { inputFailure = reason; invalidate() }
    override fun setManual(enabled: Boolean, epoch: Long) {
        manual.update(enabled, epoch); invalidate()
        if (!enabled) Thread({ synchronized(lock) {
            val state = manual.snapshot()
            if (manualDown && (!state.enabled || manualPointerEpoch != state.epoch)) {
                val video = frames
                if (video != null && video.width > 0) runCatching { controls?.touch(1, 0, 0, video.width, video.height) }
                manualDown = false
            }
            recycleRetired()
        } }, "bbui-release-touch").apply { isDaemon = true; start() }
    }
    override fun attachPreview(surface: Surface?, width: Int, height: Int) {
        previewSurface = surface; previewWidth = width; previewHeight = height
        frames?.attach(surface, width, height)
    }
    override fun touch(action: Int, x: Float, y: Float, epoch: Long) = synchronized(lock) {
        ensureConnected()
        if (!stopped.get() || !manual.accepts(epoch)) return@synchronized
        require(action in setOf(0, 1, 2, 3)) { "不支持的触摸事件" }
        val video = checkNotNull(frames)
        if (previewWidth <= 0 || previewHeight <= 0 || video.width <= 0) return@synchronized
        val scale = min(previewWidth.toFloat() / video.width, previewHeight.toFloat() / video.height)
        val px = ((x - (previewWidth - video.width * scale) / 2) / scale).toInt()
        val py = ((y - (previewHeight - video.height * scale) / 2) / scale).toInt()
        if (action == 0 && (px !in 0 until video.width || py !in 0 until video.height)) return@synchronized
        if (action != 0 && !manualDown) return@synchronized
        val normalized = if (action == 3) 1 else action
        invalidate()
        checkNotNull(controls).touch(normalized, px.coerceIn(0, video.width - 1), py.coerceIn(0, video.height - 1), video.width, video.height)
        manualDown = normalized != 1
        manualPointerEpoch = epoch
    }
    override fun close() {
        connectionEpoch.incrementAndGet()
        stopped.set(true); closed.set(true); binding.invalidate(); invalidate()
        synchronized(lock) { release() }
    }
    private fun release() {
        // Invalidate callback identity before closing sockets or scheduling Shizuku's asynchronous
        // removal. Each new binding uses another tag in both the API cache and Shizuku server.
        binding.invalidate()
        activeTicket = null; inputFailure = null; lastFrameContext = null; lastInputAt = 0L
        stopped.set(true); manual.update(false, manual.snapshot().epoch + 1); invalidate(); recycleRetired(); manualDown = false
        binderDead?.let { Shizuku.removeBinderDeadListener(it) }; binderDead = null
        // Shutdown first: closing only one duplicated descriptor does not interrupt another
        // blocked read or the service's duplicate of the socket.
        runCatching { controls?.close() }; controls = null
        runCatching { videoFd?.let { Os.shutdown(it.fileDescriptor, OsConstants.SHUT_RDWR) } }
        val oldService = service
        runCatching { if (oldService != null) DeadlineCall.run("停止scrcpy", 3000) { oldService.stop() } }
            .onFailure { android.util.Log.w("BBUI", "Service cleanup did not complete", it) }
        runCatching { frames?.close() }; frames = null
        runCatching { videoFd?.close() }; videoFd = null
        service = null; supportInitialized = false
        val options = args; val callback = connection
        if (options != null && callback != null && Shizuku.pingBinder()) {
            Thread({ runCatching { Shizuku.unbindUserService(options, callback, true) } }, "bbui-unbind")
                .apply { isDaemon = true; start() }
        }
        args = null; connection = null; displayId = -1
    }
    private fun ensureConnected() {
        check(!closed.get() && service != null && frames != null && binding.isActive() && binding.failure() == null) { binding.failure() ?: "请先连接手机" }
    }
    private fun <T> rpc(label: String, timeoutMillis: Long = 12000, affectsInput: Boolean = false, block: (IDeviceService) -> T): T {
        val ticket = checkNotNull(activeTicket) { "Shizuku 连接未建立" }
        val epoch = connectionEpoch.get()
        val remote = checkNotNull(service) { "Shizuku 执行服务未建立" }
        check(binding.isCurrent(ticket) && !closed.get()) { "Shizuku 连接已取消" }
        try {
            val result = DeadlineCall.run(label, timeoutMillis) { block(remote) }
            if (!binding.isCurrent(ticket) || closed.get()) {
                if (result is ParcelFileDescriptor) runCatching { result.close() }
                error("$label 返回时连接已取消，禁止重放")
            }
            return result
        }
        catch (error: Throwable) {
            if (error is DeadObjectException || (affectsInput && error is DeadlineCall.Uncertain)) {
                if (binding.fail(ticket, error.message ?: "$label 失败")) {
                    quarantineInput(error.message ?: "$label 失败")
                    if (error is DeadObjectException) onUnavailable(DeviceUnavailable(error.message ?: "$label 失败", epoch, DeviceUnavailable.Source.SYSTEM))
                }
            }
            throw error
        }
    }
    private fun checkRunning() {
        ensureConnected()
        check(!stopped.get() && !manual.snapshot().enabled) { "用户停止或接管已生效" }
        check(inputFailure == null) { inputFailure ?: "控制通道不可用" }
    }
    private fun waitFor(milliseconds: Long, interruptible: Boolean) {
        val until = SystemClock.elapsedRealtime() + milliseconds
        while (SystemClock.elapsedRealtime() < until) {
            if (interruptible) checkRunning()
            Thread.sleep(min(20, maxOf(1, until - SystemClock.elapsedRealtime())))
        }
    }
    private fun integer(p: JSONObject, key: String, min: Int, max: Int, default: Int? = null): Int {
        val raw = if (p.has(key)) p.get(key) else default
        require(raw is Number && raw.toDouble() == raw.toInt().toDouble() && raw.toInt() in min..max) { "$key 必须为 $min～$max 的整数" }
        return raw.toInt()
    }
    private fun point(p: JSONObject, key: String, w: Int, h: Int): Pair<Int, Int> {
        val array = p.optJSONArray(key) ?: error("$key 必须为 [x,y]")
        require(array.length() == 2) { "$key 必须为 [x,y]" }
        val x = array.get(0); val y = array.get(1)
        require(x is Number && y is Number && x.toDouble() == x.toInt().toDouble() && y.toDouble() == y.toInt().toDouble()) { "坐标必须为整数" }
        return checkedPoint(x.toInt(), y.toInt(), w, h)
    }
    private fun checkedPoint(x: Int, y: Int, w: Int, h: Int): Pair<Int, Int> {
        require(x in 0 until w && y in 0 until h) { "坐标超出原始截图" }; return x to y
    }
    private fun persist(file: File, value: JSONObject) {
        FileOutputStream(file).use { stream -> stream.write(value.toString().toByteArray()); stream.fd.sync() }
    }
    companion object {
        private val SUPPORTED = listOf("查看", "等待", "点击", "双击", "长按", "滑动", "拖拽", "放大", "缩小",
            "输入内容", "删除内容", "全选", "按键", "打开应用", "列出应用", "列出屏幕")
        private val KEYS = mapOf("返回" to 4, "主页" to 3, "最近任务" to 187, "回车" to 66, "删除" to 67,
            "向前删除" to 112, "上" to 19, "下" to 20, "左" to 21, "右" to 22)
    }
}
