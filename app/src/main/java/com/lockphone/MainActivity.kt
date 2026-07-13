package com.lockphone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
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
import com.lockphone.apps.AppEntry
import com.lockphone.security.PinGate
import com.lockphone.time.CurfewPolicy
import com.lockphone.time.TimeWindow
import com.lockphone.ui.CurfewScreen
import com.lockphone.ui.LauncherScreen
import com.lockphone.ui.PinDialog
import com.lockphone.ui.SettingsScreen
import com.lockphone.ui.TempUnlockDialog
import com.lockphone.ui.WizardScreen
import kotlinx.coroutines.launch
import java.time.ZoneId

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
            val whitelist by repo.whitelist.collectAsState(initial = emptySet())
            val orientationLocked by repo.orientationLocked.collectAsState(initial = true)
            val volumeLocked by repo.volumeLocked.collectAsState(initial = true)
            val pinFailures by repo.pinFailures.collectAsState(initial = emptyList())
            val appQuota by repo.appQuota.collectAsState(initial = emptyMap())
            val usageUsed by repo.usageUsed.collectAsState(initial = emptyMap())
            val quotaSuspended by repo.quotaSuspended.collectAsState(initial = emptySet())
            val curfewEnabled by repo.curfewEnabled.collectAsState(initial = false)
            val curfewWindows by repo.curfewWindows.collectAsState(initial = emptyList())
            val curfewActive by repo.curfewActive.collectAsState(initial = false)
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
                    startForegroundService(Intent(this@MainActivity, com.lockphone.time.TimeGuardService::class.java))
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
                Screen.LAUNCHER -> LauncherFlow(
                    allApps = allApps,
                    whitelist = whitelist,
                    quotaSuspended = quotaSuspended,
                    // 门控 enabled/windows：家长刚关开关或删空时段时不等服务下一 tick 就放行
                    curfewLocked = curfewActive && curfewEnabled && curfewWindows.isNotEmpty(),
                    curfewWindows = curfewWindows,
                    onOpenSettings = { screen = Screen.SETTINGS },
                )
                Screen.SETTINGS -> SettingsScreen(
                    allApps = allApps,
                    whitelist = whitelist,
                    onToggle = { pkg, checked ->
                        scope.launch {
                            val next = if (checked) whitelist + pkg else whitelist - pkg
                            repo.setWhitelist(next)
                            lock.applyPolicies(next)
                            // 取消白名单后无法再管理其限时，若正挂起则解挂避免永久挂死
                            if (!checked) {
                                repo.clearQuotaSuspended(pkg)
                                lock.setPackageSuspended(pkg, false)
                            }
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
                        quotaSuspended.forEach { lock.setPackageSuspended(it, false) }
                        lock.temporaryExit(this@MainActivity)
                        lock.releaseDeviceOwner()
                        finish()
                    },
                    onBack = { screen = Screen.LAUNCHER },
                    orientationLocked = orientationLocked,
                    onOrientationToggle = { scope.launch { repo.setOrientationLocked(it) } },
                    volumeLocked = volumeLocked,
                    onVolumeToggle = { scope.launch { repo.setVolumeLocked(it) } },
                    curfewEnabled = curfewEnabled,
                    curfewWindows = curfewWindows,
                    onCurfewEnabledToggle = { scope.launch { repo.setCurfewEnabled(it) } },
                    onCurfewWindowAdd = { scope.launch { repo.addCurfewWindow(TimeWindow(8 * 60, 20 * 60)) } },
                    onCurfewWindowUpdate = { index, w -> scope.launch { repo.updateCurfewWindow(index, w) } },
                    onCurfewWindowRemove = { index -> scope.launch { repo.removeCurfewWindow(index) } },
                    pinFailures = pinFailures,
                    whitelistQuota = appQuota,
                    usageUsed = usageUsed,
                    quotaSuspended = quotaSuspended,
                    onSetQuota = { pkg, minutes ->
                        scope.launch {
                            repo.setAppQuota(pkg, minutes)
                            // 配额变更（尤其改为不限时）后作废旧的挂起决策，交回下一 tick 重新判定
                            repo.clearQuotaSuspended(pkg)
                            lock.setPackageSuspended(pkg, false)
                        }
                    },
                    onAddBonus = { pkg, seconds ->
                        scope.launch {
                            repo.addBonus(pkg, seconds)
                            lock.setPackageSuspended(pkg, false)
                        }
                    },
                    usageAccessGranted = com.lockphone.time.hasUsageStatsAccess(this@MainActivity),
                    onOpenUsageAccess = {
                        startActivity(
                            Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                )
            }
        }
    }

    /** 锁定桌面流程：按 curfew 状态渲染锁定页或桌面，家长 PIN 验证后进设置或选临时解锁时长 */
    @Composable
    private fun LauncherFlow(
        allApps: List<AppEntry>,
        whitelist: Set<String>,
        quotaSuspended: Set<String>,
        curfewLocked: Boolean,
        curfewWindows: List<TimeWindow>,
        onOpenSettings: () -> Unit,
    ) {
        var showPinDialog by remember { mutableStateOf(false) }
        var showTempUnlockDialog by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        if (curfewLocked) {
            CurfewScreen(
                nextAvailableLabel = remember(curfewWindows) { nextAvailableLabel(curfewWindows) },
                onParentClick = { showPinDialog = true },
            )
        } else {
            LauncherScreen(
                apps = allApps.filter { it.packageName in whitelist },
                suspended = quotaSuspended,
                onLaunch = { pkg -> appList.launch(pkg) },
                onParentClick = { showPinDialog = true },
            )
        }
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
                    if (curfewLocked) showTempUnlockDialog = true else onOpenSettings()
                },
                onDismiss = { showPinDialog = false },
            )
        }
        if (showTempUnlockDialog) {
            TempUnlockDialog(
                onSelectMinutes = { minutes ->
                    scope.launch {
                        grantTempUnlock(minutes, curfewWindows)
                        showTempUnlockDialog = false
                    }
                },
                onOpenFullSettings = {
                    showTempUnlockDialog = false
                    onOpenSettings()
                },
                onDismiss = { showTempUnlockDialog = false },
            )
        }
    }

    /** 家长临时解锁：minutes 为 null = 直到下个可用时段开始 */
    private suspend fun grantTempUnlock(minutes: Int?, windows: List<TimeWindow>) {
        val until = if (minutes == null) {
            CurfewPolicy.nextAvailableInstant(System.currentTimeMillis(), ZoneId.systemDefault(), windows)
        } else {
            System.currentTimeMillis() + minutes * 60_000L
        }
        repo.setTempUnlockUntil(until)
        // 立即解除锁定页，不等守护服务下一 tick 再写回
        repo.setCurfewActive(false)
    }

    private fun nextAvailableLabel(windows: List<TimeWindow>): String {
        val next = CurfewPolicy.nextAvailableInstant(System.currentTimeMillis(), ZoneId.systemDefault(), windows)
        return java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(next))
    }

    override fun onResume() {
        super.onResume()
        // 临时退出后重新打开本 APP：复位暂停标记并触发 LaunchedEffect 重跑 → 自动恢复锁定（spec 5.3）
        lockPaused.value = false
        resumeTick.value++
    }

}
