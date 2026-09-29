package io.bbui.device

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal class SystemRejected(message: String) : IllegalStateException(message)
internal object SystemPayloadLimits {
    const val MAX_REQUEST_CHARS = 120000
    fun rejection(request: String): JSONObject? = if (request.length <= MAX_REQUEST_CHARS) null else JSONObject()
        .put("错误", "系统工具请求超过 Android Binder 传输限制（最多 120000 个 UTF-16 单元）；请缩小本次请求")
        .put("执行", JSONObject().put("状态", "未派发")).put("观察", JSONObject().put("状态", "未请求"))
}

/** Structured shell operations: callers cannot supply a command, switch, or another user. */
internal class SystemTools(
    private val currentUser: () -> Int,
    private val command: (List<String>) -> String,
    private val packages: () -> JSONObject,
    private val launch: (String) -> JSONObject,
    private val clipboard: (String, String) -> JSONObject,
    private val checkActive: () -> Unit,
    private val beforeMutation: () -> Unit = {},
    private val commandInput: (List<String>, File) -> String = { _, _ -> error("安装输入通道未实现") },
    private val sharedRoot: (Int) -> File = { File("/storage/emulated/$it") },
    private val validateArchive: (File) -> Unit = {},
    private val calendar: (String, JSONObject, Int) -> JSONObject = { _, _, _ -> error("日程能力未初始化") },
    private val nonGui: (String, String, JSONObject, Int) -> JSONObject = { _, _, _, _ -> error("系统能力未初始化") }
) {
    fun execute(group: String, operation: String, params: JSONObject): JSONObject {
        checkActive()
        val user = currentUser()
        require(!params.has("userId") || params.getInt("userId") == user) { "仅支持当前 Android 用户 $user" }
        val data = when (group) {
            "apps" -> apps(operation, params, user)
            "notifications" -> notifications(operation, params, user)
            "clipboard" -> clipboardOperation(operation, params)
            "files" -> SharedFiles(sharedRoot(user), checkActive, beforeMutation).execute(operation, params)
            "calendar" -> calendar(operation, params, user)
            "contacts", "sms", "call_log", "media", "clock" -> nonGui(group, operation, params, user)
            else -> error("不支持的系统能力：$group")
        }
        return data.put("userId", user)
    }
    private fun clipboardOperation(operation: String, params: JSONObject): JSONObject {
        require(operation in setOf("read", "write", "clear"))
        val text = params.optString("text")
        require(text.length <= 32768) { "单次文本写入最多 32768 个 UTF-16 单元，避免超出 Android Binder 传输限制" }
        if (operation != "read") { beforeMutation(); return clipboard(operation, text) }
        val data = clipboard(operation, "")
        val full = data.getString("text")
        val offset = params.optInt("offset", 0).also { require(it >= 0) }.coerceAtMost(full.length)
        require(offset == full.length || !Character.isLowSurrogate(full[offset])) { "文本 offset 不能位于 Emoji 字符中间" }
        val limit = params.optInt("limit", 10000).coerceIn(2, 16384)
        var end = minOf(full.length, offset + limit)
        if (end > offset && end < full.length && Character.isHighSurrogate(full[end - 1])) end--
        return data.put("text", full.substring(offset, end)).put("totalChars", full.length)
            .put("nextOffset", if (end < full.length) end else JSONObject.NULL).put("offsetUnit", "UTF-16 characters")
    }
    private fun packageName(p: JSONObject): String = p.getString("packageName").also {
        require(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*").matches(it)) { "包名无效" }
    }
    private fun run(vararg args: String): String {
        checkActive()
        return command(args.toList()).also { output ->
            val failure = output.lineSequence().map { it.trimStart() }.firstOrNull { message ->
                message.startsWith("Unknown command", true) || message.startsWith("Error:", true) ||
                    message.startsWith("Error occurred.", true) || message.startsWith("Exception", true)
            }
            if (failure != null) {
                // cmd notification may print an acknowledgement before a caught exception and exit 0.
                val receipt = if (output.length <= 1500) output else output.take(500) + "\n" + failure.take(1000)
                throw SystemRejected(receipt)
            }
        }
    }
    private fun mutate(vararg args: String): String { checkActive(); beforeMutation(); return run(*args) }
    private fun install(args: List<String>, file: File): String { checkActive(); beforeMutation(); return commandInput(args, file) }
    private fun apps(op: String, p: JSONObject, user: Int): JSONObject {
        if (op == "list") {
            val all = packages().getJSONArray("应用")
            val keyword = p.optString("query", p.optString("packageName"))
            val rows = (0 until all.length()).map { all.getJSONObject(it) }.filter { it.optString("包名").contains(keyword, true) }
            return page(rows, p)
        }
        if (op == "install") {
            val paths = p.getJSONArray("paths")
            require(paths.length() in 1..100)
            val files = (0 until paths.length()).map { SharedFiles(sharedRoot(user), checkActive).resolve(paths.getString(it)).also { file -> require(file.isFile && file.extension.equals("apk", true)) { "需要共享存储中的 APK 文件" } } }
            files.forEach(validateArchive)
            if (files.size == 1) return JSONObject().put("output", install(listOf("/system/bin/pm", "install", "-r", "--user", user.toString(), "-S", files.single().length().toString()), files.single()).also { if (it.trim() != "Success") throw SystemRejected(it) })
            val created = mutate("/system/bin/pm", "install-create", "-r", "--user", user.toString(), "-S", files.sumOf { it.length() }.toString())
            val session = Regex("\\[(\\d+)\\]").find(created)?.groupValues?.get(1) ?: error(created)
            var committed = false
            try {
                files.forEachIndexed { index, file -> install(listOf("/system/bin/pm", "install-write", "-S", file.length().toString(), session, "split$index.apk", "-"), file).also { if (!it.startsWith("Success")) throw SystemRejected(it) } }
                val output = mutate("/system/bin/pm", "install-commit", session)
                if (output.trim() != "Success") throw SystemRejected(output); committed = true
                return JSONObject().put("output", output)
            } finally { if (!committed) runCatching { command(listOf("/system/bin/pm", "install-abandon", session)) } }
        }
        val pkg = packageName(p)
        return when (op) {
            "launch_entries" -> {
                val rows = packages().getJSONArray("应用")
                (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.getString("包名") == pkg } ?: error("应用不存在")
            }
            "launch" -> { val component = p.optString("activity"); if (component.isNotBlank()) require(component.startsWith("$pkg/") && Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+").matches(component)); beforeMutation(); launch(component.ifBlank { pkg }) }
            "details", "permissions" -> {
                val output = currentUserPackageDump(run("/system/bin/dumpsys", "package", pkg), user)
                require(!output.contains("Unable to find package")) { "应用不存在" }
                val offset = p.optInt("offset", 0).coerceAtLeast(0).coerceAtMost(output.length)
                val limit = p.optInt("limit", 12000).coerceIn(1, 24000)
                JSONObject().put("packageName", pkg).put("text", output.substring(offset, minOf(output.length, offset + limit)))
                    .put("nextOffset", if (offset + limit < output.length) offset + limit else JSONObject.NULL)
            }
            "force_stop" -> JSONObject().put("output", mutate("/system/bin/am", "force-stop", "--user", user.toString(), pkg))
            "enable", "disable" -> JSONObject().put("output", mutate("/system/bin/pm", if (op == "enable") "enable" else "disable-user", "--user", user.toString(), pkg))
            "clear_data" -> JSONObject().put("output", mutate("/system/bin/pm", "clear", "--user", user.toString(), pkg).also { if (it.trim() != "Success") throw SystemRejected(it) })
            "uninstall" -> JSONObject().put("output", mutate("/system/bin/pm", "uninstall", "--user", user.toString(), pkg).also { if (it.trim() != "Success") throw SystemRejected(it) })
            "grant_permission", "revoke_permission" -> {
                val permission = p.getString("permission").also { require(Regex("[A-Za-z0-9_.]+").matches(it)) }
                JSONObject().put("output", mutate("/system/bin/pm", if (op == "grant_permission") "grant" else "revoke", "--user", user.toString(), pkg, permission))
            }
            else -> error("不支持的应用操作：$op")
        }.put("packageName", pkg)
    }
    private fun currentUserPackageDump(text: String, user: Int): String {
        var excludedIndent: Int? = null
        return text.lineSequence().filter { line ->
            val indent = line.takeWhile { it.isWhitespace() }.length
            val match = Regex("^\\s*User (\\d+):.*").matchEntire(line)
            if (match != null) excludedIndent = if (match.groupValues[1].toInt() != user) indent else null
            else if (line.isNotBlank() && excludedIndent != null && indent <= excludedIndent!!) excludedIndent = null
            excludedIndent == null
        }.joinToString("\n")
    }
    private fun notifications(op: String, p: JSONObject, user: Int): JSONObject {
        if (op == "list") {
            val pkg = p.optString("packageName")
            val keys = run("/system/bin/cmd", "notification", "list").lineSequence().map { it.trim() }.filter {
                val fields = it.split('|'); fields.size >= 3 && fields[0] == user.toString() && (pkg.isBlank() || fields[1] == pkg)
            }.map { JSONObject().put("key", it).put("packageName", it.split('|')[1]) }.toList()
            return page(keys, p)
        }
        val key = p.getString("key")
        require(key.length <= 2000 && key.split('|').firstOrNull() == user.toString()) { "通知不属于当前用户" }
        return when (op) {
            "details" -> {
                val text = run("/system/bin/cmd", "notification", "get", key).also { check(!it.contains("No notification matching")) { it } }
                val offset = p.optInt("offset", 0).coerceIn(0, text.length)
                val limit = p.optInt("limit", 12000).coerceIn(1, 24000)
                JSONObject().put("text", text.substring(offset, minOf(text.length, offset + limit))).put("nextOffset", if (offset + limit < text.length) offset + limit else JSONObject.NULL)
            }
            "snooze" -> { val ms = p.getLong("durationMs"); require(ms > 0); JSONObject().put("output", mutate("/system/bin/cmd", "notification", "snooze", "--for", ms.toString(), key)) }
            "unsnooze" -> JSONObject().put("output", mutate("/system/bin/cmd", "notification", "unsnooze", key))
            else -> error("不支持的通知操作：$op")
        }.put("key", key)
    }
    private fun page(rows: List<JSONObject>, p: JSONObject): JSONObject {
        val offset = p.optInt("offset", 0).coerceAtLeast(0)
        val limit = p.optInt("limit", 50).coerceIn(1, 200)
        return JSONObject().put("items", JSONArray(rows.drop(offset).take(limit))).put("total", rows.size)
            .put("nextOffset", if (offset.toLong() + limit < rows.size) offset + limit else JSONObject.NULL)
    }
}

/** Canonical paths are checked again per entry, including recursive operations. */
internal class SharedFiles(root: File, private val checkActive: () -> Unit = {}, private val beforeMutation: () -> Unit = {}) {
    private val root = root.canonicalFile
    fun resolve(path: String): File {
        require(path.isNotBlank() && !path.contains('\u0000')) { "文件路径无效" }
        val normalized = if (path == "/sdcard" || path.startsWith("/sdcard/")) root.path + path.removePrefix("/sdcard") else path
        val input = File(normalized).absoluteFile.normalize()
        var ancestor: File? = input
        while (ancestor != null && ancestor != root && ancestor.path.startsWith(root.path + File.separator)) {
            require(!java.nio.file.Files.isSymbolicLink(ancestor.toPath())) { "不支持共享目录中的符号链接" }
            ancestor = ancestor.parentFile
        }
        val candidate = input.canonicalFile
        require(candidate == root || candidate.path.startsWith(root.path + File.separator)) { "路径必须位于当前用户共享存储" }
        return candidate
    }
    fun execute(op: String, p: JSONObject): JSONObject {
        val file = resolve(p.getString("path")); checkActive()
        fun writableTarget(target: File) { require(target != root) { "操作需要具体文件或子目录" }; checkActive() }
        fun stat(f: File) = JSONObject().put("path", f.path).put("name", f.name).put("directory", f.isDirectory).put("size", f.length()).put("modified", f.lastModified()).put("exists", f.exists())
        val limit = p.optInt("limit", if (op == "read_text") 10000 else 50).coerceIn(if (op == "read_text") 2 else 1, if (op == "read_text") 32768 else 200)
        val offset = p.optInt("offset", 0).also { require(it >= 0) }
        when (op) {
            "stat" -> return stat(file)
            "list", "search" -> {
                require(file.isDirectory) { "目录不存在" }
                val entries = if (op == "search") file.walkTopDown().onEnter { checkActive(); resolve(it.path); true }.filter { it != file && it.name.contains(p.getString("query"), true) }
                    else (file.listFiles() ?: error("无法读取目录")).sortedBy { it.name }.asSequence()
                val values = entries.map { checkActive(); stat(resolve(it.path)) }.drop(offset).take(limit + 1).toList()
                return JSONObject().put("items", JSONArray(values.take(limit))).put("nextOffset", if (values.size > limit) offset + limit else JSONObject.NULL)
            }
            "read_text" -> {
                require(file.isFile) { "文件不存在" }
                java.io.InputStreamReader(file.inputStream(), Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)).use { reader ->
                    var skipped = 0L
                    while (skipped < offset) { checkActive(); val n = reader.skip(minOf(8192L, offset - skipped)); if (n == 0L) break; skipped += n }
                    val chars = CharArray(limit + 1); var size = 0
                    while (size < chars.size) { checkActive(); val n = reader.read(chars, size, chars.size - size); if (n < 0) break; size += n }
                    require(size == 0 || !Character.isLowSurrogate(chars[0])) { "文本 offset 不能位于 Emoji 字符中间" }
                    var count = minOf(size, limit)
                    if (count > 0 && Character.isHighSurrogate(chars[count - 1])) count--
                    val text = String(chars, 0, count); require(!text.contains('\u0000')) { "二进制文件不支持文本读取" }
                    return JSONObject().put("text", text).put("nextOffset", if (size > count) offset + count else JSONObject.NULL).put("offsetUnit", "UTF-16 characters")
                }
            }
            "write_text" -> { writableTarget(file); require(!file.exists() || p.optBoolean("overwrite")) { "目标已存在，需要 overwrite=true" }; val text = p.getString("text"); require(text.length <= 32768) { "单次文本写入最多 32768 个 UTF-16 单元，避免超出 Android Binder 传输限制" }; require(file.parentFile?.isDirectory == true); beforeMutation(); file.writeText(text, Charsets.UTF_8) }
            "mkdir" -> { writableTarget(file); beforeMutation(); check(if (p.optBoolean("recursive")) file.mkdirs() || file.isDirectory else file.mkdir() || file.isDirectory) { "创建目录失败" } }
            "copy", "move", "rename" -> {
                require(file.exists()); val destination = resolve(p.getString("destination")); writableTarget(destination)
                require(destination != file && !destination.path.startsWith(file.path + File.separator) && !(file.isDirectory && file.path.startsWith(destination.path + File.separator))) { "目标不能位于源目录内" }
                require(!destination.exists() || p.optBoolean("overwrite")) { "目标已存在，需要 overwrite=true" }
                if (op == "copy") {
                    require(!file.isDirectory || p.optBoolean("recursive")) { "复制目录需要 recursive=true" }
                    copy(file, destination, p.optBoolean("overwrite"))
                } else {
                    writableTarget(file)
                    val options = if (p.optBoolean("overwrite")) arrayOf(java.nio.file.StandardCopyOption.REPLACE_EXISTING) else emptyArray()
                    beforeMutation(); java.nio.file.Files.move(file.toPath(), destination.toPath(), *options)
                }
                return stat(destination).put("source", file.path)
            }
            "delete" -> { writableTarget(file); remove(file, p.optBoolean("recursive")) }
            else -> error("不支持的文件操作：$op")
        }
        return stat(file)
    }
    private fun copy(source: File, target: File, overwrite: Boolean) {
        checkActive(); resolve(source.path); resolve(target.path)
        if (source.isDirectory) {
            require(!target.exists() || target.isDirectory); beforeMutation(); check(target.isDirectory || target.mkdir())
            (source.listFiles() ?: error("无法读取目录")).forEach { copy(it, File(target, it.name), overwrite) }
        } else {
            require(!target.exists() || overwrite); require(!target.isDirectory)
            source.inputStream().use { input -> beforeMutation(); target.outputStream().use { output ->
                val buffer = ByteArray(65536)
                while (true) { checkActive(); val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n) }
            } }
        }
    }
    private fun remove(file: File, recursive: Boolean) {
        checkActive(); resolve(file.path)
        if (!file.exists()) return
        if (file.isDirectory) { val children = file.listFiles() ?: error("无法读取目录"); require(children.isEmpty() || recursive) { "非空目录需要 recursive=true" }; children.forEach { remove(it, true) } }
        beforeMutation(); check(file.delete()) { "删除失败：${file.path}" }
    }
}
