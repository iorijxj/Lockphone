# Lockphone（管住逆子的手）

给自家孩子的一台闲置安卓手机打造的**儿童锁定桌面**。运行后进入一个受控桌面：只显示家长允许的白名单 APP，未经家长 PIN 授权无法退出。基于 Android 官方的 **Device Owner + Lock Task Mode**，锁定是系统级的，激活全程不清除手机数据（保留原有学习 APP 进度）。

> 仅供个人给自家孩子使用，不发布、不上架。已在 vivo NEX S（Android 10 / OriginOS 1.0）真机验证。

## 功能

- **锁定桌面**：注册为系统默认 HOME，只展示白名单 APP，其余一律无法启动
- **系统级防退出**：Lock Task Mode 封堵通知栏、最近任务；禁用安全模式、恢复出厂；本 APP 不可被普通方式卸载
- **家长设置**（PIN 保护，二级密码确认）：
  - 白名单管理（勾选即时生效）
  - 修改 PIN（4–256 位数字，加盐 SHA-256 存储，连错 5 次冷却 60 秒且抗重启/旋转）
  - 临时退出锁定 / 彻底解除（一键恢复手机原状，数据无损）
  - 锁定竖屏（关闭自动旋转）
  - 锁定音量（仅媒体音量，clamp 拉回方式，保持出声：连蓝牙时 50%–100% 可调，未连时锁定 50%；越界自动拉回，不影响铃声/闹钟）
  - 密码错误记录（记录每次输错 PIN 的日期时间，可查看总次数）
- **强力时间限定**：本 APP 自行计时，不依赖系统「应用限时」。前台服务每 5 秒统计"当前前台受限应用"的用时（仅屏幕亮时累加），超过家长设定的**每应用日配额**即 Device Owner 挂起该应用、阻止再次打开，堵住"应用不退出就一直能用"的绕过口子
  - 每个白名单 APP 可单独设「限时 X 分钟 / 不限」，学习类可设不限
  - 剩余 5 分钟起每分钟 Toast 预警一次
  - 用完的应用在桌面置灰标「今日已用完」；本地零点自动清零解锁
  - 家长可临时加时（任意分钟或一键 +5 分钟）
  - 需授予「使用情况访问」权限（部署时一条 adb 命令，见下）
- **开机自启**：重启后自动回到锁定桌面（需在系统「自启动管理」中授权）

## 技术栈

Kotlin + Jetpack Compose · DevicePolicyManager（Device Owner / Lock Task）· DataStore Preferences · minSdk 28 / targetSdk 34

## 构建

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 部署（一次性，家长在电脑上操作）

激活 Device Owner 需要设备上无任何登录中的系统账号（**只需退出登录，不清数据**）：

1. 退出手机上所有系统账号，开启 USB 调试与「USB 安装」
2. 安装：`adb install -t -r app-debug.apk`（`-t` 必需，APK 带 `testOnly` 以保留 adb 救援通道）
3. 激活：`adb shell dpm set-device-owner com.lockphone/.admin.LockAdminReceiver`
4. 授予用量访问（时间限定必需，否则计时不生效）：`adb shell appops set com.lockphone android:get_usage_stats allow`
5. 打开 APP，按向导设置 PIN 与白名单，并在家长设置里为各 APP 配置限时

面向非开发者的完整图文步骤见 [docs/操作文档-真机预检安装指南.md](docs/操作文档-真机预检安装指南.md)。

**救援**（忘记 PIN / 异常时，电脑执行）：
```bash
adb shell dpm remove-active-admin com.lockphone/.admin.LockAdminReceiver
```

## 已知限制（ROM 相关，均已知并接受）

- **Home 键**：vivo 锁定模式下 Home 键无效，从白名单 APP 用**返回键**回锁定桌面
- **重启**：开机会先闪一下 vivo 桌面再自动进入锁定桌面
- **时间限定的授权依赖**：需一次性授予「使用情况访问」权限（appop `get_usage_stats`）。若未授权，前台探测拿不到数据、计时静默失效——家长设置页会红字提示并提供跳转授权入口
- **锁定音量**：无法在保持出声的前提下彻底禁止屏幕调音量（系统级 `DISALLOW_ADJUST_VOLUME` 会静音整机），故采用 clamp 拉回——孩子拖屏幕音量面板有极短窗口，随即被拉回

## 文档

- 设计：[docs/superpowers/specs/2026-07-04-lockphone-design.md](docs/superpowers/specs/2026-07-04-lockphone-design.md)
- 实施计划：[docs/superpowers/plans/2026-07-04-lockphone.md](docs/superpowers/plans/2026-07-04-lockphone.md)
- 操作指南：`docs/操作文档-*.md`（预检安装、各版本复测）
