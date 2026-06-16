# Sync Listen 多人同步听歌 App 任务列表

> 本文件是项目唯一进度跟踪清单。执行任务时逐项勾选，并在每个阶段验收通过后更新阶段状态。

## 0. 项目约定

### 0.1 目标

构建一个可供朋友实际使用的 Android 多人同步听歌 App 原型。多部手机加入同一房间，共享播放列表，上传并缓存本地 MP3/FLAC，由房主控制各设备从本地缓存大致同步播放。

### 0.2 固定技术方案

- Monorepo：`android-app/`、`backend/`、`docs/`、`README.md`
- Android：Kotlin、Jetpack Compose、Media3、Room、DataStore、WorkManager、Hilt、Retrofit、OkHttp WebSocket
- Android 配置：`minSdk 26`、`compileSdk 35`、`targetSdk 35`、包名 `com.synclisten.app`
- 后端：Node.js、TypeScript、Fastify、SQLite、本地磁盘音频存储
- 后端测试：Vitest、Fastify inject、WebSocket 多客户端测试
- 文件去重与完整性校验：SHA-256
- 身份：临时 `userId`、昵称、房间加入令牌，不实现账号系统
- 首期验收环境：本机后端 + 两个 Android 模拟器，通过局域网地址连接

### 0.3 核心规则

- Host 可播放、暂停、拖动和切歌；Member 可上传、下载和收听，不能控制播放。
- Host 离开后关闭房间，不实现主机迁移。
- 当前播放必须使用本地缓存，不使用实时音频流。
- 服务器统一分配播放列表顺序和播放执行时间。
- WebSocket 消息统一为 `{ "type": "...", "payload": {}, "serverTimeMs": 0 }`。
- P0 完整闭环验收通过后，才开始 P1。
- P2 不实现，只记录接口方向和 backlog。

### 0.4 总体验收命令

```powershell
# 后端
cd C:\Users\15224\Desktop\工程\sync-listen\backend
npm ci
npm run lint
npm run typecheck
npm test
npm run build

# Android
cd C:\Users\15224\Desktop\工程\sync-listen\android-app
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

---

## 1. 阶段一：准备与工程骨架（P0）

**阶段依赖：** 无  
**阶段完成条件：** 后端可启动；Android 可构建并安装；README 有基础运行方法。

### T001 创建 Monorepo 与基础文档

**依赖：** 无

- [x] 创建 `android-app/`、`backend/`、`docs/` 目录。
- [x] 初始化 Git，并创建适用于 Android、Node.js、SQLite 数据文件和音频缓存的 `.gitignore`。
- [x] 创建根目录 `README.md`，写明项目目标、目录结构、环境要求和当前状态。
- [x] 创建 `.env.example`，至少包含服务监听地址、端口、SQLite 路径、音频目录、房间清理时间。
- [x] 记录本地开发默认端口和 Android 模拟器访问宿主机的地址规则。

**验证：**

```powershell
cd C:\Users\15224\Desktop\工程\sync-listen
git status
Get-ChildItem -Force
```

**验收：**

- [x] 根目录结构与约定一致。
- [x] 环境配置示例不包含密钥或本机私有数据。

### T002 初始化 Fastify 后端

**依赖：** T001

- [x] 初始化 Node.js + TypeScript 工程，锁定 Node.js 版本要求。
- [x] 配置 Fastify、环境变量校验、结构化日志、统一错误处理和优雅退出。
- [x] 配置 ESLint、TypeScript 类型检查、Vitest 和构建脚本。
- [x] 实现 `GET /health`，返回服务状态和服务器时间。
- [x] 建立 `src/app.ts` 与 `src/server.ts` 分离结构，允许测试通过 Fastify inject 启动应用。

**验证：**

```powershell
cd backend
npm run lint
npm run typecheck
npm test
npm run build
npm run dev
```

**验收：**

- [x] `GET /health` 返回 HTTP 200。
- [x] 类型检查、测试和构建通过。

### T003 初始化 SQLite 与数据目录

**依赖：** T002

- [x] 选择并配置 SQLite 驱动和迁移机制。
- [x] 建立 `rooms`、`members`、`tracks`、`playback_states` 数据表。
- [x] 对 `roomId`、`trackId`、`fileHash`、`orderIndex` 建立必要索引和唯一约束。
- [x] 建立运行时音频目录和临时上传目录。
- [x] 增加数据库初始化、迁移和测试数据库清理脚本。

**验证：**

```powershell
cd backend
npm test -- database
npm run db:migrate
```

**验收：**

- [x] 新环境可通过单条命令完成数据库初始化。
- [x] 测试数据库可独立创建与清理。

### T004 初始化 Android Compose 工程

**依赖：** T001

- [x] 创建单 Activity Jetpack Compose 工程，包名为 `com.synclisten.app`。
- [x] 配置 Gradle Wrapper、版本目录和 Debug/Release 构建类型。
- [x] 配置 Hilt、Navigation Compose、Coroutines、Retrofit、OkHttp、Room、DataStore、WorkManager、Media3。
- [x] 建立 `ui/`、`data/`、`domain/`、`playback/`、`transfer/`、`nearby/`、`util/` 包结构。
- [x] 实现基础主题、导航壳、日志封装和 Debug 环境服务器地址配置。
- [x] 配置 `INTERNET`，并为局域网 HTTP 调试提供明确的 Debug 网络安全配置。

**验证：**

```powershell
cd android-app
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

**验收：**

- [x] Debug APK 构建成功。
- [x] App 可在模拟器启动并显示占位首页。

---

## 2. 阶段二：共享协议与后端核心（P0）

**阶段依赖：** 阶段一  
**阶段完成条件：** 后端测试覆盖房间、成员、上传下载、权限和播放控制。

### T005 定义共享领域模型与协议文档

**依赖：** T002、T003、T004

- [x] 定义 Room、Member、Track、PlaybackState、TransferStatus 数据结构。
- [x] 固定 Track 字段：`trackId`、`roomId`、`title`、`artist`、`durationMs`、`fileName`、`fileSize`、`fileHash`、`uploaderId`、`uploaderName`、`orderIndex`、`status`、`createdAt`。
- [x] 固定 REST 成功响应和错误响应格式。
- [x] 固定 WebSocket 信封和事件 payload。
- [x] 在 `docs/api.md` 与 `docs/websocket.md` 中记录协议。
- [x] Android 与后端分别建立与文档一致的模型和序列化测试。

**验证：**

```powershell
cd backend
npm test -- protocol
cd ..\android-app
.\gradlew.bat testDebugUnitTest --tests "*Protocol*"
```

**验收：**

- [x] Android 与后端字段名称、空值规则和枚举值一致。
- [x] 协议文档包含请求、响应和错误示例。

### T006 实现房间与成员 REST API

**依赖：** T005

- [x] 实现 `POST /api/rooms`，创建 Host、房间码和加入令牌。
- [x] 实现 `POST /api/rooms/{roomId}/join`，通过令牌或房间码加入。
- [x] 实现 `GET /api/rooms/{roomId}`，返回房间、成员和播放状态快照。
- [x] 实现 Host 离开关闭房间的逻辑。
- [x] 校验昵称、房间码、令牌和不存在/已关闭房间错误。

**验证：**

```powershell
cd backend
npm test -- rooms
```

**验收：**

- [x] 创建者获得 Host 身份。
- [x] 第二个用户可加入并获得 Member 身份。
- [x] 错误令牌和已关闭房间返回明确错误码。

### T007 实现 WebSocket 房间连接与广播

**依赖：** T006

- [x] 实现 `/ws/rooms/{roomId}?token=JOIN_TOKEN&userId=USER_ID`。
- [x] 连接时校验房间、用户和令牌。
- [x] 实现 `ROOM_JOINED`、`MEMBER_JOINED`、`MEMBER_LEFT`、`ERROR`。
- [x] 管理房间连接集合、心跳、断开清理和异常日志。
- [x] 新连接收到完整房间快照，现有连接收到成员增减广播。

**验证：**

```powershell
cd backend
npm test -- websocket
```

**验收：**

- [x] 两个 WebSocket 客户端加入同一房间后能实时看到成员变化。
- [x] 无效身份无法建立连接。

### T008 实现播放列表与并发顺序分配

**依赖：** T006、T007

- [x] 实现 `GET /api/rooms/{roomId}/playlist`。
- [x] 由服务器在事务中分配连续、唯一的 `orderIndex`。
- [x] 实现 `TRACK_ADDED`、`TRACK_READY`、`PLAYLIST_UPDATED` 广播。
- [x] 为并发添加歌曲建立集成测试，确认不会出现重复顺序。

**验证：**

```powershell
cd backend
npm test -- playlist
```

**验收：**

- [x] 播放列表按 `orderIndex` 稳定排序。
- [x] 并发添加不会覆盖或丢失歌曲。

### T009 实现音频上传、秒传与下载

**依赖：** T008

- [x] 实现 `POST /api/rooms/{roomId}/tracks` multipart 上传。
- [x] 仅接受 MP3/FLAC，并限制可配置的最大文件大小。
- [x] 校验客户端 SHA-256；服务器重新计算并拒绝不一致文件。
- [x] 相同 hash 已存在时复用文件并直接创建 READY Track。
- [x] 新文件先写临时目录，校验成功后原子移动到正式目录。
- [x] 实现 `GET /api/tracks/{trackId}/download`，支持流式下载和正确文件名。
- [x] 实现失败上传清理和过期房间文件清理任务。

**验证：**

```powershell
cd backend
npm test -- tracks
npm test -- cleanup
```

**验收：**

- [x] 上传成功后所有房间成员收到歌曲更新。
- [x] 相同 hash 不重复保存物理文件。
- [x] 下载文件 hash 与上传文件一致。

### T010 实现播放控制与服务器同步

**依赖：** T007、T008

- [x] 实现 `GET /api/time`，返回服务器时间。
- [x] 实现 Host 专用的 play、pause、seek、next API。
- [x] PLAY、SEEK、NEXT 使用服务器当前时间加固定缓冲生成 `executeAtServerTimeMs`。
- [x] PAUSE 保存准确的服务端播放状态。
- [x] 服务端持续保存可恢复的 PlaybackState。
- [x] 播放期间定期广播 `SYNC`。
- [x] 拒绝 Member 发出的播放控制请求。

**验证：**

```powershell
cd backend
npm test -- playback
npm test -- sync
```

**验收：**

- [x] 多个客户端收到相同执行时间。
- [x] Member 控制请求返回权限错误。
- [x] 新连接可获得当前播放状态。

---

## 3. 阶段三：Android 房间功能（P0）

**阶段依赖：** 阶段二  
**阶段完成条件：** 两个模拟器可加入同一房间并实时看到成员变化。

### T011 实现 Android API、身份与设置层

**依赖：** T005、T006

- [x] 使用 Retrofit 实现房间、播放列表、时间和播放控制 API。
- [x] 使用 DataStore 保存临时 `userId`、昵称和服务器地址。
- [x] 实现统一 API 错误解析、超时和日志。
- [x] 实现可切换服务器地址的 Debug 设置入口。
- [x] 为 Repository 建立 Fake API 单元测试。

**验证：**

```powershell
cd android-app
.\gradlew.bat testDebugUnitTest --tests "*Repository*"
```

**验收：**

- [x] 重启 App 后保留用户身份和服务器地址。
- [x] 网络错误在 UI 层可读。

### T012 实现首页、创建房间与手动加入

**依赖：** T011

- [x] 实现首页和临时昵称输入。
- [x] 实现创建房间流程，成功后进入房间页。
- [x] 实现手动房间码加入流程。
- [x] 实现加载、错误和重试状态。
- [x] 保证重复点击不会创建或加入多次。

**验证：**

```powershell
cd android-app
.\gradlew.bat testDebugUnitTest --tests "*Home*"
.\gradlew.bat assembleDebug
```

**验收：**

- [x] 模拟器 A 可创建房间。
- [x] 模拟器 B 可通过房间码加入。

### T013 实现 Android WebSocket 客户端与状态恢复

**依赖：** T007、T011

- [x] 使用 OkHttp WebSocket 建立房间连接。
- [x] 将连接状态暴露为 Flow：连接中、已连接、重连中、已断开、失败。
- [x] 实现指数退避重连和生命周期管理。
- [x] 解析所有 P0 房间、播放列表和播放事件。
- [x] 重连后通过房间快照恢复成员、播放列表和播放状态。
- [x] 记录连接、断开、重连和消息解析错误日志。

**验证：**

```powershell
cd android-app
.\gradlew.bat testDebugUnitTest --tests "*WebSocket*"
```

**验收：**

- [x] 两个模拟器实时看到成员加入和离开。
- [x] 短暂断网后能自动重连并恢复房间状态。

### T014 实现房间页与状态展示

**依赖：** T012、T013

- [x] 展示房间名、房间码、当前角色、连接状态和成员列表。
- [x] 展示共享播放列表、歌曲 READY/UPLOADING/FAILED 状态。
- [x] 为上传、播放器、邀请和设置提供导航入口。
- [x] Host 离开后显示房间已关闭并返回首页。

**验证：**

```powershell
cd android-app
.\gradlew.bat testDebugUnitTest --tests "*Room*"
.\gradlew.bat lintDebug
```

**验收：**

- [x] 成员和播放列表更新无需手动刷新。
- [x] 角色和连接状态清晰可见。

---

## 4. 阶段四：上传、下载与缓存（P0）

**阶段依赖：** 阶段三  
**阶段完成条件：** A 上传后 B 自动下载、校验并建立缓存索引。

### T015 实现本地音频选择、元信息与 hash

**依赖：** T004

- [x] 使用系统文件选择器选择 MP3/FLAC，不依赖广泛存储权限。
- [x] 读取文件名、大小、格式、标题、艺术家和时长。
- [x] 流式计算 SHA-256，避免一次加载整个文件。
- [x] 实现不支持格式、无法读取和取消选择状态。
- [x] 为 hash 和格式校验建立单元测试。

**验证：**

```powershell
cd android-app
.\gradlew.bat testDebugUnitTest --tests "*FileHash*"
.\gradlew.bat testDebugUnitTest --tests "*AudioMetadata*"
```

**验收：**

- [x] MP3 和 FLAC 元信息可展示。
- [x] Android 计算结果与后端 SHA-256 一致。

### T016 实现上传管理器与上传 UI

**依赖：** T009、T015

- [x] 实现 multipart 上传与进度回调。
- [x] 显示文件信息、上传进度、成功、秒传、失败和重试状态。
- [x] 上传完成后依赖 WebSocket 更新播放列表。
- [x] 防止重复提交相同上传任务。
- [x] 记录上传开始、进度、失败和完成日志。

**验证：**

```powershell
cd android-app
.\gradlew.bat testDebugUnitTest --tests "*Upload*"
```

**验收：**

- [x] A 上传后 A、B 均看到新歌曲。
- [x] 失败上传可重试。

### T017 建立 Room 缓存索引

**依赖：** T004、T015

- [x] 建立缓存实体，包含 `trackId`、`fileHash`、`localPath`、`fileName`、`fileSize`、`durationMs`、`cachedAt`、`verifyStatus`、`roomId`。
- [x] 以 `fileHash` 作为物理文件去重依据。
- [x] 支持跨房间复用同一缓存文件。
- [x] 实现缓存查询、写入、校验状态更新和安全删除 DAO。
- [x] 为迁移、去重和查询建立 Room 测试。

**验证：**

```powershell
cd android-app
.\gradlew.bat testDebugUnitTest --tests "*Cache*"
```

**验收：**

- [x] 相同 hash 不产生重复缓存文件。
- [x] 索引可标识已缓存、未缓存和校验失败。

### T018 实现优先级下载队列

**依赖：** T009、T013、T017

- [x] 使用 WorkManager 实现持久化下载任务。
- [x] 定义优先级：当前曲目 0、下一首 1、其他曲目 2。
- [x] 播放期间限制并发；首期最大并发为 1，空闲时可调整为 2。
- [x] 下载到临时文件，完成后校验 SHA-256，再原子移动并写入缓存索引。
- [x] hash 失败时删除文件并按限制重试。
- [x] WebSocket 收到 TRACK_READY/PLAYLIST_UPDATED 后自动补充队列。
- [x] 显示下载进度、队列长度、缓存状态和 hash 校验结果。

**验证：**

```powershell
cd android-app
.\gradlew.bat testDebugUnitTest --tests "*Download*"
.\gradlew.bat testDebugUnitTest --tests "*QueuePriority*"
```

**验收：**

- [x] B 自动下载 A 上传的歌曲。
- [x] 当前和下一首歌曲优先于后续歌曲。
- [x] 校验失败文件不会进入可播放状态。

### T019 实现缓存清理

**依赖：** T017、T018

- [x] 实现缓存列表和占用空间展示。
- [x] 支持清理单首和清理全部非播放文件。
- [x] 当前播放文件不可删除。
- [x] 删除物理文件后同步删除或修正缓存索引。

**验证：**

```powershell
cd android-app
.\gradlew.bat testDebugUnitTest --tests "*CacheCleanup*"
```

**验收：**

- [x] 清理不会中断当前播放。
- [x] 清理后索引与磁盘状态一致。

---

## 5. 阶段五：本地播放与基础同步（P0）

**阶段依赖：** 阶段四  
**阶段完成条件：** 两个模拟器从本地缓存大致同步播放，并同步控制操作。

### T020 实现 Media3 本地播放器

**依赖：** T017、T018

- [x] 封装 `PlayerController`，只接受已校验本地缓存路径。
- [x] 实现准备、播放、暂停、seek、切歌、结束和错误状态。
- [x] 当前曲目未缓存时显示等待缓存，不启动网络流播放。
- [x] 暴露播放位置、时长、播放器状态和当前 track Flow。
- [x] 记录播放器状态和本地进度日志。

**验证：**

```powershell
cd android-app
.\gradlew.bat testDebugUnitTest --tests "*PlayerController*"
```

**验收：**

- [x] 已缓存 MP3/FLAC 可本地播放。
- [x] 未缓存歌曲不会直接播放。

### T021 实现服务器时间偏移估算

**依赖：** T010、T011

- [x] 多次请求 `/api/time`，记录请求开始、响应结束、RTT 和服务器时间。
- [x] 使用低 RTT 样本估算 `serverOffsetMs`。
- [x] 周期刷新偏移，并暴露 `estimatedServerNowMs`。
- [x] 为延迟、时钟偏移和异常响应建立单元测试。

**验证：**

```powershell
cd android-app
.\gradlew.bat testDebugUnitTest --tests "*ServerClock*"
```

**验收：**

- [x] UI 可显示 `serverOffsetMs` 和 `rttMs`。
- [x] 固定测试时钟下偏移计算准确。

### T022 实现 P0 播放同步管理器

**依赖：** T010、T013、T020、T021

- [x] 封装 `PlaybackSyncManager` 处理 PLAY、PAUSE、SEEK、NEXT、SYNC。
- [x] PLAY/SEEK/NEXT 等待到 `executeAtServerTimeMs` 再执行。
- [x] 根据同步消息计算 `expectedPositionMs` 和 `syncErrorMs`。
- [x] `abs(error) <= 300ms` 时不修正；大于 300ms 时 seek 到期望位置。
- [x] 预留播放速度修正接口，但 P0 不启用。
- [x] WebSocket 断线时继续当前播放并显示同步断开。
- [x] 重连后按服务器状态重新校准。

**验证：**

```powershell
cd android-app
.\gradlew.bat testDebugUnitTest --tests "*PlaybackSync*"
```

**验收：**

- [x] 两端收到相同事件后在计划时间播放。
- [x] 人工制造明显偏移后自动 seek 修正。
- [x] 断线重连后恢复服务器播放状态。

### T023 实现播放器与同步调试 UI

**依赖：** T014、T020、T022

- [x] 展示当前歌曲、播放状态、进度条和缓存状态。
- [x] Host 显示播放、暂停、seek、next 控件；Member 控件禁用或隐藏。
- [x] 展示 `serverOffsetMs`、`rttMs`、`localPositionMs`、`expectedPositionMs`、`syncErrorMs`、`webSocketStatus`、`downloadQueueSize`。
- [x] 展示同步断开、等待缓存和播放错误。

**验证：**

```powershell
cd android-app
.\gradlew.bat testDebugUnitTest --tests "*Player*"
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

**验收：**

- [x] Host 控制可同步影响 Member。
- [x] Member 无法发出控制请求。
- [x] 同步误差和连接状态可见。

---

## 6. 阶段六：二维码与深链（P0）

**阶段依赖：** 阶段三  
**阶段完成条件：** B 扫描 A 展示的二维码后成功加入。

### T024 定义加入链接与深链解析

**依赖：** T006、T011

- [x] 固定链接格式：`synclisten://join?roomId=...&token=...&server=...`。
- [x] 实现严格解析、URL 编解码和字段校验。
- [x] 在 Android Manifest 注册深链。
- [x] App 冷启动和运行中接收深链均进入加入确认流程。
- [x] 为有效、缺字段、非法 server 和错误 scheme 建立测试。

**验证：**

```powershell
cd android-app
.\gradlew.bat testDebugUnitTest --tests "*JoinLink*"
adb shell am start -a android.intent.action.VIEW -d "synclisten://join?roomId=test&token=test&server=http%3A%2F%2F10.0.2.2%3A3000"
```

**验收：**

- [x] 有效深链可进入加入流程。
- [x] 非法链接不会导致崩溃或静默加入。

### T025 实现二维码展示与扫描

**依赖：** T024

- [x] 创建房间后根据加入链接生成二维码。
- [x] 房间页提供邀请二维码入口。
- [x] 使用相机扫描二维码并复用深链解析流程。
- [x] 按 Android 版本正确请求和处理 CAMERA 权限。
- [x] 保留手动房间码入口，不因相机权限失败阻塞加入。

**验证：**

```powershell
cd android-app
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

**验收：**

- [x] B 扫描 A 二维码后可加入房间。
- [x] 拒绝相机权限后仍可手动加入。

---

## 7. 阶段七：P0 完整闭环验收

**阶段依赖：** 阶段二至阶段六  
**阶段完成条件：** 以下全部验收项通过并记录结果。

### T026 执行自动化验证

- [x] 后端 lint、类型检查、测试和构建全部通过。
- [x] Android 单元测试、lint 和 Debug 构建全部通过。
- [x] 清理安装后重新执行一次 Android 构建，排除本地缓存偶然成功。

**验证：**

```powershell
cd C:\Users\15224\Desktop\工程\sync-listen\backend
npm run lint
npm run typecheck
npm test
npm run build

cd ..\android-app
.\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug
```

### T027 执行双设备端到端验收

- [x] A 创建房间。
- [x] B 扫码加入房间。
- [x] A、B 均能看到成员状态。
- [x] A 上传 MP3，B 自动下载并通过 hash 校验。
- [x] B 上传 FLAC，A 自动下载并通过 hash 校验。
- [x] Host 播放后 A、B 从本地缓存开始播放。
- [x] 暂停、继续、seek、next 可同步。
- [x] 当前播放期间后台下载不导致明显播放中断。
- [x] B 断网时继续当前播放并显示同步断开。
- [x] B 恢复网络后重连、恢复房间状态并重新校准。
- [x] 上传、下载、缓存、WebSocket 和同步状态均可见。
- [x] 将测试设备、网络环境、同步误差范围和失败项记录到 `docs/test-report.md`。

---

## 8. 阶段八：P1 增强功能

**阶段依赖：** P0 完整闭环验收通过  
**阶段完成条件：** 每个启用的 P1 功能均有独立自动化测试或双设备验证记录。

### T101 实现播放速度微调弱同步

- [x] 将同步阈值固定为：小于 80ms 不处理，80-300ms 使用 0.98x/1.02x，大于 300ms seek，大于 1000ms 强制重新同步。
- [x] 误差进入正常范围后恢复 1.0x。
- [x] 防止频繁抖动切换播放速度。
- [x] 在调试 UI 和日志中展示速度修正。
- [x] 使用假时钟和假播放器测试正负误差修正。

**验收：**

- [x] 小误差可逐步收敛且没有明显听感跳跃。

### T102 完善并发添加歌曲冲突处理

- [x] 扩展后端高并发上传和秒传测试。
- [x] 确认同一文件并发上传只保留一份物理文件。
- [x] 确认多个 Track 的 `orderIndex` 唯一且连续。
- [x] 客户端收到重复/乱序事件时以服务器快照收敛。

**验收：**

- [x] 并发添加不会产生重复顺序、丢曲或重复物理文件。

### T103 完善下载队列与下一首预下载

- [x] 播放状态变化后立即重新计算队列优先级。
- [x] 当前曲目 READY 时优先下载，下一首次优先。
- [x] 当前播放稳定后自动预下载下一首。
- [x] 网络失败和任务取消后可恢复队列。
- [x] 增加优先级变化和持久化恢复测试。

**验收：**

- [x] 切歌前下一首通常已缓存；队列顺序与 UI 一致。

### T104 完善角色权限

- [x] 后端对所有控制类操作统一执行 Host 权限校验。
- [x] Android 明确展示 Host/Member 角色与可用操作。
- [x] Member 的控制操作在 UI 和 API 两层均被阻止。
- [x] 增加权限绕过测试。

**验收：**

- [x] Member 无法通过手工 HTTP 请求绕过权限。

### T105 实现 BLE 房间邀请发现

- [x] 定义 `BleRoomDiscovery` 接口和状态模型。
- [x] Host 使用 Sync Listen Service UUID 广播不含令牌的 6 字节短房间码。
- [x] Member 扫描附近房间，选择后通过互联网加入。
- [x] 按 Android 版本处理 BLUETOOTH_SCAN、ADVERTISE、CONNECT 权限。
- [x] BLE 不传输音频、加入令牌或其他敏感数据。
- [x] 无 BLE 或权限被拒绝时不影响 P0 加入方式。

**验收：**

- [x] 两台支持 BLE 的设备可发现房间，并通过互联网完成加入。

### T106 实现 NFC 加入链接读取

- [x] 定义 `NfcJoinManager` 接口和状态模型。
- [x] 支持读取包含 `synclisten://join` 的 NFC Tag。
- [x] 复用统一深链解析和加入确认流程。
- [x] 无 NFC 或 NFC 关闭时提供清晰提示。
- [x] NFC 不用于传输音频文件。

**验收：**

- [x] 读取有效 NFC Tag 后可加入房间。

### T107 执行 P1 回归验收

- [x] 重跑全部 P0 自动化测试和双设备验收。
- [x] 验证 BLE/NFC 不影响二维码、深链和手动加入。
- [x] 验证速度微调不导致播放器异常或不可恢复速度。
- [x] 更新 `docs/test-report.md`。

---

## 9. 文档与最终交付

**阶段依赖：** P0；P1 完成后再次更新  
**阶段完成条件：** 新开发者可仅根据文档启动并完成双设备测试。

### T201 完善 README

- [x] 项目简介与功能列表。
- [x] 架构说明与目录结构。
- [x] Android 和后端运行方法。
- [x] 局域网、公网和模拟器测试方法。
- [x] 创建、加入、上传、下载和同步播放使用说明。
- [x] 常见问题与故障排查入口。

### T202 完善技术文档

- [x] `docs/api.md`：REST 接口、请求、响应、错误码。
- [x] `docs/websocket.md`：事件、payload、时序和重连恢复。
- [x] `docs/architecture.md`：组件、数据流、缓存和同步策略。
- [x] `docs/debugging.md`：日志位置、关键指标和常见故障。
- [x] `docs/known-issues.md`：已知问题、限制和规避方法。
- [x] `docs/test-report.md`：自动化与双设备验收结果。

### T203 最终交付检查

- [x] Android App 可构建、安装和运行。
- [x] 后端服务可安装依赖、迁移数据库并启动。
- [x] 环境示例、运行命令和接口文档与实际实现一致。
- [x] P0 验收标准全部通过。
- [x] 已实现 P1 功能有测试记录，未实现 P1 明确标记。
- [x] 已知问题和后续扩展建议已记录。

---

## 10. P2 Backlog：暂不实现

以下任务仅保留方向，不得在 P0/P1 完成前实施：

- [ ] 完全离线房间模式。
- [ ] Wi-Fi Direct 文件分发。
- [ ] Nearby Connections P2P 文件分发。
- [ ] 多主机与 Host 迁移。
- [ ] 真正 Mesh 网络。
- [ ] 实时音频流。
- [ ] 第三方音乐 App 音频捕获。
- [ ] iOS 客户端。
- [ ] 商业账号、收费、社交和版权校验。

---

## 11. 执行纪律

- [x] 开始任务前确认所有依赖任务已完成。
- [x] 每个任务先补测试或验收脚本，再实现功能。
- [x] 每完成一个任务，运行该任务列出的验证命令。
- [x] 每完成一个阶段，执行阶段级验收后再勾选完成。
- [x] 构建或测试失败时立即修复，不跳过、不带病进入下一阶段。
- [x] 任何协议变更同步更新后端、Android、测试和文档。
- [x] 不引入实时推流、Mesh、账号系统等非当前优先级功能。
- [x] 对未完成或受阻任务记录原因、复现步骤和下一步。

## 12. 进度摘要

| 阶段 | 优先级 | 状态 |
|---|---|---|
| 阶段一：准备与工程骨架 | P0 | 已完成 |
| 阶段二：共享协议与后端核心 | P0 | 已完成 |
| 阶段三：Android 房间功能 | P0 | 已完成 |
| 阶段四：上传、下载与缓存 | P0 | 已完成 |
| 阶段五：本地播放与基础同步 | P0 | 已完成 |
| 阶段六：二维码与深链 | P0 | 已完成 |
| 阶段七：P0 完整闭环验收 | P0 | 已完成 |
| 阶段八：P1 增强功能 | P1 | 已完成 |
| 文档与最终交付 | P0/P1 | 已完成 |
| Host 手机内嵌服务器模式 | P0 改进 | 已完成 |
| 阶段 A：Host 持久化与一键恢复 | P0 改进 | 进行中 |
| P2 Backlog | P2 | 暂缓 |

---

## 13. Host 手机内嵌服务器模式（P0 改进）

**设计：** `docs/superpowers/specs/2026-06-14-android-host-server-design.md`
**目标：** 一台 Android 手机可在同一 Wi-Fi 或手机热点内托管完整房间，不需要电脑或互联网。

### T301 设计、协议与工程基础

- [x] 确认采用 Android 内嵌 Ktor、复用现有 REST/WebSocket 协议。
- [x] 明确首期支持同一 Wi-Fi 和 Host 手机热点，不支持公网、Host 迁移和房间恢复。
- [x] 编写详细实施计划。
- [x] 增加 Ktor Server/CIO/WebSocket 依赖、前台服务权限和服务声明。
- [x] 建立 Host server 包结构和自动化测试入口。

### T302 Host 服务生命周期与可达地址

- [x] 实现 `HostAddressResolver`，仅选择其他设备可访问的 IPv4。
- [x] 实现 `HostServerController` 状态机。
- [x] 实现带常驻通知和 Wi-Fi lock 的 `HostServerService`。
- [x] 无可达地址、端口占用和启动失败时正确回滚并显示错误。
- [x] Host 离开或停止托管时关闭房间和服务。

### T303 房间、成员与 WebSocket

- [x] 实现单活动房间线程安全 Host store。
- [x] 实现创建、加入、快照、离开和 Host 权限。
- [x] 实现房间 WebSocket 认证、权威快照、成员事件和重连。
- [x] 保持错误体、公共模型和 envelope 与电脑后端兼容。

### T304 曲目、文件与播放同步

- [x] 实现播放列表连续唯一顺序。
- [x] 实现 MP3/FLAC 上传、SHA-256 校验、物理去重和下载。
- [x] 实现 Host 播放控制、计划执行时间和周期 `SYNC`。
- [x] Host 本机和成员继续复用现有上传、下载、缓存和 Media3 播放流程。

### T305 UI、邀请与 BLE

- [x] 首页增加“手机托管房间”和“使用外部服务器创建”入口。
- [x] 显示 Host 托管状态、可达地址和错误提示。
- [x] 二维码、深链和 NFC 邀请使用 Host 可达地址。
- [x] 手动加入支持 Host 地址和房间码。
- [x] BLE 版本化载荷携带 IPv4、端口和房间码，并兼容旧载荷。

### T306 验证、文档与交付

- [x] Host server 单元及 Ktor 集成测试通过。
- [x] Android 全量测试、lint 和 Debug 构建通过。
- [x] 在不启动电脑后端的前提下完成两个模拟器闭环验收。
- [x] 更新 README、架构、API、调试、已知问题和测试报告。
- [x] 更新 `EXECUTION_LOG.md` 并创建 Git 检查点。

---

## 14. 阶段 A：Host 持久化与一键恢复（P0 改进）

**设计：** `docs/superpowers/specs/2026-06-16-host-persistence-design.md`
**目标：** Host 进程被杀或重启后可一键恢复原房间，沿用房间码、播放列表和缓存文件。

### T401 编写恢复设计文档与状态生命周期规则

- [x] 创建 `docs/superpowers/specs/2026-06-16-host-persistence-design.md`。
- [x] 定义房间生命周期状态机：ACTIVE → CLOSED_BY_USER / DISCONNECTED。
- [x] 明确显式关闭与异常终止的判别条件。
- [x] 定义恢复行为：地址重解析、成员离线、播放暂停恢复。

### T402 为 Host 权威状态增加持久化层

- [x] 创建独立 `HostPersistenceDatabase` Room 数据库（5 实体 + DAO）。
- [x] 实体：HostRoom、HostMember、HostTrack、HostPlayback、RecoveryMarker。
- [x] 重构 `HostRoomStore` 为 write-through 持久化（先写 DB，再更新内存缓存）。
- [x] 新增 `loadRecoverableRoom()` 和 `recoverRoom()` 方法。
- [x] 新增 `dismissRecovery()` 方法。
- [x] 在 DataModule 注册 HostPersistenceDatabase 和 HostDao。

### T403 修改服务关闭语义

- [x] `closeAndCleanup()`：显式关闭，删除 RecoveryMarker。
- [x] `emergencyShutdown()`：异常终止，保留 RecoveryMarker，标记 DISCONNECTED。
- [x] `HostServerService.stopHosting()`：调用 `closeAndCleanup()`。
- [x] `HostServerService.onDestroy()` / `onTaskRemoved()`：调用 `emergencyShutdown()`。
- [x] 新增 `ACTION_RECOVER` intent 支持恢复启动。

### T404 增加恢复检测与首页恢复卡片

- [x] 创建 `HostRecoveryManager`（@Singleton，注入 HostDao 和 Context）。
- [x] `HomeViewModel` 启动时自动检测可恢复房间。
- [x] 首页显示 `RecoveryCard`：房间名、房间码、成员数、歌曲数。
- [x] "恢复房间" 按钮：重新解析 IPv4、启动恢复服务、进入房间。
- [x] "忽略" 按钮：删除 RecoveryMarker 和所有持久化数据。
- [x] `HostServerService` 支持 `@AndroidEntryPoint` Hilt 注入。

### T405 编写持久化层测试

- [x] 创建 `HostRoomStorePersistenceTest`（12 个测试用例）。
- [x] 包含 FakeHostDao 内存实现。
- [x] 测试覆盖：创建持久化、恢复、显式关闭清除、异常终止保留、忽略恢复。
- [x] Android 全量 testDebugUnitTest（82/82 通过）。

### T406 模拟器进程杀死与恢复验收

- [ ] 双模拟器 Host 托管 → 创建房间 → 加入成员 → 上传歌曲。
- [ ] `adb shell am force-stop` 杀进程。
- [ ] 重启 App → 验证恢复卡片显示。
- [ ] 恢复 → 验证房间码、歌曲列表、成员离线、播放暂停。
- [ ] 显式停止 → 杀进程 → 重启 → 验证无恢复卡片。

### T407 更新文档与 Git 检查点

- [x] 更新 `EXECUTION_LOG.md`，记录关键决策和验证结果。
- [x] 更新 `TASKS.md`，添加阶段 A 任务列表。
- [ ] 更新 `docs/architecture.md`，补充 Host 持久化层说明。
- [ ] 更新 `docs/debugging.md`，增加持久化相关日志标签。
- [ ] 更新 `docs/known-issues.md`，移除"Host 进程被杀后房间不可恢复"条目。
- [ ] 更新 `README.md`，补充恢复功能使用说明。
- [ ] 创建 Git checkpoint。
