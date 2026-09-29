package io.bbui.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ActionRequirementsTest {
    @Test fun identifiersAreNeverInventedOrCoerced() {
        assertThrows(IllegalArgumentException::class.java) { ActionRequirements.actionId(null) }
        assertThrows(IllegalArgumentException::class.java) { ActionRequirements.actionId(123) }
        assertThrows(IllegalArgumentException::class.java) { ActionRequirements.actionId("../old") }
        assertEquals("task-1", ActionRequirements.actionId("task-1"))
    }
    @Test fun missingAndCrossObservationReferencesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ActionRequirements.observation(null, "latest", 0, 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { ActionRequirements.observation("old", "latest", 0, 1, 1) }
    }
    @Test fun staleAndPreRotationObservationsAreRejected() {
        assertThrows(IllegalStateException::class.java) { ActionRequirements.observation("latest", "latest", 180001, 1, 1) }
        assertThrows(IllegalStateException::class.java) { ActionRequirements.observation("latest", "latest", 0, 2, 1) }
        ActionRequirements.observation("latest", "latest", 180000, 2, 2)
    }
}
