package io.bbui.device

import java.io.IOException
import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test

class CommandOutputTest {
    @Test fun largeMainStackDoesNotHideTargetOrLeakMainIdentity() {
        val dump = "Display #0 (activities from top to bottom):\n" +
            "  irrelevant main task\n".repeat(20000) +
            "  topResumedActivity=ActivityRecord{main u0 main.app/.Main}\n" +
            "Display #99 (activities from top to bottom):\n" +
            "  topResumedActivity=ActivityRecord{target u10 target.app/.Screen}\n" +
            "  longConfig={ mDisplayRotation=ROTATION_1 other}\n" +
            "ActivityTaskSupervisor state:\n  mResumedActivity=ActivityRecord{global u0 main.app/.Main}\n"
        val result = CommandOutput.read(StringReader(dump), TargetDisplayDump.activities(99))
        assertTrue(result.length < 500)
        assertTrue(result.contains("target.app/.Screen"))
        assertTrue(result.contains("mDisplayRotation=ROTATION_1"))
        assertFalse(result.contains("main.app"))
        assertEquals("", CommandOutput.read(StringReader(dump), TargetDisplayDump.activities(98)))
    }
    @Test fun windowFocusSelectionSurvivesLargeOtherDisplayAndKeepsAmbiguity() {
        val target = "  Display: mDisplayId=99 (organized)\n  mCurrentFocus=Window{target u10 target.app/Editor }\n"
        val dump = "  Display: mDisplayId=0 (organized)\n" + "  irrelevant\n".repeat(30000) +
            "  mCurrentFocus=Window{main u0 main.app/Editor }\n" + target
        val result = CommandOutput.read(StringReader(dump), TargetDisplayDump.windows(99))
        assertEquals("target", DisplayInputTarget.parse(result, 99)?.token)
        assertNull(DisplayInputTarget.parse(CommandOutput.read(StringReader(dump + target), TargetDisplayDump.windows(99)), 99))
    }
    @Test fun overflowDrainsInputAndExplicitlyFailsInsteadOfReturningTruncation() {
        var lines = 0
        assertThrows(IOException::class.java) {
            CommandOutput.read(StringReader("1234\n5678\nend\n"), { lines++; it }, limit = 5)
        }
        assertEquals(3, lines)
    }
    @Test fun eofWithoutNewlineAndExactBudgetArePreserved() {
        assertEquals("text\n", CommandOutput.read(StringReader("text"), limit = 5))
        assertEquals("", CommandOutput.read(StringReader("")))
    }
}
