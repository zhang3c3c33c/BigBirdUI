package io.bbui.assistant

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import io.bbui.device.DevicePermission

/** Required setup only; optional permissions remain in Settings. Never connects a phone or model. */
class OnboardingActivity : AppCompatActivity() {
    private val permissionMonitor = ShizukuMonitor({ if (!isFinishing && !isDestroyed) render() })
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = enter() })
    }
    override fun onResume() { super.onResume(); permissionMonitor.start(); render() }
    override fun onPause() { permissionMonitor.close(); super.onPause() }
    private fun enter() {
        if (ShizukuMonitor.current() != ShizukuAvailability.READY || !OnboardingState.hasModel(this)) return
        OnboardingState.complete(this)
        if (intent.getBooleanExtra("launchMain", false)) startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(UiStyle.background)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom); WindowInsets.CONSUMED
            }
        }
        val contentWidth = dp(minOf(resources.configuration.screenWidthDp, 560))
        fun label(value: String, size: Float, muted: Boolean = false) = TextView(this).apply {
            text = value; textSize = size; setTextColor(if (muted) UiStyle.muted else UiStyle.text)
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        fun button(label: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
            text = label; contentDescription = label; UiStyle.button(this, primaryAction = primary, quiet = !primary)
            setOnClickListener { try { action() } catch (_: Exception) { Toast.makeText(this@OnboardingActivity, "无法打开，请从系统设置中操作", Toast.LENGTH_LONG).show() } }
        }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(36), dp(24), dp(24)) }
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(form) }, LinearLayout.LayoutParams(contentWidth, 0, 1f))
        form.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_bbui_mark)
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(64), dp(68)).apply { bottomMargin = dp(20) })
        form.addView(label(getString(R.string.brand_full_name), 26f).apply { setTypeface(typeface, Typeface.BOLD) },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(28) })
        fun setupCard(title: String, description: String, action: android.view.View) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(20), dp(12), dp(20))
                background = UiStyle.shape(this@OnboardingActivity, UiStyle.surface, 16, stroke = true)
            }
            val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            copy.addView(label(title, 17f).apply { setTypeface(typeface, Typeface.BOLD) })
            copy.addView(label(description, 13f, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            card.addView(copy, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
            card.addView(action, LinearLayout.LayoutParams(-2, -2))
            form.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
        val available = DevicePermission.available(); val authorized = DevicePermission.granted()
        val permissionAction = if (authorized) label("✓", 22f).apply {
            setTextColor(UiStyle.primary); setPadding(dp(14), 0, dp(14), 0); contentDescription = "已完成"
        } else button(if (available) "授权" else "打开") {
            if (available) DevicePermission.request()
            else startActivity(packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
        }
        setupCard("手机控制", if (authorized) "Shizuku 已授权" else if (available) "允许 Shizuku 观察和操作手机" else "先在 Shizuku 中启动服务", permissionAction)
        val configured = OnboardingState.hasModel(this)
        setupCard("模型", if (configured) "默认模型已配置" else "添加供应商并选择模型",
            button(if (configured) "管理模型" else "添加模型") { startActivity(Intent(this, SettingsActivity::class.java).putExtra("page", "models").putExtra("fromOnboarding", true)) })
        val ready = authorized && configured
        val footer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(16)) }
        footer.addView(label("手机操作在本地执行。任务文字和必要截图会发送至所选模型 API。", 12f, true),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        footer.addView(button("开始使用", primary = true) { enter() }.apply { isEnabled = ready; alpha = if (ready) 1f else 0.4f },
            LinearLayout.LayoutParams(-1, -2))
        root.addView(footer, LinearLayout.LayoutParams(contentWidth, -2))
        setContentView(root)
        root.requestApplyInsets()
    }
    override fun onDestroy() { permissionMonitor.close(); super.onDestroy() }
    private fun dp(value: Int) = UiStyle.dp(this, value)
}
