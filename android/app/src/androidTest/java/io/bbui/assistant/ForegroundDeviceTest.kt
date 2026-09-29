package io.bbui.assistant

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before

/** Keep the test host foreground: some OEMs freeze instrumentation-only background processes. */
abstract class ForegroundDeviceTest {
    private var host: Activity? = null
    @Before fun foregroundHost() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        host = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.runOnMainSync { host?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    @After fun finishHost() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { host?.finish() }
    }
}
