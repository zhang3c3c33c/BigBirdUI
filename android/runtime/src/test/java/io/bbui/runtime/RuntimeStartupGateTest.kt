package io.bbui.runtime

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class RuntimeStartupGateTest {
  @Test fun lateHistoryCannotReviveTimedOutRuntime() {
    val gate = RuntimeStartupGate()
    assertFalse(gate.ready)
    assertTrue(gate.timeout())
    assertFalse(gate.historyRestored())
    assertFalse(gate.ready)
    assertFalse(gate.active)
    assertFalse(gate.timeout())
    assertFalse(gate.fail())
  }

  @Test fun completedHistoryCancelsTimeoutAndCannotBeDeliveredTwice() {
    val gate = RuntimeStartupGate()
    assertTrue(gate.historyRestored())
    assertTrue(gate.ready)
    assertFalse(gate.timeout())
    assertFalse(gate.historyRestored())
    assertTrue(gate.active)
  }

  @Test fun failureAndCloseBothPreventFurtherPromptsAndRecovery() {
    val failed = RuntimeStartupGate()
    assertTrue(failed.historyRestored())
    assertTrue(failed.fail())
    assertFalse(failed.ready)
    assertFalse(failed.active)
    assertFalse(failed.historyRestored())
    val closed = RuntimeStartupGate()
    closed.close()
    assertFalse(closed.historyRestored())
    assertFalse(closed.timeout())
    assertFalse(closed.fail())
    assertFalse(closed.active)
  }

  @Test fun timeoutAndHistoryHaveExactlyOneWinner() {
    repeat(100) {
      val gate = RuntimeStartupGate()
      val start = CountDownLatch(1)
      val winners = AtomicInteger()
      val history = thread { start.await(); if (gate.historyRestored()) winners.incrementAndGet() }
      val timer = thread { start.await(); if (gate.timeout()) winners.incrementAndGet() }
      start.countDown()
      history.join(); timer.join()
      assertEquals(1, winners.get())
      assertEquals(gate.ready, gate.active)
    }
  }
}
