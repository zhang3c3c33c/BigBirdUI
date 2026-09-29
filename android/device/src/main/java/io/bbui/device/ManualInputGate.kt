package io.bbui.device

import java.util.concurrent.atomic.AtomicReference

/** A queued touch belongs to exactly one takeover; STOP cannot authorize a later replay. */
internal class ManualInputGate {
    data class State(val enabled: Boolean, val epoch: Long)
    private val value = AtomicReference(State(false, 0))
    fun update(enabled: Boolean, epoch: Long) { value.set(State(enabled, epoch)) }
    fun snapshot(): State = value.get()
    fun accepts(epoch: Long): Boolean = value.get().let { it.enabled && it.epoch == epoch }
}
