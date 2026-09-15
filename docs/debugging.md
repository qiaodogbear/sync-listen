# 开发、模拟运行与调试

## 本机工具

本轮环境为 Windows 11 / PowerShell 7，物理内存约 14 GB。以下是这台机器的已确认路径，其他开发者应换成自己的安装位置，不要加入全局 PATH 或提交 local.properties。

| 工具 | 本机位置 / 用法 |
|---|---|
| 工程 | C:\Users\15224\Desktop\工程\sync-listen |
| ASCII 映射 | S: 指向工程；用于 Gradle 测试与打包 |
| Node / npm | C:\Program Files\nodejs；使用 Node 24，最低 22.13 |
| Git | C:\Program Files\Git\cmd\git.exe |
| GitHub CLI | gh；已登录账号通过系统凭据存储，不导出 token |
| Android JBR | C:\Program Files\Android\Android Studio\jbr |
| 完整 JDK 21 | C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot；含 jpackage |
| Android SDK | %LOCALAPPDATA%\Android\Sdk |
| adb / emulator | SDK 的 platform-tools\adb.exe / emulator\emulator.exe |
| 镜像与 AVD | API 35 Google APIs x86_64；SyncListen_A / SyncListen_B，Pixel 6 |
| draw.io | C:\Program Files\draw.io\draw.io.exe；图源在 docs/images |
| Gradle | 使用仓库 wrapper，不使用全局 Gradle |

PowerShell 路径映射（仅在 S: 空闲时执行，不覆盖已有映射）：

```powershell
subst S: 'C:\Users\15224\Desktop\工程\sync-listen'
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
$adb="$env:ANDROID_HOME\platform-tools\adb.exe"
```

## 构建和测试

```powershell
Set-Location S:\backend
npm ci
npm run lint
npm run typecheck
npm test
npm run build
npm audit --audit-level=high
```

```powershell
Set-Location S:\android-app
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --no-daemon
```

```powershell
Set-Location S:\
.\gradlew.bat :shared:test :desktop:test :desktop:createDistributable --no-daemon
```

Android 报告位于 app/build/reports，JUnit XML 位于 app/build/test-results；shared/desktop 对应各自 build/test-results/test。本机 Android JBR 不含 jpackage，桌面打包必须选择上表的完整 JDK。

## 模拟器

已有两个 AVD，无需重新创建。新机器可使用 Android Studio Device Manager，或 SDK command-line tools 的 sdkmanager / avdmanager 安装 emulator 与 `system-images;android-35;google_apis;x86_64`，接受 licenses，再按 pixel_6 设备创建 AVD。

```powershell
& "$env:ANDROID_HOME\emulator\emulator.exe" -list-avds
Start-Process -FilePath "$env:ANDROID_HOME\emulator\emulator.exe" -ArgumentList '-avd','SyncListen_A','-port','5554','-no-window','-no-audio','-no-boot-anim' -WindowStyle Hidden
& $adb -s emulator-5554 shell getprop sys.boot_completed
& $adb devices
```

boot_completed 必须为 1，devices 必须为 device。B 使用 SyncListen_B 和端口 5556。低内存机器不要同时跑两个模拟器和 R8/大规模 Gradle 构建；本轮运行中曾出现模拟器 Launcher/System UI ANR，不能误写成 App ANR。

## 安装和启动

```powershell
# Debug
& $adb -s emulator-5554 install -r S:\android-app\app\build\outputs\apk\debug\app-debug.apk
& $adb -s emulator-5554 shell am start -n com.synclisten.app.debug/com.synclisten.app.MainActivity

# 正式签名 Release
& $adb -s emulator-5554 install -r S:\android-app\app\build\outputs\apk\release\app-release.apk
& $adb -s emulator-5554 shell am start -n com.synclisten.app/com.synclisten.app.MainActivity
& $adb -s emulator-5554 shell pidof com.synclisten.app
```

Debug 的 applicationId 带 .debug，但 Activity 的类包名不带；不能写成 `com.synclisten.app.debug/.MainActivity`。

## 两种服务端

电脑后端在 backend 目录执行 `npm run dev`，默认监听 0.0.0.0:3000。可复制根目录 .env.example 到 backend/.env 做本机配置，勿覆盖已有文件。数据库及音频默认在 backend/data。迁移在启动时执行，也可以 `npm run db:migrate`。

```powershell
Invoke-RestMethod http://127.0.0.1:3000/health
Get-NetTCPConnection -LocalPort 3000 -State Listen
```

手机模式直接用 A 的界面创建房间，不需要 Node。模拟器存在 NAT，B 不能直接使用 A 的 10.0.2.15 地址：

```powershell
& $adb -s emulator-5554 forward tcp:38571 tcp:38571
```

B 手动输入 `http://10.0.2.2:38571` 和 A 的房间码；真正手机不需要这个转发，使用房主可达局域网地址。二维码验收应区分编码/解码测试、深链注入和物理摄像头扫码，三者不是同一个测试。

## 日志与截图

```powershell
& $adb -s emulator-5554 logcat -d -s AndroidRuntime SyncListen/WebSocket SyncListen/Player SyncListen/PlaybackSync SyncListen/Persistence
& $adb -s emulator-5554 shell dumpsys activity services com.synclisten.app
& $adb -s emulator-5554 shell dumpsys activity activities
& $adb -s emulator-5554 shell uiautomator dump /sdcard/window.xml
& $adb -s emulator-5554 shell screencap -p /sdcard/screen.png
& $adb -s emulator-5554 pull /sdcard/screen.png .\screen.png
```

房间「诊断信息」包含 WebSocket、缓存、serverOffsetMs、rttMs、syncErrorMs、playbackSpeed。Release 关闭 HTTP 正文日志，避免泄露设备凭据。提交日志前去除邀请 token、Authorization、设备 secret、个人音频和 IP 等不必要信息。

## 恢复验收

真实 Room 数据库回归在独立内存数据库上运行，不读取用户房间：

```powershell
Set-Location S:\android-app
$env:ANDROID_SERIAL='emulator-5554'
.\gradlew.bat connectedDebugAndroidTest --no-daemon
```

`HostRecoveryDatabaseTest` 覆盖连续三次恢复、异常关闭后恢复、曲目/凭据/暂停点保留与显式关闭清理。报告在 `app/build/reports/androidTests/connected`。必须连续恢复至少两次，单次恢复的内存快照可能掩盖父表 REPLACE 级联删除问题。

在测试房间中先暂停并记录位置。对 Host 执行 `adb shell am force-stop com.synclisten.app`，重新启动后从首页恢复卡片恢复。核对房间码、列表、成员和暂停状态；恢复后成员重新连入。此操作模拟强制停止，不等同于系统自然回收。

测试完通过界面显式结束房间，再重开 App，确认没有可恢复卡片。不通过删数据库伪造结果，不对用户正在使用的房间执行破坏性测试。

## 签名与分发

```powershell
Set-Location S:\
# 仅首次且确实没有旧签名时才使用 -InitializeSigning
.\scripts\build-release.ps1
& "$env:ANDROID_HOME\build-tools\35.0.0\apksigner.bat" verify --verbose --print-certs android-app\app\build\outputs\apk\release\app-release.apk
```

签名文件在 `$HOME\.synclisten\signing`，不在仓库。password.dpapi.xml 只可由当前 Windows 用户解密；不能把复制该文件当作可跨机恢复的密码备份。应安全保管 keystore 与可恢复的密码材料，丢失后无法维持同一 Android 签名升级。不要提交或打印密码。

使用完整 JDK 构建的桌面目录为 `desktop/build/compose/binaries/main/app/SyncListen`。发布时打包整个目录，保留 runtime/legal 和依赖 JAR 的许可证。

## 常见故障

- 401/403：检查请求头与 origin；所有设备升级到 v0.3.0 并重建旧房间，不要移除鉴权解决问题。
- 连接失败：确认端口、同一局域网、热点隔离、VPN、防火墙；离房后才切换服务器。
- 下载失败：检查可用空间、READY 状态、文件长度/hash；重新加入可重建队列，永久失败需后续改进。
- Android 测试类找不到：使用 S: ASCII 路径；不要反复换 CMD/Bash。
- jpackage 找不到：切换完整 JDK 21，不是重新安装 Android SDK。
- Release 安装签名冲突：同包名签名不同不能覆盖；先保留需要的数据，再卸载旧测试包。
