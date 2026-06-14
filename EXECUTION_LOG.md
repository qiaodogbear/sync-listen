# Execution Log

> 轻量恢复日志。只保留当前状态、关键决策、验证结果和下一步。

## Current

- Updated: 2026-06-14
- Active task: T301-T306 Host 手机内嵌服务器模式
- Status: completed
- Next: 在两台真实 Android 手机上补充同一 Wi-Fi/热点长期运行验收。

## Decisions

- Host 手机模式采用 Android 内嵌 Ktor/CIO，复用现有 REST/WebSocket 协议。
- 首期支持同一 Wi-Fi 和 Host 手机热点；不支持公网、Host 迁移和房间恢复。
- Host 前台服务监听 `0.0.0.0:38571`；Host 客户端使用 loopback，邀请使用可达 IPv4。
- Host 停止托管或离开时关闭房间；音频 hash 文件保留，业务状态与临时文件清理。
- BLE 升级为 13 字节版本化 IPv4/端口/房间码载荷，同时兼容旧 6 字节房间码。
- 项目在用户指定目录原地初始化 Git；初始目录不是 Git 仓库，因此未创建 worktree。
- 后端使用 Node.js + TypeScript + Fastify + SQLite。
- Android 使用 Kotlin + Compose，`minSdk 26`、`compileSdk/targetSdk 35`。
- 恢复日志保持轻量；详细任务状态以 `TASKS.md` 为准。
- 后端 Vitest 限制为 4 个 worker；Windows 上默认高并发启动 Fastify/SQLite 会导致 5 秒测试超时。
- Android 13+ 在首次手机托管时请求通知权限；拒绝不阻止托管，但通知栏可能不显示常驻通知。

## Verified

- T301-T306：Android 内嵌 Ktor/CIO Host、前台服务、可达地址、房间/WebSocket、文件、
  播放同步、邀请和 BLE 版本化载荷已完成。
- 不启动电脑后端时，A 模拟器托管房间，B 通过测试转发加入；MP3/FLAC 上传下载、
  SHA-256 校验、同步播放、pause、seek、next 均通过。Host 停止后 B 在约 2 秒内进入重连。
- Host 前台服务持有 Wi-Fi lock，通知记录显示持续通知和 `http://10.0.2.15:38571`。
- 2026-06-14 完整回归：Android `clean testDebugUnitTest lintDebug assembleDebug --no-daemon`
  通过；后端 lint、typecheck、24/24 tests 和 build 通过。
- Git checkpoint：`20481f0 feat: add android mobile host server`。

- T001：目标目录、Git、基础目录、README、环境示例和忽略规则已验证。
- T002：`npm test` 1/1 通过；lint、typecheck、build 通过；实际启动后 `/health` 返回 200。
- T003：数据库测试 1/1 通过；`npm run db:migrate` 创建 SQLite、audio、tmp；lint、typecheck、build 通过。
- T004：通过 `S:` ASCII 驱动器路径运行，Android 单测 1/1、lintDebug、assembleDebug 均通过；APK 约 15.8 MB。
- T004 启动验收：安装 Emulator 36.6.11 和 API 35 Google APIs x86_64；创建 `SyncListen_A`、`SyncListen_B` Pixel 6 AVD；A 启动为 Android 15/API 35，APK 安装与 MainActivity 冷启动成功，首页和调试设置页视觉检查通过。
- 阶段一已完成。
- 阶段一回归：后端 lint/typecheck/2 tests/build 通过；Android test/lint/assemble 通过。
- Git checkpoint：`035987c chore: scaffold sync listen monorepo`，分支 `feat/mvp`。
- Git checkpoint：`081ff99 feat: define protocol and room APIs`。
- Git checkpoint：`9284856 test: complete android emulator launch acceptance`。
- Git checkpoint：`60571ce feat: complete backend room sync core`。
- Git checkpoint：`afe5ae7 feat: add android data and settings layer`。
- Git checkpoint：`2cacf95 feat: add android room entry flow`。
- Git checkpoint：`7ca958b feat: add android room websocket state`。
- Git checkpoint：`3967168 feat: complete android room experience`。
- Git checkpoint：`8e5f783 feat: add local audio inspection`。
- T005：后端协议测试 3/3、Android 协议测试 2/2 通过；API 与 WebSocket 文档已建立。
- T006：创建/加入/查询/关闭房间 API 完成；后端完整回归 10/10 测试、lint、typecheck、build 通过。
- T007：WebSocket 快照、认证、心跳、成员加入/离开广播完成；2/2 WebSocket 测试通过。
- T008：播放列表查询、服务端事务顺序分配和更新广播完成；2/2 播放列表测试通过。
- T009：流式上传、SHA-256 校验、秒传复用、下载和临时文件清理完成；4/4 文件测试通过。
- T010：Host 播放控制、统一执行时间、可恢复状态、权限校验和周期 SYNC 完成；播放与同步测试 3/3 通过。
- 阶段二已完成；后端完整回归 21/21 测试、lint、typecheck、build 通过。
- T011：Retrofit P0 API、Repository 错误映射、DataStore 身份设置和可编辑调试设置页完成；Repository/Identity 测试、lint、assemble 通过，模拟器运行时设置页检查通过。
- T012：补充仅凭房间码加入的后端入口；首页创建/加入、加载错误和防重复提交完成；双模拟器创建 HOST 与房间码加入 MEMBER 验收通过。
- T013：加入响应补充 WebSocket joinToken；OkHttp WebSocket、事件 reducer、连接状态 Flow 和指数退避重连完成；双模拟器实时成员更新与断网恢复验收通过。
- T014：房间实时状态展示、功能导航入口和离房/Host 关闭房间返回首页流程完成；阶段三已完成。
- T015：系统文件选择器、MP3/FLAC 元信息读取、流式 SHA-256 和选择错误状态完成；hash/格式测试、lint、assemble 通过。
- T016：流式 multipart 上传、进度、失败重试、重复提交保护和秒传提示完成；双模拟器实时播放列表更新验收通过。
- T017：Room 缓存实体/DAO/数据库、跨房间 hash 路径复用、校验状态和安全删除引用完成；Cache 测试、lint、assemble 通过。
- T018：WorkManager 单链下载、当前/下一首优先级、临时文件、SHA-256 校验、原子移动和自动补队列完成；双模拟器自动缓存落盘验收通过。
- T019：缓存占用展示、当前播放保护、清理非播放缓存和共享物理文件安全删除完成；阶段四已完成。
- 阶段四回归：Android 单测、lint、debug 构建通过；后端测试使用独立临时 SQLite，消除并行测试锁竞争，22/22 测试、lint、typecheck、build 通过。
- Git checkpoint：`6b2189d feat: complete android transfer cache flow`。
- T020：`PlayerController` 缓存门禁与状态机、Media3 本地引擎、位置采样和房间页控制完成；模拟器从 VERIFIED MP3 进入 READY、PLAYING、ENDED，单测、lint、assemble 通过。
- Git checkpoint：`1d606ed feat: add verified local media playback`。
- T021：低 RTT 服务器时钟采样、异常样本过滤、30 秒周期刷新和调试 UI 完成；固定时钟单测通过，模拟器显示 offset 464ms、RTT 15ms。
- Git checkpoint：`c16434d feat: estimate server clock offset`。
- T022：计划执行、SYNC 期望位置、300ms 大误差 seek、断线继续播放、重连恢复和速度修正接口完成；双模拟器同步播放、pause、seek、next 验收通过，结束曲目不会被后续 SYNC 重启。
- T023：Host 控制、Member 隐藏控制、当前歌曲/进度/缓存/时钟/WebSocket/下载队列调试字段完成；真实断线显示同步断开，后端恢复后自动重连。
- WebSocket 重连并发回调风暴已修复：只允许一个待调度重连并忽略过期 socket 回调；真实断线 6 秒仍为第 1 次重连。
- Git checkpoint：`052491b feat: complete synchronized playback flow`。
- Git checkpoint：`183d0f1 feat: complete qr invite flow`。
- T024：严格加入链接编解码、token 加入、Manifest 深链和加入确认流程完成；模拟器冷启动与运行中链接均进入确认，非法 server 链接不崩溃且不静默加入。
- T025：邀请页 QR、相机扫描 Activity、CAMERA 权限拒绝回退和手动房间码保留完成；A 二维码截图被 OpenCV 实际解码，B 复用扫描结果确认后以 MEMBER 加入并连接成功，阶段六完成。
- T026：后端 lint/typecheck/22 tests/build 通过；Android test/lint/assemble 通过；停止 daemon 后 clean test/lint/assemble 无缓存构建通过。
- T027：两个 API 35 Pixel 6 模拟器完成创建、二维码加入、双向 MP3/FLAC App UI 上传、自动下载/hash 校验、本地同步播放、控制、后台传输和断线重连；稳态误差约 30-71ms，结果记录于 `docs/test-report.md`。
- P0 阶段七已完成；验收期间发现的重复并发播放事件已增加互斥串行化修复与回归测试。
- Git checkpoint：`5761b6c test: complete p0 end-to-end acceptance`。
- T101：80-300ms 正负误差分别使用 1.02x/0.98x，正常范围恢复 1.0x，大误差 seek，强制重同步、速度命令去重、调试 UI/日志和假播放器测试完成；Android 全量 test/lint/assemble 通过。
- Git checkpoint：`2a18c41 feat: add playback drift speed correction`。
- T102：8 个相同文件并发上传测试确认单物理文件和 0-7 连续唯一顺序；Android 播放列表 reducer 按 serverTime 忽略旧快照并去重排序；后端 23/23 测试和 Android 全量验证通过。
- Git checkpoint：`f11719b test: harden concurrent playlist updates`。
- T103：播放状态/列表变化重建 WorkManager 优先级单链，READY 当前曲目与下一首优先，重连强制恢复失败/取消队列；优先级和恢复修订测试及 Android 全量验证通过。
- Git checkpoint：`6341587 feat: reprioritize and recover download queue`。
- T104：Android UI 与 ViewModel 双重阻止 Member 控制，后端统一 Host 校验；play/pause/seek/next 手工 HTTP 绕过测试均返回 403，针对性后端测试与 Android 全量验证通过。
- Git checkpoint：`d5746e9 test: enforce host playback permissions`。
- T105：原生 BLE 广播/扫描接口、状态、API 版本权限、仅短房间码 payload 和 P0 回退完成；payload/权限测试通过。
- T106：NFC NDEF URI/文本读取、可用性状态和统一加入确认流程完成；单元测试及 API 35 模拟器 NDEF_DISCOVERED 有效链接运行时验收通过。
- Git checkpoint：`3edafd2 feat: add BLE and NFC room invites`。
- T107 进行中：无缓存后端 lint/typecheck/23 tests/build 与 Android clean test/lint/assemble 通过；手动房间码和深链加入回归通过，二维码入口与 NFC 状态仍可见，播放速度恢复为 1.0x。
- T107 发现并修复同一用户短时多 WebSocket 时旧连接关闭错误标记离线的问题；新增多连接在线回归测试，后端 24/24 测试通过，运行时重启后 ManualMember/DeepMember 均保持在线。
- Git checkpoint：`cd2e021 fix: preserve online state across duplicate sockets`。
- T201/T202：README、REST、WebSocket、架构、调试、已知问题与测试报告已更新；环境示例移除后端未读取的变量。
- T107 最终双模拟器闭环：Host/Member 创建与加入、MP3/FLAC 秒传、双端自动下载与本地缓存、播放、暂停、seek、next、断线继续播放、重连和房间状态恢复通过；并行采样位置误差为 1-21ms，速度恢复 1.0x。
- T107 回归中修复计划播放准备竞态：`PlayerController.prepare` 等待 Media3 Ready，错过计划时刻时按迟到量补偿 seek；补充回归测试。
- T107 回归中修复播放中重复 Ready 覆盖 PLAYING 导致暂停失效的问题；补充状态机回归测试，双端暂停位置均为 3447ms。
- T203：后端 `npm ci`、`db:migrate`、lint、typecheck、24/24 tests、build、运行时 `/health` 通过；Android `clean testDebugUnitTest lintDebug assembleDebug`、APK 覆盖安装、进程启动通过。
- T203：`npm audit --omit=dev --audit-level=high` 为 0 漏洞；完整开发依赖安装报告 5 个 high severity 漏洞，需后续在不破坏工具链的前提下升级。

## Blockers

- Node 24 的内置 `node:sqlite` 当前会输出 ExperimentalWarning；功能验证通过，后续升级 Node 时需回归。
- Android 项目路径包含中文；已通过 `android.overridePathCheck=true` 允许 AGP 在用户指定目录构建，需持续关注 Windows 工具链兼容性。
- Gradle Windows 单测无法从中文真实路径加载测试类；使用 `subst S: C:\Users\15224\Desktop\工程\sync-listen` 后测试通过。
- Robolectric 在当前环境下载运行时 Android artifact 时持续挂起；Room schema/DAO 由 KSP 构建验证，缓存策略使用纯 JVM Fake DAO 测试。
- 模拟器可访问 `10.0.2.2` 但系统不标记 VALIDATED internet；下载 Worker 不使用 `NetworkType.CONNECTED` 约束，实际请求失败时按限制重试。
- 最终审计发现原 BLE 广播同时携带 128-bit UUID 和长前缀数据，触发
  `ADVERTISE_FAILED_DATA_TOO_LARGE`；改为 Service UUID 标识协议、Service Data 仅携带
  6 字节房间码后，双 API 35 模拟器完成广播、扫描、发现 `3C74AF` 和互联网加入，两端成员均在线。
- `npm ci` 当前对开发依赖报告 5 个 high severity 漏洞；生产依赖审计为 0，升级需单独回归构建与测试工具链。
