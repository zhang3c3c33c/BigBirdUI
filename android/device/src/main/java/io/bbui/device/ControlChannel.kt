package io.bbui.device

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.system.ErrnoException
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

internal class ControlChannel(private val descriptor: ParcelFileDescriptor) {
    class Failure(message: String) : java.io.IOException(message)
    private inline fun verify(condition: Boolean, message: () -> String) { if (!condition) throw Failure(message()) }
    private val closed = AtomicBoolean(false)
    private var sequence = 0L
    init {
        val flags = Os.fcntlInt(descriptor.fileDescriptor, OsConstants.F_GETFL, 0)
        Os.fcntlInt(descriptor.fileDescriptor, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
    }
    @Synchronized fun touch(action: Int, x: Int, y: Int, width: Int, height: Int, pointer: Long = -2) {
        write(ScrcpyProtocol.touch(action, x, y, width, height, pointer))
    }
    @Synchronized fun key(code: Int, meta: Int = 0) {
        write(ScrcpyProtocol.key(code, meta))
    }
    @Synchronized fun paste(text: String, beforeKey: () -> Unit = {}, guard: () -> Unit) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        sequence++
        send { writeByte(9); writeLong(sequence); writeByte(0); writeInt(bytes.size); write(bytes) }
        verify(read(1)[0].toInt() == 1 && ByteBuffer.wrap(read(8)).long == sequence) { "剪贴板 ACK 错误，未粘贴" }
        send { writeByte(8); writeByte(0) }
        verify(read(1)[0].toInt() == 0) { "剪贴板读回类型错误" }
        val length = ByteBuffer.wrap(read(4)).int
        verify(length in 0..262144) { "剪贴板数据长度错误" }
        verify(String(read(length), Charsets.UTF_8) == text) { "剪贴板读回不一致，未粘贴" }
        guard()
        beforeKey()
        key(279)
    }
    private fun send(block: DataOutputStream.() -> Unit) {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use(block)
        write(buffer.toByteArray())
    }
    private fun write(bytes: ByteArray) {
        var offset = 0
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (offset < bytes.size) {
            await(OsConstants.POLLOUT, deadline)
            try {
                val count = Os.write(descriptor.fileDescriptor, bytes, offset, bytes.size - offset)
                verify(count > 0) { "scrcpy 控制写入中断" }; offset += count
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.EAGAIN && error.errno != OsConstants.EINTR) throw error
            }
        }
    }
    private fun read(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (offset < size) {
            await(OsConstants.POLLIN, deadline)
            try {
                val count = Os.read(descriptor.fileDescriptor, bytes, offset, size - offset)
                verify(count > 0) { "scrcpy 控制连接断开" }; offset += count
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.EAGAIN && error.errno != OsConstants.EINTR) throw error
            }
        }
        return bytes
    }
    private fun await(eventsMask: Int, deadline: Long) {
        verify(!closed.get()) { "scrcpy 控制通道已关闭" }
        val remaining = deadline - SystemClock.elapsedRealtime()
        verify(remaining > 0) { "scrcpy 控制通道超时，禁止重放" }
        val poll = StructPollfd().apply { fd = descriptor.fileDescriptor; events = eventsMask.toShort() }
        verify(Os.poll(arrayOf(poll), remaining.toInt()) > 0) { "scrcpy 控制通道超时，禁止重放" }
        verify(poll.revents.toInt() and OsConstants.POLLNVAL == 0) { "scrcpy 控制通道失效" }
    }
    fun close() {
        if (closed.getAndSet(true)) return
        runCatching { Os.shutdown(descriptor.fileDescriptor, OsConstants.SHUT_RDWR) }
        runCatching { descriptor.close() }
    }
}
