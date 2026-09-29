package io.bbui.assistant

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Point
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File

/** Debug-only side-effect-free target for display/input isolation checks. */
class TestTargetActivity : Activity() {
    private var count = 0
    private lateinit var input: EditText
    private lateinit var increment: Button
    private var lastPublished = ""
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        count = state?.getInt("count") ?: 0
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.rgb(230, 245, 235))
        }
        val targetDisplayId = display?.displayId ?: -1
        val displayText = TextView(this).apply {
            text = "BBUI 测试页 · Display $targetDisplayId"
            contentDescription = text
            textSize = 24f
        }
        val counter = TextView(this).apply { textSize = 32f }
        fun renderCount() {
            counter.text = "计数：$count"
            counter.contentDescription = "bbui_test_count_$count"
        }
        renderCount()
        root.addView(displayText)
        root.addView(counter)
        increment = Button(this).apply {
            text = "点击计数 +1"
            contentDescription = "bbui_test_increment"
            setOnClickListener { count++; renderCount(); publish() }
        }
        root.addView(increment, LinearLayout.LayoutParams(-1, 180))
        input = EditText(this).apply {
            hint = "中文输入测试"
            contentDescription = "bbui_test_input"
            isSingleLine = true
            setText(state?.getString("input").orEmpty())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(value: Editable?) { publish() }
            })
        }
        root.addView(input, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        root.viewTreeObserver.addOnGlobalLayoutListener { publish() }
        input.setOnFocusChangeListener { _, _ -> publish() }
    }
    @Suppress("DEPRECATION")
    private fun publish() {
        if (!::input.isInitialized || increment.width == 0) return
        val id = display?.displayId ?: return
        val buttonLocation = IntArray(2).also { increment.getLocationOnScreen(it) }
        val inputLocation = IntArray(2).also { input.getLocationOnScreen(it) }
        val size = Point().also { display?.getRealSize(it) }
        val state = JSONObject().put("displayId", id).put("count", count).put("text", input.text.toString())
            .put("width", size.x).put("height", size.y)
            .put("densityDpi", resources.displayMetrics.densityDpi)
            .put("inputFocused", input.hasFocus()).put("windowFocused", hasWindowFocus())
            .put("incrementX", buttonLocation[0] + increment.width / 2)
            .put("incrementY", buttonLocation[1] + increment.height / 2)
            .put("inputX", inputLocation[0] + input.width / 2)
            .put("inputY", inputLocation[1] + input.height / 2).toString()
        if (state == lastPublished) return
        val target = File(noBackupFilesDir, "test-target-$id.json")
        val temporary = File(noBackupFilesDir, "test-target-$id.tmp")
        temporary.writeText(state)
        check(temporary.renameTo(target)) { "Cannot publish test target state" }
        lastPublished = state
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("count", count)
        outState.putString("input", input.text.toString())
        super.onSaveInstanceState(outState)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        publish()
    }
}
