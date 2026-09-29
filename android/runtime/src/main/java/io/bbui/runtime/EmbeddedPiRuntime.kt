package io.bbui.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import io.bbui.core.AgentRuntime
import org.json.JSONObject
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One isolated Android process owns one libnode instance and the original Pi RPC. */
class EmbeddedPiRuntime(private val context: Context) : AgentRuntime {
  companion object {
    // The service name is shared by runtime instances. A replacement must not
    // bind to the old process between its CLOSE message and actual death.
    @Volatile private var previousProcessExit: CountDownLatch? = null
  }
  private val io = Executors.newCachedThreadPool()
  private val writer = Executors.newSingleThreadExecutor()
  private var remote: Messenger? = null
  private var input: OutputStream? = null
  private var config = JSONObject()
  private var onEvent: (JSONObject) -> Unit = {}
  private var onFailure: (String) -> Unit = {}
  private var bound = false
  private var starting = false
  private val processExit = CountDownLatch(1)
  private val main = Handler(Looper.getMainLooper())
  @Volatile private var closed = false
  private val writeLock = Any()
  private val pending = mutableListOf<ByteArray>()
  private var initialState: JSONObject? = null
  private val startupGate = RuntimeStartupGate()
  private val initializationTimeout = Runnable {
    if (startupGate.timeout()) onFailure(if (config.optBoolean("probeOnly")) "连接测试超时" else "Pi history restoration timed out; no prompt was submitted")
  }
  private fun fail(reason: String) {
    main.removeCallbacks(initializationTimeout)
    if (startupGate.fail()) onFailure(reason)
  }
  private val receiver = Messenger(Handler(Looper.getMainLooper()) { message ->
    when (message.what) {
      PiAgentService.READY -> {
        message.data.classLoader = ParcelFileDescriptor::class.java.classLoader
        @Suppress("DEPRECATION")
        val writer = message.data.getParcelable<ParcelFileDescriptor>("stdin")!!
        @Suppress("DEPRECATION")
        val reader = message.data.getParcelable<ParcelFileDescriptor>("stdout")!!
        if (!startupGate.active) { writer.close(); reader.close() }
        else {
          synchronized(writeLock) {
            input = ParcelFileDescriptor.AutoCloseOutputStream(writer)
          }
          io.execute {
            try {
              synchronized(writeLock) {
                pending.forEach { input!!.write(it) }
                pending.clear()
                input!!.flush()
              }
              // readLine splits LF/CRLF only, never Unicode U+2028/U+2029.
              ParcelFileDescriptor.AutoCloseInputStream(reader).bufferedReader(Charsets.UTF_8).use { lines ->
                val records = RpcLineReader(lines)
                while (startupGate.active) {
                  val line = records.readLine() ?: break
                  if (!startupGate.active) break
                  if (line.isNotBlank()) {
                    val receivedAt = System.currentTimeMillis()
                    val event = JSONObject(line).put("runtimeReceivedAtMs", receivedAt)
                    when (event.optString("id")) {
                      "bbui-runtime-ready" -> {
                        check(event.optBoolean("success")) { "Cannot read Pi state" }
                        initialState = event.getJSONObject("data")
                        send(JSONObject().put("id", "bbui-runtime-history").put("type", "get_messages"))
                      }
                      "bbui-runtime-history" -> {
                        check(event.optBoolean("success")) { "Cannot restore Pi history" }
                        val messages = event.getJSONObject("data").getJSONArray("messages")
                        val state = checkNotNull(initialState)
                        if (!startupGate.historyRestored()) continue
                        // READY only means pipes exist. Reclaim old payloads after Pi has
                        // actually loaded and restored history (also works in catalogue mode).
                        runCatching { remote?.send(Message.obtain(null, PiAgentService.VERIFIED)) }
                        onEvent(JSONObject().put("type", "history_snapshot").put("messages", messages)
                          .put("sessionId", state.optString("sessionId")).put("runtimeReceivedAtMs", receivedAt)
                          .put("piEmittedAtMs", event.optLong("piEmittedAtMs")))
                        main.removeCallbacks(initializationTimeout)
                        if (startupGate.ready) onEvent(JSONObject().put("type", "runtime_ready").put("state", state)
                            .put("runtimeReceivedAtMs", receivedAt).put("piEmittedAtMs", event.optLong("piEmittedAtMs")))
                      }
                      else -> onEvent(event)
                    }
                  }
                }
              }
              fail("Pi RPC stream closed; execution must remain stopped")
            } catch (error: Exception) {
              fail(if (!startupGate.ready)
                "Pi history restoration failed (${error.javaClass.simpleName}); no prompt was submitted"
                else "Pi RPC transport failed: ${error.javaClass.simpleName}")
            }
          }
        }
      }
      PiAgentService.FAILED -> fail(message.data.getString("error") ?: "Pi runtime failed")
    }
    true
  })
  private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
      if (!startupGate.active) return
      try {
        remote = Messenger(binder)
        binder.linkToDeath({ processExit.countDown() }, 0)
        val message = Message.obtain(null, PiAgentService.START)
        message.data = Bundle().apply { putString("config", config.toString()) }
        message.replyTo = receiver
        remote!!.send(message)
      } catch (_: RemoteException) {
        if (!binder.isBinderAlive) processExit.countDown()
        fail("Pi service died while connecting; execution must remain stopped")
      }
    }
    override fun onServiceDisconnected(name: ComponentName) {
      processExit.countDown()
      remote = null
      fail("Pi agent process disconnected; execution must remain stopped")
    }
    override fun onBindingDied(name: ComponentName) {
      processExit.countDown()
      fail("Pi agent process died; execution must remain stopped")
    }
  }

  override fun start(config: JSONObject, onEvent: (JSONObject) -> Unit, onFailure: (String) -> Unit) {
    check(!starting && !closed) { "Create a fresh EmbeddedPiRuntime to restart Pi" }
    starting = true
    this.config = JSONObject(config.toString())
    this.onEvent = onEvent
    this.onFailure = onFailure
    main.postDelayed(initializationTimeout, 45000)
    synchronized(writeLock) {
      if (!config.optBoolean("probeOnly")) pending.add("{\"id\":\"bbui-runtime-ready\",\"type\":\"get_state\"}\n".toByteArray())
    }
    val previous = previousProcessExit
    io.execute {
      try {
        if (previous != null && !previous.await(5, TimeUnit.SECONDS)) {
          fail("Previous Pi process has not exited; refusing to reuse a running Node instance")
          return@execute
        }
        main.post {
          if (startupGate.active) {
            bound = context.bindService(Intent(context, PiAgentService::class.java), connection, Context.BIND_AUTO_CREATE)
            if (!bound) fail("Could not bind Pi agent service")
          }
        }
      } catch (_: InterruptedException) { /* Closed before binding. */ }
    }
  }

  override fun send(command: JSONObject) {
    check(!closed) { "Pi runtime is closed" }
    check(startupGate.active) { "Pi runtime failed; create a fresh runtime" }
    check(command.optString("type") != "prompt" || startupGate.ready) {
      "Pi history must be restored before a new prompt"
    }
    val bytes = (command.toString() + "\n").toByteArray(Charsets.UTF_8)
    writer.execute {
      try {
        synchronized(writeLock) {
          if (!startupGate.active) return@execute
          val stream = input
          if (stream == null) pending.add(bytes)
          else { stream.write(bytes); stream.flush() }
        }
      } catch (error: Exception) {
        fail("Cannot send Pi command: ${error.javaClass.simpleName}")
      }
    }
  }

  override fun close() {
    closed = true
    startupGate.close()
    main.removeCallbacks(initializationTimeout)
    val service = remote
    if (service != null) {
      previousProcessExit = processExit
      runCatching { service.send(Message.obtain(null, PiAgentService.CLOSE)) }
        .onFailure { if (!service.binder.isBinderAlive) processExit.countDown() }
    }
    synchronized(writeLock) { runCatching { input?.close() }; input = null; pending.clear() }
    if (bound) { context.unbindService(connection); bound = false }
    io.shutdownNow()
    writer.shutdownNow()
  }
}
