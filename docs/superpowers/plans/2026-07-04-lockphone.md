# Lockphone 儿童锁定桌面 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在一台 OPPO/vivo 系闲置手机上部署自研锁定桌面：只显示家长白名单 APP，未经 PIN 授权无法退出，激活全程不清除手机数据。

**Architecture:** 单 APK = 自定义 HOME Launcher + Device Owner（Lock Task Mode 系统级锁定）+ PIN 保护的家长设置页。数据仅本地 DataStore（PIN 加盐哈希 + 白名单包名），无网络。时间管理由系统自带「应用限时」承担，本 APP 不做计时。

**Tech Stack:** Kotlin + Jetpack Compose，DevicePolicyManager（Device Owner / Lock Task），DataStore Preferences，JUnit4（纯逻辑单测）。

**Spec:** `docs/superpowers/specs/2026-07-04-lockphone-design.md`

## Global Constraints

- 包名固定 `com.lockphone`，AdminReceiver 固定 `com.lockphone.admin.LockAdminReceiver`（adb 激活命令依赖此名，不可改）
- Manifest 必须带 `android:testOnly="true"`：这是「adb 救援解除 Device Owner」的前提；安装一律用 `adb install -t -r`
- minSdk = 28（`setLockTaskFeatures` 需要 API 28；Task 2 第 1 步验证真机 ≥ 28，不满足则停下重议）
- 激活/解锁等所有 **真机操作步骤由用户人工执行**，AI 提供命令并核对输出；每个人工步骤是一个检查点
- 不恢复出厂设置、不执行任何 `pm clear` / 清数据命令（保护学习 APP 数据）
- PIN 规则：本地只存加盐 SHA-256 哈希；连错 5 次冷却 60 秒
- Lock Task 保留特性：KEYGUARD（锁屏可用）、GLOBAL_ACTIONS（电源菜单可用）、HOME
- 用户限制：`DISALLOW_SAFE_BOOT`、`DISALLOW_FACTORY_RESET`，彻底解除时必须成对清除
- git 提交走 Bash 工具，conventional commits 中文描述
- Windows 环境；gradle 命令在 Bash 工具中用 `./gradlew`，adb 全路径 `$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe`（下文简写 `adb`，执行时若 PATH 未配置则用全路径）

## File Structure

```
Lockphone/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── local.properties              (不入 git)
├── .gitignore
└── app/
    ├── build.gradle.kts
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── res/xml/device_admin.xml
        │   └── java/com/lockphone/
        │       ├── MainActivity.kt          # 状态宿主：向导/桌面/设置切换
        │       ├── admin/LockAdminReceiver.kt
        │       ├── admin/LockController.kt  # Device Owner 操作唯一封装
        │       ├── data/SettingsRepository.kt
        │       ├── security/PinHasher.kt    # 纯逻辑，可单测
        │       ├── security/PinGate.kt      # 纯逻辑，可单测
        │       ├── apps/AppListProvider.kt
        │       └── ui/
        │           ├── LauncherScreen.kt
        │           ├── PinDialog.kt
        │           ├── SettingsScreen.kt
        │           └── WizardScreen.kt
        └── test/java/com/lockphone/
            ├── security/PinHasherTest.kt
            ├── security/PinGateTest.kt
            └── data/HexTest.kt
```

---

### Task 1: 开发环境 + 项目脚手架 + Phase 0 最小验证 APK

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `.gitignore`, `local.properties`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/device_admin.xml`
- Create: `app/src/main/java/com/lockphone/admin/LockAdminReceiver.kt`
- Create: `app/src/main/java/com/lockphone/MainActivity.kt`（Phase 0 临时版，Task 6 会整体替换）

**Interfaces:**
- Produces: 可安装的 debug APK `app/build/outputs/apk/debug/app-debug.apk`；`LockAdminReceiver`（后续所有 Device Owner 调用的 admin 组件）

- [ ] **Step 1: 安装开发环境（人工 + AI 核对）**

依次执行，已装则跳过：

```powershell
winget install -e --id EclipseAdoptium.Temurin.17.JDK
winget install -e --id Gradle.Gradle
winget install -e --id Google.AndroidStudio
```

装完打开 Android Studio 一次，按向导装默认 SDK（含 platform-tools）。验证：

```
adb version          → 输出 "Android Debug Bridge version ..."
gradle --version     → Gradle 8.x，JVM 17
```

- [ ] **Step 2: 写项目脚手架文件**

`settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Lockphone"
include(":app")
```

`build.gradle.kts`（根）:
```kotlin
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}
```

`gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx2g
android.useAndroidX=true
```

`local.properties`（按实际 SDK 路径，注意反斜杠转义）:
```properties
sdk.dir=C\:\\Users\\IORIJXJ\\AppData\\Local\\Android\\Sdk
```

`.gitignore`:
```
.gradle/
build/
local.properties
.idea/
*.iml
.kotlin/
```

`app/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.lockphone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lockphone"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    testImplementation("junit:junit:4.13.2")
}
```

- [ ] **Step 3: 写 Manifest 与 Device Admin 声明**

`app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:label="Lockphone"
        android:testOnly="true"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:stateNotNeeded="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.HOME" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>

        <receiver
            android:name=".admin.LockAdminReceiver"
            android:exported="true"
            android:permission="android.permission.BIND_DEVICE_ADMIN">
            <meta-data
                android:name="android.app.device_admin"
                android:resource="@xml/device_admin" />
            <intent-filter>
                <action android:name="android.app.action.DEVICE_ADMIN_ENABLED" />
            </intent-filter>
        </receiver>
    </application>
</manifest>
```

`app/src/main/res/xml/device_admin.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<device-admin xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-policies />
</device-admin>
```

`app/src/main/java/com/lockphone/admin/LockAdminReceiver.kt`:
```kotlin
package com.lockphone.admin

import android.app.admin.DeviceAdminReceiver

class LockAdminReceiver : DeviceAdminReceiver()
```

- [ ] **Step 4: 写 Phase 0 临时 MainActivity（验证锁定与限制用）**

`app/src/main/java/com/lockphone/MainActivity.kt`:
```kotlin
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
```

- [ ] **Step 5: 构建 APK 验证编译通过**

```bash
gradle wrapper --gradle-version 8.9   # 仅首次，生成 gradlew
./gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`，产物 `app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: 项目脚手架与 Phase 0 最小验证 APK"
```

---

### Task 2: Phase 0 真机预检（人工操作 + 决策门）

**Files:** 无代码改动。产出一份预检结果记录 `docs/superpowers/plans/phase0-结果.md`

**Interfaces:**
- Consumes: Task 1 的 `app-debug.apk`
- Produces: **决策门**——全部通过才继续 Task 3+；第 4 步失败则整个方案停止，回到设计阶段换软锁路线

> 以下步骤全部需要用户人工操作手机，AI 负责给命令、核对输出、记录结果。

- [ ] **Step 1: 确认系统版本 ≥ API 28**

手机开 USB 调试并连接电脑后：
```bash
adb shell getprop ro.build.version.sdk
```
Expected: `28` 或更高。低于 28 → 停止，回设计阶段。

- [ ] **Step 2: 列出并删除系统账号（人工）**

```bash
adb shell dumpsys account | grep "Account {"
```
对列出的每个账号（OPPO/vivo/HeyTap/Google 等），在手机 设置→账号 中退出登录；OPPO/vivo 账号若提示需先关闭「查找手机」，照做。删完重跑上面命令确认输出为空。
**然后打开一个学习 APP，确认登录态和数据完好。**

- [ ] **Step 3: 安装验证 APK**

```bash
adb install -t -r app/build/outputs/apk/debug/app-debug.apk
```
Expected: `Success`

- [ ] **Step 4: 激活 Device Owner ← 全项目最大不确定点**

```bash
adb shell dpm set-device-owner com.lockphone/.admin.LockAdminReceiver
```
Expected: `Success: Device owner set to package ComponentInfo{com.lockphone/com.lockphone.admin.LockAdminReceiver}`

常见失败及处理：
- `there are already several users` → 设置里删掉分身/多用户后重试
- `already some accounts on the device` → 回到 Step 2 找漏网账号（`dumpsys account` 再查）
- ROM 直接拒绝/命令被阉割 → **决策门失败**：停止本计划，回设计阶段做软锁方案

- [ ] **Step 5: 验证 Lock Task 锁定效果（人工逐项）**

打开 Lockphone，确认显示「Device Owner: 已激活」，点「进入锁定」。逐项确认：
1. 下拉通知栏 → 拉不下来
2. 最近任务键 → 无反应
3. Home 键 → 停留在本 APP
4. 返回键狂点 → 退不出去
5. 电源键长按 → 关机菜单可用（GLOBAL_ACTIONS 生效）
6. 电源键短按锁屏再解锁 → 回到本 APP（KEYGUARD 生效）
7. 尝试进入安全模式（电源菜单长按「重启」，或该 ROM 的对应方式）→ 被拒绝或重启后仍正常锁定（DISALLOW_SAFE_BOOT 生效）

- [ ] **Step 6: 验证重启后行为（人工）**

锁定状态下重启手机。Expected: 开机后自动进入 Lockphone（因 HOME intent-filter + 唯一桌面尚未设置，可能弹「选择桌面」——记录实际行为，正式版会用 persistent home 消除弹窗）。

- [ ] **Step 7: 验证系统「应用限时」在锁定下生效（人工）**

点「退出锁定」→ 在系统设置里给某个学习 APP 设 1 分钟应用限时 → 回 Lockphone 进入锁定 →（正式版桌面还没做，本步在 Task 9 终验时用白名单 APP 复测；本步只确认设置项在删除账号后仍可用）

- [ ] **Step 8: 验证 adb 救援通道（关键保险）**

```bash
adb shell dpm remove-active-admin com.lockphone/.admin.LockAdminReceiver
```
Expected: `Success: Admin removed`（testOnly 标记使然）。这就是忘记 PIN / APP 锁死时的救援命令。
随后**重新激活**（重复 Step 4）供后续开发使用。

- [ ] **Step 9: 记录结果并提交**

把每步实际输出写入 `docs/superpowers/plans/phase0-结果.md`（含 ROM 版本、异常和绕过方法）。
```bash
git add -A && git commit -m "docs: Phase 0 真机预检结果"
```

---

### Task 3: PinHasher 加盐哈希（纯逻辑 TDD）

**Files:**
- Create: `app/src/main/java/com/lockphone/security/PinHasher.kt`
- Test: `app/src/test/java/com/lockphone/security/PinHasherTest.kt`

**Interfaces:**
- Produces: `object PinHasher { fun newSalt(): ByteArray; fun hash(pin: String, salt: ByteArray): String; fun verify(pin: String, salt: ByteArray, expectedHash: String): Boolean }`（hash 返回 64 位小写 hex）

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/lockphone/security/PinHasherTest.kt`:
```kotlin
package com.lockphone.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHasherTest {
    @Test
    fun `同一 PIN 同一盐哈希一致`() {
        val salt = ByteArray(16) { it.toByte() }
        assertEquals(PinHasher.hash("1234", salt), PinHasher.hash("1234", salt))
    }

    @Test
    fun `不同盐哈希不同`() {
        val saltA = ByteArray(16) { 0 }
        val saltB = ByteArray(16) { 1 }
        assertNotEquals(PinHasher.hash("1234", saltA), PinHasher.hash("1234", saltB))
    }

    @Test
    fun `verify 正确 PIN 通过 错误 PIN 拒绝`() {
        val salt = PinHasher.newSalt()
        val hash = PinHasher.hash("1234", salt)
        assertTrue(PinHasher.verify("1234", salt, hash))
        assertFalse(PinHasher.verify("0000", salt, hash))
    }

    @Test
    fun `newSalt 长度 16 且两次不同`() {
        val a = PinHasher.newSalt()
        val b = PinHasher.newSalt()
        assertEquals(16, a.size)
        assertFalse(a.contentEquals(b))
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests "com.lockphone.security.PinHasherTest"
```
Expected: FAIL（`Unresolved reference: PinHasher` 编译错误）

- [ ] **Step 3: 最小实现**

`app/src/main/java/com/lockphone/security/PinHasher.kt`:
```kotlin
package com.lockphone.security

import java.security.MessageDigest
import java.security.SecureRandom

object PinHasher {
    fun newSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    fun hash(pin: String, salt: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        return md.digest(pin.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun verify(pin: String, salt: ByteArray, expectedHash: String): Boolean =
        MessageDigest.isEqual(
            hash(pin, salt).toByteArray(Charsets.UTF_8),
            expectedHash.toByteArray(Charsets.UTF_8),
        )
}
```

- [ ] **Step 4: 运行确认通过**

同 Step 2 命令。Expected: `BUILD SUCCESSFUL`，4 tests passed

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: PIN 加盐哈希与校验"
```

---

### Task 4: PinGate 防穷举冷却（纯逻辑 TDD）

**Files:**
- Create: `app/src/main/java/com/lockphone/security/PinGate.kt`
- Test: `app/src/test/java/com/lockphone/security/PinGateTest.kt`

**Interfaces:**
- Produces: `class PinGate(clock: () -> Long) { fun canAttempt(): Boolean; fun remainingLockMs(): Long; fun recordFailure(); fun recordSuccess() }`——连错 5 次后 `canAttempt()=false` 持续 60 秒

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/lockphone/security/PinGateTest.kt`:
```kotlin
package com.lockphone.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinGateTest {
    private var now = 0L
    private val gate = PinGate(clock = { now })

    @Test
    fun `错 4 次仍可尝试`() {
        repeat(4) { gate.recordFailure() }
        assertTrue(gate.canAttempt())
    }

    @Test
    fun `错 5 次锁定 60 秒`() {
        repeat(5) { gate.recordFailure() }
        assertFalse(gate.canAttempt())
        assertEquals(60_000L, gate.remainingLockMs())
        now = 59_999
        assertFalse(gate.canAttempt())
        now = 60_000
        assertTrue(gate.canAttempt())
    }

    @Test
    fun `成功后计数清零`() {
        repeat(4) { gate.recordFailure() }
        gate.recordSuccess()
        repeat(4) { gate.recordFailure() }
        assertTrue(gate.canAttempt())
    }

    @Test
    fun `冷却结束后再错 5 次才再次锁定`() {
        repeat(5) { gate.recordFailure() }
        now = 60_000
        repeat(4) { gate.recordFailure() }
        assertTrue(gate.canAttempt())
        gate.recordFailure()
        assertFalse(gate.canAttempt())
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests "com.lockphone.security.PinGateTest"
```
Expected: FAIL（`Unresolved reference: PinGate`）

- [ ] **Step 3: 最小实现**

`app/src/main/java/com/lockphone/security/PinGate.kt`:
```kotlin
package com.lockphone.security

class PinGate(
    private val clock: () -> Long,
    private val maxFailures: Int = 5,
    private val cooldownMs: Long = 60_000L,
) {
    private var failures = 0
    private var lockedUntil = 0L

    fun canAttempt(): Boolean = clock() >= lockedUntil

    fun remainingLockMs(): Long = (lockedUntil - clock()).coerceAtLeast(0)

    fun recordFailure() {
        failures++
        if (failures >= maxFailures) {
            lockedUntil = clock() + cooldownMs
            failures = 0
        }
    }

    fun recordSuccess() {
        failures = 0
        lockedUntil = 0
    }
}
```

- [ ] **Step 4: 运行确认通过**

同 Step 2 命令。Expected: 4 tests passed

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: PIN 连错冷却门控"
```

---

### Task 5: SettingsRepository 数据层（DataStore + hex 工具 TDD）

**Files:**
- Create: `app/src/main/java/com/lockphone/data/SettingsRepository.kt`
- Test: `app/src/test/java/com/lockphone/data/HexTest.kt`

**Interfaces:**
- Consumes: `PinHasher`（Task 3）
- Produces:
  - `class SettingsRepository(context: Context)`: `val whitelist: Flow<Set<String>>`; `suspend fun setWhitelist(packages: Set<String>)`; `suspend fun isPinSet(): Boolean`; `suspend fun setPin(pin: String)`; `suspend fun verifyPin(pin: String): Boolean`
  - 顶层函数 `fun ByteArray.toHex(): String` / `fun String.hexToBytes(): ByteArray`（同文件内）

- [ ] **Step 1: 写 hex 工具的失败测试（DataStore 本身不做单测，真机验证）**

`app/src/test/java/com/lockphone/data/HexTest.kt`:
```kotlin
package com.lockphone.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HexTest {
    @Test
    fun `hex 往返一致`() {
        val bytes = byteArrayOf(0, 1, 15, 16, 127, -1, -128)
        assertArrayEquals(bytes, bytes.toHex().hexToBytes())
    }

    @Test
    fun `toHex 输出小写两位`() {
        assertEquals("00ff10", byteArrayOf(0, -1, 16).toHex())
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests "com.lockphone.data.HexTest"
```
Expected: FAIL（编译错误）

- [ ] **Step 3: 实现数据层**

`app/src/main/java/com/lockphone/data/SettingsRepository.kt`:
```kotlin
package com.lockphone.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lockphone.security.PinHasher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "lockphone_settings")

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

class SettingsRepository(private val context: Context) {
    private object Keys {
        val PIN_SALT = stringPreferencesKey("pin_salt")
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val WHITELIST = stringSetPreferencesKey("whitelist")
    }

    val whitelist: Flow<Set<String>> =
        context.dataStore.data.map { it[Keys.WHITELIST] ?: emptySet() }

    suspend fun setWhitelist(packages: Set<String>) {
        context.dataStore.edit { it[Keys.WHITELIST] = packages }
    }

    suspend fun isPinSet(): Boolean =
        context.dataStore.data.first().contains(Keys.PIN_HASH)

    suspend fun setPin(pin: String) {
        val salt = PinHasher.newSalt()
        context.dataStore.edit {
            it[Keys.PIN_SALT] = salt.toHex()
            it[Keys.PIN_HASH] = PinHasher.hash(pin, salt)
        }
    }

    suspend fun verifyPin(pin: String): Boolean {
        val prefs = context.dataStore.data.first()
        val salt = prefs[Keys.PIN_SALT]?.hexToBytes() ?: return false
        val hash = prefs[Keys.PIN_HASH] ?: return false
        return PinHasher.verify(pin, salt, hash)
    }
}
```

- [ ] **Step 4: 运行测试 + 全量编译**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```
Expected: 全部 PASS + `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: DataStore 配置存储（PIN 哈希与白名单）"
```

---

### Task 6: LockController + AppListProvider（Device Owner 封装）

**Files:**
- Create: `app/src/main/java/com/lockphone/admin/LockController.kt`
- Create: `app/src/main/java/com/lockphone/apps/AppListProvider.kt`

**Interfaces:**
- Consumes: `LockAdminReceiver`（Task 1）
- Produces:
  - `class LockController(context: Context)`: `val isDeviceOwner: Boolean`; `fun applyPolicies(whitelist: Set<String>)`; `fun enterLockTask(activity: Activity)`; `fun temporaryExit(activity: Activity)`; `fun releaseDeviceOwner()`
  - `class AppListProvider(context: Context)` + `data class AppEntry(val packageName: String, val label: String, val icon: Drawable)`; `fun launchableApps(): List<AppEntry>`; `fun launch(packageName: String)`

- [ ] **Step 1: 实现 LockController（Device Owner API 不可 JVM 单测，Task 9 真机验收）**

`app/src/main/java/com/lockphone/admin/LockController.kt`:
```kotlin
package com.lockphone.admin

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
        activity.startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun releaseDeviceOwner() {
        if (!isDeviceOwner) return
        dpm.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
        dpm.clearUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
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
```

- [ ] **Step 2: 实现 AppListProvider**

`app/src/main/java/com/lockphone/apps/AppListProvider.kt`:
```kotlin
package com.lockphone.apps

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

data class AppEntry(val packageName: String, val label: String, val icon: Drawable)

class AppListProvider(private val context: Context) {
    fun launchableApps(): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map {
                AppEntry(
                    packageName = it.activityInfo.packageName,
                    label = it.loadLabel(pm).toString(),
                    icon = it.loadIcon(pm),
                )
            }
            .sortedBy { it.label }
    }

    fun launch(packageName: String) {
        context.packageManager.getLaunchIntentForPackage(packageName)?.let {
            context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
./gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: Device Owner 锁定封装与应用列表"
```

---

### Task 7: 锁定桌面 UI + PIN 对话框

**Files:**
- Create: `app/src/main/java/com/lockphone/ui/LauncherScreen.kt`
- Create: `app/src/main/java/com/lockphone/ui/PinDialog.kt`

**Interfaces:**
- Consumes: `AppEntry`（Task 6）、`PinGate`（Task 4）
- Produces:
  - `@Composable fun LauncherScreen(apps: List<AppEntry>, onLaunch: (String) -> Unit, onParentClick: () -> Unit)`
  - `@Composable fun PinDialog(title: String, gate: PinGate, onVerify: suspend (String) -> Boolean, onSuccess: () -> Unit, onDismiss: () -> Unit)`

- [ ] **Step 1: 实现桌面网格（含明面「家长模式」按钮）**

`app/src/main/java/com/lockphone/ui/LauncherScreen.kt`:
```kotlin
package com.lockphone.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.lockphone.apps.AppEntry

@Composable
fun LauncherScreen(
    apps: List<AppEntry>,
    onLaunch: (String) -> Unit,
    onParentClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TextButton(
            onClick = onParentClick,
            modifier = Modifier.align(Alignment.End).padding(8.dp),
        ) { Text("家长模式") }

        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            items(apps, key = { it.packageName }) { app ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onLaunch(app.packageName) }
                        .padding(4.dp),
                ) {
                    Image(
                        bitmap = app.icon.toBitmap(144, 144).asImageBitmap(),
                        contentDescription = app.label,
                        modifier = Modifier.size(56.dp),
                    )
                    Text(
                        app.label,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 2: 实现 PIN 对话框（含冷却提示）**

`app/src/main/java/com/lockphone/ui/PinDialog.kt`:
```kotlin
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
                if (!gate.canAttempt()) {
                    error = "错误次数过多，请 ${gate.remainingLockMs() / 1000} 秒后再试"
                    return@TextButton
                }
                scope.launch {
                    if (onVerify(pin)) {
                        gate.recordSuccess()
                        onSuccess()
                    } else {
                        gate.recordFailure()
                        pin = ""
                        error = if (gate.canAttempt()) "密码错误"
                        else "错误次数过多，请 60 秒后再试"
                    }
                }
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
```

- [ ] **Step 3: 编译验证**

```bash
./gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: 锁定桌面网格与 PIN 对话框"
```

---

### Task 8: 家长设置页 + 首次启动向导 + MainActivity 整合

**Files:**
- Create: `app/src/main/java/com/lockphone/ui/SettingsScreen.kt`
- Create: `app/src/main/java/com/lockphone/ui/WizardScreen.kt`
- Modify: `app/src/main/java/com/lockphone/MainActivity.kt`（整体替换 Phase 0 临时版）

**Interfaces:**
- Consumes: Task 3-7 全部产出（签名见各任务 Produces）
- Produces: 完整可运行 APP；`MainActivity` 状态机 `WIZARD → LAUNCHER ⇄ SETTINGS`

- [ ] **Step 1: 实现设置页（四项功能）**

`app/src/main/java/com/lockphone/ui/SettingsScreen.kt`:
```kotlin
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
```

- [ ] **Step 2: 实现首次启动向导（设 PIN → 勾白名单 → 进入锁定）**

`app/src/main/java/com/lockphone/ui/WizardScreen.kt`:
```kotlin
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
```

- [ ] **Step 3: 整体替换 MainActivity 为正式状态机**

`app/src/main/java/com/lockphone/MainActivity.kt`（完整替换）:
```kotlin
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
    private val pinGate = PinGate(clock = { System.currentTimeMillis() })

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
            val scope = rememberCoroutineScope()
            val allApps = remember { appList.launchableApps() }

            BackHandler { /* 锁定桌面吞掉返回键 */ }

            LaunchedEffect(Unit) {
                screen = if (repo.isPinSet()) Screen.LAUNCHER else Screen.WIZARD
            }

            LaunchedEffect(screen, whitelist, resumeTick.value) {
                if (screen == Screen.LAUNCHER && !lockPaused.value) {
                    lock.applyPolicies(whitelist)
                    lock.enterLockTask(this@MainActivity)
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
}
```

注意：`onRelease` 里先 `temporaryExit`（退出 lock task + 清 persistent home）再 `releaseDeviceOwner`，顺序不可颠倒——放弃 Device Owner 之后就无权调用 DPM API 了。

- [ ] **Step 4: 全量测试 + 编译**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```
Expected: 全部 PASS + `BUILD SUCCESSFUL`

- [ ] **Step 5: 安装到真机冒烟（人工）**

```bash
adb install -t -r app/build/outputs/apk/debug/app-debug.apk
```
手机上打开 Lockphone：应进入向导 → 设 PIN → 勾 2-3 个 APP → 完成后进入锁定桌面，白名单 APP 可点开。

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: 家长设置页、首次向导与主状态机整合"
```

---

### Task 9: 整机验收（人工攻击清单 + 决策收尾）

**Files:**
- Create: `docs/superpowers/plans/验收清单.md`（记录每项结果）

**Interfaces:**
- Consumes: Task 8 完整 APP
- Produces: 验收通过的部署版本；遗留问题清单

- [ ] **Step 1: 功能验收（人工逐项，记录到验收清单）**

1. 白名单 APP 从桌面点开正常使用，退出后回锁定桌面
2. 白名单外 APP 不出现在桌面，且无法通过任何入口启动
3. 家长模式按钮 → 正确 PIN 进入设置页；错误 PIN 5 次后冷却 60 秒
4. 设置页勾选/取消 APP，返回桌面即时生效
5. 修改 PIN 后旧 PIN 失效、新 PIN 可用
6. 临时退出锁定 → 到系统桌面；重新打开 Lockphone → 自动恢复锁定
7. 系统「应用限时」对白名单 APP 生效（补 Task 2 Step 7 的完整验证）

- [ ] **Step 2: 孩子视角攻击清单（人工逐项）**

1. 下拉通知栏 / 快捷设置 → 均无法呼出
2. 最近任务、Home、疯狂点返回 → 退不出
3. 重启手机 → 直接回锁定桌面，无「选择桌面」弹窗（persistent home 生效）
4. 长按电源键 → 只有关机/重启，无其他逃逸入口
5. 白名单 APP 内点外链（预期：静默无反应，属设计内行为）
6. 插数据线连电脑（预期：adb 可用——已知取舍，记录即可）
7. 锁屏后解锁 → 回到锁定桌面

- [ ] **Step 3: 稳定性观察**

保持锁定状态放置一天，期间孩子正常使用，观察是否有异常退出锁定。杀进程测试：
```bash
adb shell am force-stop com.lockphone
```
Expected: 系统作为 HOME 自动重新拉起 Lockphone 并回到锁定。

- [ ] **Step 4: 收尾提交**

```bash
git add -A && git commit -m "docs: 整机验收结果"
```

遗留事项记录到验收清单：USB 调试是否后续禁用（当前决定：保留）、发现的 ROM 特异行为。

---

## 降级路径（决策门失败时）

Task 2 Step 4 若被 ROM 拦截且无法绕过：停止本计划，回到 brainstorming 重新设计软锁方案（普通 Launcher + `startLockTask` 屏幕固定模式 + 无障碍服务拦截设置页）。软锁方案另出 spec 与 plan，不在本文档范围。
