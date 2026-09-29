package io.bbui.runtime

import android.app.Service
import android.content.Intent
import android.os.*
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

class PiAgentService : Service() {
  companion object {
    internal const val START = 1
    internal const val CLOSE = 2
    internal const val READY = 3
    internal const val FAILED = 4
    internal const val VERIFIED = 5
  }
  @Volatile private var started = false
  private val startupFileLock = Any()
  private var stopping = false
  private var client: Messenger? = null
  private var payloadLease: PayloadStorage.Lease? = null
  @Volatile private var payloadRoot: File? = null
  private var cleanupStarted = false
  private val incoming = Messenger(Handler(Looper.getMainLooper()) { message ->
    when (message.what) {
      VERIFIED -> if (started && !stopping && !cleanupStarted) {
        cleanupStarted = true
        thread(name = "bbui-payload-cleanup") {
          payloadRoot?.let { root -> runCatching { PayloadStorage(noBackupFilesDir).cleanUnused(root) } }
        }
      }
      START -> {
        if (started) fail("Pi already started; create a fresh agent process")
        else {
          started = true
          client = message.replyTo
          val config = message.data.getString("config") ?: "{}"
          thread(name = "bbui-pi-bootstrap") { launch(config) }
        }
      }
      CLOSE -> {
        // STOP belongs to the independent execution service and must be set by
        // the task owner first. Killing this isolated process cannot kill the UI.
        synchronized(startupFileLock) {
          stopping = true
          File(noBackupFilesDir, "pi-agent/runtime-config.json").delete()
          android.os.Process.killProcess(android.os.Process.myPid())
        }
      }
    }
    true
  })
  override fun onBind(intent: Intent): IBinder = incoming.binder

  private fun fail(reason: String) {
    runCatching {
      client?.send(Message.obtain(null, FAILED).apply {
        data = Bundle().apply { putString("error", reason) }
      })
    }
  }

  private fun launch(configText: String) {
    try {
      val root = preparePayload()
      val home = File(noBackupFilesDir, "pi-agent").apply { mkdirs() }
      val temp = File(cacheDir, "pi-cache").apply { mkdirs() }
      val runtimeConfig = JSONObject(configText)
      val isolated = runtimeConfig.optBoolean("gate") || runtimeConfig.optBoolean("mockPhone") || runtimeConfig.optBoolean("probeOnly")
      val memory = File(noBackupFilesDir, if (isolated) "test-user-memory" else "user-memory").apply { mkdirs() }
      runtimeConfig.put("memoryDir", memory.absolutePath)
      if (isolated) runtimeConfig.remove("tools")
      synchronized(startupFileLock) {
        if (stopping) return
        File(home, "runtime-config.json").writeText(runtimeConfig.toString())
      }
      val pipes = NativeNode.createPipes()
      val stdin = ParcelFileDescriptor.adoptFd(pipes[0])
      val stdout = ParcelFileDescriptor.adoptFd(pipes[1])
      val stderr = ParcelFileDescriptor.adoptFd(pipes[2])
      // Always drain diagnostics to prevent a full pipe blocking Pi. Do not
      // persist raw provider errors (they may contain user content or secrets).
      thread(name = "bbui-pi-stderr", isDaemon = true) {
        runCatching {
          ParcelFileDescriptor.AutoCloseInputStream(stderr).use { stream ->
            val buffer = ByteArray(4096)
            while (stream.read(buffer) >= 0) { }
          }
        }
      }
      client!!.send(Message.obtain(null, READY).apply {
        data = Bundle().apply { putParcelable("stdin", stdin); putParcelable("stdout", stdout) }
      })
      stdin.close(); stdout.close()
      val code = NativeNode.run(arrayOf("node", File(root, "bootstrap.mjs").path,
        File(home, "runtime-config.json").path), home.path, temp.path, root.path)
      fail("Pi runtime exited (code $code); restart required")
    } catch (error: Throwable) {
      fail("Pi initialization failed: ${error.javaClass.simpleName}: ${error.message?.take(240)}")
    }
  }

  private fun preparePayload(): File {
    val manifest = JSONObject(assets.open("runtime-manifest.json").bufferedReader().use { it.readText() })
    val expected = manifest.getString("payloadSha256")
    val root = File(noBackupFilesDir, "pi-payload-$expected")
    payloadLease = checkNotNull(PayloadStorage(noBackupFilesDir).acquire(root)) { "Pi payload is in use" }
    payloadRoot = root
    val archive = File(cacheDir, "pi-runtime.zip")
    if (File(root, ".complete").takeIf { it.isFile }?.readText() == expected) { archive.delete(); return root }
    try {
      assets.open("pi-runtime.zip").use { source -> archive.outputStream().use { source.copyTo(it) } }
      val digest = MessageDigest.getInstance("SHA-256")
      archive.inputStream().use { source ->
        val buffer = ByteArray(65536)
        while (true) { val count = source.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
      }
      check(digest.digest().joinToString("") { "%02x".format(it) } == expected) { "Pi payload hash mismatch" }
      root.mkdirs()
      val prefix = root.canonicalPath + File.separator
      ZipInputStream(archive.inputStream().buffered()).use { zip ->
        while (true) {
          val entry = zip.nextEntry ?: break
          val target = File(root, entry.name)
          check(target.canonicalPath.startsWith(prefix)) { "Invalid runtime archive path" }
          if (entry.isDirectory) target.mkdirs()
          else { target.parentFile!!.mkdirs(); target.outputStream().use { zip.copyTo(it) } }
          zip.closeEntry()
        }
      }
      File(root, ".complete").writeText(expected)
      return root
    } finally { archive.delete() }
  }
}
