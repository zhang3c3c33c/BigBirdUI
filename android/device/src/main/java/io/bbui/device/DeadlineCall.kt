package io.bbui.device

import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A vendor Binder/native implementation is not assumed to honor thread interruption. */
internal object DeadlineCall {
    class Uncertain(message: String, cause: Throwable) : IllegalStateException(message, cause)
    fun <T> run(label: String, timeoutMillis: Long, block: () -> T): T {
        val task = FutureTask(java.util.concurrent.Callable { block() })
        Thread(task, "bbui-deadline-$label").apply { isDaemon = true; start() }
        try { return task.get(timeoutMillis, TimeUnit.MILLISECONDS) }
        catch (error: TimeoutException) {
            task.cancel(true)
            throw Uncertain("$label 超时；原调用可能仍在执行", error)
        } catch (error: InterruptedException) {
            task.cancel(true); Thread.currentThread().interrupt()
            throw Uncertain("$label 等待已取消；原调用可能仍在执行", error)
        } catch (error: ExecutionException) { throw (error.cause ?: error) }
    }
}
