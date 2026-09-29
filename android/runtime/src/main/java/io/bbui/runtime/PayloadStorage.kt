package io.bbui.runtime

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes

/** Locks live outside payloads: deleting a directory must never replace its lock inode. */
internal class PayloadStorage(directory: File) {
    private val base = directory.canonicalFile
    private val names = Regex("pi-payload-[a-f0-9]{64}")
    class Lease(private val file: RandomAccessFile, private val lock: FileLock) : Closeable {
        override fun close() { try { lock.release() } finally { file.close() } }
    }
    fun acquire(root: File): Lease? {
        require(root.parentFile.canonicalFile == base.canonicalFile && names.matches(root.name))
        if (Files.isSymbolicLink(root.toPath())) return null
        val locks = File(base, "pi-payload-locks")
        if (Files.isSymbolicLink(locks.toPath())) return null
        locks.mkdirs()
        val lockFile = File(locks, "${root.name}.lock")
        if (Files.isSymbolicLink(lockFile.toPath())) return null
        val file = RandomAccessFile(lockFile, "rw")
        val lock = runCatching { file.channel.tryLock() }.getOrNull()
        if (lock == null) { file.close(); return null }
        return Lease(file, lock)
    }
    /** Called only after a successful RPC/history handshake; never during extraction/startup failure. */
    fun cleanUnused(current: File): Int {
        require(current.parentFile.canonicalFile == base.canonicalFile && names.matches(current.name))
        if (Files.isSymbolicLink(current.toPath()) || !File(current, ".complete").isFile) return 0
        var removed = 0
        for (candidate in base.listFiles().orEmpty()) {
            if (!names.matches(candidate.name) || candidate.name == current.name || !candidate.isDirectory) continue
            val lease = acquire(candidate) ?: continue
            lease.use { if (runCatching { removeTree(candidate); true }.getOrDefault(false)) removed++ }
        }
        return removed
    }
    // walkFileTree does not follow symlinks. A link inside a stale payload cannot delete its target.
    private fun removeTree(root: File) {
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file); return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir); return FileVisitResult.CONTINUE
            }
        })
    }
}
