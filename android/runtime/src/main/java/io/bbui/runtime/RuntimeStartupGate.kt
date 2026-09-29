package io.bbui.runtime

import java.util.concurrent.atomic.AtomicReference

/** Timeout and the reader compete once; a late history reply cannot revive a failed runtime. */
internal class RuntimeStartupGate {
  private enum class State { WAITING, READY, FAILED, CLOSED }
  private val state = AtomicReference(State.WAITING)
  val ready: Boolean get() = state.get() == State.READY
  val active: Boolean get() = state.get().let { it == State.WAITING || it == State.READY }
  fun historyRestored(): Boolean = state.compareAndSet(State.WAITING, State.READY)
  fun timeout(): Boolean = state.compareAndSet(State.WAITING, State.FAILED)
  fun fail(): Boolean {
    while (true) {
      val previous = state.get()
      if (previous == State.FAILED || previous == State.CLOSED) return false
      if (state.compareAndSet(previous, State.FAILED)) return true
    }
  }
  fun close() { state.set(State.CLOSED) }
}
