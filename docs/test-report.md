# 测试与验收报告

## 2026-06-13 P0 验收

### 环境

- 宿主机：Windows，Fastify 后端运行于本机 `0.0.0.0:3000`。
- Android：两个 Pixel 6 AVD，Android 15 / API 35 Google APIs x86_64。
- AVD：`SyncListen_A`（`emulator-5554`）、`SyncListen_B`（`emulator-5556`）。
- 客户端服务器地址：`http://10.0.2.2:3000`。
- 房间：A 以 Host 创建，B 通过二维码加入并以 Member 连接。

### 自动化验证

- 后端：lint、TypeScript 类型检查、22 个测试和构建全部通过。
- Android：单元测试、lint 和 Debug 构建全部通过。
- Android 清理构建：停止 Gradle daemon 后执行
  `clean testDebugUnitTest lintDebug assembleDebug --no-daemon`，全部通过。

### 双设备闭环

- A、B 均能实时看到成员、播放列表、缓存、连接和同步状态。
- A 通过 App UI 上传 MP3，服务端确认 uploader 为 Host；B 自动下载并校验。
- B 通过 App UI 上传 FLAC，服务端确认 uploader 为 Member；A 自动下载并校验。
- MP3 校验样本：
  `6b46c048860efaaed016d72e28351d54999726de9f6a193e9451c1870e157690`。
- FLAC 校验样本：
  `41a283752f22ceb50e52353eb9b8f5a5dedc3a250e30c2ebb9105b0564883e8c`。
- 后台下载样本：
  `5494702a01c88491e015a5f3c6d5fbf3002ce80d46ebd64af77a38427d329ec3`。
- A、B 均从本地 VERIFIED 缓存播放；播放、暂停、继续、seek 和 next 同步生效。
- 当前播放期间后台上传和下载未观察到明显播放中断。
- B 断网后继续本地播放并显示同步断开；网络恢复后自动重连并恢复房间状态。

### 同步观测

- 稳态 `syncErrorMs` 通常约为 30-71ms。
- UI 曾观察到约 555ms 的瞬时误差，随后由大误差 seek 自动修正。
- 服务端时钟偏移和 RTT 会显示于调试区域并周期更新。

### 邀请验收说明

- A 生成的二维码截图由 OpenCV 实际解码为预期的
  `synclisten://join` 链接，B 复用扫描结果的统一解析与确认加入流程成功加入。
- 当前两个无界面模拟器无法让 B 的虚拟相机物理对准 A 的屏幕，因此未验证真实相机光学采集。
- 相机权限拒绝后仍可使用手动房间码；冷启动、热启动和非法深链均已验证。

### 验收中修复的问题

- 后端测试使用独立临时 SQLite，消除并行测试锁竞争。
- WorkManager 不依赖模拟器未标记为 VALIDATED 的本地网络状态。
- 已结束短曲目不会被后续 `SYNC` 重启。
- WebSocket 过期回调不会触发并发重连风暴。
- 重复并发播放事件由互斥锁串行处理，避免重复 prepare。

### 已知环境限制

- Node.js 24 的内置 `node:sqlite` 会输出 ExperimentalWarning。
- Windows 中文真实路径下 Gradle 单测类路径存在编码问题，使用 `S:` ASCII 映射运行。
- Robolectric 运行时 Android artifact 在当前网络环境下载挂起；缓存策略使用纯 JVM Fake DAO 测试，Room schema/DAO 由 KSP 构建验证。

## 2026-06-13 P1 增强验收

- T101 播放速度微调：假时钟/假播放器覆盖正负 80-300ms 误差、恢复
  1.0x、重复命令抑制和大误差 seek；Android 全量单测、lint 与 Debug 构建通过。
- T102 并发添加：8 个相同文件并发上传仅产生一份物理文件，Track 顺序为
  0-7 且唯一连续；客户端拒绝迟到播放列表快照并规范化重复/乱序条目。
