package io.bbui.device

import java.io.IOException
import java.io.Reader

/** Drain the whole pipe, but retain only the requested projection within a fixed budget. */
internal object CommandOutput {
    fun read(reader: Reader, select: (String) -> String? = { it }, limit: Int = 300000): String {
        val output = StringBuilder()
        var overflow = false
        reader.buffered().useLines { lines -> lines.forEach { line ->
            val selected = select(line)
            if (selected != null) {
                if (selected.length.toLong() + output.length + 1 > limit) overflow = true
                else if (!overflow) output.append(selected).append('\n')
            }
        } }
        if (overflow) throw IOException("系统命令输出超出读取上限，结果不完整")
        return output.toString()
    }
}

/** Select before buffering: a long main-screen task stack must not hide the target display. */
internal object TargetDisplayDump {
    fun activities(display: Int): (String) -> String? {
        var selected = false
        val heading = Regex("^Display #(\\d+)\\b.*")
        val rotation = Regex("mDisplayRotation=ROTATION_\\d+")
        return { line ->
            val header = heading.matchEntire(line)
            if (header != null) {
                selected = header.groupValues[1].toInt() == display
                if (selected) line else null
            } else {
                if (line.isNotBlank() && !line.first().isWhitespace()) selected = false
                if (!selected) null
                else when {
                    line.trimStart().startsWith("mResumedActivity") || line.trimStart().startsWith("topResumedActivity") -> line
                    else -> rotation.find(line)?.value?.let { "  $it" }
                }
            }
        }
    }

    fun windows(display: Int): (String) -> String? {
        var selected = false
        val heading = Regex("^\\s*Display: mDisplayId=(\\d+)\\b.*")
        return { line ->
            val header = heading.matchEntire(line)
            if (header != null) {
                selected = header.groupValues[1].toInt() == display
                if (selected) line else null
            } else if (selected && line.trimStart().startsWith("mCurrentFocus=")) line else null
        }
    }
}
