package io.bbui.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PayloadStorageTest {
    @get:Rule val temp = TemporaryFolder()
    private fun payload(base: File, key: Char, complete: Boolean = true) = File(base, "pi-payload-${key.toString().repeat(64)}").apply {
        mkdirs(); resolve("bootstrap.mjs").writeText("runtime")
        if (complete) resolve(".complete").writeText(key.toString().repeat(64))
    }
    @Test fun keepsCurrentAndLeasedVersionsAndNeverTouchesUserData() {
        val base = temp.newFolder("app"); val storage = PayloadStorage(base)
        val current = payload(base, 'a'); val old = payload(base, 'b'); val busy = payload(base, 'c')
        val partial = payload(base, 'd', false)
        val history = File(base, "pi-agent/sessions/history.jsonl").apply { parentFile.mkdirs(); writeText("history") }
        val memory = File(base, "user-memory/preferences.md").apply { parentFile.mkdirs(); writeText("memory") }
        val unknown = File(base, "pi-payload-not-a-version").apply { mkdirs() }
        storage.acquire(current)!!.use {
            val lease = storage.acquire(busy)!!
            assertEquals(2, storage.cleanUnused(current))
            assertTrue(current.exists()); assertTrue(busy.exists()); assertFalse(old.exists()); assertFalse(partial.exists())
            lease.close()
            assertEquals(1, storage.cleanUnused(current))
            assertEquals(0, storage.cleanUnused(current))
        }
        assertEquals("history", history.readText()); assertEquals("memory", memory.readText()); assertTrue(unknown.exists())
    }
    @Test fun incompleteCurrentCannotTriggerCollection() {
        val base = temp.newFolder("failed"); val current = payload(base, 'a', false); val old = payload(base, 'b')
        assertEquals(0, PayloadStorage(base).cleanUnused(current)); assertTrue(old.exists())
    }
    @Test fun rejectsPathsOutsidePayloadRoot() {
        val base = temp.newFolder("app"); val outside = temp.newFolder("outside")
        assertThrows(IllegalArgumentException::class.java) { PayloadStorage(base).acquire(payload(outside, 'a')) }
    }
    @Test fun nestedLinksNeverDeleteTheirTargets() {
        val base = temp.newFolder("links"); val current = payload(base, 'a'); val old = payload(base, 'b')
        val outside = temp.newFolder("protected"); val keep = File(outside, "keep").apply { writeText("safe") }
        val supported = runCatching { Files.createSymbolicLink(old.resolve("link").toPath(), outside.toPath()); true }.getOrDefault(false)
        org.junit.Assume.assumeTrue("Host cannot create symlinks", supported)
        assertEquals(1, PayloadStorage(base).cleanUnused(current)); assertEquals("safe", keep.readText())
    }
}
