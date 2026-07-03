package com.lockphone

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.UserManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.lockphone.admin.LockAdminReceiver

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, LockAdminReceiver::class.java)

        setContent {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(if (dpm.isDeviceOwnerApp(packageName)) "Device Owner: 已激活" else "Device Owner: 未激活")
                Button(onClick = {
                    dpm.setLockTaskPackages(admin, arrayOf(packageName))
                    dpm.setLockTaskFeatures(
                        admin,
                        DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD or
                            DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
                            DevicePolicyManager.LOCK_TASK_FEATURE_HOME,
                    )
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
                    startLockTask()
                }) { Text("进入锁定") }
                Button(onClick = { stopLockTask() }) { Text("退出锁定") }
            }
        }
    }
}
