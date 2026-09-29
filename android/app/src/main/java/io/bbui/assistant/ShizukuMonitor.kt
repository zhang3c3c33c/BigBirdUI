package io.bbui.assistant

import android.os.Handler
import android.os.Looper
import io.bbui.device.DevicePermission
import rikka.shizuku.Shizuku

enum class ShizukuAvailability { STOPPED, UNAUTHORIZED, READY }

/** Observe Binder/permission events; revalidate on foreground entry and before execution. */
class ShizukuMonitor(private val changed: (ShizukuAvailability) -> Unit,
    private val read: () -> ShizukuAvailability = ::current) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private var active = false
    private var previous: ShizukuAvailability? = null
    private val check = Runnable { if (active) refresh() }
    private val received = Shizuku.OnBinderReceivedListener { main.post(check) }
    private val dead = Shizuku.OnBinderDeadListener { main.post(check) }
    private val permission = Shizuku.OnRequestPermissionResultListener { _, _ -> main.post(check) }
    fun start() {
        if (active) return
        active = true
        Shizuku.addBinderReceivedListenerSticky(received); Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
        refresh()
    }
    internal fun refresh() {
        if (!active) return
        val state = read()
        if (state != previous) { previous = state; changed(state) }
    }
    override fun close() {
        active = false; main.removeCallbacks(check)
        Shizuku.removeBinderReceivedListener(received); Shizuku.removeBinderDeadListener(dead)
        Shizuku.removeRequestPermissionResultListener(permission)
    }
    companion object {
        fun current(): ShizukuAvailability = runCatching {
            if (!DevicePermission.available()) ShizukuAvailability.STOPPED
            else if (!DevicePermission.granted()) ShizukuAvailability.UNAUTHORIZED else ShizukuAvailability.READY
        }.getOrDefault(ShizukuAvailability.STOPPED)
        fun message(state: ShizukuAvailability) = when (state) {
            ShizukuAvailability.STOPPED -> "Shizuku 未启动"
            ShizukuAvailability.UNAUTHORIZED -> "Shizuku 未授权"
            ShizukuAvailability.READY -> "Shizuku 已就绪"
        }
    }
}
