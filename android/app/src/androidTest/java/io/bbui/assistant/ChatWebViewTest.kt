package io.bbui.assistant

import android.content.Intent
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.ClipboardManager
import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.IBinder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.graphics.Bitmap
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File

/** Local assets and synthetic snapshots only: never reads credentials, sends a model prompt or taps a target app. */
@RunWith(AndroidJUnit4::class)
class ChatWebViewTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun bridgeRejectsForeignFramesUnknownCommandsAndRemoteResources() {
        val received = mutableListOf<JSONObject>()
        var view: ChatWebView? = null
        instrumentation.runOnMainSync {
            view = ChatWebView(instrumentation.targetContext, { received.add(it) }, {})
            val web = requireNotNull(view)
            val stop = "{\"type\":\"stop\"}"
            web.receive(stop, Uri.parse("https://example.com"), true)
            web.receive(stop, Uri.parse("http://appassets.androidplatform.net"), true)
            web.receive(stop, Uri.parse("https://appassets.androidplatform.net:8443"), true)
            web.receive(stop, Uri.parse(ChatWebView.ORIGIN), false)
            web.receive("{\"type\":\"phone_action\",\"操作\":\"点击\"}", Uri.parse(ChatWebView.ORIGIN), true)
            web.receive("{\"type\":\"send\",\"text\":{\"apiKey\":\"not-a-key\"}}", Uri.parse(ChatWebView.ORIGIN), true)
            for (type in listOf("takeOver", "resumeTask", "endManual")) {
                web.receive(JSONObject().put("type", type).toString(), Uri.parse(ChatWebView.ORIGIN), true)
                web.receive(JSONObject().put("type", type).put("controlId", 42).toString(), Uri.parse(ChatWebView.ORIGIN), true)
                web.receive(JSONObject().put("type", type).put("controlId", " ").toString(), Uri.parse(ChatWebView.ORIGIN), true)
            }
            web.receive("{\"type\":\"steer\",\"text\":\"supplement\",\"sessionId\":42,\"submissionId\":\"one\"}", Uri.parse(ChatWebView.ORIGIN), true)
            assertTrue(received.isEmpty())
            web.receive(stop, Uri.parse(ChatWebView.ORIGIN), true)
            assertEquals(listOf("stop"), received.map { it.getString("type") })
            for (type in listOf("takeOver", "resumeTask", "endManual")) {
                web.receive(JSONObject().put("type", type).put("controlId", "owner-token").put("sessionId", "untrusted-selection").toString(), Uri.parse(ChatWebView.ORIGIN), true)
                assertEquals("owner-token", received.last().getString("controlId"))
                assertFalse(received.last().has("sessionId"))
            }
            web.receive("{\"type\":\"steer\",\"text\":\"supplement\",\"sessionId\":\"owner\",\"submissionId\":\"one\"}", Uri.parse(ChatWebView.ORIGIN), true)
            assertEquals("steer", received.last().getString("type"))
            assertEquals("owner", received.last().getString("sessionId"))
            val selection = JSONObject().put("type", "selectModel").put("sessionId", "selected").put("requestId", "choice-1")
                .put("connectionId", "connection-a").put("modelId", "model-a").put("apiKey", "must-not-pass").put("baseUrl", "https://example.com")
            web.receive(selection.toString(), Uri.parse(ChatWebView.ORIGIN), true)
            assertEquals("choice-1", received.last().getString("requestId"))
            assertFalse(received.last().has("apiKey"))
            assertFalse(received.last().has("baseUrl"))
            val selectionCount = received.size
            selection.remove("requestId")
            web.receive(selection.toString(), Uri.parse(ChatWebView.ORIGIN), true)
            web.receive("{\"type\":\"setThinkingLevel\",\"sessionId\":\"selected\",\"requestId\":\"choice-2\",\"thinkingLevel\":42}", Uri.parse(ChatWebView.ORIGIN), true)
            web.receive("{\"type\":\"openSettings\",\"page\":\"https://example.com\"}", Uri.parse(ChatWebView.ORIGIN), true)
            assertEquals(selectionCount, received.size)
            web.receive("{\"type\":\"setThinkingLevel\",\"sessionId\":\"selected\",\"requestId\":\"choice-2\",\"thinkingLevel\":\"high\"}", Uri.parse(ChatWebView.ORIGIN), true)
            assertEquals("high", received.last().getString("thinkingLevel"))
            web.receive("{\"type\":\"openSettings\",\"page\":\"models\",\"apiKey\":\"drop\"}", Uri.parse(ChatWebView.ORIGIN), true)
            assertEquals("models", received.last().getString("page"))
            assertFalse(received.last().has("apiKey"))
        }
        // Assets are opened on the instrumentation worker, matching Chromium's request callback.
        val web = requireNotNull(view)
        try {
            assertEquals(403, web.intercept(Uri.parse("https://example.com/tracker.js")).statusCode)
            assertEquals(403, web.intercept(Uri.parse("file:///data/data/io.bbui.assistant/files/config")).statusCode)
            assertEquals(403, web.intercept(Uri.parse("${ChatWebView.ORIGIN}/assets/runtime.zip")).statusCode)
            assertEquals(403, web.intercept(Uri.parse("${ChatWebView.ORIGIN}/assets/chat/%2e%2e/runtime.zip")).statusCode)
            val asset = web.intercept(Uri.parse(ChatWebView.ENTRY_URL))
            try { assertTrue(asset.responseHeaders["Content-Security-Policy"].orEmpty().contains("connect-src 'none'")) }
            finally { asset.data?.close() }
        } finally {
            instrumentation.runOnMainSync { web.dispose() }
        }
    }

    @Test(timeout = 45000) fun reloadRestoresOneCompleteSnapshotWithoutExecutingMessageText() = withSyntheticActivity { activity ->
        val web = find<ChatWebView>(activity.window.decorView)!!
        awaitPage(web)
        val text = "恢复后的中文🙂\n\n**完整回复**\n';window.__bbuiInjected=true;//"
        val snapshot = JSONObject().put("type", "snapshot").put("revision", 77).put("runId", "local-test").put("sessionId", "local-test")
            .put("status", JSONObject().put("phase", "idle").put("message", "本地回放"))
            .put("isRunning", false).put("hasOlder", false)
            .put("timing", JSONObject().put("nativeReceivedAtMs", 0).put("projectionAtMs", 0))
            .put("messages", JSONArray().put(JSONObject().put("id", "test-answer").put("role", "assistant").put("status", "complete")
                .put("parts", JSONArray().put(JSONObject().put("id", "test-text").put("type", "text").put("state", "complete").put("text", text)))))
        instrumentation.runOnMainSync { web.showSnapshot(snapshot) }
        awaitCondition { evaluate(web, "document.body.innerText.includes('恢复后的中文🙂')") == "true" }
        instrumentation.runOnMainSync { web.reload() }
        awaitPage(web)
        awaitCondition { evaluate(web, "document.body.innerText.includes('恢复后的中文🙂')") == "true" }
        awaitCondition { evaluate(web, "document.body.innerText.split('恢复后的中文🙂').length-1") == "1" }
        awaitCondition { evaluate(web, "window.__bbuiInjected===true") == "false" }
    }

    @Test(timeout = 45000) fun hiddenChatReceivesOnlyTheLatestSnapshotOnResume() = withSyntheticActivity { activity ->
        val web = find<ChatWebView>(activity.window.decorView)!!
        awaitPage(web)
        evaluate(web, "window.__received=[];window.addEventListener('bbui-message',e=>window.__received.push(e.detail.revision));true")
        instrumentation.runOnMainSync {
            web.setPresentationActive(false)
            for (revision in 100..199) web.showSnapshot(JSONObject().put("type", "snapshot")
                .put("revision", revision).put("sessionId", "hidden-test").put("runId", "")
                .put("messages", JSONArray()).put("isRunning", false).put("hasOlder", false)
                .put("status", JSONObject().put("phase", "idle").put("message", ""))
                .put("timing", JSONObject().put("nativeReceivedAtMs", 0).put("projectionAtMs", 0)))
        }
        SystemClock.sleep(200)
        assertEquals("0", evaluate(web, "window.__received.length"))
        instrumentation.runOnMainSync { web.setPresentationActive(true) }
        awaitCondition { evaluate(web, "JSON.stringify(window.__received)") == "\"[199]\"" }
    }

    @Test(timeout = 45000) fun composerFocusAndNativePreviewRemainUsable() = withActivity { activity ->
        val web = find<ChatWebView>(activity.window.decorView)!!
        awaitPage(web)
        evaluate(web, "document.querySelector('textarea').focus(); true")
        instrumentation.runOnMainSync {
            web.requestFocus()
            activity.getSystemService(InputMethodManager::class.java).showSoftInput(web, InputMethodManager.SHOW_IMPLICIT)
        }
        awaitCondition { evaluate(web, "document.activeElement.tagName==='TEXTAREA'") == "true" }
        awaitCondition { val visible = AtomicReference(false); instrumentation.runOnMainSync { visible.set(activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true) }; visible.get() }
        assertEquals("true", evaluate(web, "document.querySelector('textarea').getBoundingClientRect().bottom <= window.innerHeight + 1"))
        instrumentation.runOnMainSync {
            activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(web.windowToken, 0)
            web.evaluateJavascript("document.querySelector('.preview-entry').click()", null)
        }
        awaitCondition { val visible = AtomicReference(false); instrumentation.runOnMainSync { visible.set(findButton(activity.window.decorView, "执行画面菜单")?.isShown == true) }; visible.get() }
        instrumentation.runOnMainSync {
            assertNull(findButton(activity.window.decorView, "立即停止"))
            activity.onBackPressed()
            assertFalse(find<android.view.SurfaceView>(activity.window.decorView)!!.isShown)
            assertTrue(web.isShown)
        }
    }

    @Test(timeout = 60000) fun taskRecordShowsModelStateAndSurvivesWebViewReload() = withSyntheticActivity { activity ->
        val web = find<ChatWebView>(activity.window.decorView)!!
        awaitPage(web)
        val task = JSONObject().put("version", 1).put("id", "local-task-record").put("goal", "比较附近门店")
            .put("status", "waiting_user").put("summary", "已查看门店，等待尚未提供的偏好")
            .put("constraints", JSONArray().put(JSONObject().put("text", "附近且销量高").put("source", "user")))
            .put("facts", JSONArray().put("已展开购物车查看内容（本地模拟）"))
            .put("unknowns", JSONArray().put(JSONObject().put("text", "用户尚未选择饮品").put("resolveBy", "user")))
            .put("steps", JSONArray().put(JSONObject().put("id", "inspect").put("title", "查看购物车").put("status", "completed")))
            .put("question", "请选择饮品")
        val snapshot = chatSnapshot(900, JSONArray().put(chatMessage("task-answer", "assistant", "已查看购物车，请选择饮品。")), false)
            .put("task", task).put("taskUpdatedThisRun", true)
            .put("taskDisplay", JSONObject().put("state", "waiting_user").put("label", "等待你补充"))
        instrumentation.runOnMainSync { web.showSnapshot(snapshot) }
        awaitCondition { evaluate(web, "!!document.querySelector('.progress-button')") == "true" }
        awaitCondition { evaluate(web, "document.querySelectorAll('.task-progress').length") == "0" }
        tapElement(web, ".progress-button")
        awaitCondition { evaluate(web, "!!document.querySelector('[data-task-state=waiting_user]')") == "true" }
        assertEquals("true", evaluate(web, "document.querySelector('.task-progress').innerText.includes('请选择饮品')"))
        assertEquals("false", evaluate(web, "document.querySelector('.task-progress').innerText.includes('已展开购物车查看内容')"))
        saveChatScreenshot(web, "task-state-ui.png")
        instrumentation.runOnMainSync { web.reload() }
        awaitPage(web)
        awaitCondition { evaluate(web, "!!document.querySelector('.progress-button')") == "true" }
        awaitCondition { evaluate(web, "document.querySelectorAll('.task-progress').length") == "0" }
        tapElement(web, ".progress-button")
        awaitCondition { evaluate(web, "!!document.querySelector('[data-task-state=waiting_user]')") == "true" }
        // The model's last declaration remains available, but a new unanswered run
        // must not inherit it as the current task outcome.
        snapshot.put("revision", 901).put("taskUpdatedThisRun", false)
            .put("taskDisplay", JSONObject().put("state", "unconfirmed").put("label", "本轮回复结束 · 任务状态未确认"))
        instrumentation.runOnMainSync { web.showSnapshot(snapshot) }
        awaitCondition { evaluate(web, "!!document.querySelector('[data-task-state=unconfirmed]')") == "true" }
        assertEquals("false", evaluate(web, "!!document.querySelector('[data-task-state=completed]')"))
    }

    @Test(timeout = 45000) fun previewControlsFollowExecutionOwnerAndHandoffState() = withSyntheticActivity { activity ->
        val web = find<ChatWebView>(activity.window.decorView)!!
        awaitPage(web)
        @Suppress("UNCHECKED_CAST")
        val listener = MainActivity::class.java.getDeclaredField("chatListener").apply { isAccessible = true }.get(activity) as (JSONObject) -> Unit
        val snapshot = chatSnapshot(1000, JSONArray(), false).put("sessionId", "viewed-b")
            .put("environment", JSONObject().put("state", "ready").put("id", "synthetic-display").put("videoWidth", 1080).put("videoHeight", 2160))
            .put("execution", JSONObject().put("toolCallId", "step-1").put("intent", "点击打开订单列表"))
            .put("runningSessionId", "owner-a")
            .put("sessions", JSONArray().put(JSONObject().put("id", "owner-a").put("title", "任务 A"))
                .put(JSONObject().put("id", "viewed-b").put("title", "任务 B")))
        fun present(mode: String, resume: Boolean) {
            snapshot.put("revision", snapshot.getInt("revision") + 1)
                .put("control", JSONObject().put("id", "test-$mode").put("mode", mode).put("sessionId", "owner-a")
                    .put("canResume", resume).put("canSteer", mode == "running"))
            instrumentation.runOnMainSync { listener(snapshot) }
        }
        present("running", false)
        evaluate(web, "document.querySelector('.preview-entry').click(); true")
        awaitCondition { val shown = AtomicBoolean(false); instrumentation.runOnMainSync { shown.set(find<android.view.SurfaceView>(activity.window.decorView)!!.isShown) }; shown.get() }
        instrumentation.runOnMainSync {
            assertTrue(findButton(activity.window.decorView, "我来操作")!!.isEnabled)
            assertNull(findButton(activity.window.decorView, "立即停止"))
            assertNull(findButton(activity.window.decorView, "关闭执行画面"))
            assertTrue(flatten(activity.window.decorView).filterIsInstance<android.widget.TextView>().any { it.text.toString() == "点击打开订单列表" })
            val edge = find<ExecutionEdgeView>(activity.window.decorView)!!
            assertTrue(edge.isShown)
            assertFalse(edge.isClickable)
            val edgeBounds = android.graphics.Rect()
            val videoBounds = android.graphics.Rect()
            val controlBounds = android.graphics.Rect()
            edge.getGlobalVisibleRect(edgeBounds)
            find<android.view.SurfaceView>(activity.window.decorView)!!.getGlobalVisibleRect(videoBounds)
            findButton(activity.window.decorView, "我来操作")!!.getGlobalVisibleRect(controlBounds)
            assertEquals("The border surrounds only the video viewport", videoBounds, edgeBounds)
            assertFalse(android.graphics.Rect.intersects(edgeBounds, controlBounds))
            val image = io.bbui.core.PreviewViewport.fit(edge.width, edge.height, 1080, 2160)!!
            assertEquals(android.graphics.RectF(image.left.toFloat(), image.top.toFloat(),
                (image.left + image.width).toFloat(), (image.top + image.height).toFloat()), edge.pictureBounds())
        }
        instrumentation.uiAutomation.takeScreenshot()?.let { image ->
            val directory = File(instrumentation.targetContext.filesDir, "gate-evidence").apply { mkdirs() }
            File(directory, "preview-ai-edge.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        present("taking_over", false)
        instrumentation.runOnMainSync {
            assertFalse(findButton(activity.window.decorView, "正在交接")!!.isEnabled)
            assertTrue(find<ExecutionEdgeView>(activity.window.decorView)!!.isShown)
        }
        present("manual", true)
        instrumentation.runOnMainSync {
            assertFalse(find<ExecutionEdgeView>(activity.window.decorView)!!.isShown)
            assertTrue(findButton(activity.window.decorView, "交给 AI 继续")!!.isEnabled)
            assertNull(findButton(activity.window.decorView, "我来操作"))
        }
        present("manual", false)
        instrumentation.runOnMainSync { assertTrue(findButton(activity.window.decorView, "结束操作")!!.isEnabled) }
        present("idle", false)
        instrumentation.runOnMainSync {
            assertTrue(find<ExecutionEdgeView>(activity.window.decorView)!!.isShown)
            assertTrue(findButton(activity.window.decorView, "我来操作")!!.isEnabled)
            assertTrue(flatten(activity.window.decorView).filterIsInstance<android.widget.TextView>().any { it.text.toString() == "闲置" })
        }
        present("error", false)
        instrumentation.runOnMainSync {
            assertTrue(find<ExecutionEdgeView>(activity.window.decorView)!!.isShown)
            assertTrue("An idle error must still allow manual control", findButton(activity.window.decorView, "我来操作")!!.isEnabled)
        }
        present("stopped", true)
        instrumentation.runOnMainSync {
            assertTrue(findButton(activity.window.decorView, "继续任务")!!.isEnabled)
            for ((state, label) in listOf("creating" to "准备中", "invalid" to "未连接", "releasing" to "关闭中", "absent" to "未连接")) {
                snapshot.getJSONObject("environment").put("state", state)
                listener(snapshot)
                assertFalse("A $state environment must not display a stale frame", find<android.view.SurfaceView>(activity.window.decorView)!!.isShown)
                assertFalse(find<ExecutionEdgeView>(activity.window.decorView)!!.isShown)
                assertTrue(flatten(activity.window.decorView).filterIsInstance<android.widget.TextView>().any { it.text.toString() == "执行画面 · $label" })
            }
            snapshot.getJSONObject("environment").put("state", "ready")
            listener(snapshot)
            assertTrue(find<android.view.SurfaceView>(activity.window.decorView)!!.isShown)
            activity.onBackPressed()
            assertTrue(web.isShown)
        }
        // Synthetic presentation must not call takeOver/resume or send a model prompt.
    }

    @SdkSuppress(minSdkVersion = 31)
    @Test(timeout = 60000) fun rotationAndBackgroundRestoreServiceSnapshotAndPreview() {
        var activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            val executionService = serviceInstance()
            val snapshot = AtomicReference<JSONObject>()
            instrumentation.runOnMainSync {
                val listener: (JSONObject) -> Unit = { snapshot.set(it) }
                executionService.subscribeChat(listener)
                executionService.unsubscribeChat(listener)
            }
            assertFalse("Lifecycle test requires an idle assistant", snapshot.get().optBoolean("isRunning"))
            var web = find<ChatWebView>(activity.window.decorView)!!
            awaitPage(web)
            awaitCondition { evaluate(web, "document.querySelectorAll('.message').length") == snapshot.get().getJSONArray("messages").length().toString() }
            // Compare the actual service projection, including an empty new session. Never replace user history.
            val before = evaluate(web, "JSON.stringify(Array.from(document.querySelectorAll('.message')).map(x=>x.innerText))")
            evaluate(web, "document.querySelector('.preview-entry').click(); true")
            awaitCondition { val shown = AtomicBoolean(false); instrumentation.runOnMainSync { shown.set(findButton(activity.window.decorView, "执行画面菜单")!!.isShown) }; shown.get() }
            instrumentation.runOnMainSync {
                activity.requestedOrientation = if (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            activity = (monitor.waitForActivityWithTimeout(12000) as? MainActivity) ?: error("Rotation did not recreate MainActivity")
            assertSame("Activity rotation must retain the execution service", executionService, serviceInstance())
            web = find<ChatWebView>(activity.window.decorView)!!
            awaitPage(web)
            awaitCondition { evaluate(web, "JSON.stringify(Array.from(document.querySelectorAll('.message')).map(x=>x.innerText))") == before }
            instrumentation.runOnMainSync {
                assertTrue("Preview expansion should survive rotation", findButton(activity.window.decorView, "执行画面菜单")!!.isShown)
                assertNull(findButton(activity.window.decorView, "立即停止"))
                activity.onBackPressed()
            }
            val wasStopped = AtomicBoolean(false)
            val lifecycle = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStopped(stopped: Activity) { if (stopped === activity) wasStopped.set(true) }
                override fun onActivityCreated(created: Activity, state: Bundle?) = Unit
                override fun onActivityStarted(started: Activity) = Unit
                override fun onActivityResumed(resumed: Activity) = Unit
                override fun onActivityPaused(paused: Activity) = Unit
                override fun onActivitySaveInstanceState(saved: Activity, state: Bundle) = Unit
                override fun onActivityDestroyed(destroyed: Activity) = Unit
            }
            activity.application.registerActivityLifecycleCallbacks(lifecycle)
            // Some OEMs freeze the whole test process in the background. Schedule the user-like
            // return in an independent shell BEFORE backgrounding, with no app timer or policy change.
            val shell = instrumentation.uiAutomation.executeShellCommandRw("sh")
            var returnDiagnostic = "Return command has not completed"
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(shell[1]).use {
                    it.write("sleep 3\nam start -W -n io.bbui.assistant/.MainActivity 2>&1\nprintf '\\nBBUI_RETURN_STATUS=%s\\n' \$?\nprintf 'BBUI_RETURN_COMPLETE\\n'\n".toByteArray(Charsets.UTF_8))
                    it.flush()
                }
                instrumentation.runOnMainSync { assertTrue("Expected task to move to background", activity.moveTaskToBack(true)) }
                val returned = ParcelFileDescriptor.AutoCloseInputStream(shell[0]).bufferedReader().use { it.readText() }
                // This command only names our own Activity; retain its status/error output for diagnosis.
                returnDiagnostic = returned.take(4096)
                assertTrue("Independent return command failed: $returnDiagnostic", returned.contains("BBUI_RETURN_COMPLETE") && returned.contains("BBUI_RETURN_STATUS=0") && !returned.contains("Error:"))
                assertTrue("Activity must actually stop while backgrounded", wasStopped.get())
            } finally {
                runCatching { shell[0].close() }; runCatching { shell[1].close() }
                activity.application.unregisterActivityLifecycleCallbacks(lifecycle)
            }
            // Returning via the launcher may recreate the Activity again as device orientation changes.
            // The service and chat are the invariant, not the old Activity/WebView object identities.
            val resumed = AtomicReference<MainActivity?>()
            try {
                awaitCondition {
                    instrumentation.runOnMainSync {
                        resumed.set(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .filterIsInstance<MainActivity>().firstOrNull { it.hasWindowFocus() })
                    }
                    resumed.get() != null
                }
            } catch (error: AssertionError) {
                throw AssertionError("No focused RESUMED MainActivity after return: $returnDiagnostic", error)
            }
            activity = requireNotNull(resumed.get())
            web = find<ChatWebView>(activity.window.decorView)!!
            awaitPage(web)
            assertSame("Backgrounding must retain the execution service", executionService, serviceInstance())
            awaitCondition { evaluate(web, "JSON.stringify(Array.from(document.querySelectorAll('.message')).map(x=>x.innerText))") == before }
            awaitCondition { evaluate(web, "!!document.querySelector('textarea')") == "true" }
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED; activity.finish() }
        }
    }

    @Test(timeout = 90000) fun streamingPreservesReadingPositionAndCopiesMarkdown() = withSyntheticActivity { activity ->
        val web = find<ChatWebView>(activity.window.decorView)!!
        awaitPage(web)
        val messages = JSONArray()
        for (i in 0 until 24) messages.put(chatMessage("history-$i", "assistant", "这是第 ${i + 1} 条本地示例回复。\n\n向上阅读历史时，新内容应该继续生成，页面保持在你正在阅读的位置。"))
        val streaming = chatMessage("live-answer", "assistant", "正在整理最后一条回复。", "streaming")
        messages.put(streaming)
        val snapshot = chatSnapshot(500, messages, true)
        instrumentation.runOnMainSync { web.showSnapshot(snapshot) }
        awaitCondition { evaluate(web, "document.querySelectorAll('.message').length") == "25" }
        evaluate(web, "document.querySelector('.viewport').scrollTop=1e9; true")
        awaitCondition { evaluate(web, "(()=>{const v=document.querySelector('.viewport');return v.scrollHeight-v.clientHeight-v.scrollTop<4})()") == "true" }
        val viewport = JSONArray(evaluate(web, "(()=>{const r=document.querySelector('.viewport').getBoundingClientRect();return [r.left+r.width/2,r.top+r.height*.3,r.top+r.height*.8,innerWidth]})()"))
        swipe(web, viewport.getDouble(0), viewport.getDouble(1), viewport.getDouble(2), viewport.getDouble(3))
        awaitCondition { evaluate(web, "(()=>{const v=document.querySelector('.viewport');return v.scrollHeight-v.clientHeight-v.scrollTop>120})()") == "true" }
        // Let touch inertia finish before measuring the anchor.
        Thread.sleep(700)
        val readingTop = evaluate(web, "document.querySelector('.viewport').scrollTop").toDouble()
        val deltaText = "正在整理最后一条回复。\n\n" + (1..12).joinToString("\n\n") { "新片段 $it：中文和 Emoji 🙂 连续到达，仍然不会打断历史阅读。" }
        streaming.getJSONArray("parts").getJSONObject(0).put("text", deltaText)
        snapshot.put("revision", 501)
        instrumentation.runOnMainSync { web.showSnapshot(snapshot) }
        awaitCondition { evaluate(web, "document.body.innerText.includes('新片段 12')") == "true" }
        Thread.sleep(250)
        assertEquals("Streaming must not pull a reader to the bottom", readingTop, evaluate(web, "document.querySelector('.viewport').scrollTop").toDouble(), 3.0)
        tapElement(web, ".scroll-bottom")
        awaitCondition { evaluate(web, "(()=>{const v=document.querySelector('.viewport');return v.scrollHeight-v.clientHeight-v.scrollTop<4})()") == "true" }

        val answer = "已完成界面检查。\n\n- **正文** 连续显示，不重复追加。\n- 思考与手机操作各自折叠。\n- 可以随时接管或停止。\n\n```text\n中文与 Emoji 🙂 保持完整\n```"
        val sample = chatMessage("sample-step", "assistant", "先打开账单列表，核对每个月的用量。")
        sample.put("parts", JSONArray()
            .put(JSONObject().put("id", "reasoning").put("type", "reasoning").put("state", "complete").put("text", "先查看当前页面，再核对结果，避免重复操作。这是本地界面演示文字。").put("durationMs", 1800))
            .put(JSONObject().put("id", "text").put("type", "text").put("state", "complete").put("text", "先打开账单列表，核对每个月的用量。"))
            .put(JSONObject().put("id", "tool").put("type", "tool").put("state", "complete").put("toolCallId", "sample-tool").put("toolName", "phone_action").put("title", "点击打开账单列表").put("summary", "已获得执行画面（本地演示，无真实操作）")))
        val showcase = chatSnapshot(502, JSONArray().put(chatMessage("sample-user", "user", "帮我检查一下聊天界面。" )).put(sample)
            .put(chatMessage("sample-answer", "assistant", answer)), false)
        instrumentation.runOnMainSync { web.showSnapshot(showcase) }
        awaitCondition { evaluate(web, "document.querySelectorAll('.message').length===3 && document.body.innerText.includes('已完成界面检查')") == "true" }
        assertEquals("false", evaluate(web, "document.querySelector('.reasoning').open"))
        assertEquals("false", evaluate(web, "document.querySelector('.tool-card').open"))
        assertEquals("0", evaluate(web, "document.querySelectorAll('.execution-note').length"))
        assertEquals("0", evaluate(web, "document.querySelectorAll('.waiting').length"))
        evaluate(web, "document.querySelector('.viewport').scrollTop=0; true")
        Thread.sleep(300)
        saveChatScreenshot(web)

        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        val previous = AtomicReference<android.content.ClipData?>()
        instrumentation.runOnMainSync { previous.set(clipboard.primaryClip) }
        try {
            evaluate(web, "document.querySelector('[data-message-id=sample-answer] .copy-button').scrollIntoView({block:'center'}); true")
            tapElement(web, "[data-message-id=sample-answer] .copy-button")
            awaitCondition {
                val matches = AtomicReference(false)
                instrumentation.runOnMainSync { matches.set(clipboard.primaryClip?.getItemAt(0)?.text?.toString() == answer) }
                matches.get()
            }
            assertEquals("true", evaluate(web, "document.querySelector('[data-message-id=sample-answer] .copy-button').hasAttribute('data-copied')"))
        } finally {
            instrumentation.runOnMainSync { previous.get()?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip() }
        }
    }

    @Test(timeout = 45000) fun restoredIdleHistoryHasNoWaitingOrExpandedExecutionDetails() = withActivity { activity ->
        val web = find<ChatWebView>(activity.window.decorView)!!
        awaitPage(web)
        val snapshot = AtomicReference<JSONObject>()
        val executionService = serviceInstance()
        instrumentation.runOnMainSync {
            val listener: (JSONObject) -> Unit = { snapshot.set(it) }
            executionService.subscribeChat(listener); executionService.unsubscribeChat(listener)
        }
        assertFalse(snapshot.get().getBoolean("isRunning"))
        awaitCondition { evaluate(web, "document.querySelectorAll('.message').length") == snapshot.get().getJSONArray("messages").length().toString() }
        assertEquals("0", evaluate(web, "document.querySelectorAll('.waiting').length"))
        assertEquals("0", evaluate(web, "document.querySelectorAll('.reasoning[open],.execution-note[open]').length"))
    }

    private fun chatMessage(id: String, role: String, text: String, state: String = "complete") = JSONObject()
        .put("id", id).put("role", role).put("status", state)
        .put("parts", JSONArray().put(JSONObject().put("id", "text").put("type", "text").put("state", state).put("text", text)))
    private fun chatSnapshot(revision: Long, messages: JSONArray, running: Boolean) = JSONObject()
        .put("type", "snapshot").put("revision", revision).put("runId", "local-ui-sample").put("sessionId", "local-ui-sample")
        .put("status", JSONObject().put("phase", if (running) "running" else "idle").put("message", "本地界面演示"))
        .put("isRunning", running).put("hasOlder", false).put("messages", messages)
        .put("timing", JSONObject().put("nativeReceivedAtMs", 0).put("projectionAtMs", 0))
    private fun tapElement(web: ChatWebView, selector: String) {
        val point = JSONArray(evaluate(web, "(()=>{const r=document.querySelector(${JSONObject.quote(selector)}).getBoundingClientRect();return [r.left+r.width/2,r.top+r.height/2,innerWidth]})()"))
        gesture(web, point.getDouble(0), point.getDouble(1), point.getDouble(1), point.getDouble(2), 0)
    }
    private fun swipe(web: ChatWebView, x: Double, fromY: Double, toY: Double, cssWidth: Double) = gesture(web, x, fromY, toY, cssWidth, 12)
    private fun gesture(web: ChatWebView, x: Double, fromY: Double, toY: Double, cssWidth: Double, steps: Int) {
        val location = IntArray(2)
        val scale = AtomicReference(1.0)
        instrumentation.runOnMainSync { web.getLocationOnScreen(location); scale.set(web.width / cssWidth) }
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, y: Double) {
            val motion = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, (location[0] + x * scale.get()).toFloat(), (location[1] + y * scale.get()).toFloat(), 0)
            motion.source = InputDevice.SOURCE_TOUCHSCREEN
            try { instrumentation.sendPointerSync(motion) } finally { motion.recycle() }
        }
        event(MotionEvent.ACTION_DOWN, fromY)
        for (step in 1..steps) { Thread.sleep(25); event(MotionEvent.ACTION_MOVE, fromY + (toY - fromY) * step / steps) }
        Thread.sleep(50)
        event(MotionEvent.ACTION_UP, toY)
    }
    private fun saveChatScreenshot(web: ChatWebView, name: String = "chat-ui.png") {
        val rectangle = IntArray(4)
        instrumentation.runOnMainSync { val location = IntArray(2); web.getLocationOnScreen(location); rectangle[0] = location[0]; rectangle[1] = location[1]; rectangle[2] = web.width; rectangle[3] = web.height }
        val screen = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        // Keep only synthetic chat pixels; exclude native status, notifications, and every other app.
        val chat = Bitmap.createBitmap(screen, rectangle[0], rectangle[1], rectangle[2], rectangle[3])
        try {
            val file = File(instrumentation.targetContext.filesDir, "gate-evidence/$name")
            file.parentFile!!.mkdirs()
            file.outputStream().use { assertTrue(chat.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { if (chat !== screen) chat.recycle(); screen.recycle() }
    }

    private fun serviceInstance(): AssistantService {
        val ready = CountDownLatch(1)
        val result = AtomicReference<AssistantService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { result.set((binder as AssistantService.LocalBinder).service); ready.countDown() }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val context = instrumentation.targetContext
        assertTrue(context.bindService(Intent(context, AssistantService::class.java), connection, Context.BIND_AUTO_CREATE))
        try { assertTrue("Execution service unavailable", ready.await(5, TimeUnit.SECONDS)); return result.get() }
        finally { context.unbindService(connection) }
    }

    // Synthetic snapshots must not race with live catalog hydration or view-state flushes.
    // Lifecycle tests above continue to use the real service subscription.
    private fun withSyntheticActivity(action: (MainActivity) -> Unit) = withActivity { activity ->
        val service = serviceInstance()
        val serviceField = MainActivity::class.java.getDeclaredField("service").apply { isAccessible = true }
        awaitCondition { val connected = AtomicBoolean(false); instrumentation.runOnMainSync { connected.set(serviceField.get(activity) != null) }; connected.get() }
        @Suppress("UNCHECKED_CAST")
        val listener = MainActivity::class.java.getDeclaredField("chatListener").apply { isAccessible = true }.get(activity) as (JSONObject) -> Unit
        instrumentation.runOnMainSync { service.unsubscribeChat(listener) }
        action(activity)
    }

    private fun withActivity(action: (MainActivity) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try { action(activity) } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    private fun awaitPage(web: ChatWebView) = awaitCondition { evaluate(web, "!!document.querySelector('textarea')") == "true" }
    private fun evaluate(web: ChatWebView, script: String): String {
        val latch = CountDownLatch(1)
        val value = AtomicReference("")
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { value.set(it); latch.countDown() } }
        // Chromium may discard an evaluate callback when reload replaces its JS context.
        // The surrounding awaitCondition retries against the new context instead of failing early.
        if (!latch.await(1500, TimeUnit.MILLISECONDS)) return "<context-transition>"
        return value.get()
    }
    private fun awaitCondition(check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) { if (check()) return; Thread.sleep(100) }
        fail("UI condition timed out")
    }
    private inline fun <reified T : View> find(root: View): T? = flatten(root).filterIsInstance<T>().firstOrNull()
    private fun findButton(root: View, description: String) = flatten(root).filterIsInstance<Button>().firstOrNull { it.contentDescription == description }
    private fun flatten(root: View): List<View> = listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { flatten(root.getChildAt(it)) } else emptyList()
}
