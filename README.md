# HyperFCMFix

针对小米 HyperOS 4 / Android 15 的 FCM 推送唤醒与后台治理修复 LSPosed 模块（LibXposed API 102）。

## 本版本改动

### 1. FCM 应用动态识别

模块在 `system_server` 中通过查询：

`com.google.android.c2dm.intent.RECEIVE`

动态发现已安装的 FCM 接收应用，不再硬编码 Telegram、Nextcloud 等包名。

FCM 应用列表会在每次修复周期重新扫描。

### 2. AOSP 电池策略自动恢复为「优化」

针对动态发现的第三方 FCM 应用：

- 系统启动完成后 **5 分钟**执行第一次扫描与修复；
- 此后 **每 12 小时**重新扫描并校正；
- 将 FCM 应用从 AOSP `deviceidle` power-save whitelist 中移除，使 AOSP 电池策略回到 **Optimized / 优化**；
- 不修改 HyperOS 自启动开关；
- 不把第三方 FCM 应用加入 AOSP 电池无限制白名单；
- GMS 自身继续保持原有的 FCM 保护。

> 注意：AOSP「优化」与 HyperOS 自启动、Greezer、Aurogon 是不同层级的机制。本模块不会把它们混成一个开关。

### 3. FCM stopped-app 广播穿透

`BroadcastController.broadcastIntentLocked` / `BroadcastQueueModernImpl.enqueueBroadcastLocked` 仅针对 FCM 广播进行处理：

- `com.google.android.c2dm.intent.RECEIVE`
- `com.google.android.c2dm.intent.REGISTRATION`

对 FCM 广播注入 `FLAG_RECEIVER_INCLUDE_STOPPED_PACKAGES`，并清除 `EXCLUDE_STOPPED_PACKAGES`。

同时仅在 FCM 场景下处理原有的 `appOp == -1 -> 11` 兼容逻辑，避免对普通广播产生全局影响。

### 4. HyperOS Greezer FCM 穿透

动态将检测到的 FCM 应用加入 Greezer 的 FCM 广播白名单。

`shouldStopBroadcastDispatch` 只针对能够识别为 GMS/FCM 的广播放行，不再对所有普通广播直接返回 false。

### 5. 不再全局放开第三方 AUTO_START

旧版本会对所有应用的 `OP_AUTO_START (10008)` 返回 `MODE_ALLOWED`。

本版本删除这一全局行为：

- GMS 保留自身自启动保护；
- 第三方应用不会因为安装本模块而统一获得 HyperOS 自启动权限；
- FCM 唤醒依赖 system_server 的 FCM 广播穿透。

因此目标是实现：

**HyperOS 自启动关闭 + AOSP 电池策略为「优化」+ App 进程被杀/进入 stopped 状态时，仍允许 GMS 的 FCM 广播唤醒目标 App。**

实际效果需要在具体 HyperOS 版本上用 GMS FCM Diagnostics 和真实推送进行验证。

## 内置状态 / 日志界面

现在模块 APK 自带一个简单的原生 UI：

- 显示当前识别到的 FCM 应用；
- 显示包名；
- 显示电池策略修复周期；
- 显示最近的 system_server 修复日志；
- 支持刷新；
- 支持清空日志。

日志包括：

- FCM 应用发现；
- Battery Optimization 校正；
- Greezer bypass；
- FCM stopped-app bypass；
- 初始化命令；
- 异常。

## LSPosed 作用域

仍然只需要：

`android`

即 system framework / system_server。

## 验证建议

1. 关闭 Telegram / Discord 等测试应用的 HyperOS 自启动；
2. 将 AOSP 电池策略手动设置为「不受限制」；
3. 等待开机 5 分钟后的首次修复，确认恢复为「优化」；
4. 或手动结束目标 App 进程；
5. 使用 GMS FCM Diagnostics / 实际推送测试；
6. 查看模块 UI 中的 FCM 列表与日志；
7. 重点观察是否仍出现：
   - `Failed to broadcast to stopped app`
   - `No response to broadcast`
   - `Greeze Denial`

## 参考

- kooritea/fcmfix
- dingwen07/hyperos-fcm-fix
- libxposed/api
