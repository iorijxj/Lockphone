package com.lockphone.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.lockphone.security.PinGate
import kotlinx.coroutines.launch

@Composable
fun PinDialog(
    title: String,
    gate: PinGate,
    onVerify: suspend (String) -> Boolean,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                )
                error?.let { Text(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (busy) return@TextButton
                if (!gate.canAttempt()) {
                    error = "错误次数过多，请 ${gate.remainingLockMs() / 1000} 秒后再试"
                    return@TextButton
                }
                busy = true
                scope.launch {
                    try {
                        if (onVerify(pin)) {
                            gate.recordSuccess()
                            onSuccess()
                        } else {
                            gate.recordFailure()
                            pin = ""
                            error = if (gate.canAttempt()) "密码错误"
                            else "错误次数过多，请 ${gate.remainingLockMs() / 1000} 秒后再试"
                        }
                    } finally {
                        busy = false
                    }
                }
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
