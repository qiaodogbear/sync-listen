# Execution Log

> 轻量恢复日志。只保留当前状态、关键决策、验证结果和下一步。

## Current

- Updated: 2026-06-12
- Active task: T017 建立 Room 缓存索引
- Status: in progress
- Next: 建立缓存实体、DAO、数据库和去重查询测试。

## Decisions

- 项目在用户指定目录原地初始化 Git；初始目录不是 Git 仓库，因此未创建 worktree。
- 后端使用 Node.js + TypeScript + Fastify + SQLite。
- Android 使用 Kotlin + Compose，`minSdk 26`、`compileSdk/targetSdk 35`。
- 恢复日志保持轻量；详细任务状态以 `TASKS.md` 为准。
- 后端 Vitest 限制为 4 个 worker；Windows 上默认高并发启动 Fastify/SQLite 会导致 5 秒测试超时。

## Verified

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

## Blockers

- Node 24 的内置 `node:sqlite` 当前会输出 ExperimentalWarning；功能验证通过，后续升级 Node 时需回归。
- Android 项目路径包含中文；已通过 `android.overridePathCheck=true` 允许 AGP 在用户指定目录构建，需持续关注 Windows 工具链兼容性。
- Gradle Windows 单测无法从中文真实路径加载测试类；使用 `subst S: C:\Users\15224\Desktop\工程\sync-listen` 后测试通过。
