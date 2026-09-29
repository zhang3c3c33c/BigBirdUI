package io.bbui.device

import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Process

/** Acquires the current user's provider with shell attribution, without a display. */
internal class ShellProvider(val client: ContentProviderClient, private val release: () -> Unit) : AutoCloseable {
    val resolver = ContentResolver.wrap(client)
    override fun close() { try { runCatching { client.close() } } finally { runCatching(release) } }
    companion object {
        fun open(context: Context, user: Int, authority: String): ShellProvider {
            check(Process.myUid() == 2000) { "系统工具需要Shizuku shell UID2000" }
            val amClass = Class.forName("android.app.ActivityManager")
            val manager = amClass.getDeclaredMethod("getService").invoke(null)
            val api = Class.forName("android.app.IActivityManager")
            val token = Binder()
            val acquire = api.methods.firstOrNull { it.name == "getContentProviderExternal" && it.parameterTypes.size == 4 }
                ?: error("此设备不支持系统Provider连接")
            val holder = acquire.invoke(manager, authority, user, token, "bbui-system")
                ?: error("此设备未提供所需 Provider：$authority")
            val release = {
                api.getMethod("removeContentProviderExternalAsUser", String::class.java, IBinder::class.java, Int::class.javaPrimitiveType)
                    .invoke(manager, authority, token, user)
                Unit
            }
            try {
                val provider = holder.javaClass.getField("provider").get(holder)
                val constructor = ContentProviderClient::class.java.getDeclaredConstructor(ContentResolver::class.java,
                    Class.forName("android.content.IContentProvider"), Boolean::class.javaPrimitiveType)
                constructor.isAccessible = true
                val client = constructor.newInstance(context.contentResolver, provider, true)
                return ShellProvider(client, release)
            } catch (error: Throwable) { runCatching(release); throw error }
        }
    }
}
