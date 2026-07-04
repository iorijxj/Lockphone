package com.lockphone.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
fun WizardScreen(
    allApps: List<AppEntry>,
    onFinish: (pin: String, whitelist: Set<String>) -> Unit,
) {
    var step by remember { mutableStateOf(1) }
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        when (step) {
            1 -> {
                Text("第 1 步：设置家长 PIN（至少 4 位数字）", fontSize = 18.sp)
                OutlinedTextField(p1, { p1 = it.filter(Char::isDigit).take(8) },
                    label = { Text("PIN") },
                    visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(p2, { p2 = it.filter(Char::isDigit).take(8) },
                    label = { Text("再输一次") },
                    visualTransformation = PasswordVisualTransformation())
                Button(
                    onClick = { if (p1.length >= 4 && p1 == p2) step = 2 },
                    modifier = Modifier.padding(top = 16.dp),
                ) { Text("下一步") }
            }
            2 -> {
                Text("第 2 步：勾选允许孩子使用的 APP", fontSize = 18.sp)
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(allApps, key = { it.packageName }) { app ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Checkbox(
                                checked = app.packageName in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + app.packageName
                                    else selected - app.packageName
                                },
                            )
                            Text(app.label)
                        }
                    }
                }
                Button(onClick = { onFinish(p1, selected) }) { Text("完成并进入锁定") }
            }
        }
    }
}
