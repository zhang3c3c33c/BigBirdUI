package io.bbui.device

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** Loaded by Shizuku in a shell process, never an exported Android Service. */
class DeviceService : IDeviceService.Stub() {
    private var child: java.lang.Process? = null
    private var videoSocket: LocalSocket? = null
    private var controlSocket: LocalSocket? = null
    private var directory: File? = null
    private var lease: IBinder? = null
    private val death = IBinder.DeathRecipient { stop() }
    @Volatile private var displayId = -1
    private val logs = StringBuilder()

    private val systemStopped = SystemExecutionGate()
    private var supportLoader: ClassLoader? = null
    private var clipboardManager: Any? = null
    override fun beginSystemActions(epoch: Long) { systemStopped.update(epoch, false) }
    override fun setSystemStopped(epoch: Long) { systemStopped.update(epoch, true) }
    @Synchronized override fun initializeSupport(server: ParcelFileDescriptor) {
        try {
        if (supportLoader != null) { server.close(); return }
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(server).use { input ->
            val data = input.readBytes(); check(data.size <= 2 * 1024 * 1024); data
        }
        check(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == SERVER_HASH)
        val dex = java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null && entry.name != "classes.dex") entry = zip.nextEntry
            check(entry != null) { "scrcpy DEX 缺失" }; zip.readBytes()
        }
        val loader = dalvik.system.InMemoryDexClassLoader(java.nio.ByteBuffer.wrap(dex), javaClass.classLoader)
        onServiceMain { loader.loadClass("com.genymobile.scrcpy.Workarounds").getMethod("apply").invoke(null) }
        supportLoader = loader
        } catch (error: Throwable) {
            throw IllegalStateException("scrcpy 兼容库初始化失败：${error.message ?: error.javaClass.simpleName}", error)
        }
    }
    private fun <T> onServiceMain(block: () -> T): T {
        val looper = checkNotNull(android.os.Looper.getMainLooper()) { "Shizuku 主线程不可用" }
        if (android.os.Looper.myLooper() == looper) return block()
        val done = java.util.concurrent.CountDownLatch(1)
        val abandoned = java.util.concurrent.atomic.AtomicBoolean(false)
        val result = java.util.concurrent.atomic.AtomicReference<Result<T>>()
        val handler = android.os.Handler(looper)
        val action = Runnable {
            try {
                if (abandoned.get()) return@Runnable
                result.set(runCatching(block))
            } finally { done.countDown() }
        }
        check(handler.post(action)) { "Shizuku 主线程已退出" }
        if (!done.await(10, TimeUnit.SECONDS)) {
            abandoned.set(true); handler.removeCallbacks(action)
            error("scrcpy 兼容调用超时，结果待确认")
        }
        return checkNotNull(result.get()).getOrThrow()
    }

    private fun clipboard(operation: String, text: String): JSONObject = onServiceMain {
        if (operation != "read") check(!systemStopped.get()) { "用户停止已生效" }
        val loader = checkNotNull(supportLoader) { "scrcpy 兼容库未加载" }
        val wrapper = clipboardManager ?: checkNotNull(loader.loadClass("com.genymobile.scrcpy.wrappers.ServiceManager")
            .getMethod("getClipboardManager").invoke(null)) { "此设备不支持剪贴板" }.also { clipboardManager = it }
        val result = JSONObject()
        when (operation) {
            "read" -> {
                val fake = loader.loadClass("com.genymobile.scrcpy.FakeContext").getMethod("get").invoke(null) as android.content.Context
                val clip = (fake.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
                val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                val plain = clip != null && clip.itemCount == 1 && clip.description.mimeTypeCount == 1 &&
                    clip.description.getMimeType(0) == "text/plain" && item?.text != null && item.htmlText == null && item.uri == null && item.intent == null
                result.put("text", item?.text?.toString() ?: "").put("hasClip", clip != null).put("isPlainText", plain)
                    .put("itemCount", clip?.itemCount ?: 0)
            }
            "write", "clear" -> {
                require(text.length <= 32768) { "剪贴板文本超出单次 Binder 传输上限" }
                if (operation == "clear") {
                    val fake = loader.loadClass("com.genymobile.scrcpy.FakeContext").getMethod("get").invoke(null) as android.content.Context
                    (fake.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).clearPrimaryClip()
                } else check(wrapper.javaClass.getMethod("setText", CharSequence::class.java).invoke(wrapper, text) != false) { "剪贴板写入未确认" }
                result.put("updated", true)
            }
        }
        result
    }
    override fun system(request: String): String {
        val body = JSONObject(request)
        val group = body.getString("group"); val operation = body.getString("operation")
        val readonly = operation in setOf("capabilities", "calendars", "list", "details", "launch_entries", "permissions", "read", "stat", "search", "read_text")
        val details = JSONObject().put("观察", JSONObject().put("状态", "未请求"))
        var attempted = false
        try {
            if (!readonly) check(!systemStopped.get()) { "用户停止已生效" }
            val data = SystemTools({ currentUser().toInt() }, { args ->
                if (!readonly) check(!systemStopped.get()) { "用户停止已生效" }; runCommand(args)
            }, { JSONObject(packages()) }, { JSONObject(launch(it)) }, ::clipboard, { if (!readonly) check(!systemStopped.get()) { "用户停止已生效" } }, { attempted = true }, { args, input -> runCommand(args, inputFile = input) },
                validateArchive = { file ->
                    val blocked = body.optJSONArray("blockedPackages") ?: JSONArray()
                    if (blocked.length() > 0) {
                        val loader = checkNotNull(supportLoader)
                        val context = loader.loadClass("com.genymobile.scrcpy.FakeContext").getMethod("get").invoke(null) as android.content.Context
                        val info = checkNotNull(context.packageManager.getPackageArchiveInfo(file.path, 0)) { "无法确认 APK 包名，未安装" }
                        require((0 until blocked.length()).none { blocked.getString(it) == info.packageName }) { "应用已被禁止操作：${info.packageName}" }
                    }
                }, calendar = { op, p, user ->
                    val identity = android.os.Binder.clearCallingIdentity()
                    try {
                        val loader = checkNotNull(supportLoader) { "scrcpy兼容库未加载" }
                        val context = loader.loadClass("com.genymobile.scrcpy.FakeContext").getMethod("get").invoke(null) as android.content.Context
                        val active = { check(!systemStopped.get()) { "用户停止已生效" }; Unit }
                        CalendarProviderBackend.open(context, user, active).use { provider ->
                            CalendarTools(provider, active, { attempted = true }).execute(op, p)
                        }
                    } finally { android.os.Binder.restoreCallingIdentity(identity) }
                }, nonGui = { toolGroup, op, p, user ->
                    val identity = android.os.Binder.clearCallingIdentity()
                    try {
                        val loader = checkNotNull(supportLoader) { "scrcpy兼容库未加载" }
                        val context = loader.loadClass("com.genymobile.scrcpy.FakeContext").getMethod("get").invoke(null) as android.content.Context
                        val active = { check(!systemStopped.get()) { "用户停止已生效" }; Unit }
                        NonGuiTools(context, user, active, { attempted = true }, { args -> runCommand(args) }).execute(toolGroup, op, p)
                    } finally { android.os.Binder.restoreCallingIdentity(identity) }
                })
                .execute(group, operation, body.getJSONObject("params"))
            details.put("data", data).put("执行", JSONObject().put("状态", if (readonly) "无需派发" else if (operation == "launch") data.optString("状态", "未知") else "已派发"))
            if (data.has("错误")) details.put("错误", data.optString("错误"))
            if (group in setOf("contacts", "clock") && data.optBoolean("dispatched")) {
                val receipt = JSONObject().put("operation", operation).put("group", group)
                for (key in listOf("rawContactId", "contactId", "handler", "action", "skipUiRequested", "creationVerified")) if (data.has(key)) receipt.put(key, data.get(key))
                details.put("receipt", receipt)
                details.put("观察", JSONObject().put("状态", when (data.optString("verification")) { "observed" -> "已取得"; "failed" -> "失败"; else -> "未请求" }))
                if (data.optString("verification") == "failed") details.put("错误", "操作已派发，回读失败：${data.optString("verificationError")}")
            }
            if (group == "calendar" && data.optBoolean("dispatched")) {
                details.put("receipt", JSONObject().put("eventId", data.getLong("eventId")).put("operation", operation).put("scope", "series"))
                details.put("观察", JSONObject().put("状态", if (data.optString("verification") == "observed") "已取得" else "失败"))
                if (data.optString("verification") == "failed") details.put("错误", "操作已派发，回读失败：${data.optString("verificationError")}")
            }
        } catch (error: Throwable) {
            val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
            val unsupported = group == "notifications" && operation == "unsnooze" && cause.message?.contains("Permission Denial", true) == true
            if (unsupported) details.put("unsupported", true)
            details.put("错误", if (unsupported) "此设备不支持恢复延后通知：${cause.message}" else cause.message ?: cause.javaClass.simpleName)
                .put("执行", JSONObject().put("状态", if (readonly) "无需派发" else if (attempted && (cause is SystemRejected || cause is CommandRejected)) "已派发" else if (attempted) "未知" else "未派发"))
        }
        return details.toString()
    }

    @Synchronized override fun start(server: ParcelFileDescriptor, lease: IBinder): String {
        check(Process.myUid() == 2000) { "需要 adb 模式的 Shizuku（UID 2000），不使用 root" }
        stop()
        this.lease = lease
        lease.linkToDeath(death, 0)
        try {
            val bytes = ParcelFileDescriptor.AutoCloseInputStream(server).use { input ->
                val data = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(data.size() + count <= 2 * 1024 * 1024) { "scrcpy server 文件过大" }
                    data.write(buffer, 0, count)
                }
                data.toByteArray()
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(hash == SERVER_HASH) { "scrcpy server 校验失败" }
            val scid = SecureRandom().nextInt(Int.MAX_VALUE)
            val dir = File("/data/local/tmp/bbui-apk-${scid.toString(16)}")
            check(dir.mkdir()) { "无法创建本次 scrcpy 运行目录" }
            directory = dir
            val jar = File(dir, "server.jar")
            jar.writeBytes(bytes)
            check(jar.setReadOnly()) { "无法将 scrcpy server 设为只读" }
            synchronized(logs) { logs.setLength(0) }
            val args = listOf("/system/bin/app_process", "/", "com.genymobile.scrcpy.Server", "4.1",
                "scid=%08x".format(scid), "video=true", "audio=false", "control=true", "tunnel_forward=true",
                "new_display=1080x2160/480", "video_codec=h264", "max_fps=15", "video_bit_rate=4000000",
                "send_dummy_byte=true", "send_device_meta=false", "send_frame_meta=true", "send_stream_meta=true",
                "clipboard_autosync=false", "power_on=false", "cleanup=false", "vd_system_decorations=false",
                "vd_destroy_content=true", "display_ime_policy=local", "keep_active=true")
            val builder = ProcessBuilder(args).redirectErrorStream(true)
            builder.environment()["CLASSPATH"] = jar.absolutePath
            val process = builder.start()
            child = process
            Thread({
                try {
                process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                    synchronized(logs) {
                        logs.append(line).append('\n')
                        if (logs.length > 32768) logs.delete(0, logs.length - 32768)
                    }
                    Regex("New display: .*?\\(id=(\\d+)\\)").find(line)?.let {
                        if (child === process) displayId = it.groupValues[1].toInt()
                    }
                } }
                } catch (_: java.io.IOException) {
                    // stop() closes this pipe concurrently. A log reader must never
                    // terminate the privileged service during normal display cleanup.
                    synchronized(logs) { logs.append("scrcpy log stream closed\n") }
                }
            }, "bbui-scrcpy-log").apply { isDaemon = true; start() }
            val name = "scrcpy_%08x".format(scid)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            var first: LocalSocket? = null
            while (System.nanoTime() < deadline && process.isAlive) {
                val socket = LocalSocket()
                try { socket.connect(LocalSocketAddress(name)); first = socket; break }
                catch (_: Exception) { socket.close(); Thread.sleep(60) }
            }
            checkNotNull(first) { "scrcpy 连接失败: ${logText()}" }
            videoSocket = first
            first.soTimeout = 5000
            check(first.inputStream.read() == 0) { "scrcpy 握手错误" }
            first.soTimeout = 0
            controlSocket = LocalSocket().also { it.connect(LocalSocketAddress(name)) }
            while (displayId < 0 && process.isAlive && System.nanoTime() < deadline) Thread.sleep(30)
            check(displayId > 0) { "虚拟屏创建失败: ${logText()}" }
            return JSONObject().put("displayId", displayId).put("uid", Process.myUid()).put("version", "4.1").toString()
        } catch (error: Throwable) { stop(); throw IllegalStateException(error.message, error) }
    }

    @Synchronized override fun video(): ParcelFileDescriptor = ParcelFileDescriptor.dup(checkNotNull(videoSocket).fileDescriptor)
    @Synchronized override fun control(): ParcelFileDescriptor = ParcelFileDescriptor.dup(checkNotNull(controlSocket).fileDescriptor)

    override fun inspect(): String = inspectDisplay().apply {
        runCatching { runCommand(listOf("/system/bin/dumpsys", "window", "displays"), TargetDisplayDump.windows(getInt("displayId"))) }
            .onSuccess { dump ->
                DisplayInputTarget.parse(dump, getInt("displayId"))?.let { window ->
                    put("focusedWindow", window.token).put("focusedWindowUser", window.user).put("focusedWindowName", window.name)
                }
            }.onFailure { put("windowError", it.message) }
        runCatching { runCommand(listOf("/system/bin/dumpsys", "input_method")) }
            .onSuccess { put("ime", it) }.onFailure { put("imeError", it.message) }
    }.toString()

    private fun inspectDisplay(): JSONObject {
        val id = displayId
        check(id > 0 && child?.isAlive == true) { "虚拟屏服务已断开" }
        val activities = runCommand(listOf("/system/bin/dumpsys", "activity", "activities"), TargetDisplayDump.activities(id))
        val block = Regex("(?ms)^Display #$id .*?:\\s*\\n(.*?)(?=^Display #|\\z)").find(activities)?.groupValues?.get(1)
            ?: error("无法确认虚拟屏 $id，禁止回退主屏")
        val activity = Regex("(?:mResumedActivity|topResumedActivity).*?\\bu(\\d+)\\s+([A-Za-z0-9_.]+)/([^ }]+)").find(block)
        val packageName = activity?.groupValues?.get(2) ?: ""
        return JSONObject().put("displayId", id).put("package", packageName)
            .put("activity", activity?.groupValues?.get(3) ?: "")
            .put("userId", activity?.groupValues?.get(1)?.toInt() ?: -1)
            .put("rotation", Regex("mDisplayRotation=ROTATION_(\\d+)").find(block)?.groupValues?.get(1)?.toInt() ?: 0)
    }

    override fun launch(component: String): String {
        var attempted = false
        val result = JSONObject().put("目标包名", component.substringBefore('/'))
        try {
            val user = currentUser()
            var target = component
            if (!target.contains('/')) {
                check(Regex("[A-Za-z0-9_.]+").matches(target)) { "包名格式无效" }
                val resolved = runCommand(listOf("/system/bin/cmd", "package", "resolve-activity", "--brief", "--user", user,
                    "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-p", target))
                target = resolved.lineSequence().map { it.trim() }.filter { it.startsWith("$component/") }.singleOrNull()
                    ?: error("找不到唯一应用启动入口")
            }
            check(Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+").matches(target)) { "应用组件格式无效" }
            check(displayId > 0 && child?.isAlive == true) { "虚拟屏服务未运行" }
            result.put("用户编号", user.toInt()).put("启动组件", target)
            attempted = true
            // Dispatch once. The resumed activity may be a resolver, login, permission or app page.
            // Its meaning belongs to the model; it is never a reason to invalidate the channel.
            val output = runCommand(listOf("/system/bin/am", "start", "-W", "--user", user, "--display", displayId.toString(),
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-f", "0x10000000", "-n", target))
            result.put("状态", if (output.contains("Error:") || output.contains("Exception")) "未派发" else "已派发")
                .put("系统返回", output.take(1500))
        } catch (error: Exception) {
            result.put("状态", if (attempted) "未知" else "未派发").put("错误", error.message)
                .put("调用未确认", attempted && error is CommandTimeout)
        }
        return result.toString()
    }

    override fun packages(): String {
        val user = currentUser()
        val output = runCommand(listOf("/system/bin/cmd", "package", "query-activities", "--brief", "--components",
            "--user", user, "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER"))
        fun installed(systemOnly: Boolean): Set<String> = runCommand(listOf("/system/bin/pm", "list", "packages") +
            (if (systemOnly) listOf("-s") else emptyList()) + listOf("--user", user))
            .lineSequence().map { it.trim() }.filter { it.startsWith("package:") }.map { it.removePrefix("package:") }.toSet()
        val system = installed(true)
        val entries = output.lineSequence().map { it.trim() }
            .filter { Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+").matches(it) }.distinct().groupBy { it.substringBefore('/') }
        val apps = JSONArray()
        installed(false).sorted().forEach { pkg ->
            val components = entries[pkg].orEmpty()
            apps.put(JSONObject().put("包名", pkg).put("用户编号", user.toInt()).put("系统应用", pkg in system)
                .put("可启动", components.isNotEmpty()).put("启动入口", JSONArray(components)))
        }
        return JSONObject().put("应用", apps).put("用户编号", user.toInt())
            .put("范围", "当前 Android 用户；不保证包含其他用户或厂商分身实例").toString()
    }

    @Synchronized override fun stop() {
        lease?.let { runCatching { it.unlinkToDeath(death, 0) } }; lease = null
        runCatching { videoSocket?.shutdownInput() }; runCatching { videoSocket?.shutdownOutput() }
        runCatching { controlSocket?.shutdownInput() }; runCatching { controlSocket?.shutdownOutput() }
        runCatching { videoSocket?.close() }; runCatching { controlSocket?.close() }
        videoSocket = null; controlSocket = null
        child?.let { process ->
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        child = null; displayId = -1
        directory?.let { dir -> File(dir, "server.jar").delete(); dir.delete() }
        directory = null
    }

    override fun destroy() {
        // A vendor framework call must not keep an unbound privileged process alive forever.
        Thread({ Thread.sleep(3000); Process.killProcess(Process.myPid()) }, "bbui-service-exit-watchdog")
            .apply { isDaemon = true; start() }
        try { stop() } finally { kotlin.system.exitProcess(0) }
    }
    private fun logText(): String = synchronized(logs) { logs.toString() }
    private fun currentUser(): String = runCommand(listOf("/system/bin/am", "get-current-user")).trim().also {
        check(Regex("\\d+").matches(it)) { "无法确认当前 Android 用户" }
    }
    private class CommandRejected(message: String) : IllegalStateException(message)
    private class CommandTimeout : IllegalStateException("系统命令超时；请求可能已被系统接收")
    private fun runCommand(args: List<String>, select: (String) -> String? = { it }, inputFile: File? = null): String {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val result = java.util.concurrent.atomic.AtomicReference<String>()
        val failure = java.util.concurrent.atomic.AtomicReference<Exception>()
        val reader = Thread {
            try { result.set(CommandOutput.read(process.inputStream.reader(), select)) }
            catch (error: Exception) { failure.set(error) }
        }.apply { isDaemon = true; start() }
        val inputFailure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val writer = inputFile?.let { file -> Thread({
            try { file.inputStream().use { input -> process.outputStream.use { output ->
                val buffer = ByteArray(65536)
                while (true) { check(!systemStopped.get()) { "用户停止已生效" }; val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count) }
            } } } catch (error: Throwable) { inputFailure.set(error); runCatching { process.outputStream.close() } }
        }, "bbui-install-input").apply { isDaemon = true; start() } }
        try {
            if (!process.waitFor(if (inputFile == null) 15 else 60, TimeUnit.SECONDS)) throw CommandTimeout()
            writer?.join(1000)
            inputFailure.get()?.let { throw IllegalStateException("安装输入未完成", it) }
            reader.join(1000)
            check(!reader.isAlive) { "系统命令输出尚未读取完整" }
            failure.get()?.let { throw it }
            val output = checkNotNull(result.get()) { "系统命令未返回完整输出" }
            if (process.exitValue() != 0) throw CommandRejected("系统命令失败：${output.take(1500)}")
            return output
        } finally {
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.inputStream.close() }
        }
    }

    companion object {
        const val SERVER_HASH = "deacb991ed2509715160ffdc7907e47b4160eb30d1566217e9047fd5b8850cae"
    }
}
