package com.lockphone.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class TimeAccountantTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val day1 = 1_700_000_000_000L // 2023-11-14 22:13 +08
    private val day1Date = TimeAccountant.today(day1, zone)

    private fun input(
        now: Long = day1,
        date: String = day1Date,
        used: Map<String, Int> = emptyMap(),
        bonus: Map<String, Int> = emptyMap(),
        suspended: Set<String> = emptySet(),
        quota: Map<String, Int> = mapOf("com.game" to 30),
        fg: String? = "com.game",
        tick: Int = 5,
    ) = TickInput(now, zone, date, used, bonus, suspended, quota, fg, tick)

    @Test
    fun `前台受限应用累加秒数`() {
        val out = TimeAccountant.tick(input(used = mapOf("com.game" to 10)))
        assertEquals(15, out.usedSeconds["com.game"])
        assertTrue(out.changed)
        assertTrue(out.toSuspend.isEmpty())
    }

    @Test
    fun `非限时应用不计时`() {
        val out = TimeAccountant.tick(input(quota = emptyMap()))
        assertFalse(out.changed)
        assertNull(out.usedSeconds["com.game"])
    }

    @Test
    fun `无前台应用不计时`() {
        val out = TimeAccountant.tick(input(fg = null))
        assertFalse(out.changed)
    }

    @Test
    fun `超额触发挂起`() {
        // 配额 30 分钟=1800 秒，已用 1799，再加 5 秒越界
        val out = TimeAccountant.tick(input(used = mapOf("com.game" to 1799)))
        assertEquals(setOf("com.game"), out.toSuspend)
        assertTrue("com.game" in out.suspended)
        assertTrue(out.changed)
    }

    @Test
    fun `加时延长后不再挂起`() {
        // 已用 1799，配额 30 分钟，但加时 60 秒 → 有效 1860 秒，仍有余量
        val out = TimeAccountant.tick(
            input(used = mapOf("com.game" to 1799), bonus = mapOf("com.game" to 60)),
        )
        assertTrue(out.toSuspend.isEmpty())
        assertEquals(1804, out.usedSeconds["com.game"])
    }

    @Test
    fun `已挂起应用不再累加`() {
        val out = TimeAccountant.tick(
            input(used = mapOf("com.game" to 1800), suspended = setOf("com.game")),
        )
        assertFalse(out.changed)
        assertTrue(out.toSuspend.isEmpty())
    }

    @Test
    fun `剩余进入 5 分钟档触发一次预警`() {
        // 配额 30 分钟=1800 秒；已用 1495（剩 305=5min01s，bucket=6），+5 → 剩 300（bucket=5）
        val out = TimeAccountant.tick(input(used = mapOf("com.game" to 1495)))
        assertEquals("com.game", out.warnPkg)
        assertEquals(5, out.warnRemainingMin)
    }

    @Test
    fun `同一分钟档内不重复预警`() {
        // 已用 1500（剩 300 bucket=5），+5 → 剩 295（bucket=5），档位没变，不预警
        val out = TimeAccountant.tick(input(used = mapOf("com.game" to 1500)))
        assertNull(out.warnPkg)
    }

    @Test
    fun `剩余大于 5 分钟不预警`() {
        val out = TimeAccountant.tick(input(used = mapOf("com.game" to 100)))
        assertNull(out.warnPkg)
    }

    @Test
    fun `跨天清零并解挂`() {
        val nextDay = day1 + 24 * 3600 * 1000L
        val out = TimeAccountant.tick(
            input(
                now = nextDay,
                used = mapOf("com.game" to 1800),
                bonus = mapOf("com.game" to 60),
                suspended = setOf("com.game"),
            ),
        )
        assertEquals(TimeAccountant.today(nextDay, zone), out.date)
        assertTrue(out.usedSeconds.isEmpty())
        assertTrue(out.bonusSeconds.isEmpty())
        assertTrue(out.suspended.isEmpty())
        assertEquals(setOf("com.game"), out.toUnsuspend)
        assertTrue(out.changed)
    }
}
