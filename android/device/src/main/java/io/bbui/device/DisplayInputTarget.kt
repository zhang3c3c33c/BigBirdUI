package io.bbui.device

/** The target display's own focus, independent of the top-focused display and global IME. */
internal object DisplayInputTarget {
    data class Window(val token: String, val user: Int, val name: String)

    fun parse(dump: String, display: Int): Window? {
        val headers = Regex("(?m)^\\s*Display: mDisplayId=(\\d+)\\b[^\\r\\n]*").findAll(dump).toList()
        val index = headers.indices.singleOrNull { headers[it].groupValues[1].toInt() == display } ?: return null
        val section = dump.substring(headers[index].range.last + 1,
            headers.getOrNull(index + 1)?.range?.first ?: dump.length)
        val lines = section.lineSequence().map { it.trim() }.filter { it.startsWith("mCurrentFocus=") }.toList()
        val focus = lines.singleOrNull() ?: return null
        val match = Regex("^mCurrentFocus=Window\\{(\\S+) u(\\d+) (.+?)\\s*\\}$").matchEntire(focus) ?: return null
        return Window(match.groupValues[1], match.groupValues[2].toInt(), match.groupValues[3])
    }

    fun requireTarget(display: Int, user: Int, windowToken: String, windowUser: Int) {
        check(display > 0) { "文字操作必须指定虚拟屏" }
        check(user >= 0) { "无法确认目标应用用户" }
        check(windowToken.isNotBlank() && windowUser >= 0) { "无法确认虚拟屏焦点窗口，请查看后继续" }
        check(user == windowUser) { "虚拟屏焦点窗口用户与观察不一致，本次未派发" }
    }
}
