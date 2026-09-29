package io.bbui.device

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NonGuiValuesTest {
    @Test fun acknowledgedMutationSurvivesMissingIdOrStoppedReadbackWithoutRetry() {
        var calls = 0
        val missingId = NonGuiValues.afterDispatch("local_unsynced") { calls++; error("Provider did not return ID") }
        assertTrue(missingId.getBoolean("dispatched")); assertEquals("failed", missingId.getString("verification"))
        assertFalse(missingId.has("rawContactId")); assertEquals(1, calls)
        val stopped = NonGuiValues.afterDispatch("existing_raw_contact") { it.put("rawContactId", 73); error("STOP during readback") }
        assertTrue(stopped.getBoolean("dispatched")); assertEquals(73, stopped.getInt("rawContactId"))
        assertEquals("failed", stopped.getString("verification"))
    }
    @Test fun literalSearchEscapesWildcardsWithoutInterpolatingSql() {
        assertEquals("%O'Reilly\\%\\_\\\\%", NonGuiValues.literal("O'Reilly%_\\"))
    }
    @Test fun contactsArraysDistinguishUnspecifiedAndExplicitClearAndRejectOversizeBeforeDispatch() {
        assertNull(NonGuiValues.strings(JSONObject(), "phones"))
        assertEquals(emptyList<String>(), NonGuiValues.strings(JSONObject().put("phones", JSONArray()), "phones"))
        val p = JSONObject().put("phones", JSONArray().put("+65 123\n中文😀"))
        assertEquals(listOf("+65 123\n中文😀"), NonGuiValues.strings(p, "phones"))
        assertThrows(IllegalArgumentException::class.java) { NonGuiValues.strings(JSONObject().put("phones", JSONArray((1..21).map { "123" })), "phones") }
        assertThrows(IllegalArgumentException::class.java) { NonGuiValues.strings(JSONObject().put("phones", JSONArray().put("a".repeat(321))), "phones") }
        assertEquals("😀".repeat(320), NonGuiValues.strings(JSONObject().put("phones", JSONArray().put("😀".repeat(320))), "phones")!!.single())
        assertThrows(IllegalArgumentException::class.java) { NonGuiValues.text(JSONObject().put("label", "a\u0000b"), "label", 2000) }
    }
    @Test fun smsPaginationPreservesSurrogatesAndExplicitContinuation() {
        val first = NonGuiValues.slice("a😀b", JSONObject().put("textLimit", 2))
        assertEquals("a", first.getString("body")); assertEquals(1, first.getInt("textNextOffset"))
        val second = NonGuiValues.slice("a😀b", JSONObject().put("textOffset", 1).put("textLimit", 2))
        assertEquals("😀", second.getString("body")); assertEquals(3, second.getInt("textNextOffset"))
        assertThrows(IllegalArgumentException::class.java) { NonGuiValues.slice("a😀b", JSONObject().put("textOffset", 2)) }
        assertThrows(IllegalArgumentException::class.java) { NonGuiValues.slice("😀", JSONObject().put("textLimit", 1)) }
        assertTrue(NonGuiValues.slice("abc", JSONObject().put("textOffset", 3)).isNull("textNextOffset"))
    }
    @Test fun clockUsesStandardTypedExtrasAndNeverTreatsLabelAsCommand() {
        val label = "a; am start bad $(id) 中文"
        val args = NonGuiValues.clockArgs("create_alarm", JSONObject().put("hour", 8).put("minute", 5).put("label", label)
            .put("days", JSONArray().put(2).put(4)).put("vibrate", false))
        assertEquals("android.intent.action.SET_ALARM", NonGuiValues.clockAction("create_alarm"))
        assertTrue(args.contains(label)); assertTrue(args.contains("--eial")); assertTrue(args.contains("2,4"))
        assertEquals(listOf("--ez", "android.intent.extra.alarm.SKIP_UI", "true"), args.take(3))
        val timer = NonGuiValues.clockArgs("create_timer", JSONObject().put("seconds", 90))
        assertEquals("90", timer.last())
    }
    @Test fun invalidClockValuesNeverCreateDispatchArguments() {
        for (p in listOf(JSONObject().put("hour", 24).put("minute", 0), JSONObject().put("hour", 8).put("minute", 60),
            JSONObject().put("hour", 8).put("minute", 0).put("days", JSONArray().put(0)))) {
            assertThrows(IllegalArgumentException::class.java) { NonGuiValues.clockArgs("create_alarm", p) }
        }
        for (seconds in listOf(0, 86401)) assertThrows(IllegalArgumentException::class.java) { NonGuiValues.clockArgs("create_timer", JSONObject().put("seconds", seconds)) }
    }
    @Test fun routingRetainsCurrentUserAndStopChecksForNewGroups() {
        var calls = 0
        val tools = SystemTools({ 10 }, { "" }, { JSONObject() }, { JSONObject() }, { _, _ -> JSONObject() }, {}, nonGui = { group, op, _, user ->
            calls++; JSONObject().put("group", group).put("operation", op).put("seenUser", user)
        })
        assertEquals(10, tools.execute("contacts", "list", JSONObject()).getInt("seenUser"))
        assertThrows(IllegalArgumentException::class.java) { tools.execute("contacts", "list", JSONObject().put("userId", 0)) }
        assertEquals(1, calls)
        val stopped = SystemTools({ 10 }, { "" }, { JSONObject() }, { JSONObject() }, { _, _ -> JSONObject() }, { error("STOP") }, nonGui = { _, _, _, _ -> calls++; JSONObject() })
        assertThrows(IllegalStateException::class.java) { stopped.execute("clock", "create_timer", JSONObject().put("seconds", 1)) }
        assertEquals(1, calls)
    }
}
