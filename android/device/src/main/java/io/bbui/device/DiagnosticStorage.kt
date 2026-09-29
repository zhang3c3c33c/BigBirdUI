package io.bbui.device

import java.io.File
import java.nio.file.Files

/** Disposable evidence only. Never traverses session, memory, draft or action-claim directories. */
object DiagnosticStorage {
    const val MAX_BYTES = 100L * 1024 * 1024
    const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private val legacyPng = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.png")
    private fun files(directory: File): List<File> {
        if (Files.isSymbolicLink(directory.toPath())) return emptyList()
        return directory.listFiles().orEmpty().filter { !Files.isSymbolicLink(it.toPath()) && it.isFile }
    }
    @Synchronized fun prune(directory: File, now: Long = System.currentTimeMillis(), maxBytes: Long = MAX_BYTES, maxAge: Long = MAX_AGE_MS) {
        val candidates = files(directory).sortedBy { it.lastModified() }
        var bytes = candidates.sumOf { it.length() }
        for (file in candidates) {
            if (now - file.lastModified() > maxAge || bytes > maxBytes || file.extension == "tmp") {
                val size = file.length()
                if (file.delete()) bytes -= size
            }
        }
    }
    @Synchronized fun save(directory: File, name: String, bytes: ByteArray) {
        require(Regex("[a-zA-Z0-9-]+\\.(png|json)").matches(name))
        if (bytes.size > MAX_BYTES || Files.isSymbolicLink(directory.toPath())) return
        directory.mkdirs()
        val target = File(directory, name)
        val temp = File(directory, "$name.tmp")
        if (Files.isSymbolicLink(target.toPath()) || Files.isSymbolicLink(temp.toPath())) return
        try {
            temp.writeBytes(bytes)
            check(temp.renameTo(target)) { "Cannot save diagnostic evidence" }
        } finally { temp.delete() }
        prune(directory)
    }
    @Synchronized fun maintain(filesDir: File, cacheDir: File) {
        // Legacy PNGs are unreferenced diagnostic duplicates; model images live in Pi JSONL.
        files(File(filesDir, "device-runs")).filter { legacyPng.matches(it.name) }.forEach { it.delete() }
        prune(File(cacheDir, "bbui-diagnostics"))
        // These legacy test-evidence roots contain disposable files only. Apply one shared budget.
        val legacy = listOf("gate-evidence", "paste-isolation").flatMap { files(File(filesDir, it)) }.sortedBy { it.lastModified() }
        var bytes = legacy.sumOf { it.length() } + files(File(cacheDir, "bbui-diagnostics")).sumOf { it.length() }
        val now = System.currentTimeMillis()
        for (file in legacy) {
            if (now - file.lastModified() > MAX_AGE_MS || bytes > MAX_BYTES) {
                val size = file.length(); if (file.delete()) bytes -= size
            }
        }
    }
}
