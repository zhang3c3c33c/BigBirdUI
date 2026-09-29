package io.bbui.device

import org.junit.Assert.*
import org.junit.Test

class ConnectionGenerationTest {
    @Test fun reconnectNeverReusesShizukuCacheKey() {
        val generations = ConnectionGeneration()
        val old = generations.begin()
        generations.invalidate()
        val current = generations.begin()
        assertNotEquals(old.tag, current.tag)
        assertFalse(generations.isCurrent(old))
        assertTrue(generations.isCurrent(current))
    }

    @Test fun lateDisconnectAndVideoErrorsCannotPoisonNewBinding() {
        val generations = ConnectionGeneration()
        val old = generations.begin()
        generations.invalidate()
        val current = generations.begin()
        assertFalse(generations.fail(old, "old binder disconnected"))
        assertNull(generations.failure())
        assertTrue(generations.fail(current, "current connection broke"))
        assertEquals("current connection broke", generations.failure())
    }
}
