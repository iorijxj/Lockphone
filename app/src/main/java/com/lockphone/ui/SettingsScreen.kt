package com.lockphone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockphone.apps.AppEntry

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    allApps: List<AppEntry>,
    whitelist: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onChangePin: (String) -> Unit,
    onTemporaryExit: () -> Unit,
    onRelease: () -> Unit,
    onBack: () -> Unit,
    orientationLocked: Boolean,
    onOrientationToggle: (Boolean) -> Unit,
    volumeLocked: Boolean,
    onVolumeToggle: (Boolean) -> Unit,
    pinFailures: List<Long>,
    whitelistQuota: Map<String, Int>,
    usageUsed: Map<String, Int>,
    quotaSuspended: Set<String>,
    onSetQuota: (String, Int?) -> Unit,
    onAddBonus: (String, Int) -> Unit,
    usageAccessGranted: Boolean,
    onOpenUsageAccess: () -> Unit,
) {
    var showPinChange by remember { mutableStateOf(false) }
    var showReleaseConfirm by remember { mutableStateOf(false) }
    var showFailuresDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("家长设置", fontSize = 20.sp, modifier = Modifier.padding(end = 16.dp))
            TextButton(onClick = onBack) { Text("返回桌面") }
        }
        if (!usageAccessGranted) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Text(
                    "⚠ 未授予「使用情况访问」权限，时间限制不会生效",
                    color = Color.Red,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onOpenUsageAccess) { Text("去授权") }
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Button(onClick = { showPinChange = true }) {
                Text("修改 PIN")
            }
            Button(onClick = onTemporaryExit) {
                Text("临时退出锁定")
            }
            Button(onClick = { showReleaseConfirm = true }) {
                Text("彻底解除")
            }
            Button(onClick = { showFailuresDialog = true }) { Text("密码错误记录") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Text("锁定竖屏（关闭自动旋转）", modifier = Modifier.weight(1f))
            Switch(checked = orientationLocked, onCheckedChange = onOrientationToggle)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Text("锁定音量（禁止调节音量）", modifier = Modifier.weight(1f))
            Switch(checked = volumeLocked, onCheckedChange = onVolumeToggle)
        }
        Text("白名单与限时（勾选后出现在孩子桌面）", modifier = Modifier.padding(vertical = 8.dp))
        LazyColumn {
            items(allApps, key = { it.packageName }) { app ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = app.packageName in whitelist,
                            onCheckedChange = { onToggle(app.packageName, it) },
                        )
                        Text(app.label, modifier = Modifier.weight(1f))
                    }
                    if (app.packageName in whitelist) {
                        AppQuotaControls(
                            pkg = app.packageName,
                            quotaMinutes = whitelistQuota[app.packageName],
                            usedSeconds = usageUsed[app.packageName] ?: 0,
                            suspended = app.packageName in quotaSuspended,
                            onSetQuota = onSetQuota,
                            onAddBonus = onAddBonus,
                        )
                    }
                }
            }
        }
    }

    if (showPinChange) {
        var p1 by remember { mutableStateOf("") }
        var p2 by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPinChange = false },
            title = { Text("修改 PIN") },
            text = {
                Column {
                    OutlinedTextField(p1, { p1 = it.filter(Char::isDigit).take(256) },
                        label = { Text("新 PIN") },
                        visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(p2, { p2 = it.filter(Char::isDigit).take(256) },
                        label = { Text("再输一次") },
                        visualTransformation = PasswordVisualTransformation())
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (p1.length >= 4 && p1 == p2) {
                            onChangePin(p1)
                            showPinChange = false
                        }
                    },
                ) { Text("确定") }
            },
            dismissButton = { TextButton({ showPinChange = false }) { Text("取消") } },
        )
    }

    if (showReleaseConfirm) {
        AlertDialog(
            onDismissRequest = { showReleaseConfirm = false },
            title = { Text("彻底解除锁定？") },
            text = { Text("手机将完全恢复正常，本 APP 变为普通应用可卸载。数据不受影响。") },
            confirmButton = { TextButton(onClick = onRelease) { Text("确认解除") } },
            dismissButton = { TextButton({ showReleaseConfirm = false }) { Text("取消") } },
        )
    }

    if (showFailuresDialog) {
        val fmt = remember { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()) }
        AlertDialog(
            onDismissRequest = { showFailuresDialog = false },
            title = { Text("密码错误记录（共 ${pinFailures.size} 次）") },
            text = {
                if (pinFailures.isEmpty()) {
                    Text("暂无记录")
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp),
                    ) {
                        items(pinFailures.reversed()) { ts ->
                            Text(fmt.format(java.util.Date(ts)), modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showFailuresDialog = false }) { Text("关闭") } },
        )
    }
}

@Composable
private fun AppQuotaControls(
    pkg: String,
    quotaMinutes: Int?,
    usedSeconds: Int,
    suspended: Boolean,
    onSetQuota: (String, Int?) -> Unit,
    onAddBonus: (String, Int) -> Unit,
) {
    val limited = quotaMinutes != null
    var minutesText by remember(pkg) { mutableStateOf(quotaMinutes?.toString() ?: "30") }
    var bonusText by remember(pkg) { mutableStateOf("") }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 48.dp)) {
        Text("限时", fontSize = 13.sp, modifier = Modifier.padding(end = 4.dp))
        Switch(
            checked = limited,
            onCheckedChange = { on ->
                onSetQuota(pkg, if (on) minutesText.toIntOrNull()?.takeIf { it > 0 } ?: 30 else null)
            },
        )
        if (limited) {
            OutlinedTextField(
                value = minutesText,
                onValueChange = {
                    minutesText = it.filter(Char::isDigit).take(4)
                    minutesText.toIntOrNull()?.takeIf { n -> n > 0 }?.let { n -> onSetQuota(pkg, n) }
                },
                label = { Text("分钟") },
                singleLine = true,
                modifier = Modifier.width(100.dp).padding(start = 8.dp),
            )
        }
    }
    if (limited) {
        Text(
            "今日已用 ${usedSeconds / 60} / $quotaMinutes 分" + if (suspended) "（已用完）" else "",
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 48.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 48.dp)) {
            OutlinedTextField(
                value = bonusText,
                onValueChange = { bonusText = it.filter(Char::isDigit).take(4) },
                label = { Text("加时") },
                singleLine = true,
                modifier = Modifier.width(96.dp),
            )
            TextButton(onClick = {
                bonusText.toIntOrNull()?.takeIf { it > 0 }?.let {
                    onAddBonus(pkg, it * 60)
                    bonusText = ""
                }
            }) { Text("加时") }
            TextButton(onClick = { onAddBonus(pkg, 5 * 60) }) { Text("+5分") }
        }
    }
}
