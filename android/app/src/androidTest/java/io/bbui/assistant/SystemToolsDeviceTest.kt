package io.bbui.assistant

import android.app.Activity
import android.view.WindowManager
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.bbui.device.ShizukuPhoneDevice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SystemToolsDeviceTest {
    private var host: Activity? = null
    @Before fun keepTestHostForeground() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        host = instrumentation.startActivitySync(Intent(instrumentation.targetContext, TestTargetActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.runOnMainSync { host?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    @After fun finishTestHost() { InstrumentationRegistry.getInstrumentation().runOnMainSync { host?.finish() }; host = null }
    private fun restoreTestHost() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { checkNotNull(host).startActivity(Intent(context, TestTargetActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
        val deadline = System.currentTimeMillis() + 3000
        var focused = false
        do { instrumentation.runOnMainSync { focused = host?.hasWindowFocus() == true }; if (focused) break; Thread.sleep(50) } while (System.currentTimeMillis() < deadline)
        assertTrue("Test host must be foreground before launching notification fixture", focused)
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun ready() = ShizukuPhoneDevice(context).also { it.bindSystem(); it.setStopped(false) }
    private fun call(device: ShizukuPhoneDevice, group: String, op: String, p: JSONObject = JSONObject(), id: String = UUID.randomUUID().toString()): JSONObject {
        val result = device.systemAction(group, op, p, id)
        assertFalse("$group.$op failed: ${result.optJSONObject("details")?.optString("错误")}", result.optBoolean("isError"))
        return result.getJSONObject("details")
    }
    private fun awaitNotificationPresence(device: ShizukuPhoneDevice, pkg: String, key: String, present: Boolean) {
        val deadline = System.currentTimeMillis() + 3000
        var found: Boolean
        do {
            val items = call(device, "notifications", "list", JSONObject().put("packageName", pkg)).getJSONObject("data").getJSONArray("items")
            found = (0 until items.length()).any { items.getJSONObject(it).getString("key") == key }
            if (found == present) return
            Thread.sleep(100)
        } while (System.currentTimeMillis() < deadline)
        assertEquals("Fixture notification presence after ${if (present) "unsnooze" else "snooze"}", present, found)
    }
    private fun restoreFixtureNotification(device: ShizukuPhoneDevice, pkg: String, key: String) {
        val result = device.systemAction("notifications", "unsnooze", JSONObject().put("key", key), UUID.randomUUID().toString())
        val details = result.getJSONObject("details")
        val evidence = JSONObject().put("device", android.os.Build.MODEL).put("androidApi", android.os.Build.VERSION.SDK_INT)
            .put("operation", "notifications.unsnooze").put("fixturePackage", pkg)
            .put("supported", !result.optBoolean("isError")).put("details", details)
        val path = java.io.File(context.cacheDir, "bbui-tools-acceptance/notification-capability.json")
        path.parentFile!!.mkdirs(); path.writeText(evidence.toString(2))
        if (result.optBoolean("isError")) {
            val reason = details.optString("错误")
            assertTrue("Unexpected unsnooze failure: $reason", details.optBoolean("unsupported") && reason.contains("Permission Denial"))
            assertEquals("已派发", details.getJSONObject("执行").getString("状态"))
            assertTrue("Dispatch acknowledgement must be retained", reason.contains("unsnoozing: $key"))
        } else {
            awaitNotificationPresence(device, pkg, key, true)
        }
    }
    @Test fun sharedFilesArePagedDeduplicatedAndConfinedWithoutDisplay() {
        val device = ready(); val dir = "/sdcard/Download/bbui-tools-test-${UUID.randomUUID()}"
        try {
            call(device, "files", "mkdir", JSONObject().put("path", dir))
            val p = JSONObject().put("path", "$dir/中文.txt").put("text", "😀中文工具测试")
            val id = UUID.randomUUID().toString()
            call(device, "files", "write_text", p, id)
            assertTrue(call(device, "files", "write_text", p, id).getBoolean("本次未重放"))
            val text = call(device, "files", "read_text", JSONObject().put("path", "$dir/中文.txt").put("limit", 1)).getJSONObject("data")
            assertEquals("😀", text.getString("text")); assertEquals(2, text.getInt("nextOffset"))
            call(device, "files", "copy", JSONObject().put("path", "$dir/中文.txt").put("destination", "$dir/copy.txt"))
            call(device, "files", "rename", JSONObject().put("path", "$dir/copy.txt").put("destination", "$dir/renamed.txt"))
            assertEquals(2, call(device, "files", "list", JSONObject().put("path", dir)).getJSONObject("data").getJSONArray("items").length())
            assertTrue(device.systemAction("files", "read_text", JSONObject().put("path", "/data/local/tmp/private.txt"), UUID.randomUUID().toString()).optBoolean("isError"))
            assertEquals(0, device.action("列出屏幕", JSONObject()).getJSONObject("details").getJSONArray("屏幕").length())
            device.setStopped(true)
            assertThrows(IllegalStateException::class.java) { device.systemAction("files", "delete", JSONObject().put("path", dir).put("recursive", true), UUID.randomUUID().toString()) }
        } finally {
            device.setStopped(false)
            runCatching { call(device, "files", "delete", JSONObject().put("path", dir).put("recursive", true)) }
            device.close()
        }
    }
    @Test(timeout = 90000) fun clipboardUsesScrcpyWithoutVideoAndRestoresPreviousText() {
        val device = ready()
        var original: String? = null
        var hadClip = false
        try {
            val previous = call(device, "clipboard", "read").getJSONObject("data")
            org.junit.Assume.assumeTrue("Preserving non-text/multi-item user clipboard", !previous.getBoolean("hasClip") || previous.getBoolean("isPlainText"))
            org.junit.Assume.assumeTrue("Preserving user clipboard requiring multiple pages", previous.isNull("nextOffset"))
            hadClip = previous.getBoolean("hasClip"); original = previous.getString("text")
            call(device, "clipboard", "write", JSONObject().put("text", "BBUI 测试😀"))
            assertEquals("BBUI 测试😀", call(device, "clipboard", "read").getJSONObject("data").getString("text"))
            call(device, "clipboard", "clear")
            assertEquals("", call(device, "clipboard", "read").getJSONObject("data").getString("text"))
            assertEquals(0, device.action("列出屏幕", JSONObject()).getJSONObject("details").getJSONArray("屏幕").length())
        } finally {
            original?.let { if (!hadClip) call(device, "clipboard", "clear") else call(device, "clipboard", "write", JSONObject().put("text", it)) }
            device.close()
        }
    }
    /** Main prepares this disposable package and its own notification before invoking this test. */
    @Test fun notificationsExistingFixture() {
        val device = ready(); val pkg = "io.bbui.toolfixture"
        fun target() = JSONObject().put("packageName", pkg)
        try {
            val notifications = call(device, "notifications", "list", target()).getJSONObject("data").getJSONArray("items")
            org.junit.Assume.assumeTrue("Main must post the dedicated fixture notification first", notifications.length() > 0)
            val key = notifications.getJSONObject(0).getString("key")
            call(device, "notifications", "details", JSONObject().put("key", key))
            call(device, "notifications", "snooze", JSONObject().put("key", key).put("durationMs", 60000))
            awaitNotificationPresence(device, pkg, key, false)
            restoreFixtureNotification(device, pkg, key)
            call(device, "apps", "force_stop", target())
            call(device, "apps", "disable", target()); call(device, "apps", "enable", target())
            call(device, "apps", "clear_data", target())
            call(device, "apps", "uninstall", target())
        } finally { device.close() }
    }
    @Test fun dedicatedFixtureApplicationLifecycleAndNotifications() {
        val device = ready(); val pkg = "io.bbui.toolfixture"
        fun target() = JSONObject().put("packageName", pkg)
        try {
            val install = JSONObject().put("paths", JSONArray().put("/sdcard/Download/bbui-tool-fixture.apk"))
            call(device, "apps", "install", install)
            restoreTestHost()
            call(device, "apps", "install", install)
            restoreTestHost()
            assertTrue(call(device, "apps", "details", target()).getJSONObject("data").getString("text").contains(pkg))
            assertTrue(call(device, "apps", "launch_entries", target()).getJSONObject("data").getJSONArray("启动入口").length() > 0)
            call(device, "apps", "grant_permission", target().put("permission", "android.permission.CAMERA"))
            call(device, "apps", "permissions", target())
            call(device, "apps", "revoke_permission", target().put("permission", "android.permission.CAMERA"))
            call(device, "apps", "grant_permission", target().put("permission", "android.permission.POST_NOTIFICATIONS"))
            restoreTestHost()
            // Prepare only our disposable fixture through the same shell path verified manually.
            // This is test setup, not a product permission fallback.
            val automation = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            for (command in listOf(
                "am force-stop io.bbui.toolfixture",
                "am start -W -n io.bbui.toolfixture/.FixtureActivity --ez postNotification true"
            )) {
                val output = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
                    .bufferedReader().use { it.readText() }
                assertFalse("Fixture setup command failed: $output", output.lineSequence().any { it.trimStart().startsWith("Error", true) })
                if (command.startsWith("am start")) assertTrue("Fixture Activity launch was not acknowledged: $output", output.contains("Status: ok"))
            }
            val notificationDeadline = System.currentTimeMillis() + 5000
            var notifications = JSONArray()
            try {
                do {
                    notifications = call(device, "notifications", "list", target()).getJSONObject("data").getJSONArray("items")
                    if (notifications.length() > 0) break
                    Thread.sleep(100)
                } while (System.currentTimeMillis() < notificationDeadline)
            } finally { restoreTestHost() }
            assertTrue("Dedicated fixture notification missing after fresh Activity launch and 5s observation", notifications.length() > 0)
            val key = notifications.getJSONObject(0).getString("key")
            call(device, "notifications", "details", JSONObject().put("key", key))
            call(device, "notifications", "snooze", JSONObject().put("key", key).put("durationMs", 60000))
            awaitNotificationPresence(device, pkg, key, false)
            restoreFixtureNotification(device, pkg, key)
            call(device, "apps", "force_stop", target())
            call(device, "apps", "disable", target()); call(device, "apps", "enable", target())
            call(device, "apps", "clear_data", target())
        } finally { runCatching { call(device, "apps", "uninstall", target()) }; device.close() }
    }
}
