package io.bbui.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.Window
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Local snapshots only. No send, Pi session creation, model request, or device-tool operation. */
@RunWith(AndroidJUnit4::class)
class ChatScrollPerformanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test(timeout = 180000) fun scrollDraftPaginationAndQuestionHistoryRemainResponsive() {
        val context = instrumentation.targetContext
        val persisted = File(context.noBackupFilesDir, "conversations.json")
        assertTrue("An existing idle user catalog is required; this test never creates Pi sessions", persisted.isFile)
        val saved = JSONObject(persisted.readText())
        assertNull("Never interrupt user execution", saved.optJSONObject("running"))
        assertEquals("Never drain user waiting tasks", 0, saved.optJSONArray("queue")?.length() ?: 0)
        assertNotEquals("Never replace user questions", "pending", saved.optJSONObject("pendingQuestion")?.optString("status"))
        assertTrue("Keep existing catalog", (saved.optJSONArray("sessions")?.length() ?: 0) > 0)
        assertFalse(saved.optString("controlMode") in setOf("running", "manual", "taking_over", "resuming"))
        val protectedBefore = protectedFiles(context)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var service: AssistantService? = null
        var web: ChatWebView? = null
        var original: SessionController? = null
        var originalHash = ""
        var isolated: SessionController? = null
        var isolation: AutoCloseable? = null
        val fixtureId = "scroll-fixture-${UUID.randomUUID()}"
        val frames = CopyOnWriteArrayList<Long>()
        val frameThread = HandlerThread("bbui-scroll-frame-test").apply { start() }
        val frameListener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (frames.size < 4000) frames.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
        }
        var listening = false
        var failure: Throwable? = null
        fun snapshot(): JSONObject = onMain { requireNotNull(service).sessionSnapshot() }
        fun publish() = onMain { requireNotNull(web).showSnapshot(requireNotNull(service).sessionSnapshot()) }
        try {
            await("MainActivity bound to its service") {
                onMain {
                    service = field(activity, "service") as? AssistantService
                    web = field(activity, "chat") as? ChatWebView
                }
                service != null && web != null
            }
            await("user catalog ready") { snapshot().optBoolean("sessionsReady") && !requireNotNull(service).coordinator.isBusy() }
            await("real local page ready") { evaluate(requireNotNull(web), "!!window.BBUI && !!document.querySelector('.composer-input')") == "true" }
            onMain {
                original = field(requireNotNull(service), "sessions") as SessionController
                originalHash = stableState(requireNotNull(original).state)
                isolation = requireNotNull(service).isolateQuestionTestSessions()
                isolated = field(requireNotNull(service), "sessions") as SessionController
            }
            // refresh() marks ready before its history response. Wait for that response
            // as well, so it cannot overwrite the purely local fixture afterwards.
            await("isolated catalog and initial history settled") {
                onMain {
                    val controller = requireNotNull(isolated)
                    val loaded = field(controller, "loaded") as Set<*>
                    controller.state.ready && controller.state.selected in loaded && !requireNotNull(service).coordinator.isBusy()
                }
            }
            onMain {
                val state = requireNotNull(isolated).state
                state.sessions.clear(); state.chats.clear(); state.questionHistory.clear(); state.pendingQuestion = null
                state.queue.clear(); state.running = null; state.continuation = null
                state.paused = true; state.transition("idle"); state.error = ""
                state.sessions[fixtureId] = JSONObject().put("id", fixtureId).put("title", "本地滚动性能验收").put("modified", 1)
                state.selected = fixtureId; state.limits[fixtureId] = 40
                state.chats[fixtureId] = ChatStore().apply { restore(fixtureMessages(fixtureId)) }
                for ((index, status) in listOf("answered", "cancelled", "interrupted").withIndex()) state.questionHistory.add(question(fixtureId, index, status))
                @Suppress("UNCHECKED_CAST")
                (field(requireNotNull(isolated), "loaded") as MutableSet<String>).add(fixtureId)
            }
            publish()
            await("40 local messages and collapsed question records") {
                evaluate(requireNotNull(web), "document.querySelectorAll('.message').length === 40 && document.querySelectorAll('details.question-summary').length === 3") == "true"
            }
            assertEquals("false", evaluate(requireNotNull(web), "[...document.querySelectorAll('details.question-summary')].some(e => e.open)"))
            assertEquals("0", evaluate(requireNotNull(web), "document.querySelectorAll('details.question-summary input, details.question-summary textarea').length"))
            dispatch(requireNotNull(web), "document.querySelector('details.question-summary summary').click()")
            await("question record expands as plain text") { evaluate(requireNotNull(web), "document.querySelector('details.question-summary').open") == "true" }
            dispatch(requireNotNull(web), "document.querySelector('details.question-summary summary').click()")

            assertEquals("true", evaluate(requireNotNull(web), installTelemetry()))
            dispatch(requireNotNull(web), "document.querySelector('.viewport').scrollTop = document.querySelector('.viewport').scrollHeight / 2")
            Thread.sleep(1300)
            dispatch(requireNotNull(web), "window.__bbuiScroll.reset()")
            onMain { activity.window.addOnFrameMetricsAvailableListener(frameListener, Handler(frameThread.looper)); listening = true }
            val measurementStart = SystemClock.uptimeMillis()
            var downward = true
            while (SystemClock.uptimeMillis() - measurementStart < 8000) {
                swipeInsideViewport(requireNotNull(web), downward)
                downward = !downward
            }
            Thread.sleep(350) // Allow the trailing debounced save, not an artificial typing delay.
            onMain { activity.window.removeOnFrameMetricsAvailableListener(frameListener); listening = false }
            val metrics = jsonResult(requireNotNull(web), "window.__bbuiScroll.report()")
            assertTrue("Actual scrolling must have occurred", metrics.getInt("scrollEvents") >= 20)
            val writes = metrics.getJSONObject("commands").optInt("viewState")
            assertTrue("Eight-second scrolling must be throttled to fewer than 15 bridge writes: $writes", writes in 1..14)
            assertEquals("No task or device commands", 0, metrics.getInt("blockedCommands"))
            val performance = JSONObject().put("elapsedMs", SystemClock.uptimeMillis() - measurementStart)
                .put("web", metrics).put("nativeFrames", frameStats(frames)).put("refreshRate", onMain { activity.display?.refreshRate ?: 0f })
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "BBUI_SCROLL_PERFORMANCE ${performance}\n") })

            val draft = "本地草稿，保留逗号与换行\n中文😀不发送"
            dispatch(requireNotNull(web), "(() => { const e=document.querySelector('.composer-input'); Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(e,${JSONObject.quote(draft)}); e.dispatchEvent(new Event('input',{bubbles:true})); })()")
            await("draft reached native controller") { onMain { requireNotNull(isolated).state.drafts[fixtureId]?.optString("text") == draft } }
            val isolatedContext = onMain { field(requireNotNull(isolated), "context") as Context }
            val viewStateFile = File(isolatedContext.noBackupFilesDir, "view-state.json")
            await("small view-state sidecar is durable") {
                runCatching { JSONObject(viewStateFile.readText()).getJSONObject("entries").getJSONObject(fixtureId).getJSONObject("value").getString("text") == draft }.getOrDefault(false)
            }
            val sidecar = JSONObject(viewStateFile.readText())
            assertEquals(1, sidecar.getInt("version"))
            assertFalse("Sidecar contains no chat history", sidecar.has("chats") || sidecar.has("messages"))
            assertTrue("Sidecar remains small for one local draft", viewStateFile.length() < 16000)
            val priorTop = onMain { requireNotNull(isolated).state.drafts.getValue(fixtureId).getDouble("scrollTop") }
            // A page-ready handshake must refresh the service-owned view state;
            // merely replaying the last full snapshot would lose lightweight saves.
            val previousDocument = evaluate(requireNotNull(web), "performance.timeOrigin")
            onMain { requireNotNull(web).reload() }
            await("new document restores draft and reading position") {
                evaluate(requireNotNull(web), "performance.timeOrigin !== $previousDocument && document.querySelector('.composer-input')?.value === ${JSONObject.quote(draft)} && Math.abs((document.querySelector('.viewport')?.scrollTop ?? -9999)-$priorTop) < 4") == "true"
            }

            // Pagination uses the existing local limits+40 route. Clicking it is
            // permitted; no refresh/select/newSession request is made.
            dispatch(requireNotNull(web), "document.querySelector('.viewport').scrollTop=0")
            await("viewport at history start") { evaluate(requireNotNull(web), "document.querySelector('.viewport').scrollTop < 1") == "true" }
            val anchor = jsonResult(requireNotNull(web), "(() => { const e=document.querySelector('.message'); return {id:e.dataset.messageId,top:e.getBoundingClientRect().top}; })()")
            dispatch(requireNotNull(web), "document.querySelector('.load-older').click()")
            await("older local page rendered") { evaluate(requireNotNull(web), "document.querySelectorAll('.message').length === 80") == "true" }
            await("reading anchor retained after pagination") {
                evaluate(requireNotNull(web), "Math.abs(document.querySelector('[data-message-id='+CSS.escape(${JSONObject.quote(anchor.getString("id"))})+']').getBoundingClientRect().top-${anchor.getDouble("top")}) < 4") == "true"
            }
            assertEquals(draft, onMain { requireNotNull(isolated).state.drafts.getValue(fixtureId).getString("text") })
            assertFalse(requireNotNull(service).coordinator.isBusy())
            assertEquals(0, onMain { requireNotNull(isolated).state.queue.size })
            assertNull(onMain { requireNotNull(isolated).state.running })

            // Pending appearance is a projection-only sample, not a fake native
            // executor; no answering, queue ownership or Agent request is created.
            val pending = snapshot().put("isRunning", true).put("runId", "scroll-pending").put("runningSessionId", fixtureId)
                .put("pendingQuestion", question(fixtureId, 3, "pending").put("runId", "scroll-pending"))
            onMain { requireNotNull(web).showSnapshot(pending) }
            await("pending question remains expanded and editable") {
                evaluate(requireNotNull(web), "!!document.querySelector('form.question-card textarea:not(:disabled)') && document.querySelectorAll('details.question-summary').length === 3") == "true"
            }
        } catch (error: Throwable) { failure = error }
        finally {
            try {
                onMain {
                    if (listening) activity.window.removeOnFrameMetricsAvailableListener(frameListener)
                    isolated?.state?.let { state ->
                        state.pendingQuestion = null; state.running = null; state.queue.clear(); state.continuation = null
                        state.remove(fixtureId)
                    }
                    isolation?.close()
                    if (original != null) {
                        assertSame(original, field(requireNotNull(service), "sessions"))
                        assertEquals("User state and all message caches preserved", originalHash, stableState(requireNotNull(original).state))
                    }
                }
                assertEquals("User history, memory and credentials remain byte-identical", protectedBefore, protectedFiles(context))
            } catch (error: Throwable) { if (failure == null) failure = error else failure!!.addSuppressed(error) }
            finally { frameThread.quitSafely(); onMain { activity.finish() } }
        }
        failure?.let { throw it }
    }

    private fun fixtureMessages(session: String): JSONObject = JSONObject().put("sessionId", session).put("messages", JSONArray().apply {
        repeat(200) { i ->
            val parts = JSONArray().put(JSONObject().put("id", "text").put("type", "text").put("state", "complete")
                .put("text", "第${i + 1}条本地消息。\n\n中文、Emoji 😀、列表与长正文用于真实滚动。\n\n- 读取画面\n- 核对结果\n\n" + "这是一段不会发给模型的本地性能样本。".repeat(8)))
            if (i in 190..192) parts.put(JSONObject().put("id", "tool").put("type", "tool").put("state", "complete")
                .put("toolCallId", "scroll-call-${i - 190}").put("toolName", "ask_user_question").put("title", "向用户提问"))
            put(JSONObject().put("id", "scroll-message-$i").put("sourceKey", "scroll-source-$i").put("role", if (i % 2 == 0 || i in 190..192) "assistant" else "user")
                .put("status", "complete").put("timestamp", i.toLong() + 1).put("parts", parts))
        }
    })
    private fun question(session: String, index: Int, status: String): JSONObject = JSONObject()
        .put("sessionId", session).put("runId", "scroll-history").put("requestId", "scroll-question-$index").put("toolCallId", "scroll-call-$index")
        .put("messageSourceKey", "scroll-source-${190 + index}").put("status", status)
        .put("questions", JSONArray().put(JSONObject().put("id", "target").put("header", "收件人").put("question", "发给谁？").put("options", JSONArray()).put("multiSelect", false)))
        .put("answers", JSONArray().put(JSONObject().put("questionId", "target").put("selected", JSONArray()).put("text", "本地测试对象")))
    private fun swipeInsideViewport(web: ChatWebView, upward: Boolean) {
        val bounds = jsonResult(web, "(() => { const r=document.querySelector('.viewport').getBoundingClientRect();return {x:r.left+r.width*.7,y1:r.top+r.height*.7,y2:r.top+r.height*.3,scale:devicePixelRatio}; })()")
        val location = IntArray(2); onMain { web.getLocationOnScreen(location) }
        val scale = bounds.getDouble("scale").toFloat()
        val x = location[0] + bounds.getDouble("x").toFloat() * scale
        val a = location[1] + bounds.getDouble(if (upward) "y1" else "y2").toFloat() * scale
        val b = location[1] + bounds.getDouble(if (upward) "y2" else "y1").toFloat() * scale
        val down = SystemClock.uptimeMillis()
        fun motion(action: Int, y: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        motion(MotionEvent.ACTION_DOWN, a)
        try { for (step in 1..18) { SystemClock.sleep(16); motion(MotionEvent.ACTION_MOVE, a + (b - a) * step / 18) } }
        finally { motion(MotionEvent.ACTION_UP, b) }
    }
    private fun installTelemetry() = """(() => {
      const native=window.BBUI.postMessage.bind(window.BBUI), viewport=document.querySelector('.viewport');
      const state={scrollEvents:0,commands:{},blockedCommands:0,gaps:[],last:0,active:true};
      const blocked=new Set(['send','steer','newSession','resumeQueue','resumeTask','takeOver','stop','deleteSession','answerQuestion','openPreview','selectSession']);
      const wrapped=raw=>{const type=JSON.parse(raw).type;if(blocked.has(type)){state.blockedCommands++;throw Error('Performance fixture prohibits execution');}state.commands[type]=(state.commands[type]||0)+1;return native(raw);};
      window.BBUI.postMessage=wrapped;
      viewport.addEventListener('scroll',()=>state.scrollEvents++,{passive:true});
      const frame=t=>{if(state.last && state.gaps.length<4000)state.gaps.push(t-state.last);state.last=t;if(state.active)requestAnimationFrame(frame);};requestAnimationFrame(frame);
      window.__bbuiScroll={reset(){state.scrollEvents=0;state.commands={};state.gaps=[];state.last=0;},report(){state.active=false;const a=state.gaps.slice().sort((x,y)=>x-y);return {scrollEvents:state.scrollEvents,commands:state.commands,blockedCommands:state.blockedCommands,rafFrames:a.length,rafP50Ms:a[Math.floor(a.length*.5)]||0,rafP95Ms:a[Math.floor(a.length*.95)]||0,rafMaxMs:a.at(-1)||0};}};
      return window.BBUI.postMessage===wrapped;
    })()""".trimIndent()
    private fun frameStats(frames: List<Long>): JSONObject {
        val sorted = frames.filter { it >= 0 }.sorted()
        fun percentile(p: Double) = if (sorted.isEmpty()) 0.0 else sorted[(sorted.size * p).toInt().coerceAtMost(sorted.lastIndex)] / 1000000.0
        return JSONObject().put("count", sorted.size).put("p50Ms", percentile(.5)).put("p95Ms", percentile(.95)).put("maxMs", percentile(1.0))
    }
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }
    private fun dispatch(web: ChatWebView, script: String) = onMain { web.evaluateJavascript(script, null) }
    private fun evaluate(web: ChatWebView, script: String): String {
        val latch = CountDownLatch(1); val result = AtomicReference("")
        onMain { web.evaluateJavascript(script) { result.set(it); latch.countDown() } }
        return if (latch.await(2000, TimeUnit.MILLISECONDS)) result.get() else "<context-transition>"
    }
    private fun jsonResult(web: ChatWebView, expression: String): JSONObject = JSONObject(JSONTokener(evaluate(web, "JSON.stringify($expression)")).nextValue() as String)
    private fun await(label: String, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 30000
        while (SystemClock.uptimeMillis() < end) { if (predicate()) return; SystemClock.sleep(75) }
        fail("Timed out: $label")
    }
    private fun stableState(state: ConversationStore): String {
        val saved = state.save()
        saved.optJSONObject("chats")?.let { chats -> chats.keys().forEach { id -> chats.getJSONObject(id).remove("timing") } }
        return hash(saved.toString().toByteArray(Charsets.UTF_8))
    }
    private fun protectedFiles(context: Context): Map<String, String> {
        val roots = listOf("pi-agent/sessions", "user-memory", "model.enc", "search.enc")
        return roots.flatMap { relative -> File(context.noBackupFilesDir, relative).let { root ->
            if (root.isFile) listOf(root) else if (root.isDirectory) root.walkTopDown().filter(File::isFile).toList() else emptyList()
        } }.associate { it.relativeTo(context.noBackupFilesDir).path to hash(it.readBytes()) }
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
