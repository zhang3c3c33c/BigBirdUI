package io.bbui.assistant

import android.app.Activity
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.bbui.device.ShizukuPhoneDevice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real provider smoke checks; never enumerate personal messages or call history. */
@RunWith(AndroidJUnit4::class)
class NonGuiIntegrationTest {
    @Test(timeout = 180000) fun isolatedContactAndReadOnlyProvidersWithoutVirtualDisplay() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val stateFile = File(context.noBackupFilesDir, "conversations.json")
        if (stateFile.exists()) {
            val state = JSONObject(stateFile.readText())
            assertNull("Never interrupt a user task", state.optJSONObject("running"))
            assertEquals(0, state.optJSONArray("queue")?.length() ?: 0)
            assertNotEquals("pending", state.optJSONObject("pendingQuestion")?.optString("status"))
        }
        val host = instrumentation.startActivitySync(Intent(context, TestTargetActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as Activity
        instrumentation.runOnMainSync { host.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val device = ShizukuPhoneDevice(context)
        val marker = "BBUI临时联系人验收-${UUID.randomUUID()}"
        val phone = "+999" + UUID.randomUUID().toString().filter(Char::isDigit).take(12)
        val email = "bbui-${UUID.randomUUID()}@example.invalid"
        var rawId = -1L
        var contactId = -1L
        fun call(group: String, op: String, p: JSONObject = JSONObject(), action: String = UUID.randomUUID().toString()): JSONObject {
            val result = device.systemAction(group, op, p, action)
            val details = result.getJSONObject("details")
            if (group == "contacts" && op == "create" && rawId <= 0) {
                rawId = details.optJSONObject("receipt")?.optLong("rawContactId", -1) ?: -1
                if (rawId <= 0) rawId = details.optJSONObject("data")?.optLong("rawContactId", -1) ?: -1
            }
            assertFalse("$group.$op: ${details.optString("错误")}", result.optBoolean("isError"))
            return details
        }
        fun readContact() = call("contacts", "details", JSONObject().put("contactId", contactId)).getJSONObject("data")
        try {
            device.bindSystem(); device.setStopped(false)
            val create = JSONObject().put("name", marker).put("phones", JSONArray().put(phone))
                .put("emails", JSONArray().put(email))
            val actionId = UUID.randomUUID().toString()
            val created = call("contacts", "create", create, actionId).getJSONObject("data")
            assertTrue(rawId > 0)
            contactId = created.getLong("contactId")
            val detail = readContact()
            assertEquals(marker, detail.getJSONObject("contact").getString("name"))
            assertTrue(detail.toString().contains(email))
            assertTrue(detail.toString().contains(phone))
            val duplicate = call("contacts", "create", create, actionId)
            assertTrue(duplicate.getBoolean("本次未重放"))
            assertEquals(rawId, duplicate.getJSONObject("receipt").getLong("rawContactId"))
            val listed = call("contacts", "list", JSONObject().put("query", marker)).getJSONObject("data").getJSONArray("items")
            assertEquals(1, listed.length())
            call("contacts", "update", JSONObject().put("rawContactId", rawId).put("contactId", contactId)
                .put("name", "$marker，修改😀").put("phones", JSONArray()))
            val updated = readContact()
            assertEquals("$marker，修改😀", updated.getJSONObject("contact").getString("name"))
            assertFalse(updated.toString().contains(phone))
            assertTrue("Omitted emails must remain", updated.toString().contains(email))
            device.setStopped(true)
            assertThrows(IllegalStateException::class.java) {
                device.systemAction("contacts", "update", JSONObject().put("rawContactId", rawId).put("name", "Must not execute"), UUID.randomUUID().toString())
            }
            device.setStopped(false)
            assertEquals("$marker，修改😀", readContact().getJSONObject("contact").getString("name"))
            // Exact synthetic filters prove provider access without collecting personal history.
            assertEquals(0, call("sms", "list", JSONObject().put("address", marker)).getJSONObject("data").getJSONArray("items").length())
            assertEquals(0, call("call_log", "list", JSONObject().put("number", marker)).getJSONObject("data").getJSONArray("items").length())
            assertEquals(0, call("media", "list", JSONObject().put("kind", "image").put("query", marker)).getJSONObject("data").getJSONArray("items").length())
            val fixtureName = InstrumentationRegistry.getArguments().getString("mediaFixture")
            if (fixtureName != null) {
                val rows = call("media", "list", JSONObject().put("kind", "image").put("query", fixtureName)).getJSONObject("data").getJSONArray("items")
                assertEquals("Expected exactly the host-created media fixture", 1, rows.length())
                val mediaId = rows.getJSONObject(0).getLong("mediaId")
                val media = call("media", "details", JSONObject().put("kind", "image").put("mediaId", mediaId)).getJSONObject("data").getJSONObject("media")
                assertEquals(fixtureName, media.getString("name"))
                assertEquals(4, media.getInt("width")); assertEquals(3, media.getInt("height"))
            }
            val clock = call("clock", "capabilities").getJSONObject("data")
            File(context.cacheDir, "nongui-clock-capabilities.json").writeText(clock.toString())
            assertEquals(0, device.action("列出屏幕", JSONObject()).getJSONObject("details").getJSONArray("屏幕").length())
        } finally {
            try {
                if (rawId > 0) {
                    device.setStopped(false)
                    if (contactId <= 0) {
                        val rows = call("contacts", "list", JSONObject().put("query", marker)).getJSONObject("data").getJSONArray("items")
                        assertEquals("Cannot identify test contact for cleanup", 1, rows.length())
                        contactId = rows.getJSONObject(0).getLong("contactId")
                    }
                    val exact = readContact().getJSONObject("contact")
                    assertTrue("Cleanup restricted to this unique test contact", exact.getString("name").startsWith(marker))
                    val raws = exact.getJSONArray("rawContacts")
                    assertTrue((0 until raws.length()).any { raws.getJSONObject(it).getLong("rawContactId") == rawId })
                    val command = "content delete --uri content://com.android.contacts/raw_contacts/$rawId"
                    ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
                    assertEquals(0, call("contacts", "list", JSONObject().put("query", marker)).getJSONObject("data").getJSONArray("items").length())
                }
            } finally { device.close(); instrumentation.runOnMainSync { host.finish() } }
        }
    }
}
