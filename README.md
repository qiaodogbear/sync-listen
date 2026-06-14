# Sync Listen

Sync Listen 是一个 Android 多人同步听歌原型。成员加入同一房间后共享播放列表，
将 MP3/FLAC 上传到中心服务器并自动缓存到各设备；Host 统一控制播放，各设备从
本地已校验文件播放，并通过服务器时钟、计划执行、周期 SYNC、seek 和轻微变速保持同步。

## 功能

- 临时昵称创建/加入房间，Host/Member 权限。
- 手动房间码、二维码、深链、BLE 房间码发现和 NFC 加入链接。
- MP3/FLAC 上传、SHA-256 去重、自动下载、校验和缓存清理。
- Media3 本地播放，统一播放/暂停/seek/next。
- 断线继续播放、自动重连、同步误差与下载队列调试信息。
- 可由一台 Android 手机在同一 Wi-Fi 或手机热点内托管房间，无需电脑后端。

## 架构与目录

```text
sync-listen/
  android-app/        Kotlin、Compose、Media3、Room、WorkManager、Hilt
  backend/            Node.js、TypeScript、Fastify、SQLite、本地音频目录
  docs/               API、WebSocket、架构、调试、已知问题和测试报告
  .env.example        后端环境变量示例
  TASKS.md            唯一任务与进度清单
  EXECUTION_LOG.md    轻量恢复日志
```

详细数据流见 [docs/architecture.md](docs/architecture.md)。

## 环境要求

- Node.js 22 或更新版本，npm。
- JDK 17；推荐 Android Studio 内置 JBR。
- Android SDK 35、Android Emulator。
- 首次验收推荐两个 Pixel 6 / API 35 Google APIs x86_64 AVD。

## 启动后端

```powershell
cd backend
npm install
Copy-Item ..\.env.example .env
npm run db:migrate
npm run dev
```

健康检查：`http://127.0.0.1:3000/health`。生产式运行使用
`npm run build` 后执行 `npm start`。

## 构建与安装 Android

Windows 中文路径下建议使用 ASCII 驱动器映射：

```powershell
subst S: C:\Users\15224\Desktop\工程\sync-listen
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
cd S:\android-app
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Debug APK 位于 `android-app/app/build/outputs/apk/debug/app-debug.apk`。

使用电脑后端时，Debug App 默认连接 `http://10.0.2.2:3000`。真机需在“调试设置”
中改为后端电脑的局域网地址，例如 `http://192.168.1.10:3000`。

## 手机托管模式

1. 让两台手机连接同一 Wi-Fi，或让成员手机连接 Host 手机创建的热点。
2. Host 输入昵称和房间名，点击“手机托管房间”，并允许通知权限。
3. Host 保持 Sync Listen 前台托管通知运行；成员通过二维码、深链、BLE、NFC，或输入
   Host 地址和房间码加入。
4. 后续上传、下载、缓存和同步播放操作与电脑后端模式相同。

手机托管服务监听 TCP `38571`。不需要电脑或互联网，但 Host 与成员必须能通过局域网
互相访问；厂商热点若启用了客户端隔离会导致成员无法加入。

## 双设备使用

1. 选择手机托管模式，或启动电脑后端并确认两个 Android 设备能访问 `/health`。
2. A 输入昵称和房间名并创建房间。
3. B 使用房间码、二维码、深链、BLE 发现或 NFC 邀请加入。
4. 任一成员通过“上传歌曲”选择 MP3/FLAC；各设备自动下载并校验。
5. Host 点击“播放此曲”，再使用暂停、前进 5 秒和下一首控制。
6. 房间页查看缓存、队列、WebSocket、`serverOffsetMs`、`rttMs`、
   `syncErrorMs` 和 `playbackSpeed`。

二维码/深链/NFC 可携带加入令牌；手机托管模式的 BLE 邀请还携带 Host IPv4 和端口。

## 网络测试

- 模拟器到宿主机：使用 `http://10.0.2.2:3000`。
- 双模拟器测试手机托管：使用 `adb -s emulator-5554 forward tcp:38571 tcp:38571`，
  B 输入 `http://10.0.2.2:38571`；这只用于绕过模拟器 NAT。
- 局域网真机：开放 TCP 3000，并使用宿主机局域网 IP。
- 手机托管真机：使用 Host 页面/邀请显示的局域网 IPv4，端口为 TCP 38571。
- 公网：当前无 TLS、账号或反向代理内置支持；必须自行放在 HTTPS/WSS 反向代理后，
  且不应直接公开本原型服务。

## 验证

```powershell
cd backend
npm run lint
npm run typecheck
npm test
npm run build

cd ..\android-app
.\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug
```

完整结果见 [docs/test-report.md](docs/test-report.md)。故障排查见
[docs/debugging.md](docs/debugging.md)，限制见 [docs/known-issues.md](docs/known-issues.md)。
