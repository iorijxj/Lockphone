package com.lockphone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.lockphone.admin.LockController
import com.lockphone.apps.AppListProvider
import com.lockphone.data.SettingsRepository
import com.lockphone.security.PinGate
import com.lockphone.ui.LauncherScreen
import com.lockphone.ui.PinDialog
import com.lockphone.ui.SettingsScreen
import com.lockphone.ui.WizardScreen
import kotlinx.coroutines.launch

private enum class Screen { LOADING, WIZARD, LAUNCHER, SETTINGS }

class MainActivity : ComponentActivity() {
    private val repo by lazy { SettingsRepository(applicationContext) }
    private val lock by lazy { LockController(applicationContext) }
    private val appList by lazy { AppListProvider(applicationContext) }
    private val pinGate = PinGate(
        clock = { System.currentTimeMillis() },
        onLockedUntilChanged = { ts -> lifecycleScope.launch(kotlinx.coroutines.NonCancellable) { repo.setCooldownUntil(ts) } },
    )

    // 临时退出锁定期间置 true，防止 LaunchedEffect 在退出瞬间把锁又加回去；
    // 重新打开 APP 时 onResume 复位并递增 resumeTick，触发自动恢复锁定（spec 5.3）
    private val lockPaused = mutableStateOf(false)
    private val resumeTick = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var screen by remember { mutableStateOf(Screen.LOADING) }
            var showPinDialog by remember { mutableStateOf(false) }
            val whitelist by repo.whitelist.collectAsState(initial = emptySet())
            val orientationLocked by repo.orientationLocked.collectAsState(initial = true)
            val volumeLocked by repo.volumeLocked.collectAsState(initial = true)
            val scope = rememberCoroutineScope()
            val allApps = remember { appList.launchableApps() }

            BackHandler { /* 锁定桌面吞掉返回键 */ }

            LaunchedEffect(Unit) {
                pinGate.restore(repo.getCooldownUntil())
                screen = if (repo.isPinSet()) Screen.LAUNCHER else Screen.WIZARD
            }

            LaunchedEffect(screen, whitelist, resumeTick.value) {
                if (screen == Screen.LAUNCHER && !lockPaused.value) {
                    lock.applyPolicies(whitelist)
                    lock.enterLockTask(this@MainActivity)
                }
            }

            LaunchedEffect(orientationLocked) {
                this@MainActivity.requestedOrientation =
                    if (orientationLocked) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }

            LaunchedEffect(volumeLocked) { lock.setVolumeLocked(volumeLocked) }

            when (screen) {
                Screen.LOADING -> {}
                Screen.WIZARD -> WizardScreen(
                    allApps = allApps,
                    onFinish = { pin, selected ->
                        scope.launch {
                            repo.setPin(pin)
                            repo.setWhitelist(selected)
                            screen = Screen.LAUNCHER
                        }
                    },
                )
                Screen.LAUNCHER -> {
                    LauncherScreen(
                        apps = allApps.filter { it.packageName in whitelist },
                        onLaunch = { appList.launch(it) },
                        onParentClick = { showPinDialog = true },
                    )
                    if (showPinDialog) {
                        PinDialog(
                            title = "家长模式验证",
                            gate = pinGate,
                            onVerify = { repo.verifyPin(it) },
                            onSuccess = {
                                showPinDialog = false
                                screen = Screen.SETTINGS
                            },
                            onDismiss = { showPinDialog = false },
                        )
                    }
                }
                Screen.SETTINGS -> SettingsScreen(
                    allApps = allApps,
                    whitelist = whitelist,
                    onToggle = { pkg, checked ->
                        scope.launch {
                            val next = if (checked) whitelist + pkg else whitelist - pkg
                            repo.setWhitelist(next)
                            lock.applyPolicies(next)
                        }
                    },
                    onChangePin = { scope.launch { repo.setPin(it) } },
                    onTemporaryExit = {
                        lockPaused.value = true
                        lock.temporaryExit(this@MainActivity)
                        screen = Screen.LAUNCHER
                    },
                    onRelease = {
                        lockPaused.value = true
                        lock.temporaryExit(this@MainActivity)
                        lock.releaseDeviceOwner()
                        finish()
                    },
                    onBack = { screen = Screen.LAUNCHER },
                    orientationLocked = orientationLocked,
                    onOrientationToggle = { scope.launch { repo.setOrientationLocked(it) } },
                    volumeLocked = volumeLocked,
                    onVolumeToggle = { scope.launch { repo.setVolumeLocked(it) } },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 临时退出后重新打开本 APP：复位暂停标记并触发 LaunchedEffect 重跑 → 自动恢复锁定（spec 5.3）
        lockPaused.value = false
        resumeTick.value++
    }

    override fun onStop() {
        super.onStop()
        if (!lockPaused.value && !isFinishing) {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            am.moveTaskToFront(taskId, 0)
        }
    }
}
