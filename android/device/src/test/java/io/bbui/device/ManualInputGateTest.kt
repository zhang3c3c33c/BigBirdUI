package io.bbui.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ManualInputGateTest {
    @Test fun stopRejectsQueuedTouchEvenAfterAnotherTakeover() {
        val gate = ManualInputGate()
        gate.update(true, 1)
        assertTrue(gate.accepts(1))
        gate.update(false, 2)
        assertFalse(gate.accepts(1))
        gate.update(true, 3)
        assertFalse(gate.accepts(1))
        assertTrue(gate.accepts(3))
    }

    @Test fun stopDoesNotWaitForActionLock() {
        val gate = ManualInputGate()
        gate.update(true, 1)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val lock = Any()
        val worker = Thread { synchronized(lock) { held.countDown(); release.await(2, TimeUnit.SECONDS) } }
        worker.start()
        assertTrue(held.await(1, TimeUnit.SECONDS))
        Thread { gate.update(false, 2); done.countDown() }.start()
        try { assertTrue(done.await(1, TimeUnit.SECONDS)); assertFalse(gate.accepts(1)) }
        finally { release.countDown(); worker.join(2000) }
    }
}
