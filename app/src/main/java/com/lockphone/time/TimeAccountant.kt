package com.lockphone.time

import java.time.Instant
import java.time.ZoneId

/** 计时账本的一次 tick 输入（全部来自持久化 + 前台探测 + 屏幕状态），纯数据、无 Android 依赖。 */
data class TickInput(
    val nowMillis: Long,
    val zoneId: ZoneId,
    val storedDate: String,
    val usedSeconds: Map<String, Int>,
    val bonusSeconds: Map<String, Int>,
    val suspended: Set<String>,
    val quotaMinutes: Map<String, Int>,
    /** 当前前台受限应用包名；息屏、无前台、非限时应用、本 APP 自身时传 null */
    val foregroundPkg: String?,
    val tickSeconds: Int,
)

/** 一次 tick 的决策结果：新状态 + 需要执行的副作用（挂起/解挂/预警）。 */
data class TickOutput(
    val date: String,
    val usedSeconds: Map<String, Int>,
    val bonusSeconds: Map<String, Int>,
    val suspended: Set<String>,
    val toSuspend: Set<String>,
    val toUnsuspend: Set<String>,
    /** 本 tick 需要 Toast 预警的应用；无预警为 null */
    val warnPkg: String?,
    /** 预警时剩余分钟数（1..5） */
    val warnRemainingMin: Int,
    /** 状态是否变化、需要落盘 */
    val changed: Boolean,
)

object TimeAccountant {
    private const val WARN_START_MIN = 5

    fun today(nowMillis: Long, zoneId: ZoneId): String =
        Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate().toString()

    private fun ceilMinutes(seconds: Int): Int =
        if (seconds <= 0) 0 else (seconds + 59) / 60

    fun tick(input: TickInput): TickOutput {
        val today = today(input.nowMillis, input.zoneId)

        // 跨天：清零计数与加时、解挂所有因配额挂起的应用
        if (today != input.storedDate) {
            return TickOutput(
                date = today,
                usedSeconds = emptyMap(),
                bonusSeconds = emptyMap(),
                suspended = emptySet(),
                toSuspend = emptySet(),
                toUnsuspend = input.suspended,
                warnPkg = null,
                warnRemainingMin = 0,
                changed = true,
            )
        }

        val pkg = input.foregroundPkg
        val unchanged = TickOutput(
            date = today,
            usedSeconds = input.usedSeconds,
            bonusSeconds = input.bonusSeconds,
            suspended = input.suspended,
            toSuspend = emptySet(),
            toUnsuspend = emptySet(),
            warnPkg = null,
            warnRemainingMin = 0,
            changed = false,
        )

        // 无前台受限应用、未配额、或已被挂起（不应再前台，防御性跳过）→ 不计时
        val quotaMin = pkg?.let { input.quotaMinutes[it] } ?: return unchanged
        if (pkg in input.suspended) return unchanged

        val quotaSec = quotaMin * 60 + (input.bonusSeconds[pkg] ?: 0)
        val oldUsed = input.usedSeconds[pkg] ?: 0
        val newUsed = oldUsed + input.tickSeconds
        val used = input.usedSeconds + (pkg to newUsed)
        val newRemaining = quotaSec - newUsed

        // 时间到：挂起
        if (newRemaining <= 0) {
            return TickOutput(
                date = today,
                usedSeconds = used,
                bonusSeconds = input.bonusSeconds,
                suspended = input.suspended + pkg,
                toSuspend = setOf(pkg),
                toUnsuspend = emptySet(),
                warnPkg = null,
                warnRemainingMin = 0,
                changed = true,
            )
        }

        // 预警：剩余进入新的 ≤5 分钟档位时提醒一次（5/4/3/2/1 各一次）
        val oldBucket = ceilMinutes(quotaSec - oldUsed)
        val newBucket = ceilMinutes(newRemaining)
        val warn = newBucket in 1..WARN_START_MIN && newBucket != oldBucket
        return TickOutput(
            date = today,
            usedSeconds = used,
            bonusSeconds = input.bonusSeconds,
            suspended = input.suspended,
            toSuspend = emptySet(),
            toUnsuspend = emptySet(),
            warnPkg = if (warn) pkg else null,
            warnRemainingMin = if (warn) newBucket else 0,
            changed = true,
        )
    }
}
