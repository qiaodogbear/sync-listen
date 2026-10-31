# 测试与验收报告

最新开发分支的同步、悬浮窗和附近发现验证独立记录于 [sync-nearby-validation.md](sync-nearby-validation.md)，共 155 项自动测试通过。下面保留 v0.3.0 发布时的数据，不与新增结果混算。

## 云端解析库验证（2026-10-30）

Windows、Node.js 24.19.0、FFmpeg；各来源搜索 `test`，选取一项、解析候选首项，发送 Range 请求，用 ffprobe 读取元数据并解码前 2 秒。此验证未使用客户端或房间，未下载完整音频，也不证明内容授权、稳定性或完整播放。

| 来源 | 搜索项 | 地址与音频响应 | 曲目身份证据 |
|---|---|---|---|
| 网易 | TEST / 邓典果DDG，189.401 秒 | 5 个候选；首项 HTTP 206，可解码 | 解析元数据与搜索相符，但 ffprobe 为 30 秒且无标签，可能是试听片段，不能视为完整原曲 |
| QQ | TEST ME / CHANMINA，171 秒 | 2 个候选；首项 HTTP 206，可解码 | QQ 返回的歌曲 MID 与媒体文件名相符；标签与搜索相符，171.549 秒；地址接口没有独立歌曲名称 |
| 酷狗 | TEST / 邓典果DDG，189 秒 | 4 个候选；首项 HTTP 206，可解码 | 标签与搜索相符，189.401 秒；聚合服务的解析标题包含歌手 |
| 酷我 | Test / Inala，搜索未提供时长 | 3 个候选；首项 HTTP 206，可解码 | 标签与解析元数据、搜索相符，212.027 秒 |

Node 解析库的固定响应测试覆盖解密、网易分页及失败回退、QQ 标识校验与不跨源回退、音质排序、时长差异、酷我搜索格式；云接口测试覆盖成员鉴权、参数验证、准备和播放控制。共享模块的下载测试覆盖过期地址刷新、曲目标识变更、未确认结果、已有文件、HTML 和无效音频响应；元数据测试覆盖不同版本的标题以及临时地址的字符串脱敏。后端 56 项测试、lint、typecheck 和 build 通过；共享 Kotlin 35 项、桌面 6 项和 Android 110 项单元测试及构建通过。Android API 35 模拟器设备测试 5 项通过，包括 Room 数据库迁移、模拟服务下载、落盘回读校验和流式播放。

运行依赖审计仍报告 Fastify 和 fast-uri 两项中危问题；修复版本未在当前 npm 缓存中，部署时应重新检查并更新依赖。

2026-10-31 在 Android API 35 模拟器中实际搜索网易曲目，搜索列表显示封面，曲目详情返回带时间戳的歌词文本；新版搜索面板验证了曲目详情、音质候选展示以及关闭后重新打开时保留选择。尚未验证歌词与音频的时间对应关系，其他来源歌词不可用。

桌面播放引擎和 Android Media3 使用模拟 HTTP 服务测试分段传输的合成 WAV：在完整文件未到达时完成缓冲并开始播放。Android 测试在前台界面取得音频焦点，模拟器关闭音频输出；桌面 HTTP 403 的错误信息不包含临时地址。两台 API 35 模拟器通过 Node 测试房间搜索、入队并播放合成 WAV，准备期间返回 403 后重新解析并继续；暂停、暂停时前进和继续播放可用。Android 和 Windows 客户端也在同一模拟服务房间完成搜索、入队和开始流式播放。这些测试不证明第三方 MP3 或真实网络环境下的端到端同步精度。

在同一台 Windows 主机的两台 API 35 x86_64 模拟器、Node 房间和模拟 HTTP 服务下，使用播放器日志时间戳、ADB 设备时钟校准及相邻采样间的线性估算，比较 B 相对 A 的软件播放位置。约 3、10、20、30 秒的估计偏差分别为：云端流 −3、−17、−8、−13 ms；本地上传文件 +3、+7、+6、+5 ms；从模拟服务下载后上传的文件 −9、+6、−3、−3 ms。三次校时最佳往返约 41–44 ms，配对采样间隔不超过 29 ms。下载文件及两端房间缓存的 SHA-256 相同，文件长 90 秒；模拟器音频输出关闭。这些是单次软件位置测量，并非声学时延或真实设备同步精度保证。

2026-10-31 使用 WHPX、两台 API 35 x86_64 Pixel 6 模拟器，在共享虚拟 Wi-Fi 上测试本地音频：Android A 托管房间，B 以房间码加入；上传 60 秒自生成 WAV，双方播放，暂停后均报告 31,737 ms，前进 5 秒后均报告 36,737 ms。模拟器关闭音频输出。通过两端播放器日志及 ADB 往返时间估计设备时钟偏移，在 A 的播放位置约 3 秒和 20 秒时，估算 B 相对 A 分别为 2 ms 和 11 ms；两次日志取样间隔分别约 4 ms 和 16 ms，校时最佳往返约 40 ms。这是单次软件位置估计，不是声学测量或同步精度保证。测试结束后房间已关闭。

手机托管房间中的认证测试成员调用 QQ 搜索和解析：搜索返回 30 项，`TEST ME / CHANMINA` 返回两个匹配标识的候选，首项 HTTP 206 并读取 32 字节。Android API 35 模拟器通过房间界面完成同一曲目的搜索、解析和音质展示，并打开系统保存器后取消。Windows 客户端连接本机 Node 测试房间，通过界面完成同一曲目的搜索、解析和音质展示；没有下载该第三方曲目或在房间播放该曲目。Windows 文件保存界面和两端第三方资源下载尚未进行交互验收。

## 2026-09-16 / v0.3.0 本轮回归

下方 2026-06 历史记录仅供追溯，不代替本次验证。当前完整审查见 [review-2026-09-16.md](review-2026-09-16.md)。

### 自动化结果

| 范围 | 本轮实际结果 | 证据 |
|---|---|---|
| backend | 39/39，通过；lint / typecheck / build 通过 | Vitest 4.1.11，12 个测试文件 |
| Android | 85/85，通过；lint / assembleDebug 通过 | app/build/test-results/testDebugUnitTest |
| shared | 6/6，通过 | shared/build/test-results/test |
| desktop | 4/4，通过；createDistributable 通过 | desktop/build/test-results/test |
| 单元/服务集成合计 | 134 个执行用例，0 失败、0 错误 | 工程看板使用此口径，不是代码覆盖率 |
| Android 设备数据库 | 1/1，通过，API35 AVD 上运行1.524s | HostRecoveryDatabaseTest；真实 Room，非 Fake DAO |
| 总计 | 135 个用例通过 | 134 单元/服务集成 + 1 设备数据库 |
| npm 依赖审计 | 完整 npm audit：0 vulnerabilities | 包括本轮升级的开发测试依赖 |
| Android lint | 0 Error、48 Warning、1 Hint | app/build/reports/lint-results-debug.xml |

Android 新增真实 Room 连续三次恢复及异常关闭测试，确认父表 @Upsert 不级联删除曲目。首次实际连续恢复暴露原 REPLACE 导致的曲目丢失，已修复；新增事务提交失败恢复内存状态测试；PlayerController 测试补充了本地路径/缓冲上报、曲终 seek 后仍能暂停、重复 Ready 保持暂停。shared 同样覆盖结束后暂停。Desktop 使用假音频输出覆盖生命周期，不依赖测试机真实声卡。

### 正式版模拟运行

环境：SyncListen_A / emulator-5554 与 SyncListen_B / emulator-5556，Pixel 6、API 35 Google APIs x86_64。手机内置 Ktor 托管，未使用 Node 服务；adb forward 仅绕过模拟器 NAT。安装包为 com.synclisten.app，不是 Debug 包。

已执行：A 创建房间 SPUXHW；B 通过带服务器地址的深链进入确认对话框、输入 Bob 后加入；两端成员在线状态更新；Host 长按 Lab 成员晋升 Admin，B 同步显示新角色。

测试音频：自行生成的 45 秒、16 kHz、16-bit 单声道 PCM WAV，1,440,044 字节，SHA-256：

`88afa2731e4221b9be840733ef0f50683f8aff8dddd04a2fe2bfd5bb85655628`

经过认证的测试客户端上传，A/B 自动缓存；B 显示 1 首 / 1406 KB 并实际进入 PLAYING。下载完整 hash 匹配；Range bytes=10-19 返回 206 和 10 字节；匿名房间快照返回 401。

B 曾显示 RTT 17ms、serverOffset -619ms、上次 syncError 155ms、速度 1.02x。它是一次软件诊断采样，不是稳定误差分布、双端同时采样或声学测量。

首轮运行发现曲终后 seek 暂停未真正传入引擎、重播使用结束位置、诊断路径/缓冲缺失，已修复。复测双端稳定暂停于15.000s、缓冲100%、文件存在；曲终 Host UI 再次播放时服务端位置143ms。通过系统文件选择器的批量入口上传 WAV 成功，列表2首共用1406KB缓存；Host UI next 切换到第二首。

连续恢复进一步发现父表 REPLACE 触发歌曲外键CASCADE，第一次恢复的内存快照掩盖了落盘丢失。已改 @Upsert 并通过真实 Room 设备测试。最终签名 APK（SHA-256 `6e4947a34737ecc94ce13c1411f741bab1b3f1e1af945958061c010cb0e8455b`）已在 A/B 安装，重新上传测试曲目后完成连续两次进程停止/恢复，曲目仍保留。B 在 Host 停止时显示重连中，缓存播放从33秒继续到38秒；恢复后35秒观测窗内自动连回，服务端与 B 均暂停于15秒，无需重新加入。显式结束房间后重开 A，无恢复卡片。最终首页/房间截图已更新。

模拟器 Wi-Fi/data 开关没有立即断开既有连接，因此本轮使用 Host 进程停止验证服务中断，不把它记录成完整网络切换或真实热点验收。

### 发布校验

[v0.3.0 Preview](https://github.com/qiaodogbear/sync-listen/releases/tag/v0.3.0) 已在公开 MIT 仓库发布，标签对应84fc8aa；[标签代码CI](https://github.com/qiaodogbear/sync-listen/actions/runs/35012478206) 三端通过。

- APK 7,407,866字节；Windows ZIP 139,259,418字节；第三方源码ZIP 112,640,996字节。
- 4个Release附件的GitHub SHA-256 digest与本地全部匹配。实际匿名下载APK和SHA256SUMS后再次校验通过；Windows下载入口HEAD200。
- Windows ZIP完整解压后运行，进程存活10秒且无stderr；已结束测试进程。它不是桌面声学或视觉端到端验收。
- 包含Java运行时、许可证、音频库对应源码，另附JDK对应源码包。签名材料未上传。

### 产物与验证边界

最终正式签名构建通过，apksigner 验证 v2 签名与 RSA4096 证书；APK 含 LICENSE 和 THIRD_PARTY_NOTICES。GitHub CI run [35010915893](https://github.com/qiaodogbear/sync-listen/actions/runs/35010915893) 三端通过，其中 Android 编译仪器化测试 APK，实际设备测试在本机执行。Desktop 分发目录已构建，隐藏启动存活 8 秒、未输出错误；这不等于完成桌面视觉或声卡端到端验收。

未验证：真机物理扫码/BLE/NFC、Android API26 设备、两小时锁屏播放、厂商省电、真实 Android SQLite 迁移故障、音箱声学延迟、Windows 广泛格式兼容。低内存并发启动模拟器时出现过 Launcher/System UI ANR；已关闭模拟器后进行构建并冷启动恢复，不能记为 App 自身崩溃。

## 历史记录

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
- T105 BLE 邀请初版：Sync Listen Service UUID 的 Service Data 仅携带 6 字节房间码，
  API 版本权限、解析和拒绝权限回退测试通过；手机 Host 模式随后升级为版本化地址载荷。
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
- 当次修复由 128-bit Service UUID 标识 Sync Listen，Service Data 只携带 6 字节房间码。
  手机 Host 模式随后使用 scan response 承载 13 字节版本化地址载荷，同时保留旧版解析。

## 2026-06-14 手机 Host 模式验收

- 未启动电脑后端；`SyncListen_A` 的前台服务以内嵌 Ktor/CIO 监听 TCP 38571。
- `SyncListen_B` 通过 ADB 端口转发绕过模拟器 NAT 后加入 A 托管的房间；真实手机在同一
  Wi-Fi/热点内直接使用 Host 页面显示的局域网 IPv4，不需要转发。
- A、B 均显示 Host/Member 在线；MP3、FLAC 和下一首 MP3 上传、物理去重、自动下载及
  hash 校验通过。
- 播放、暂停、seek 和 next 通过；短曲结束位置采样误差约 2ms，暂停位置两端均为 489ms。
- Host 离开后前台服务和监听停止，成员端约 2 秒内从已连接进入重连状态。
- 通知权限授予后，系统通知记录显示 `Sync Listen 正在托管房间` 和可达地址。
- 回归中补充服务器 `GOING_AWAY` 关闭原因及 OkHttp `onClosing` 处理，避免成员端在
  Host 停止后继续错误显示已连接。
- 最终自动化：Android 干净 `testDebugUnitTest`、`lintDebug`、`assembleDebug` 通过；
  电脑后端 lint、typecheck、24/24 tests 和 build 通过。
