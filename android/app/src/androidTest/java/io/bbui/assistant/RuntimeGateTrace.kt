package io.bbui.assistant

import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.io.Closeable

/** Test-only timing evidence. Never copies event bodies, prompts, errors or credentials. */
internal class RuntimeGateTrace(directory: File, name: String) : Closeable {
    private val output = File(directory.apply { mkdirs() }, name).bufferedWriter(Charsets.UTF_8)
    private var closed = false

    @Synchronized fun record(event: JSONObject) {
        if (closed) return
        val entry = JSONObject().put("type", event.optString("type"))
            .put("callbackReceivedAtMs", System.currentTimeMillis())
            .put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
        for (key in listOf("piEmittedAtMs", "runtimeReceivedAtMs")) {
            if (event.has(key)) entry.put(key, event.optLong(key))
        }
        output.write(entry.toString())
        output.newLine()
        output.flush()
    }

    @Synchronized override fun close() {
        if (!closed) { closed = true; output.close() }
    }
}
