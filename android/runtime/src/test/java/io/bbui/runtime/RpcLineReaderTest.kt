package io.bbui.runtime

import java.io.IOException
import java.io.Reader
import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test

class RpcLineReaderTest {
    @Test fun preservesUnicodeAndCrLfAcrossChunks() {
        val input = "中文👋\u2028\u2029\r\nnext\nlast"
        val reader = RpcLineReader(object : Reader() {
            var index = 0
            override fun read(buffer: CharArray, off: Int, len: Int): Int {
                if (index == input.length) return -1
                buffer[off] = input[index++]; return 1
            }
            override fun close() {}
        })
        assertEquals("中文👋\u2028\u2029", reader.readLine())
        assertEquals("next", reader.readLine())
        assertEquals("last", reader.readLine())
        assertNull(reader.readLine())
    }
    @Test fun rejectsUnterminatedOversizeRecordBeforeReadingWholeInput() {
        var consumed = 0
        val reader = RpcLineReader(object : Reader() {
            override fun read(buffer: CharArray, off: Int, len: Int): Int {
                buffer.fill('x', off, off + len); consumed += len; return len
            }
            override fun close() {}
        }, 16384)
        assertThrows(IOException::class.java) { reader.readLine() }
        assertTrue(consumed <= 24576)
    }
    @Test fun acceptsExactLimitAndEmptyRecords() {
        val reader = RpcLineReader(StringReader("1234\n\nend"), 4)
        assertEquals("1234", reader.readLine()); assertEquals("", reader.readLine())
        assertEquals("end", reader.readLine()); assertNull(reader.readLine())
    }
}
