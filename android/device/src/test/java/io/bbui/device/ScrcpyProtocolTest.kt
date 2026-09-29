package io.bbui.device

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ScrcpyProtocolTest {
    private fun hex(value: String): ByteArray = value.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun touchMatchesPinnedPythonFixture() {
        assertArrayEquals(hex("02 00 fffffffffffffffe 0000000c 00000022 0438 0780 ffff 00000000 00000000"),
            ScrcpyProtocol.touch(0, 12, 34, 1080, 1920))
    }

    @Test fun fingerReleaseHasZeroPressureAndPreservesPointer() {
        assertArrayEquals(hex("02 01 0000000000000001 00000064 000000c8 0780 0438 0000 00000000 00000000"),
            ScrcpyProtocol.touch(1, 100, 200, 1920, 1080, 1))
    }

    @Test fun backIsDownAndUpWithBigEndianKeycode() {
        assertArrayEquals(hex("00 00 00000004 00000000 00000000 00 01 00000004 00000000 00000000"),
            ScrcpyProtocol.key(4))
    }
}
