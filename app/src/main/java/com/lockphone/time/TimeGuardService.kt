package com.lockphone.time

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.widget.Toast
import com.lockphone.MainActivity
import com.lockphone.admin.LockController
import com.lockphone.data.SettingsRepository
import com.lockphone.data.UsageSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * 强力时间限定：常驻前台服务每 5 秒 tick 一次，用 UsageStats 判定当前前台受限应用，
 * 屏幕亮着时累加其用时；超额即 Device Owner 挂起该应用（阻止再次拉起），本地零点自动清零解挂。
 */
class TimeGuardService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var usm: UsageStatsManager
    private lateinit var power: PowerManager
    private lateinit var lock: LockController
    private lateinit var repo: SettingsRepository
    private lateinit var quotaNotifier: QuotaNotifier

    // 前台包名跨 tick 记忆：仅在收到新的 RESUMED 事件时更新，空窗期保持不变，
    // 从而正确统计「停在应用里不动」的时长，堵住旧方案的绕过口子
    private var currentFg: String? = null
    private var lastQueryTime = 0L
    private var lastCurfewActive: Boolean? = null
    private var lastNotifiedPkg: String? = null

    override fun onCreate() {
        super.onCreate()
        usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        power = getSystemService(Context.POWER_SERVICE) as PowerManager
        lock = LockController(applicationContext)
        repo = SettingsRepository(applicationContext)
        quotaNotifier = QuotaNotifier(applicationContext)
        lastQueryTime = System.currentTimeMillis() - INITIAL_LOOKBACK_MS
        startForeground(NOTIF_ID, buildNotification())
        scope.launch { loop() }
    }

    private suspend fun loop() {
        var firstRun = true
        while (scope.isActive) {
            refreshForeground()
            val snapshot = repo.usageSnapshot()
            val interactive = power.isInteractive
            val fg = if (!interactive) null else currentFg?.takeIf { it != packageName }
            val now = System.currentTimeMillis()

            handleCurfew(now, fg)

            val out = TimeAccountant.tick(
                TickInput(
                    nowMillis = now,
                    zoneId = ZoneId.systemDefault(),
                    storedDate = snapshot.date,
                    usedSeconds = snapshot.used,
                    bonusSeconds = snapshot.bonus,
                    suspended = snapshot.suspended,
                    quotaMinutes = snapshot.quota,
                    foregroundPkg = fg,
                    tickSeconds = TICK_SECONDS,
                ),
            )

            // 重启兜底：首轮重新套用仍处挂起态的应用
            if (firstRun) {
                out.suspended.forEach { lock.setPackageSuspended(it, true) }
                firstRun = false
            }

            out.toUnsuspend.forEach { lock.setPackageSuspended(it, false) }
            out.toSuspend.forEach { enforce(it) }
            out.warnPkg?.let { warn(it, out.warnRemainingMin) }
            if (out.changed) {
                repo.applyUsage(out.date, out.usedSeconds, out.bonusSeconds, out.suspended)
            }

            notifyIfNewlyActivated(fg, snapshot, out)
            lastNotifiedPkg = fg

            delay(TICK_SECONDS * 1000L)
        }
    }

    private fun refreshForeground() {
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(lastQueryTime, now)
        val e = UsageEvents.Event()
        while (events.getNextEvent(e)) {
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                currentFg = e.packageName
            }
        }
        lastQueryTime = now
    }

    private fun enforce(pkg: String) {
        lock.setPackageSuspended(pkg, true)
        kickToLauncher()
        Toast.makeText(this, "「${appLabel(this, pkg)}」今天的时间用完啦", Toast.LENGTH_LONG).show()
    }

    private fun kickToLauncher() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** 非授权时段：写回状态供 UI 观察，并把仍在前台的应用踢回锁定页 */
    private suspend fun handleCurfew(now: Long, fg: String?) {
        val snap = repo.curfewSnapshot()
        val out = CurfewPolicy.evaluate(
            CurfewInput(now, ZoneId.systemDefault(), snap.enabled, snap.windows, snap.tempUnlockUntil),
        )
        if (out.curfewActive != lastCurfewActive) {
            repo.setCurfewActive(out.curfewActive)
            lastCurfewActive = out.curfewActive
        }
        if (out.curfewActive && fg != null) kickToLauncher()
    }

    /** 受限应用切到前台的那个 tick，弹一次醒目限额通知 */
    private fun notifyIfNewlyActivated(fg: String?, snapshot: UsageSnapshot, out: TickOutput) {
        if (fg == null || fg == lastNotifiedPkg) return
        val quotaMin = snapshot.quota[fg] ?: return
        if (fg in out.suspended) return
        val usedSec = out.usedSeconds[fg] ?: 0
        val bonusSec = out.bonusSeconds[fg] ?: 0
        val remainingMin = ((quotaMin * 60 + bonusSec - usedSec + 59) / 60).coerceAtLeast(0)
        quotaNotifier.notifyActivated(fg, quotaMin, remainingMin)
    }

    private fun warn(pkg: String, remainingMin: Int) {
        quotaNotifier.notifyWarning(pkg, remainingMin)
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "时间守护", NotificationManager.IMPORTANCE_MIN),
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("时间守护运行中")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "time_guard"
        private const val NOTIF_ID = 1002
        private const val TICK_SECONDS = 5
        private const val INITIAL_LOOKBACK_MS = 60_000L
    }
}
