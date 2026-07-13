package com.lockphone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CURFEW_BACKGROUND = Color(0xFF1C1C1E)
private val CURFEW_TEXT_SECONDARY = Color(0xFF8E8E93)
private val CURFEW_TEXT_SUBTLE = Color(0xFF636366)

/** 非授权时段全屏锁定页：无任何应用入口，仅留低调的家长验证按钮 */
@Composable
fun CurfewScreen(nextAvailableLabel: String, onParentClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(CURFEW_BACKGROUND).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🔒", fontSize = 56.sp)
        Spacer(Modifier.height(24.dp))
        Text("非授权使用时间段", color = Color.White, fontSize = 26.sp)
        Spacer(Modifier.height(12.dp))
        Text("下次可用：$nextAvailableLabel", color = CURFEW_TEXT_SECONDARY, fontSize = 16.sp)
        Spacer(Modifier.height(48.dp))
        TextButton(onClick = onParentClick) {
            Text("家长模式", color = CURFEW_TEXT_SUBTLE, fontSize = 14.sp)
        }
    }
}

/** 家长 PIN 验证通过后选择临时解锁时长；onSelectMinutes(null) = 直到下个可用时段开始 */
@Composable
fun TempUnlockDialog(
    onSelectMinutes: (Int?) -> Unit,
    onOpenFullSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("临时解锁多久？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60).forEach { min ->
                    Button(
                        onClick = { onSelectMinutes(min) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("$min 分钟") }
                }
                Button(
                    onClick = { onSelectMinutes(null) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("直到下个可用时段") }
                TextButton(
                    onClick = onOpenFullSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("打开完整设置") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
