package com.lockphone.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HexTest {
    @Test
    fun `hex 往返一致`() {
        val bytes = byteArrayOf(0, 1, 15, 16, 127, -1, -128)
        assertArrayEquals(bytes, bytes.toHex().hexToBytes())
    }

    @Test
    fun `toHex 输出小写两位`() {
        assertEquals("00ff10", byteArrayOf(0, -1, 16).toHex())
    }
}
