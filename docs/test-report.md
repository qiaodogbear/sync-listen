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
- T103 下载队列：播放状态变化重建当前/下一首优先的 WorkManager 单链，重连
  强制恢复失败或取消队列；规划与修订测试、Android 全量验证通过。
- T104 角色权限：Member 控制在 Android UI/ViewModel 和后端 API 双层阻止；
  play/pause/seek/next 四个手工 HTTP 绕过请求均返回 `403 HOST_REQUIRED`。
- T105 BLE 邀请：Sync Listen Service UUID 的 Service Data 仅携带 6 字节房间码，
  API 版本权限、解析和拒绝权限回退测试通过。
- T106 NFC 加入：有效 `NDEF_DISCOVERED` URI 在 API 35 模拟器进入统一邀请确认
  流程；无效 Tag、无 NFC/关闭提示和链接筛选有独立测试。
- T107 中间回归：后端 lint/typecheck/24 tests/build、Android 无缓存 clean
  test/lint/assemble 通过；两个模拟器分别通过手动房间码与深链加入同一房间。
  回归中修复同一用户多 WebSocket 时旧连接关闭导致在线状态误报的问题。

## 2026-06-13 最终交付回归

### 双模拟器闭环

- 当前版本在 `SyncListen_A` 与 `SyncListen_B` 上重新完成创建房间、手动加入、MP3/FLAC
  上传、秒传、双端自动下载、缓存校验、播放、暂停、seek、next、断线与重连。
- 双端缓存包含相同的 MP3 与 FLAC，文件名使用预期 SHA-256；UI 均显示本地缓存可用。
- 播放中并行采样：短曲结束位置分别为 `11993ms` 与 `11994ms`，误差 `1ms`；
  seek 后位置分别为 `11836ms` 与 `11844ms`，误差 `8ms`；断线继续播放采样误差 `21ms`。
- 暂停后双端位置均为 `3447ms`，服务端重启期间均显示重连中并继续本地播放；
  服务恢复后两端自动变为已连接并恢复成员、列表和当前曲目。
- 速度修正曾显示 `1.02x`，收敛后恢复 `1.0x`。

### 回归中修复

- Media3 异步准备此前可能让设备按各自准备完成时间启动。现在 `prepare` 等待 Ready，
  且错过计划执行时间时按迟到量补偿 seek。
- 播放中重复 Ready 事件此前会覆盖 PLAYING 状态并导致暂停命令被忽略。状态机现保留
  PLAYING，回归测试覆盖重复 Ready 后暂停。

### 最终自动化与交付检查

- 后端：`npm ci`、`npm run db:migrate`、lint、typecheck、24/24 tests、build 与运行时
  `/health` 全部通过。
- Android：`clean testDebugUnitTest lintDebug assembleDebug --no-daemon` 通过；干净 APK
  已覆盖安装并启动，应用进程持续运行。
- `npm audit --omit=dev --audit-level=high` 为 0；完整开发依赖审计报告 5 个 high severity
  漏洞，后续升级时需回归测试工具链。
- BLE 最终验收：`SyncListen_A` 进入 `ADVERTISING`，`SyncListen_B` 扫描发现房间码
  `3C74AF`，点击附近房间后通过互联网以 MEMBER 加入，两端均显示 Host/Member 在线。
- 真实 NFC Tag 射频读取建议在发布前补验；当前 NFC 协议和加入闭环通过模拟器 Intent 注入验收。

### BLE 广播兼容性修复

- 最终审计发现同时广播 128-bit Service UUID、Service Data UUID 和
  `SyncListen:<ROOM_CODE>` 会超过传统 BLE 31 字节限制，模拟器返回
  `ADVERTISE_FAILED_DATA_TOO_LARGE`。
- 广播现由 128-bit Service UUID 标识 Sync Listen，Service Data 只携带 6 字节房间码。
  修复后 A 广播成功，B 的低延迟扫描收到结果并完成后端加入。
