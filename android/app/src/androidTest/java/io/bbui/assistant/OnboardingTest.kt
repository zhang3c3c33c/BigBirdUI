package io.bbui.assistant

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OnboardingTest : ForegroundDeviceTest() {
    @Test fun permissionLossPausesWaitingWorkAndRecoveryNeverRestartsIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val directory = File(base.cacheDir, "permission-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = File(directory, "no-backup").apply { mkdirs() }
            override fun getFilesDir(): File = File(directory, "files").apply { mkdirs() }
        }
        var coordinator: AppCoordinator? = null
        var controller: SessionController? = null
        try {
            instrumentation.runOnMainSync {
                val host = AppCoordinator(context) {}
                coordinator = host
                val isolated = SessionController(context, host) {}
                controller = isolated
                isolated.state.queue.add(JSONObject().put("id", "permission-fixture").put("sessionId", "not-a-pi-session").put("text", "never execute"))
                isolated.permissionChanged(ShizukuAvailability.STOPPED)
                assertTrue(isolated.state.paused); assertEquals(1, isolated.state.queue.size)
                isolated.permissionChanged(ShizukuAvailability.READY)
                assertTrue(isolated.state.paused); assertEquals(1, isolated.state.queue.size); assertNull(isolated.state.running)
            }
        } finally {
            instrumentation.runOnMainSync { controller?.close(); coordinator?.close() }
            // The fixture's final AtomicFile write runs on its private disk executor.
            Thread.sleep(200)
            directory.deleteRecursively()
        }
    }
    @Test fun recurringPermissionChangesAreObservedAfterSetupAndRecoveryDoesNotEmitOperations() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val states = mutableListOf<ShizukuAvailability>()
        var available = ShizukuAvailability.READY
        val monitor = ShizukuMonitor({ states.add(it) }, { available })
        instrumentation.runOnMainSync {
            monitor.start()
            available = ShizukuAvailability.STOPPED; monitor.refresh()
            available = ShizukuAvailability.UNAUTHORIZED; monitor.refresh()
            available = ShizukuAvailability.READY; monitor.refresh(); monitor.refresh()
            monitor.close()
            available = ShizukuAvailability.STOPPED; monitor.refresh()
            assertEquals(listOf(ShizukuAvailability.READY, ShizukuAvailability.STOPPED, ShizukuAvailability.UNAUTHORIZED, ShizukuAvailability.READY), states)
            monitor.start(); monitor.close()
            assertEquals(ShizukuAvailability.STOPPED, states.last())
        }
    }
    @Test fun freshInstallResumesUntilSkippedAndExistingInstallIsNotInterrupted() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "onboarding-test-${UUID.randomUUID()}"
        val directory = File(base.cacheDir, name).apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getNoBackupFilesDir(): File = directory
            override fun getSharedPreferences(key: String, mode: Int): SharedPreferences = base.getSharedPreferences("$name-$key", mode)
        }
        try {
            assertTrue(OnboardingState.shouldShow(context))
            File(directory, "conversations.json").writeText("{}")
            assertTrue("Setup-created history cannot masquerade as an upgrade", OnboardingState.shouldShow(context))
            OnboardingState.complete(context)
            assertFalse(OnboardingState.shouldShow(context))
            context.getSharedPreferences("onboarding", Context.MODE_PRIVATE).edit().clear().commit()
            assertFalse("A previous installation skips the new wizard", OnboardingState.shouldShow(context))
            File(directory, "conversations.json").delete()
            assertFalse(OnboardingState.shouldShow(context))
        } finally { base.deleteSharedPreferences("$name-onboarding"); directory.deleteRecursively() }
    }
    @Test fun guideDoesNotRequestPermissionsAutomaticallyAndReusesModelSettings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, OnboardingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as OnboardingActivity
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        try {
            instrumentation.runOnMainSync {
                assertTrue(activity.hasWindowFocus())
                val buttons = descendants(activity.window.decorView).filterIsInstance<Button>()
                val ready = io.bbui.device.DevicePermission.granted() && OnboardingState.hasModel(activity)
                assertFalse(buttons.any { it.text == "稍后设置" })
                assertEquals(ready, buttons.first { it.text == "开始使用" }.isEnabled)
                assertTrue(buttons.any { it.text == "管理模型" || it.text == "添加模型" })
                assertTrue(buttons.any { it.text == "开始使用" })
            }
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            val directory = File(instrumentation.targetContext.filesDir, "gate-evidence").apply { mkdirs() }
            File(directory, "onboarding.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
