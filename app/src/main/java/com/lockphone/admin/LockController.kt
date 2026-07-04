package com.lockphone.admin

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.UserManager
import com.lockphone.MainActivity

class LockController(private val context: Context) {
    private val dpm =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val am =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val admin = ComponentName(context, LockAdminReceiver::class.java)

    val isDeviceOwner: Boolean
        get() = dpm.isDeviceOwnerApp(context.packageName)

    fun applyPolicies(whitelist: Set<String>) {
        if (!isDeviceOwner) return
        dpm.setLockTaskPackages(admin, (whitelist + context.packageName).toTypedArray())
        dpm.setLockTaskFeatures(
            admin,
            DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD or
                DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
                DevicePolicyManager.LOCK_TASK_FEATURE_HOME,
        )
        dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
        dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
        setPersistentHome(true)
    }

    fun enterLockTask(activity: Activity) {
        if (!isDeviceOwner) return
        if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
            activity.startLockTask()
        }
    }

    fun temporaryExit(activity: Activity) {
        if (am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) {
            activity.stopLockTask()
        }
        if (isDeviceOwner) setPersistentHome(false)
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val other = context.packageManager
            .queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull { it.activityInfo.packageName != context.packageName }
        if (other != null) {
            activity.startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    setClassName(other.activityInfo.packageName, other.activityInfo.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }

    fun setVolumeAdjustRestricted(restricted: Boolean) {
        if (!isDeviceOwner) return
        if (restricted) {
            dpm.addUserRestriction(admin, UserManager.DISALLOW_ADJUST_VOLUME)
        } else {
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_ADJUST_VOLUME)
        }
    }

    fun releaseDeviceOwner() {
        if (!isDeviceOwner) return
        dpm.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
        dpm.clearUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
        dpm.clearUserRestriction(admin, UserManager.DISALLOW_ADJUST_VOLUME)
        setPersistentHome(false)
        @Suppress("DEPRECATION")
        dpm.clearDeviceOwnerApp(context.packageName)
    }

    private fun setPersistentHome(enabled: Boolean) {
        if (enabled) {
            val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(
                admin, filter, ComponentName(context, MainActivity::class.java),
            )
        } else {
            dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
        }
    }
}
