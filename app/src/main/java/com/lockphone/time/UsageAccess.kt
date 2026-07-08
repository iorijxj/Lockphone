package com.lockphone.time

import android.app.AppOpsManager
import android.content.Context
import android.os.Process

/**
 * 是否已授予「使用情况访问」权限（appop GET_USAGE_STATS）。
 * 未授予时 UsageStatsManager 会静默返回空数据，计时将永不生效，
 * 故设置页需据此显著提示家长去授权。
 */
fun hasUsageStatsAccess(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = appOps.unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName,
    )
    return mode == AppOpsManager.MODE_ALLOWED
}
