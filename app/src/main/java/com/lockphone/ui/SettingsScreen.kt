package com.lockphone.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockphone.apps.AppEntry

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
) {
    var showPinChange by remember { mutableStateOf(false) }
    var showReleaseConfirm by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("家长设置", fontSize = 20.sp, modifier = Modifier.padding(end = 16.dp))
            TextButton(onClick = onBack) { Text("返回桌面") }
        }
        Row {
            Button(onClick = { showPinChange = true }, modifier = Modifier.padding(end = 8.dp)) {
                Text("修改 PIN")
            }
            Button(onClick = onTemporaryExit, modifier = Modifier.padding(end = 8.dp)) {
                Text("临时退出锁定")
            }
            Button(onClick = { showReleaseConfirm = true }) { Text("彻底解除") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Text("锁定竖屏（关闭自动旋转）", modifier = Modifier.weight(1f))
            Switch(checked = orientationLocked, onCheckedChange = onOrientationToggle)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Text("锁定音量（禁止调节音量）", modifier = Modifier.weight(1f))
            Switch(checked = volumeLocked, onCheckedChange = onVolumeToggle)
        }
        Text("白名单（勾选后出现在孩子桌面）", modifier = Modifier.padding(vertical = 8.dp))
        LazyColumn {
            items(allApps, key = { it.packageName }) { app ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Checkbox(
                        checked = app.packageName in whitelist,
                        onCheckedChange = { onToggle(app.packageName, it) },
                    )
                    Text(app.label)
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
                    OutlinedTextField(p1, { p1 = it.filter(Char::isDigit).take(8) },
                        label = { Text("新 PIN") },
                        visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(p2, { p2 = it.filter(Char::isDigit).take(8) },
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
}
