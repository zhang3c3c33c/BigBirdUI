package io.bbui.device

import android.content.pm.PackageManager
import android.app.Activity
import rikka.shizuku.Shizuku

object DevicePermission {
    fun available(): Boolean = Shizuku.pingBinder()
    fun granted(): Boolean = available() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    fun request(code: Int = 7301) {
        check(available()) { "Shizuku 未启动，请先在 Shizuku 中启动服务" }
        Shizuku.requestPermission(code)
    }
    fun request(activity: Activity) { request() }
}

object ShizukuAccess {
    fun granted(): Boolean = DevicePermission.granted()
    fun request(activity: Activity) { DevicePermission.request() }
}
