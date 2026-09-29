package io.bbui.runtime

import java.io.IOException
import java.io.Reader

/** Bound a malformed/oversized record before readLine can exhaust the Android heap. */
internal class RpcLineReader(private val reader: Reader, private val maxChars: Int = 4 * 1024 * 1024) {
    private val buffer = CharArray(8192)
    private var offset = 0
    private var count = 0
    private var skipLf = false

    fun readLine(): String? {
        if (count < 0) return null
        val line = StringBuilder()
        while (true) {
            if (offset == count) {
                count = reader.read(buffer); offset = 0
                if (count < 0) return if (line.isEmpty()) null else line.toString()
                if (count == 0) continue
            }
            val char = buffer[offset++]
            if (skipLf) { skipLf = false; if (char == '\n') continue }
            if (char == '\r' || char == '\n') { skipLf = char == '\r'; return line.toString() }
            if (line.length == maxChars) throw IOException("Pi RPC record exceeds the UI transport limit")
            line.append(char)
        }
    }
}
