package io.bbui.assistant

/** Generates each snapshot only after the preceding write's commit/rollback callback has run. */
internal class SessionPersistenceQueue(
    private val snapshot: (String?) -> ByteArray,
    private val write: (ByteArray, (Boolean) -> Unit) -> Unit
) {
    private data class Request(val submissionId: String?, val success: () -> Unit, val failure: () -> Unit, val prepare: () -> Unit)
    private val requests = java.util.ArrayDeque<Request>()
    private var writing = false
    private var closed = false
    fun save(submissionId: String? = null, success: () -> Unit = {}, failure: () -> Unit = {}, prepare: () -> Unit = {}) {
        if (closed) return
        requests.add(Request(submissionId, success, failure, prepare)); drain()
    }
    fun close() { closed = true; requests.clear() }
    private fun drain() {
        if (closed || writing || requests.isEmpty()) return
        val request = requests.removeFirst(); writing = true
        var finished = false
        fun complete(ok: Boolean) {
            if (finished) return
            finished = true
            try { if (!closed) { if (ok) request.success() else request.failure() } }
            finally { writing = false; drain() }
        }
        try { request.prepare(); write(snapshot(request.submissionId), ::complete) }
        catch (_: Exception) { complete(false) }
    }
}
