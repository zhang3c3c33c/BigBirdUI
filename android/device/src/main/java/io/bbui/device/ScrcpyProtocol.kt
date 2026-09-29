package io.bbui.device

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Wire format pinned to upstream scrcpy 4.1; these packets also match BBUI's Python runtime. */
internal object ScrcpyProtocol {
    fun touch(action: Int, x: Int, y: Int, width: Int, height: Int, pointer: Long = -2): ByteArray = packet {
        writeByte(2); writeByte(action); writeLong(pointer); writeInt(x); writeInt(y)
        writeShort(width); writeShort(height); writeShort(if (action == 1) 0 else 65535)
        writeInt(0); writeInt(0)
    }
    fun key(code: Int, meta: Int = 0): ByteArray = packet {
        for (action in 0..1) { writeByte(0); writeByte(action); writeInt(code); writeInt(0); writeInt(meta) }
    }
    private fun packet(block: DataOutputStream.() -> Unit): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use(block)
        return buffer.toByteArray()
    }
}
