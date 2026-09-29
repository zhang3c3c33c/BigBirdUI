package io.bbui.device

import org.junit.Assert.*
import org.junit.Test

class SystemExecutionGateTest {
    @Test fun staleBinderMessagesCannotRestoreInputOrStopNewLease() {
        val gate = SystemExecutionGate()
        assertTrue(gate.get())
        gate.update(2, false); gate.update(1, true); assertFalse(gate.get())
        gate.update(3, true); gate.update(2, false); assertTrue(gate.get())
        gate.update(3, false); assertTrue(gate.get())
        gate.update(4, false); assertFalse(gate.get())
    }
}
