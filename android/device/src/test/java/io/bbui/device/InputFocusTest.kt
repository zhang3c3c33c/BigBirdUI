package io.bbui.device

import org.junit.Assert.*
import org.junit.Test

class InputFocusTest {
    private fun dump(display: Int = 44, pkg: String = "io.bbui.assistant", type: String = "1", client: String = "a") = """
        Current Input Method Manager state:
          mCurrentImeUserId=0
          mUserDataRepository:
            userId=0
              curClient=ClientState{a mUid=10123 mPid=123 mSelfReportedDisplayId=$display}
              curEditorInfo:
                inputType=0x$type imeOptions=0x6
                packageName=$pkg fieldId=0
              imeBindingState:
                mFocusedWindowClient=ClientState{$client mUid=10123 mPid=123 mSelfReportedDisplayId=$display}
            userId=999
              curClient=ClientState{other mSelfReportedDisplayId=0}
              curEditorInfo:
                inputType=0x81
                packageName=wrong.user
          mStartInputHistory:
            mCurTokenDisplayId=0
            curEditorInfo:
              inputType=0x81
              packageName=history
        IME own state:
          mCurTokenDisplayId=0
          mCurrentEditorInfo:
            inputType=0x81
            packageName=wrong.ime
    """.trimIndent()

    @Test fun onlyCurrentUserLiveStateCanAuthorizeInput() {
        assertTrue(InputFocus.assess(dump(), 0, 44, "io.bbui.assistant").ready)
        assertThrows(IllegalStateException::class.java) { InputFocus.assess(dump(), 999, 44, "io.bbui.assistant") }
    }
    @Test fun wrongDisplayAndPackageRejectButEditorTypeIsAFact() {
        assertThrows(IllegalStateException::class.java) { InputFocus.assess(dump(display = 0), 0, 44, "io.bbui.assistant") }
        assertThrows(IllegalStateException::class.java) { InputFocus.assess(dump(pkg = "other.app"), 0, 44, "io.bbui.assistant") }
        val password = InputFocus.assess(dump(type = "81"), 0, 44, "io.bbui.assistant")
        assertTrue(password.ready)
        assertEquals(0x81, password.inputType)
    }
    @Test fun unboundAndTransitioningEditorArePending() {
        assertFalse(InputFocus.assess(dump(display = -1), 0, 44, "io.bbui.assistant").ready)
        assertFalse(InputFocus.assess(dump(type = "0"), 0, 44, "io.bbui.assistant").ready)
        assertFalse(InputFocus.assess(dump(client = "b"), 0, 44, "io.bbui.assistant").ready)
    }
    @Test fun legacyFieldsInImeDumpCannotSubstituteForLiveState() {
        assertFalse(InputFocus.assess("mCurTokenDisplayId=44\nmCurrentEditorInfo:\n inputType=0x1\n packageName=io.bbui.assistant",
            0, 44, "io.bbui.assistant").ready)
    }
}
