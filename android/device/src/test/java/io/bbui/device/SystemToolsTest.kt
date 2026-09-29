package io.bbui.device

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SystemToolsTest {
    @Test fun clipboardPagesAreBoundedAndDoNotSplitEmojiOrLoseMetadata() {
        val text = "😀中文😀" + "x".repeat(20000)
        val tools = SystemTools({ 0 }, { "" }, { JSONObject() }, { JSONObject() }, { _, _ ->
            JSONObject().put("text", text).put("hasClip", true).put("isPlainText", true).put("itemCount", 1)
        }, {})
        val first = tools.execute("clipboard", "read", JSONObject().put("limit", 1))
        assertEquals("😀", first.getString("text")); assertEquals(2, first.getInt("nextOffset"))
        assertEquals(text.length, first.getInt("totalChars")); assertTrue(first.getBoolean("isPlainText"))
        assertEquals(1, first.getInt("itemCount")); assertTrue(first.getBoolean("hasClip"))
        assertThrows(IllegalArgumentException::class.java) { tools.execute("clipboard", "read", JSONObject().put("offset", 1)) }
        val huge = tools.execute("clipboard", "read", JSONObject().put("limit", Int.MAX_VALUE))
        assertTrue(huge.getString("text").length <= 16384)
        var offset = 0; val reconstructed = StringBuilder()
        do {
            val page = tools.execute("clipboard", "read", JSONObject().put("offset", offset).put("limit", 3))
            reconstructed.append(page.getString("text")); val next = page.optInt("nextOffset", -1)
            assertTrue(next == -1 || next > offset); offset = next
        } while (offset >= 0)
        assertEquals(text, reconstructed.toString())
    }
    @Test fun oversizedWritesAndRequestsAreRejectedBeforeDispatch() {
        var dispatches = 0
        val tools = SystemTools({ 0 }, { "" }, { JSONObject() }, { JSONObject() }, { _, _ -> JSONObject() }, {}, { dispatches++ })
        val failure = assertThrows(IllegalArgumentException::class.java) { tools.execute("clipboard", "write", JSONObject().put("text", "x".repeat(32769))) }
        assertTrue(failure.message!!.contains("Binder")); assertEquals(0, dispatches)
        val root = Files.createTempDirectory("bbui-write-bound").toFile()
        try {
            val target = File(root, "never-created.txt")
            assertThrows(IllegalArgumentException::class.java) { SharedFiles(root, {}, { dispatches++ }).execute("write_text", JSONObject().put("path", target.path).put("text", "x".repeat(32769))) }
            assertFalse(target.exists()); assertEquals(0, dispatches)
        } finally { root.deleteRecursively() }
        assertNull(SystemPayloadLimits.rejection("x".repeat(120000)))
        val rejected = SystemPayloadLimits.rejection("x".repeat(120001))!!
        assertEquals("未派发", rejected.getJSONObject("执行").getString("状态"))
        assertTrue(rejected.getString("错误").contains("Binder"))
    }
    @Test fun notificationShellErrorsAreRejectedEvenWhenProcessReportsSuccess() {
        val key = "0|test.app|1|fixture|10001"
        for (output in listOf("error: no snoozed otification matching key: $key", "Error occurred. Check logcat for details. fixture failure",
            "unsnoozing: $key\nError occurred. Check logcat for details. Permission Denial: getIntentSender() from pid=23780, uid=2000 is not allowed to send as package android")) {
            var dispatches = 0
            val tools = SystemTools({ 0 }, { output }, { JSONObject() }, { JSONObject() }, { _, _ -> JSONObject() }, {}, { dispatches++ })
            val failure = assertThrows(SystemRejected::class.java) { tools.execute("notifications", "unsnooze", JSONObject().put("key", key)) }
            assertEquals(output, failure.message)
            assertEquals(1, dispatches)
        }
    }
    @Test fun textPagesProgressWithoutSplittingEmojiAndRejectBinary() {
        val root = Files.createTempDirectory("bbui-files").toFile()
        try {
            val file = File(root, "中文.txt").apply { writeText("😀中😀文") }; val tools = SharedFiles(root)
            var offset = 0; val text = StringBuilder()
            do { val page = tools.execute("read_text", JSONObject().put("path", file.path).put("offset", offset).put("limit", 1))
                text.append(page.getString("text")); val next = page.optInt("nextOffset", -1); assertTrue(next == -1 || next > offset); offset = next
            } while (offset >= 0)
            assertEquals("😀中😀文", text.toString())
            file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
            assertThrows(java.nio.charset.MalformedInputException::class.java) { tools.execute("read_text", JSONObject().put("path", file.path)) }
        } finally { root.deleteRecursively() }
    }
    @Test fun mutationsRequireExplicitOverwriteAndRemainInsideSharedRoot() {
        val root = Files.createTempDirectory("bbui-files").toFile()
        try {
            val file = File(root, "one.txt").apply { writeText("first") }; val tools = SharedFiles(root)
            assertThrows(IllegalArgumentException::class.java) { tools.execute("write_text", JSONObject().put("path", file.path).put("text", "second")) }
            assertEquals("first", file.readText())
            assertThrows(IllegalArgumentException::class.java) { tools.resolve(File(root, "../outside").path) }
            val directory = File(root, "parent").apply { mkdir() }; val child = File(directory, "child").apply { mkdir() }
            assertThrows(IllegalArgumentException::class.java) { tools.execute("copy", JSONObject().put("path", child.path).put("destination", directory.path).put("recursive", true).put("overwrite", true)) }
            tools.execute("rename", JSONObject().put("path", file.path).put("destination", File(root, "renamed.txt").path))
            assertFalse(file.exists()); assertEquals("first", File(root, "renamed.txt").readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun installUsesDocumentedStdinArgumentsForSingleAndSplitPackages() {
        val root = Files.createTempDirectory("bbui-install").toFile()
        try {
            val base = File(root, "base.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val split = File(root, "config.apk").apply { writeBytes(byteArrayOf(4, 5)) }
            val commands = mutableListOf<List<String>>()
            val streams = mutableListOf<Pair<List<String>, List<Byte>>>()
            val tools = SystemTools({ 0 }, { args -> commands.add(args); if (args[1] == "install-create") "Success: created install session [42]" else "Success" },
                { JSONObject() }, { JSONObject() }, { _, _ -> JSONObject() }, {}, commandInput = { args, file -> streams.add(args to file.readBytes().toList()); "Success" }, sharedRoot = { root })
            tools.execute("apps", "install", JSONObject().put("paths", org.json.JSONArray().put(base.path)))
            assertEquals(listOf("/system/bin/pm", "install", "-r", "--user", "0", "-S", "3"), streams.single().first)
            assertEquals(listOf<Byte>(1, 2, 3), streams.single().second)
            streams.clear()
            tools.execute("apps", "install", JSONObject().put("paths", org.json.JSONArray().put(base.path).put(split.path)))
            assertEquals(listOf("/system/bin/pm", "install-write", "-S", "3", "42", "split0.apk", "-"), streams[0].first)
            assertEquals(listOf("/system/bin/pm", "install-write", "-S", "2", "42", "split1.apk", "-"), streams[1].first)
            assertEquals(listOf("/system/bin/pm", "install-commit", "42"), commands.last())
            assertEquals(listOf<Byte>(4, 5), streams[1].second)
        } finally { root.deleteRecursively() }
    }
    @Test fun invalidMutationNeverCrossesDispatchBoundaryAndAndroidPackageIsValid() {
        var dispatches = 0
        val calls = mutableListOf<List<String>>()
        val tools = SystemTools({ 0 }, { args -> calls.add(args); "" }, { JSONObject() }, { JSONObject() }, { _, _ -> JSONObject() }, {}, { dispatches++ })
        assertThrows(IllegalArgumentException::class.java) { tools.execute("apps", "force_stop", JSONObject().put("packageName", "bad;package")) }
        assertEquals(0, dispatches); assertTrue(calls.isEmpty())
        tools.execute("apps", "force_stop", JSONObject().put("packageName", "android"))
        assertEquals(1, dispatches); assertEquals("android", calls.single().last())
    }
    @Test fun cancelPreventsFurtherMutationAndWrongUserNeverDispatches() {
        val root = Files.createTempDirectory("bbui-files").toFile()
        try {
            assertThrows(IllegalStateException::class.java) { SharedFiles(root) { error("STOP") }.execute("mkdir", JSONObject().put("path", File(root, "new").path)) }
            assertFalse(File(root, "new").exists())
            var calls = 0
            val tools = SystemTools({ 0 }, { calls++; "" }, { JSONObject() }, { JSONObject() }, { _, _ -> JSONObject() }, {})
            assertThrows(IllegalArgumentException::class.java) { tools.execute("apps", "force_stop", JSONObject().put("packageName", "test.app").put("userId", 10)) }
            assertEquals(0, calls)
        } finally { root.deleteRecursively() }
    }
}
