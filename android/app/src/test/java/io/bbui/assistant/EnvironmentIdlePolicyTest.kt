package io.bbui.assistant

import org.junit.Assert.*
import org.junit.Test

class EnvironmentIdlePolicyTest {
    private val idle = EnvironmentIdlePolicy.Usage(resourceReady = true)

    @Test fun backgroundIdleReleasesAfterTenMinutesWithoutPollingOrExtendingOnRefresh() {
        val policy = EnvironmentIdlePolicy()
        assertEquals(600000L, policy.remaining(1000, idle))
        assertEquals(500000L, policy.remaining(101000, idle))
        assertEquals(1L, policy.remaining(600999, idle))
        assertEquals(0L, policy.remaining(601000, idle))
    }

    @Test fun everyLiveUseCancelsTheCountdownAndNextIdleGetsTheFullRetention() {
        val busyStates = listOf(idle.copy(foreground = true), idle.copy(previewVisible = true),
            idle.copy(resourceReady = false), idle.copy(executorBusy = true), idle.copy(running = true),
            idle.copy(manual = true), idle.copy(continuation = true), idle.copy(runnableQueue = true),
            idle.copy(waitingForUser = true))
        for (busy in busyStates) {
            val policy = EnvironmentIdlePolicy()
            assertEquals(600000L, policy.remaining(0, idle))
            assertNull(policy.remaining(590000, busy))
            assertNull(policy.remaining(1000000, busy))
            assertEquals(600000L, policy.remaining(1000001, idle))
        }
    }

    @Test fun PausedQueueAloneDoesNotPinTheScreenButRetainedTaskDoes() {
        val policy = EnvironmentIdlePolicy(100)
        assertEquals(100L, policy.remaining(0, idle.copy(runnableQueue = false)))
        assertEquals(0L, policy.remaining(100, idle))
        assertNull(policy.remaining(101, idle.copy(continuation = true)))
        assertEquals(100L, policy.remaining(102, idle))
    }
}
