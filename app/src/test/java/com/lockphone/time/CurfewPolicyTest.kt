package com.lockphone.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CurfewPolicyTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    /** 2023-11-14 当天 hour:minute 的时间戳 */
    private fun at(hour: Int, minute: Int): Long =
        ZonedDateTime.of(2023, 11, 14, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun min(hour: Int, minute: Int = 0): Int = hour * 60 + minute

    private fun input(
        now: Long,
        enabled: Boolean = true,
        windows: List<TimeWindow> = listOf(TimeWindow(min(8), min(20))),
        tempUnlockUntil: Long = 0L,
    ) = CurfewInput(now, zone, enabled, windows, tempUnlockUntil)

    @Test
    fun `总开关关闭时永不锁定`() {
        val out = CurfewPolicy.evaluate(input(now = at(3, 0), enabled = false))
        assertFalse(out.curfewActive)
    }

    @Test
    fun `窗口列表为空时永不锁定`() {
        val out = CurfewPolicy.evaluate(input(now = at(3, 0), windows = emptyList()))
        assertFalse(out.curfewActive)
    }

    @Test
    fun `非跨天窗口边界左闭右开`() {
        val w = listOf(TimeWindow(min(8), min(20)))
        assertTrue(CurfewPolicy.isCovered(min(8), w))
        assertTrue(CurfewPolicy.isCovered(min(19, 59), w))
        assertFalse(CurfewPolicy.isCovered(min(20), w))
        assertFalse(CurfewPolicy.isCovered(min(7, 59), w))
    }

    @Test
    fun `跨午夜窗口覆盖判断`() {
        val w = listOf(TimeWindow(min(22), min(6)))
        assertTrue(CurfewPolicy.isCovered(min(23), w))
        assertTrue(CurfewPolicy.isCovered(min(2), w))
        assertFalse(CurfewPolicy.isCovered(min(6), w))
        assertFalse(CurfewPolicy.isCovered(min(21, 59), w))
    }

    @Test
    fun `多窗口重叠取并集`() {
        val w = listOf(TimeWindow(min(8), min(12)), TimeWindow(min(11), min(14)))
        assertTrue(CurfewPolicy.isCovered(min(11, 30), w))
        assertTrue(CurfewPolicy.isCovered(min(13), w))
        assertFalse(CurfewPolicy.isCovered(min(14), w))
    }

    @Test
    fun `窗口外锁定生效`() {
        val out = CurfewPolicy.evaluate(input(now = at(21, 0)))
        assertTrue(out.curfewActive)
        assertFalse(out.tempUnlockActive)
    }

    @Test
    fun `窗口内不锁定`() {
        val out = CurfewPolicy.evaluate(input(now = at(10, 0)))
        assertFalse(out.curfewActive)
    }

    @Test
    fun `临时解锁生效期间不锁定`() {
        val out = CurfewPolicy.evaluate(input(now = at(21, 0), tempUnlockUntil = at(21, 30)))
        assertFalse(out.curfewActive)
        assertTrue(out.tempUnlockActive)
    }

    @Test
    fun `临时解锁过期后恢复锁定`() {
        val out = CurfewPolicy.evaluate(input(now = at(21, 0), tempUnlockUntil = at(20, 30)))
        assertTrue(out.curfewActive)
        assertFalse(out.tempUnlockActive)
    }

    @Test
    fun `窗口内且临时解锁未过期时自然可用`() {
        val out = CurfewPolicy.evaluate(input(now = at(10, 0), tempUnlockUntil = at(11, 0)))
        assertFalse(out.curfewActive)
        assertTrue(out.tempUnlockActive)
    }

    @Test
    fun `起止相同视为空区间`() {
        val w = listOf(TimeWindow(min(9), min(9)))
        assertFalse(CurfewPolicy.isCovered(min(9), w))
        val out = CurfewPolicy.evaluate(input(now = at(9, 0), windows = w))
        assertTrue(out.curfewActive)
    }

    @Test
    fun `下个可用时刻-窗口开始前返回今天开始点`() {
        val next = CurfewPolicy.nextAvailableInstant(at(7, 0), zone, listOf(TimeWindow(min(8), min(20))))
        assertEquals(at(8, 0), next)
    }

    @Test
    fun `下个可用时刻-今天已过返回明天开始点`() {
        val next = CurfewPolicy.nextAvailableInstant(at(21, 0), zone, listOf(TimeWindow(min(8), min(20))))
        assertEquals(at(8, 0) + 24 * 3600_000L, next)
    }

    @Test
    fun `下个可用时刻-跨午夜窗口gap内返回今天开始点`() {
        val next = CurfewPolicy.nextAvailableInstant(at(20, 0), zone, listOf(TimeWindow(min(22), min(6))))
        assertEquals(at(22, 0), next)
    }

    @Test
    fun `下个可用时刻-多窗口取最近边界`() {
        val windows = listOf(TimeWindow(min(9), min(10)), TimeWindow(min(8), min(8, 30)))
        val next = CurfewPolicy.nextAvailableInstant(at(7, 0), zone, windows)
        assertEquals(at(8, 0), next)
    }

    @Test
    fun `下个可用时刻-窗口为空返回当前时刻`() {
        assertEquals(at(7, 0), CurfewPolicy.nextAvailableInstant(at(7, 0), zone, emptyList()))
    }

    @Test
    fun `下个可用时刻-空区间窗口不参与计算`() {
        val windows = listOf(TimeWindow(min(9), min(9)), TimeWindow(min(10), min(12)))
        assertEquals(at(10, 0), CurfewPolicy.nextAvailableInstant(at(7, 0), zone, windows))
        // 全部是空区间：没有真实进入点，返回当前时刻（与空列表一致）
        assertEquals(at(7, 0), CurfewPolicy.nextAvailableInstant(at(7, 0), zone, listOf(TimeWindow(min(9), min(9)))))
    }
}
