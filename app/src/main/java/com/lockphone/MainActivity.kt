package com.lockphone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.delay
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

    // 限额兜底提示（Task 15）：generation counter 防止连点时的竞态条件
    // 每次发起启动递增，onPause 时再递增；等待中的 delay 检查 generation 是否仍匹配
    private var launchGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var screen by remember { mutableStateOf(Screen.LOADING) }
            var showPinDialog by remember { mutableStateOf(false) }
            var showLimitDialog by remember { mutableStateOf(false) }
            val whitelist by repo.whitelist.collectAsState(initial = emptySet())
            val orientationLocked by repo.orientationLocked.collectAsState(initial = true)
            val volumeLocked by repo.volumeLocked.collectAsState(initial = true)
            val pinFailures by repo.pinFailures.collectAsState(initial = emptyList())
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

            LaunchedEffect(screen) {
                if (screen == Screen.LAUNCHER) {
                    startForegroundService(Intent(this@MainActivity, com.lockphone.audio.VolumeGuardService::class.java))
                }
            }

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
                        onLaunch = { pkg ->
                            appList.launch(pkg)
                            val gen = ++launchGeneration
                            scope.launch {
                                delay(1500)
                                if (launchGeneration == gen) showLimitDialog = true
                            }
                        },
                        onParentClick = { showPinDialog = true },
                    )
                    if (showPinDialog) {
                        PinDialog(
                            title = "家长模式验证",
                            gate = pinGate,
                            onVerify = { entered ->
                                val ok = repo.verifyPin(entered)
                                if (!ok) repo.recordPinFailure(System.currentTimeMillis())
                                ok
                            },
                            onSuccess = {
                                showPinDialog = false
                                screen = Screen.SETTINGS
                            },
                            onDismiss = { showPinDialog = false },
                        )
                    }
                    if (showLimitDialog) {
                        AlertDialog(
                            onDismissRequest = { showLimitDialog = false },
                            title = { Text("暂时打不开") },
                            text = { Text("该应用今日可能已达使用限额，请稍后再试或让家长检查。") },
                            confirmButton = { TextButton(onClick = { showLimitDialog = false }) { Text("知道了") } },
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
                    pinFailures = pinFailures,
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

    override fun onPause() {
        super.onPause()
        // 成功退到后台说明刚才的 launch(pkg) 生效了，递增 generation 使任何在途的延迟检查失效
        launchGeneration++
    }
}
