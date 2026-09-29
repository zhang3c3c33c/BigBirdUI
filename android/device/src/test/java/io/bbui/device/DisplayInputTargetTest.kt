package io.bbui.device

import org.junit.Assert.*
import org.junit.Test

class DisplayInputTargetTest {
    private val dump = """
        WINDOW MANAGER DISPLAY CONTENTS
          Display: mDisplayId=71 (organized)
            init=1080x1920 480dpi
          mCurrentFocus=Window{virtual u0 test.app/test.app.Editor type=1 }
          mFocusedApp=ActivityRecord{editor u0 test.app/.Editor}
          Display: mDisplayId=0 (organized)
          mCurrentFocus=Window{main u0 other.app/other.app.Editor type=1 }
          mTopFocusedDisplayId=0
    """.trimIndent()

    @Test fun mainFocusDoesNotReplaceVirtualWindow() {
        val window = checkNotNull(DisplayInputTarget.parse(dump, 71))
        assertEquals("virtual", window.token)
        assertEquals(0, window.user)
        DisplayInputTarget.requireTarget(71, 0, window.token, window.user)
        assertEquals(window, DisplayInputTarget.parse(dump.replace("Window{main", "Window{changed"), 71))
    }

    @Test fun missingNullAndAmbiguousFocusNeverFallBackToMain() {
        assertNull(DisplayInputTarget.parse(dump, 72))
        assertNull(DisplayInputTarget.parse("mCurrentFocus=Window{global u0 app/Editor}", 71))
        assertNull(DisplayInputTarget.parse(dump.replace("Window{virtual u0 test.app/test.app.Editor type=1 }", "null"), 71))
        assertNull(DisplayInputTarget.parse(dump + "\nDisplay: mDisplayId=71\nmCurrentFocus=Window{second u0 app/Editor}", 71))
    }

    @Test fun focusCanBeDialogOrIntermediateWindowWithoutAppPolicy() {
        val window = checkNotNull(DisplayInputTarget.parse(dump.replace("test.app/test.app.Editor type=1", "PopupWindow:98 type=1000"), 71))
        assertEquals("PopupWindow:98 type=1000", window.name)
        DisplayInputTarget.requireTarget(71, 0, window.token, window.user)
    }

    @Test fun unknownAndCrossUserTargetsRemainRejected() {
        assertThrows(IllegalStateException::class.java) { DisplayInputTarget.requireTarget(0, 0, "main", 0) }
        assertThrows(IllegalStateException::class.java) { DisplayInputTarget.requireTarget(71, -1, "virtual", 0) }
        assertThrows(IllegalStateException::class.java) { DisplayInputTarget.requireTarget(71, 0, "", -1) }
        assertThrows(IllegalStateException::class.java) { DisplayInputTarget.requireTarget(71, 0, "virtual", 999) }
    }
}
