package io.bbui.device

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DeadlineCallTest {
    @Test fun uninterruptibleVendorCallCannotBlockCallerForever() {
        val release = CountDownLatch(1)
        val started = System.nanoTime()
        try {
            assertThrows(IllegalStateException::class.java) {
                DeadlineCall.run("fake vendor", 100) {
                    while (release.count > 0) {
                        try { release.await(50, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
                    }
                }
            }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500)
        } finally { release.countDown() }
    }

    @Test fun originalFailureAndSuccessfulResultArePreserved() {
        assertEquals(42, DeadlineCall.run("value", 1000) { 42 })
        val original = IllegalArgumentException("invalid operation")
        val thrown = assertThrows(IllegalArgumentException::class.java) { DeadlineCall.run("failure", 1000) { throw original } }
        assertSame(original, thrown)
    }
}
