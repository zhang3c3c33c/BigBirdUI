package io.bbui.assistant

import io.bbui.device.DeviceUnavailable
import org.junit.Assert.*
import org.junit.Test

class EnvironmentFailurePolicyTest {
    private fun event(epoch: Long = 1, source: DeviceUnavailable.Source = DeviceUnavailable.Source.DISPLAY) =
        DeviceUnavailable("连接关闭", epoch, source)
    private fun accepts(event: DeviceUnavailable, epoch: Long = 1, state: String = "ready", releasing: Boolean = false, closed: Boolean = false) =
        EnvironmentFailurePolicy.accepts(event, epoch, state, releasing, closed)

    @Test fun normalReleaseDiscardsCallbacksDuringAndAfterCleanup() {
        val late = event()
        assertFalse(accepts(late, state = "releasing", releasing = true))
        assertFalse(accepts(late, epoch = 2, state = "absent"))
        assertFalse(accepts(late, state = "absent"))
    }
    @Test fun oldResourceCannotInvalidateReplacementEvenInSameTask() {
        for (state in listOf("creating", "ready")) {
            assertFalse(accepts(event(), epoch = 3, state = state))
            assertFalse(accepts(event(source = DeviceUnavailable.Source.SYSTEM), epoch = 3, state = state))
        }
    }
    @Test fun liveVideoFailureIsReportedWhileCreatingOrReady() {
        assertTrue(accepts(event(), state = "creating"))
        assertTrue(accepts(event()))
        assertFalse(accepts(event(), state = "invalid"))
    }
    @Test fun systemOnlyConnectionFailureIsReportedWithoutDisplay() {
        val system = event(source = DeviceUnavailable.Source.SYSTEM)
        assertTrue(accepts(system, state = "absent"))
        // Internal error cleanup does not advance ownership; user close/rebind does.
        assertFalse(accepts(system, epoch = 2, state = "absent"))
        assertFalse(accepts(system, state = "ready", releasing = true))
    }
    @Test fun closedCoordinatorRejectsEverySource() {
        for (source in DeviceUnavailable.Source.entries) assertFalse(accepts(event(source = source), closed = true))
    }
}
