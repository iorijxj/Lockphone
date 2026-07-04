package com.lockphone.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHasherTest {
    @Test
    fun `同一 PIN 同一盐哈希一致`() {
        val salt = ByteArray(16) { it.toByte() }
        assertEquals(PinHasher.hash("1234", salt), PinHasher.hash("1234", salt))
    }

    @Test
    fun `不同盐哈希不同`() {
        val saltA = ByteArray(16) { 0 }
        val saltB = ByteArray(16) { 1 }
        assertNotEquals(PinHasher.hash("1234", saltA), PinHasher.hash("1234", saltB))
    }

    @Test
    fun `verify 正确 PIN 通过 错误 PIN 拒绝`() {
        val salt = PinHasher.newSalt()
        val hash = PinHasher.hash("1234", salt)
        assertTrue(PinHasher.verify("1234", salt, hash))
        assertFalse(PinHasher.verify("0000", salt, hash))
    }

    @Test
    fun `newSalt 长度 16 且两次不同`() {
        val a = PinHasher.newSalt()
        val b = PinHasher.newSalt()
        assertEquals(16, a.size)
        assertFalse(a.contentEquals(b))
    }
}
