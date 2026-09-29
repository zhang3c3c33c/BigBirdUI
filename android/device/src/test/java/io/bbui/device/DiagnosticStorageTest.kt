package io.bbui.device

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticStorageTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun expiryAndCapacityRemoveOldestEvidence() {
        val dir = temp.newFolder("evidence")
        fun file(name: String, time: Long) = File(dir, name).apply { writeBytes(ByteArray(10)); setLastModified(time) }
        val expired = file("old.png", 1000); val oldest = file("first.png", 8500); val newest = file("last.png", 9500)
        DiagnosticStorage.prune(dir, now = 10000, maxAge = 2000, maxBytes = 10)
        assertFalse(expired.exists()); assertFalse(oldest.exists()); assertTrue(newest.exists())
    }
    @Test fun migrationOnlyRemovesLegacyScreenshotDuplicates() {
        val files = temp.newFolder("files"); val cache = temp.newFolder("cache")
        val png = File(files, "device-runs/01234567-1234-1234-1234-0123456789ab.png").apply { parentFile.mkdirs(); writeText("duplicate") }
        val protected = listOf("device-claims/action.json", "device-runs/latest.json", "device-runs/connection.json", "sessions/history.jsonl", "user-memory/preferences.md")
            .map { File(files, it).apply { parentFile.mkdirs(); writeText("keep") } }
        DiagnosticStorage.maintain(files, cache)
        assertFalse(png.exists()); protected.forEach { assertEquals("keep", it.readText()) }
        DiagnosticStorage.maintain(files, cache)
    }
    @Test fun saveIsAtomicAndCacheMayBeMissing() {
        val dir = File(temp.root, "new-cache")
        DiagnosticStorage.save(dir, "sample.png", byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), dir.resolve("sample.png").readBytes())
        assertFalse(dir.resolve("sample.png.tmp").exists())
        assertThrows(IllegalArgumentException::class.java) { DiagnosticStorage.save(dir, "../session.json", byteArrayOf()) }
        DiagnosticStorage.prune(dir, maxBytes = 0); assertEquals(0, dir.listFiles()!!.size)
    }
}
