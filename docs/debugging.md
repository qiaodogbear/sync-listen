# 调试指南

## 日志位置

- 后端前台开发日志：`npm run dev` 控制台。
- 当前本机后台验收日志：`backend/data/runtime.log`、`runtime.err.log`。
- Android：`adb logcat -s SyncListen/HTTP SyncListen/WebSocket SyncListen/Player SyncListen/PlaybackSync SyncListen/ServerClock`。
- Android 构建报告：`android-app/app/build/reports/`。

## 房间页指标

- `webSocketStatus`：连接、重连和失败状态。
- `downloadQueueSize`：当前 WorkManager 优先级链。
- `serverOffsetMs`、`rttMs`：服务器时钟估算。
- `expectedPositionMs`、`syncErrorMs`、`playbackSpeed`：同步修正状态。
- 本地播放器状态和缓存摘要。

## 常用命令

```powershell
Invoke-RestMethod http://localhost:3000/health
Get-NetTCPConnection -LocalPort 3000 -State Listen
adb devices
adb -s emulator-5554 shell pidof com.synclisten.app.debug
adb -s emulator-5554 logcat -d | Select-String SyncListen
adb -s emulator-5554 shell dumpsys activity services com.synclisten.app.debug
adb -s emulator-5554 shell dumpsys notification --noredact
```

## 常见故障

### 模拟器无法连接后端

使用 `10.0.2.2` 而不是 `localhost`。确认后端监听 `0.0.0.0:3000`，必要时执行
`adb shell svc data enable`。模拟器可能能访问该地址但不把网络标记为 VALIDATED。

### 成员无法连接手机 Host

确认 Host 前台通知显示 `http://<LAN_IP>:38571`，成员与 Host 位于同一 Wi-Fi/热点，
且热点未启用客户端隔离。模拟器之间受 NAT 隔离，验收时可在 A 执行
`adb -s emulator-5554 forward tcp:38571 tcp:38571`，再让 B 使用
`http://10.0.2.2:38571`；真机不需要该转发。

### Windows 中文路径下测试类无法加载

```powershell
subst S: C:\Users\15224\Desktop\工程\sync-listen
cd S:\android-app
.\gradlew.bat testDebugUnitTest
```

### Gradle clean 被 lint-cache 占用

先执行 `.\gradlew.bat --stop`，再使用 `--no-daemon` 清理构建。

### 成员或播放状态不收敛

检查 WebSocket 日志和服务端 `GET /api/rooms/{roomId}` 权威快照。重连会用
`ROOM_JOINED` 替换本地状态；同一用户的多个短时连接不会错误标记离线。

### 下载失败

确认 Track 为 READY、下载 URL 可访问且磁盘可写。Worker 最多重试，重新连接房间会
强制恢复下载队列。hash 不匹配的临时文件会删除并标记失败。
