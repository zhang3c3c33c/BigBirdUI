package io.bbui.assistant

/** Only resource retention: a reply ending is not evidence that the user's task is complete. */
internal class EnvironmentIdlePolicy(private val retentionMs: Long = 10 * 60 * 1000L) {
    data class Usage(
        val foreground: Boolean = false,
        val previewVisible: Boolean = false,
        val resourceReady: Boolean = false,
        val executorBusy: Boolean = false,
        val running: Boolean = false,
        val manual: Boolean = false,
        val continuation: Boolean = false,
        val runnableQueue: Boolean = false,
        val waitingForUser: Boolean = false
    ) {
        val canRelease get() = resourceReady && !foreground && !previewVisible && !executorBusy &&
            !running && !manual && !continuation && !runnableQueue && !waitingForUser
    }
    private var idleSince: Long? = null
    fun remaining(now: Long, usage: Usage): Long? {
        if (!usage.canRelease) { idleSince = null; return null }
        val start = idleSince ?: now.also { idleSince = it }
        return (retentionMs - (now - start).coerceAtLeast(0)).coerceAtLeast(0)
    }
}
