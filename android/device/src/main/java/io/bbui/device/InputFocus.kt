package io.bbui.device

/** Diagnostic only: global IME matching is not a prerequisite for display-targeted scrcpy input. */
internal object InputFocus {
    data class Result(val ready: Boolean, val reason: String, val diagnostic: String, val inputType: Int? = null)
    class Rejected(message: String, val diagnostic: String) : IllegalStateException(message)

    fun assess(dump: String, expectedUser: Int, expectedDisplay: Int, expectedPackage: String): Result {
        val lines = dump.lines()
        val imeUser = Regex("^\\s*mCurrentImeUserId=(\\d+)\\s*$", RegexOption.MULTILINE)
            .find(dump)?.groupValues?.get(1)?.toInt()
        val repository = block(lines, "mUserDataRepository:")
        val user = if (imeUser != null) block(repository, "userId=$imeUser") else emptyList()
        val diagnostic = buildString {
            append("mCurrentImeUserId=").append(imeUser).append('\n')
            user.filter { line ->
                val key = line.trimStart()
                key.startsWith("curClient=") || key.startsWith("mFocusedWindowClient=") ||
                    key.startsWith("curEditorInfo:") || key.startsWith("inputType=") || key.startsWith("packageName=")
            }.forEach {
                val line = it.trim()
                append(if (line.startsWith("inputType=") || line.startsWith("packageName=")) line.substringBefore(' ') else line)
                append('\n')
            }
        }
        fun pending(reason: String) = Result(false, reason, diagnostic)
        fun requireSafe(condition: Boolean, reason: String) { if (!condition) throw Rejected(reason, diagnostic) }
        if (imeUser == null) return pending("当前用户的输入法状态尚不可用")
        requireSafe(imeUser == expectedUser, "全局输入法属于另一用户")
        if (repository.isEmpty() || user.isEmpty()) return pending("当前用户的输入法状态尚不可用")
        fun client(key: String): Pair<String, Int>? {
            val line = user.singleOrNull { it.trimStart().startsWith("$key=") } ?: return null
            val identity = Regex("ClientState\\{(\\S+)").find(line)?.groupValues?.get(1) ?: return null
            val display = Regex("mSelfReportedDisplayId=(-?\\d+)").find(line)?.groupValues?.get(1)?.toInt() ?: return null
            return identity to display
        }
        val current = client("curClient")
        val focused = client("mFocusedWindowClient")
        for (candidate in listOfNotNull(current, focused)) {
            requireSafe(candidate.second < 0 || candidate.second == expectedDisplay, "全局输入法焦点位于另一显示屏 ${candidate.second}")
        }
        if (current == null || focused == null || current.second < 0 || focused.second < 0) return pending("输入法客户端尚未绑定")
        if (current.first != focused.first) return pending("输入法当前客户端和焦点客户端尚未一致")
        val editor = block(user, "curEditorInfo:").joinToString("\n")
        val packageName = Regex("(?:^|\\s)packageName=([^\\s]+)").find(editor)?.groupValues?.get(1)
        if (packageName == null) return pending("当前 EditorInfo 尚未建立")
        requireSafe(packageName == expectedPackage, "全局输入法编辑器属于另一应用")
        val inputType = Regex("(?:^|\\s)inputType=0x([0-9a-fA-F]+)").find(editor)?.groupValues?.get(1)?.toInt(16)
            ?: return pending("输入框类型尚不可用")
        if (inputType == 0) return pending("当前没有可输入的编辑器")
        return Result(true, "ready", diagnostic, inputType)
    }

    private fun block(lines: List<String>, marker: String): List<String> {
        val index = lines.indexOfFirst { it.trim() == marker }
        if (index < 0) return emptyList()
        val indentation = lines[index].indexOfFirst { !it.isWhitespace() }
        return lines.drop(index + 1).takeWhile { it.isBlank() || it.indexOfFirst { c -> !c.isWhitespace() } > indentation }
    }
}
