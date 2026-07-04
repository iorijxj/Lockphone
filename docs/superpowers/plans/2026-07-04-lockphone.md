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
- 本构建锁定单台 API 29 设备：targetSdk 34 但未声明 `<queries>` 包可见性；若换 API 30+ 设备部署，必须先补 `<queries>` 声明，否则应用列表为空

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
            android:stateNotNeeded="true"
            android:configChanges="orientation|screenSize|keyboardHidden">
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
3. 家长模式按钮 → 正确 PIN 进入设置页；错误 PIN 5 次后冷却 60 秒（冷却期间旋转屏幕/锁屏再解锁，冷却仍须生效）
4. 设置页勾选/取消 APP，返回桌面即时生效
5. 修改 PIN 后旧 PIN 失效、新 PIN 可用
6. 临时退出锁定 → 到系统桌面；重新打开 Lockphone → 自动恢复锁定
7. 系统「应用限时」对白名单 APP 生效（补 Task 2 Step 7 的完整验证）
8. 向导完成进入锁定的瞬间，观察桌面是否闪烁/短暂锁死（Task 8 审查发现的白名单 Flow 竞态，理论上几毫秒自愈；若可感知则按审查报告的修复方向处理）

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

---

### Task 10: 真机加固第一轮

**背景：** vivo NEX S（Android 10 / OriginOS 1.0）真机验收暴露出若干 ROM 兼容性问题，本任务针对性修复，不改变整体架构。

**改动清单：**

1. **应用改名**（`AndroidManifest.xml`）：`android:label` 由 `Lockphone` 改为 `管住逆子的手`，仅影响桌面显示名，不动 `applicationId` / `rootProject.name`。

2. **临时退出修复**（`LockController.kt` `temporaryExit`）：原实现用裸 `ACTION_MAIN` + `CATEGORY_HOME` 广播意图，OriginOS 上会把自己（本 APP 已注册 HOME）也解析进候选，导致退出常失败/原地打转。改为 `queryIntentActivities` 显式过滤掉自身包名，取系统真正的桌面 Launcher 组件后用显式 `Intent` 启动。

3. **PIN 冷却持久化**（`PinGate.kt` + `SettingsRepository.kt` + `MainActivity.kt`）：原冷却状态只存在内存变量，OriginOS 后台回收/旋转屏幕重建 Activity 后 `lockedUntil` 归零，孩子可借旋转屏幕绕过冷却。`PinGate` 新增 `initialLockedUntil` 构造参数、`restore()` 方法与 `onLockedUntilChanged` 回调；`SettingsRepository` 新增 `COOLDOWN_UNTIL`（`longPreferencesKey`）及 `getCooldownUntil` / `setCooldownUntil`；`MainActivity` 在 `onCreate` 中用 `lifecycleScope` 恢复冷却时间，并在冷却状态变化时写回 DataStore，跨进程重建后冷却仍然生效。

4. **Kiosk 兜底**（`AndroidManifest.xml` 权限 + `MainActivity.onStop`）：OriginOS 的全面屏手势/多任务卡片可绕过 Lock Task 直接把本 APP 切到后台。新增 `REORDER_TASKS` 权限，`MainActivity` 重写 `onStop`，只要不是主动临时退出（`lockPaused`）或正在结束（`isFinishing`），立即 `moveTaskToFront` 把任务拉回前台，作为系统级锁定之外的应用层兜底。

**验证：** `PinGateTest.kt` 新增 3 个用例覆盖 `initialLockedUntil`、锁定触发回调、`recordSuccess` 回调归零；`./gradlew :app:testDebugUnitTest` 与 `:app:assembleDebug` 均 BUILD SUCCESSFUL。`LockController`/`MainActivity` 的改动依赖 Android 框架类（`PackageManager`、`ActivityManager`、`lifecycleScope`），无本地单测覆盖，需真机复测确认（第二轮真机验收）。

### Task 11: 锁定竖屏开关

**背景：** 用户反馈自动旋转体验很烦，希望家长设置页里加一个开关，锁定屏幕为竖屏（关闭自动旋转），默认开启；开关藏在 PIN 保护的设置页内，孩子碰不到。

**改动清单：**

1. **`SettingsRepository.kt`**：新增 `ORIENTATION_LOCKED`（`booleanPreferencesKey("orientation_locked")`），暴露 `orientationLocked: Flow<Boolean>`（默认 `true`）与 `setOrientationLocked(locked: Boolean)`，风格与已有 `whitelist` Flow/setter 一致。

2. **`SettingsScreen.kt`**：新增入参 `orientationLocked: Boolean` 与 `onOrientationToggle: (Boolean) -> Unit`；在顶部操作按钮下方、白名单列表上方插入一行 Material3 `Switch`，文案“锁定竖屏（关闭自动旋转）”。

3. **`MainActivity.kt`**：`collectAsState` 订阅 `repo.orientationLocked`（初值 `true`）；新增 `LaunchedEffect(orientationLocked)`，据此把 `requestedOrientation` 切换为 `SCREEN_ORIENTATION_PORTRAIT` 或 `SCREEN_ORIENTATION_UNSPECIFIED`；`SettingsScreen` 调用处透传状态与回调，回调用现有 `scope` 写回 DataStore。

**验证：** `./gradlew :app:testDebugUnitTest` 与 `:app:assembleDebug` 均 BUILD SUCCESSFUL，无新增单测（纯 UI/持久化接线，DataStore 与 `requestedOrientation` 依赖 Android 框架，留待真机复测）。

### Task 12: 锁定音量开关

**背景：** 家长希望限制孩子随意调节系统音量，在家长设置页里加一个「锁定音量」开关，默认开启；与 Task 11 的锁定竖屏开关同源同构，藏在 PIN 保护的设置页内。

**改动清单：**

1. **`LockController.kt`**：新增 `setVolumeLocked(locked: Boolean)`，Device Owner 下用 `dpm.addUserRestriction` / `dpm.clearUserRestriction` 切换 `UserManager.DISALLOW_ADJUST_VOLUME`；`releaseDeviceOwner()` 中同步补上该限制的清除，与 `DISALLOW_SAFE_BOOT` / `DISALLOW_FACTORY_RESET` 成对，保证「彻底解除」完全恢复手机。

2. **`SettingsRepository.kt`**：新增 `VOLUME_LOCKED`（`booleanPreferencesKey("volume_locked")`），暴露 `volumeLocked: Flow<Boolean>`（默认 `true`）与 `setVolumeLocked(locked: Boolean)`，风格与 `orientationLocked` 完全一致。

3. **`SettingsScreen.kt`**：新增入参 `volumeLocked: Boolean` 与 `onVolumeToggle: (Boolean) -> Unit`；紧跟在「锁定竖屏」开关行之后插入一行同样式 `Switch`，文案“锁定音量（禁止调节音量）”。

4. **`MainActivity.kt`**：`collectAsState` 订阅 `repo.volumeLocked`（初值 `true`）；新增 `LaunchedEffect(volumeLocked)` 调用 `lock.setVolumeLocked(volumeLocked)`；`SettingsScreen` 调用处透传状态与回调，回调复用现有 `scope` 写回 DataStore。

**验证：** `./gradlew :app:testDebugUnitTest` 与 `:app:assembleDebug` 均 BUILD SUCCESSFUL，无新增单测（`DevicePolicyManager` 用户限制依赖 Android 框架，`DISALLOW_ADJUST_VOLUME` 在 OriginOS 上的实际拦截效果需真机复测确认）。

### Task 13: 蓝牙感知媒体音量控制

**背景：** Task 12 的「锁定音量」开关是非黑即白的全锁（`DISALLOW_ADJUST_VOLUME`）。但家长实际诉求更细：接了蓝牙音箱/耳机听歌时不该完全锁死音量（外放设备音量本就该能调），而是把媒体音量夹在 [50%, 100%] 之间，防止孩子调到很小听不清或静音；没接蓝牙时冻结媒体音量在当前值。本任务把开关语义从「是否全锁音量」升级为「音量策略总开关」，具体策略由是否检测到蓝牙 A2DP 输出设备决定，且**只控制 `STREAM_MUSIC`（媒体音量），不触碰铃声/闹钟/通知音量**。

**策略（仅作用于 STREAM_MUSIC / 媒体音量，纯 clamp，不使用 `DISALLOW_ADJUST_VOLUME`）：**
- 开关 OFF → 完全不干预媒体音量
- 开关 ON + 已连接蓝牙 A2DP 输出 → 媒体音量一旦低于系统最大音量的 50% 就立即拉回 50%（上限即系统最大值，无需处理）
- 开关 ON + 未连接蓝牙 A2DP → 冻结媒体音量在进入该状态时的当前值：孩子按音量键调低/调高后，`ContentObserver` 立即把它拉回冻结值，形成"按了但弹回"的观感

**为什么放弃 `DISALLOW_ADJUST_VOLUME`（Code Review 修正）：** 该 User Restriction 是全局的，一旦施加会连带锁死铃声、闹钟、通知音量，与"只控媒体音量"的 spec 矛盾（Important 级别 review 发现）。改为在 Service 内部维护 `lockedMediaVolume: Int?` 状态，靠 `ContentObserver` 检测到音量变化后用 `setStreamVolume` 把 `STREAM_MUSIC` 拉回目标值实现"冻结"，不再调用限制类 API，也就完全不影响其他音频流。

**为什么要用前台 Service：** 该策略需要在孩子处于白名单 APP（本 Activity 退到后台）时持续生效——音量条随时可能被调、蓝牙耳机随时可能被摘下，靠 Activity 生命周期挂钩的 `LaunchedEffect` 覆盖不到后台场景。因此把策略执行下沉到独立前台 Service，用 `ContentObserver` 监听系统音量变化、用 `AudioManager.registerAudioDeviceCallback` 监听蓝牙 A2DP 设备插拔，任一事件触发即重新评估并应用策略。蓝牙检测走 `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` 判断 `AudioDeviceInfo.TYPE_BLUETOOTH_A2DP`，不声明蓝牙权限。

**改动清单：**

1. **`LockController.kt`**：`setVolumeLocked(locked: Boolean)` 更名为 `setVolumeAdjustRestricted(restricted: Boolean)`，方法体不变（仍是 Device Owner 下 add/clear `DISALLOW_ADJUST_VOLUME`）。迁移到纯 clamp 方案后该方法不再是常规策略路径，仅保留给 Service `onCreate` 调用一次 `setVolumeAdjustRestricted(false)` 用于清除 Task 12 遗留的全局限制（历史升级用户的兼容处理）；`releaseDeviceOwner()` 中对该限制的清除也保持不变（同样是清理历史状态，无害）。

2. **新增 `audio/VolumeGuardService.kt`**：前台 Service，持有 `lockedMediaVolume: Int?` 记录无蓝牙状态下的冻结音量。`onCreate` 中启动前台通知（`IMPORTANCE_MIN` 静默渠道），先调用一次 `lock.setVolumeAdjustRestricted(false)` 清理遗留限制；随后在协程里**先 `repo.volumeLocked.first()` 拿到真实初始值赋给 `volumeLocked`，再注册 `ContentObserver` / `AudioDeviceCallback`，再 `applyPolicy()` 应用一次，最后订阅 `repo.volumeLocked.drop(1)` 的后续变化**——这个顺序是为了修另一处 Code Review 发现的竞态：若先注册回调、Flow 默认值 `true` 还没被真实值覆盖时设备回调抢先触发，会在开关实际是 OFF 的情况下误触发一次 clamp。`applyPolicy()` 改为三态 clamp 逻辑：开关 OFF 清空 `lockedMediaVolume`、不做任何操作；开关 ON + 蓝牙 A2DP 已连接则清空 `lockedMediaVolume` 并在 `cur < min` 时 `setStreamVolume` 拉回 `min = round(max * 0.5).coerceAtLeast(1)`；开关 ON + 无蓝牙则取 `lockedMediaVolume ?: cur`（首次进入该分支记录当前值为冻结目标），若 `cur != target` 就拉回 target。`onStartCommand` 返回 `START_STICKY`；新增 `registered: Boolean` 标记，`onDestroy` 仅在 `registered` 为真时才注销 Observer/Callback（因为注册被推迟到协程内部，避免协程还没跑到注册那一步就 `onDestroy` 导致注销未注册对象抛异常）。

3. **`AndroidManifest.xml`**：新增 `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` 权限；注册 `VolumeGuardService`（`exported=false`，`foregroundServiceType="specialUse"`，子类型 `parental_media_volume_control`）。

4. **`MainActivity.kt`**：移除 Task 12 遗留的 `LaunchedEffect(volumeLocked) { lock.setVolumeLocked(volumeLocked) }` 直接调用——音量策略的施加权完全交给 Service。启动 `VolumeGuardService` 的 `LaunchedEffect` 从 `LaunchedEffect(Unit)`（WIZARD 阶段就会跑）改为 `LaunchedEffect(screen) { if (screen == Screen.LAUNCHER) startForegroundService(...) }`，只在进入锁定桌面时才拉起 Service，避免设置向导阶段就常驻通知。`volumeLocked` 仍从 `repo.volumeLocked` 订阅并透传给 `SettingsScreen` 驱动开关 UI 与写回 DataStore，Service 侧监听同一个 Flow 各自应用策略，两边通过 DataStore 解耦。

5. **`SettingsRepository.kt`**：`volumeLocked` Flow 追加 `.distinctUntilChanged()`，避免 DataStore 里其他键（如白名单）的无关写入触发一次多余的 `applyPolicy()`。

**验证：** `./gradlew :app:testDebugUnitTest` 与 `:app:assembleDebug` 均 BUILD SUCCESSFUL，无新增单测（`AudioManager`/`AudioDeviceCallback`/前台 Service 生命周期依赖 Android 框架，蓝牙 A2DP 插拔检测、无蓝牙下"冻结音量回弹"手感、Service 在 OriginOS 后台存活情况均需真机复测确认）。

### Task 14: v3 反馈修复（移除兜底/音量70%/开机自启）

**背景：** 真机 v3 复测反馈三项问题：Task 10 加的 `onStop` kiosk 兜底会误伤白名单 APP（孩子点开白名单 APP → 本 Activity 退到后台触发 `onStop` → `moveTaskToFront` 把 Lockphone 抢回前台，白名单 APP 完全打不开）；音量冻结/下限从 50% 调高到 70%；新增开机自启尝试。

**改动清单：**

1. **移除 kiosk 兜底**（`MainActivity.kt`）：三键导航下最近任务手势本就被 Lock Task 拦住，且本 APP 是 persistent HOME，Home 键天然回到自己，`onStop` 里的 `moveTaskToFront` 兜底纯属多余且会误伤——只要孩子点开白名单 APP，本 Activity 就会被这段代码强行拉回前台。整段删除 `override fun onStop() { ... }`；`onResume` 里 `lockPaused` 复位与 `resumeTick` 自增（临时退出恢复用）保持不动。`AndroidManifest.xml` 同步移除不再需要的 `android.permission.REORDER_TASKS`。

2. **音量锚点提到 70%**（`VolumeGuardService.kt`）：`MIN_FRACTION = 0.5` 改名并改值为 `LOCK_FRACTION = 0.7`。蓝牙已连接分支不变逻辑，只是下限从 50% 提到 70%（`min = round(max * 0.7).coerceAtLeast(1)`，上限仍是系统最大值，可调到 100%）。无蓝牙分支从"记录进入时的当前值再冻结"改为直接锚定固定目标 `target = round(max * 0.7).coerceAtLeast(1)`，删除 `lockedMediaVolume: Int?` 字段及其 `?: cur.also { ... }` 逻辑——现在无论从哪个音量值进入该分支，都会被拉回固定的 70%，而不是冻结在进入时的任意值。OFF 分支不再需要重置 `lockedMediaVolume`（字段已删除），逻辑简化为空操作。

3. **开机自启**（新增 `boot/BootReceiver.kt` + `AndroidManifest.xml`）：新增 `BroadcastReceiver` 监听 `BOOT_COMPLETED`，收到后用 `FLAG_ACTIVITY_NEW_TASK` 拉起 `MainActivity`。Manifest 新增 `RECEIVE_BOOT_COMPLETED` 权限与 `exported=true` 的 receiver 声明。**已知限制：** OriginOS（vivo）等厂商 ROM 默认限制后台应用接收 `BOOT_COMPLETED` 广播，通常需要用户在「设置 → 应用管理 → Lockphone → 自启动」手动授权该权限，此接收器才会实际触发；纯软件层面无法绕过这个厂商限制，真机复测需人工确认自启动权限已开启。

**验证：** `./gradlew :app:testDebugUnitTest` 与 `:app:assembleDebug` 均 BUILD SUCCESSFUL。`onStop` 删除、`BootReceiver` 均依赖 Android 框架生命周期/广播机制，无本地单测覆盖；三项均需真机复测：白名单 APP 是否可正常打开使用、Home 键行为是否仍符合预期、音量锚点是否感知为 70%、开机自启在授权/未授权自启动权限两种情况下的实际表现。

### Task 15: 限额兜底提示

**背景：** v3 复测反馈——孩子点白名单 APP 图标，若 OriginOS 的应用时长限额已触发，系统会静默拦截该 APP 前台化（我们已确认无法从系统读到"限额已用尽"这个状态，没有官方 API 或可靠间接信号可查）。此时点击图标毫无反应，孩子会盯着一个"点了没反应"的死图标，不知道发生了什么。本任务加一个启发式兜底：点了图标之后，用"本 Activity 是否成功退到后台"这个可观察信号，反推"启动到底有没有生效"，没生效就弹一个解释性提示，而不是放任孩子对着死图标发呆。

**启发式检测逻辑（`MainActivity.kt`）：**
- `LauncherScreen` 的 `onLaunch(pkg)` 回调里：先 `appList.launch(pkg)` 照常发起启动，再把 `pkg` 记到类级别的 `pendingLaunch: MutableState<String?>` 上，然后用 `scope`（`rememberCoroutineScope`）起一个协程 `delay(1500)` 后检查——如果 `pendingLaunch.value` 还没被清空，说明这 1.5 秒内本 Activity 从未真正退到后台，即启动大概率没生效，于是清空 `pendingLaunch` 并把 `showLimitDialog` 置 `true`。
- `pendingLaunch` 之所以放在 Activity 类级别而非 `remember` 里，是因为要让 `onPause()` 能直接清掉它：只要启动真的成功，系统会在 APP 切走时回调 `onPause()`，这里立刻 `pendingLaunch.value = null`，1.5 秒后的检查就会发现"已经清空了"从而什么都不做——这是判断"启动成功"的唯一信号来源。
- `showLimitDialog` 为 `true` 时在 LAUNCHER 分支渲染一个 `AlertDialog`：标题「暂时打不开」，正文「该应用今日可能已达使用限额，请稍后再试或让家长检查。」，一个「知道了」按钮关闭。

**已知的不精确性（需真机复测确认，不是理论上可消除的）：**
- 这是纯粹的时间窗口启发式，不是真正读到了限额状态——1.5 秒只是一个经验阈值，换不同机型、不同 APP 冷启动耗时会有出入。
- **假阳性风险：** 某些 APP（尤其大型 APP 冷启动、或设备负载高时）前台化耗时可能超过 1.5 秒，会被误判为"被限额拦截"而弹出兜底提示，实际上 APP 只是慢；需要真机用几个偏重的白名单 APP（如游戏、地图类）测试冷启动是否会误触发。
- **假阴性风险：** 如果 OriginOS 的拦截机制并非"完全不启动"而是"启动后极快速地把本 Activity 重新拉回前台"（例如短暂展示一个系统提示层后又切回来），1.5 秒内可能被误判为"启动成功"从而不弹提示，孩子依然看不到解释。
- 需要真机验证两个方向：(a) 正常未达限额的白名单 APP 点击后不应误弹提示；(b) 人为把某个白名单 APP 的系统时长限额跑满后点击，应该弹出提示。若 1500ms 阈值经验证过短/过长，后续可调整为常量并按真机实测结果微调。

**验证：** `./gradlew :app:testDebugUnitTest` 与 `:app:assembleDebug` 均 BUILD SUCCESSFUL，无新增单测（纯 UI 时序启发式，依赖 Activity 生命周期回调与真实系统限额拦截行为，无法在 JVM 单测里模拟，只能真机复测）。

### Task 16: PIN 错误记录统计

**背景：** 家长想知道孩子有没有在偷偷试密码、试了多少次。本任务记录每次家长模式验证 PIN 失败的时间戳，并在设置页提供一个入口查看完整列表和总次数。只记录家长模式验证入口（`LauncherScreen` 点「家长模式」弹出的 `PinDialog`）的失败，不记录设置页内「修改 PIN」流程——后者不是一次未授权访问尝试。

**改动清单：**

1. **`SettingsRepository.kt`**：新增 `stringPreferencesKey("pin_failures")`（`Keys.PIN_FAILURES`），值为换行分隔的 epoch 毫秒时间戳字符串，FIFO 上限 `MAX_PIN_FAILURES = 100`（超过丢最旧的）。暴露 `val pinFailures: Flow<List<Long>>`（按写入顺序，最新的在最后）与 `suspend fun recordPinFailure(timestamp: Long)`。

2. **`MainActivity.kt`**：家长模式验证 `PinDialog` 的 `onVerify` 从 `{ repo.verifyPin(it) }` 改为先校验、失败时调用 `repo.recordPinFailure(System.currentTimeMillis())` 再返回结果；`collectAsState` 新增订阅 `repo.pinFailures`（初值 `emptyList()`），透传给 `SettingsScreen` 新增的 `pinFailures` 入参。

3. **`SettingsScreen.kt`**：新增入参 `pinFailures: List<Long>`；操作按钮行新增「密码错误记录」按钮，点击弹出 `AlertDialog`：标题带总次数「密码错误记录（共 N 次）」，正文用 `LazyColumn`（`heightIn(max = 360.dp)`）倒序（最新在前）列出 `yyyy-MM-dd HH:mm:ss` 格式的时间戳，空列表时显示「暂无记录」。

**验证：** `./gradlew :app:testDebugUnitTest` 与 `:app:assembleDebug` 均 BUILD SUCCESSFUL，无新增单测（`DataStore` 读写已有 Task 5/12 的既有模式覆盖，纯 UI 列表渲染无框架无关的可单测逻辑）。

### Task 17: PIN 最大长度提升到 256 位
