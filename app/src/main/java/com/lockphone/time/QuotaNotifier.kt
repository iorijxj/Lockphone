package com.lockphone.time

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.widget.Toast

/** 包名 → 应用显示名，解析失败回退包名 */
fun appLabel(context: Context, pkg: String): String =
    runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

/**
 * 受限 APP 醒目限额通知：激活时展示「限时 X 分钟，还剩 Y 分钟」，剩余 ≤5 分钟逐档预警。
 * IMPORTANCE_HIGH 渠道保证 heads-up 弹出；同包名固定 tag+id 覆盖更新，不堆叠。
 * 通知不可用（权限未授予/被家长手动关闭）时降级为 Toast，保证提醒不静默丢失。
 */
class QuotaNotifier(private val context: Context) {
    private val mgr = context.getSystemService(NotificationManager::class.java)

    init {
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "限额提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            },
        )
    }

    fun notifyActivated(pkg: String, quotaMinutes: Int, remainingMinutes: Int) {
        show(pkg, "「${appLabel(context, pkg)}」限时 $quotaMinutes 分钟", "今天还剩 $remainingMinutes 分钟可用")
    }

    fun notifyWarning(pkg: String, remainingMinutes: Int) {
        show(pkg, "「${appLabel(context, pkg)}」只剩 $remainingMinutes 分钟了", "时间到会自动锁定，请尽快收尾")
    }

    private fun show(pkg: String, title: String, text: String) {
        if (!mgr.areNotificationsEnabled()) {
            Toast.makeText(context, "$title，$text", Toast.LENGTH_LONG).show()
            return
        }
        val notif = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setColor(ALERT_COLOR)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build()
        // tag=完整包名（天然唯一）+ 固定 id → 同应用覆盖更新，不同应用互不影响
        mgr.notify(pkg, NOTIF_ID, notif)
    }

    companion object {
        private const val CHANNEL_ID = "quota_alert"
        private const val NOTIF_ID = 3001
        private const val ALERT_COLOR = 0xFFFF3B30.toInt()
    }
}
