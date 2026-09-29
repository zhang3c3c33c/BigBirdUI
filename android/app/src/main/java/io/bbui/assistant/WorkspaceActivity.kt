package io.bbui.assistant

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/** Neutral landing page for the execution display in release builds. */
class WorkspaceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(UiStyle.muted)
            setBackgroundColor(UiStyle.background)
        })
    }
}
