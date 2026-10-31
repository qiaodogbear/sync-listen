# 开发、模拟运行与调试

## 本机工具

本机使用 Windows / PowerShell 7。以下路径为本机配置，其他开发者应换成自己的安装位置，不要提交 local.properties。

| 工具 | 本机位置 / 用法 |
|---|---|
| 工程 | C:\JoeProjects\Temp\sync-listen |
| Node / npm | Node 24；最低 22.13 |
| Git | C:\Program Files\Git\cmd\git.exe |
| GitHub CLI | gh；已登录账号通过系统凭据存储，不导出 token |
| 完整 JDK 21 | C:\JoeProjects\Temp\android-toolchain\jdk\jdk-21.0.13.0.2+1；含 jpackage |
| Android SDK | C:\JoeProjects\Temp\android-toolchain\sdk |
| adb / emulator | SDK 的 platform-tools\adb.exe / emulator\emulator.exe |
| 镜像与 AVD | API 35 Google APIs x86_64；SyncListen_Test_A / SyncListen_Test_B，Pixel 6；AVD 目录在 C:\JoeProjects\Temp\android-toolchain\avd |
| Gradle | 使用仓库 wrapper，不使用全局 Gradle |

PowerShell 会话配置：

```powershell
$projectRoot='C:\JoeProjects\Temp\sync-listen'
$env:JAVA_HOME='C:\JoeProjects\Temp\android-toolchain\jdk\jdk-21.0.13.0.2+1'
$env:ANDROID_HOME='C:\JoeProjects\Temp\android-toolchain\sdk'
$env:ANDROID_AVD_HOME='C:\JoeProjects\Temp\android-toolchain\avd'
$adb="$env:ANDROID_HOME\platform-tools\adb.exe"
```

## 构建和测试

```powershell
Set-Location "$projectRoot\backend"
npm ci
npm run lint
npm run typecheck
npm test
npm run build
npm audit --audit-level=high
```

```powershell
Set-Location "$projectRoot\android-app"
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --no-daemon
```

```powershell
Set-Location $projectRoot
.\gradlew.bat :shared:test :desktop:test :desktop:createDistributable --no-daemon
```

Android 报告位于 app/build/reports，JUnit XML 位于 app/build/test-results；shared/desktop 对应各自 build/test-results/test。桌面打包须使用含 jmods 和 jpackage 的完整 JDK。

## 模拟器

已有两个 AVD，无需重新创建。新机器可使用 Android Studio Device Manager，或 SDK command-line tools 的 sdkmanager / avdmanager 安装 emulator 与 `system-images;android-35;google_apis;x86_64`，接受 licenses，再按 pixel_6 设备创建 AVD。

```powershell
& "$env:ANDROID_HOME\emulator\emulator.exe" -list-avds
Start-Process -FilePath "$env:ANDROID_HOME\emulator\emulator.exe" -ArgumentList '-avd','SyncListen_Test_A','-port','5554','-no-window','-no-audio','-no-boot-anim' -WindowStyle Hidden
& $adb -s emulator-5554 shell getprop sys.boot_completed
& $adb devices
```

boot_completed 必须为 1，devices 必须为 device。B 使用 SyncListen_Test_B 和端口 5556。本机 Windows Hypervisor Platform 已启用，`emulator -accel-check` 检测到 WHPX 可用。避免同时运行两个模拟器和大规模 Gradle/R8 构建。

## 安装和启动

```powershell
# Debug
& $adb -s emulator-5554 install -r "$projectRoot\android-app\app\build\outputs\apk\debug\app-debug.apk"
& $adb -s emulator-5554 shell am start -n com.synclisten.app.debug/com.synclisten.app.MainActivity

# 正式签名 Release
& $adb -s emulator-5554 install -r "$projectRoot\android-app\app\build\outputs\apk\release\app-release.apk"
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

手机模式直接用 A 的界面创建房间，不需要 Node。Emulator 36.5+ 支持共享虚拟 Wi-Fi：

```powershell
# A/B 都使用这些额外参数启动，B 改 AVD 名和端口 5556。
Start-Process -FilePath "$env:ANDROID_HOME\emulator\emulator.exe" -ArgumentList '-avd','SyncListen_Test_A','-port','5554','-no-window','-no-audio','-no-snapshot-load','-feature','WiFiPacketStream','-memory','1536' -WindowStyle Hidden
# 等待两台启动完成后，让两台都连到扫描中实际存在的 AndroidWifi。
& $adb -s emulator-5554 shell cmd wifi connect-network AndroidWifi open
& $adb -s emulator-5556 shell cmd wifi connect-network AndroidWifi open
& $adb -s emulator-5554 shell ip -4 addr show wlan0
& $adb -s emulator-5556 shell ip -4 addr show wlan0
```

地址由模拟器网络分配，不应写死。附近功能必须双方打开“附近可见”且 App 在前台。系统版本和配置不同可能不支持共享 Wi-Fi，参见 [官方模拟器互联说明](https://developer.android.com/studio/run/emulator-networking-interconnect)。

旧版或隔离 NAT 模式中，B 不能直接使用另一个模拟器的 10.0.2.15，此时可用以下后备方案。它只能测试手动加入，不代表 NSD 被测通：

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

房间「诊断信息」包含 WebSocket、缓存、serverOffsetMs、rttMs、syncErrorMs、playbackSpeed，以及检查点计数、时钟不确定度和纠偏策略。UI 当前进度与最近检查点不是同时取样，不可直接相减；断线时误差只作历史样本显示，不标“正常”。Release 关闭 HTTP 正文日志，避免泄露设备凭据。提交日志前去除邀请 token、Authorization、设备 secret、个人音频和 IP 等不必要信息。

## 恢复验收

真实 Room 数据库回归在独立内存数据库上运行，不读取用户房间：

```powershell
Set-Location "$projectRoot\android-app"
$env:ANDROID_SERIAL='emulator-5554'
.\gradlew.bat connectedDebugAndroidTest --no-daemon
```

`HostRecoveryDatabaseTest` 覆盖连续三次恢复、异常关闭后恢复、曲目/凭据/暂停点保留与显式关闭清理；`HomeRecoveryStateTest` 覆盖活动房间和显式结束之后不能残留恢复卡片。报告在 `app/build/reports/androidTests/connected`。必须连续恢复至少两次，单次恢复的内存快照可能掩盖父表 REPLACE 级联删除问题。

在测试房间中先暂停并记录位置。对 Host 执行 `adb shell am force-stop com.synclisten.app`，重新启动后从首页恢复卡片恢复。核对房间码、列表、成员和暂停状态；恢复后成员重新连入。此操作模拟强制停止，不等同于系统自然回收。

测试完通过界面显式结束房间，再重开 App，确认没有可恢复卡片。不通过删数据库伪造结果，不对用户正在使用的房间执行破坏性测试。

## 签名与分发

```powershell
Set-Location $projectRoot
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
- Android 测试类找不到：先检查工程路径和构建日志；非 ASCII 路径可使用映射到工程的空闲盘符。
- jpackage 找不到：切换完整 JDK 21，不是重新安装 Android SDK。
- Release 安装签名冲突：同包名签名不同不能覆盖；先保留需要的数据，再卸载旧测试包。
