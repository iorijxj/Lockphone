package com.lockphone.time

import java.time.Instant
import java.time.ZoneId

/** 一条可用时段，以「一天中的第几分钟」表示（0..1439）。start > end 表示跨午夜；start == end 视为空区间。 */
data class TimeWindow(val startMinute: Int, val endMinute: Int)

/** 时段判定的一次评估输入（全部来自持久化 + 当前时刻），纯数据、无 Android 依赖。 */
data class CurfewInput(
    val nowMillis: Long,
    val zoneId: ZoneId,
    val enabled: Boolean,
    val windows: List<TimeWindow>,
    /** 临时解锁截止的绝对时间戳；0 = 无临时解锁 */
    val tempUnlockUntilMillis: Long,
)

data class CurfewOutput(
    /** true = 当前为非可用时段，需要锁定 */
    val curfewActive: Boolean,
    /** 临时解锁当前是否生效 */
    val tempUnlockActive: Boolean,
)

object CurfewPolicy {

    /** 当前分钟是否落在任一可用时段内（并集，区间左闭右开） */
    fun isCovered(minuteOfDay: Int, windows: List<TimeWindow>): Boolean =
        windows.any { w ->
            if (w.startMinute <= w.endMinute) {
                minuteOfDay >= w.startMinute && minuteOfDay < w.endMinute
            } else {
                minuteOfDay >= w.startMinute || minuteOfDay < w.endMinute
            }
        }

    fun evaluate(input: CurfewInput): CurfewOutput {
        val zdt = Instant.ofEpochMilli(input.nowMillis).atZone(input.zoneId)
        val minute = zdt.hour * 60 + zdt.minute
        val curfewByWindow = input.enabled && input.windows.isNotEmpty() && !isCovered(minute, input.windows)
        val tempUnlockActive = input.tempUnlockUntilMillis > input.nowMillis
        return CurfewOutput(
            curfewActive = curfewByWindow && !tempUnlockActive,
            tempUnlockActive = tempUnlockActive,
        )
    }

    /**
     * 从 now 起最近的「进入可用时段」边界时刻。
     * 候选 = 今天/明天的 00:00 + startMinute 中所有严格大于 now 的最小值（时段按天循环，24 小时内必有边界）；
     * windows 为空视为全天可用，返回 nowMillis。
     */
    fun nextAvailableInstant(nowMillis: Long, zoneId: ZoneId, windows: List<TimeWindow>): Long {
        // 空区间窗口（start == end）永不可用，其 startMinute 不是真实的进入点，排除
        val real = windows.filter { it.startMinute != it.endMinute }
        if (real.isEmpty()) return nowMillis
        val startOfDay = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate().atStartOfDay(zoneId)
        return real
            .flatMap { w ->
                listOf(0L, 1L).map { day ->
                    startOfDay.plusDays(day).plusMinutes(w.startMinute.toLong()).toInstant().toEpochMilli()
                }
            }
            .filter { it > nowMillis }
            .minOrNull() ?: nowMillis
    }
}
