package com.lockphone.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinGateTest {
    private var now = 0L
    private val gate = PinGate(clock = { now })

    @Test
    fun `错 4 次仍可尝试`() {
        repeat(4) { gate.recordFailure() }
        assertTrue(gate.canAttempt())
    }

    @Test
    fun `错 5 次锁定 60 秒`() {
        repeat(5) { gate.recordFailure() }
        assertFalse(gate.canAttempt())
        assertEquals(60_000L, gate.remainingLockMs())
        now = 59_999
        assertFalse(gate.canAttempt())
        now = 60_000
        assertTrue(gate.canAttempt())
    }

    @Test
    fun `成功后计数清零`() {
        repeat(4) { gate.recordFailure() }
        gate.recordSuccess()
        repeat(4) { gate.recordFailure() }
        assertTrue(gate.canAttempt())
    }

    @Test
    fun `冷却结束后再错 5 次才再次锁定`() {
        repeat(5) { gate.recordFailure() }
        now = 60_000
        repeat(4) { gate.recordFailure() }
        assertTrue(gate.canAttempt())
        gate.recordFailure()
        assertFalse(gate.canAttempt())
    }

    @Test
    fun `initialLockedUntil 生效`() {
        val restoredGate = PinGate(clock = { now }, initialLockedUntil = 60_000L)
        now = 0
        assertFalse(restoredGate.canAttempt())
        now = 60_000
        assertTrue(restoredGate.canAttempt())
    }

    @Test
    fun `锁定触发时回调收到 lockedUntil`() {
        var lastValue = -1L
        val callbackGate = PinGate(clock = { now }, onLockedUntilChanged = { lastValue = it })
        repeat(5) { callbackGate.recordFailure() }
        assertEquals(60_000L, lastValue)
    }

    @Test
    fun `recordSuccess 回调收到 0`() {
        var lastValue = -1L
        val callbackGate = PinGate(clock = { now }, onLockedUntilChanged = { lastValue = it })
        repeat(5) { callbackGate.recordFailure() }
        callbackGate.recordSuccess()
        assertEquals(0L, lastValue)
    }
}
