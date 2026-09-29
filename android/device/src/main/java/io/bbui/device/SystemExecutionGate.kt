package io.bbui.device

/** A late one-way STOP or enable from another Binder thread cannot change a newer lease. */
internal class SystemExecutionGate {
    private var epoch = -1L
    @Volatile private var stopped = true
    @Synchronized fun update(generation: Long, value: Boolean) {
        if (generation > epoch) { epoch = generation; stopped = value }
    }
    fun get(): Boolean = stopped
}
