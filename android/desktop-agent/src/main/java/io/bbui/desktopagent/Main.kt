package io.bbui.desktopagent

import android.os.Looper
import android.os.ParcelFileDescriptor
import io.bbui.device.DeviceService
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONObject

/** ADB shell entrypoint. Reuses the APK's executor without installing or binding the app. */
object Main {
    @JvmStatic fun main(args: Array<String>) {
        check(android.os.Process.myUid() == 2000) { "BBUI requires the ADB shell user" }
        Looper.prepareMainLooper()
        val service = DeviceService()
        val output = System.out
        val executor = Executors.newSingleThreadExecutor()
        fun reply(id: String, data: JSONObject) = synchronized(output) {
            output.println("BBUI " + JSONObject().put("id", id).put("data", data)); output.flush()
        }
        Thread({
            try {
                service.initializeSupport(ParcelFileDescriptor.open(File(args.single()), ParcelFileDescriptor.MODE_READ_ONLY))
                reply("ready", JSONObject().put("uid", android.os.Process.myUid()))
                System.`in`.bufferedReader().forEachLine { line ->
                    require(line.length <= 120000) { "Request too large" }
                    val request = JSONObject(line)
                    val id = request.getString("id")
                    when (request.getString("type")) {
                        "stop" -> { service.setSystemStopped(request.getLong("epoch")); reply(id, JSONObject().put("stopped", true)) }
                        "begin" -> { service.beginSystemActions(request.getLong("epoch")); reply(id, JSONObject().put("stopped", false)) }
                        "system" -> executor.execute {
                            try { reply(id, JSONObject(service.system(request.getJSONObject("body").toString()))) }
                            catch (error: Throwable) { reply(id, JSONObject().put("错误", error.message).put("执行", JSONObject().put("状态", "未知"))) }
                        }
                        else -> reply(id, JSONObject().put("错误", "Unknown host operation"))
                    }
                }
            } catch (error: Throwable) {
                synchronized(output) {
                    output.println("BBUI " + JSONObject().put("id", "ready").put("error", error.message ?: error.javaClass.simpleName))
                    output.flush()
                }
            } finally {
                service.setSystemStopped(Long.MAX_VALUE)
                executor.shutdownNow()
                service.destroy()
            }
        }, "bbui-host-input").start()
        Looper.loop()
    }
}
