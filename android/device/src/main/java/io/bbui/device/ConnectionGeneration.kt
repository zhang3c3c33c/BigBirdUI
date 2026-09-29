package io.bbui.device

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Identity for one Shizuku binding, including callbacks that arrive after unbind. */
internal class ConnectionGeneration {
    data class Ticket(val tag: String = "bbui-device-${UUID.randomUUID()}")
    private data class State(val ticket: Ticket?, val failure: String? = null)
    private val state = AtomicReference(State(null))
    fun begin(): Ticket = Ticket().also { state.set(State(it)) }
    fun invalidate() { state.set(State(null)) }
    fun isCurrent(ticket: Ticket): Boolean = state.get().ticket == ticket
    fun isActive(): Boolean = state.get().ticket != null
    fun failure(): String? = state.get().failure
    fun fail(ticket: Ticket, message: String): Boolean {
        while (true) {
            val current = state.get()
            if (current.ticket != ticket) return false
            if (state.compareAndSet(current, State(ticket, message))) return true
        }
    }
}
