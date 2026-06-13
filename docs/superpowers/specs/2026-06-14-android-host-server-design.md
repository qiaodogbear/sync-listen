# Android Host 手机内嵌服务器设计

## 目标

允许一台 Android 手机直接托管 Sync Listen 房间。其他手机连接同一 Wi-Fi，或连接
Host 手机创建的热点后，即可完成加入、上传、下载和同步播放，不需要电脑或互联网。

首期同时保留现有电脑后端模式。公网异地连接、Host 迁移、Host 服务终止后的房间恢复、
Wi-Fi Direct、Mesh 和实时音频流不在本次范围内。

## 方案选择

### 采用：Android 内嵌 Ktor 服务，复用现有 REST/WebSocket 协议

Host 手机运行前台服务，服务内启动 Ktor/CIO 并监听 `0.0.0.0:38571`。Host 自身和
成员设备都通过现有 Retrofit、OkHttp WebSocket、上传、下载和同步播放客户端访问该
服务。

该方案只保留一条客户端数据路径，能够最大限度复用现有协议和 Android 功能，也继续
兼容电脑 Fastify 后端。

### 未采用：Host 本机直接调用领域对象

该方案可减少 Host 自身的 HTTP 往返，但会产生本机直调与远程 REST 两条行为路径，
容易导致权限、事件和错误处理不一致。

### 未采用：Nearby Connections 或自定义 P2P 协议

该方案适合后续无路由器 P2P 扩展，但需要重写发现、连接、文件传输和同步协议，不能
复用当前 REST/WebSocket 架构。

## 用户流程

首页提供两种创建方式：

- `手机托管房间`：检查当前 Wi-Fi/热点可达 IPv4 地址，启动 Host 前台服务，将客户端
  临时切换到 `http://127.0.0.1:38571`，创建房间后进入房间页。
- `使用外部服务器创建`：保持现有行为，使用设置中的服务器地址。

Host 房间页显示成员应使用的 `http://<LAN_IPV4>:38571` 地址及常驻托管状态。邀请
二维码、深链和 NFC 使用这个可达地址，而不是 Host 自身使用的 loopback 地址。

成员可通过二维码、深链、NFC、BLE 或手动输入 `Host 地址 + 房间码` 加入。加入成功后，
客户端继续使用邀请或手动输入的 Host 地址。

Host 离开、点击停止托管或前台服务被显式停止时，服务先关闭当前房间和连接，再停止
监听。首期不恢复房间，也不迁移 Host。

## Android 组件

### `host/HostServerController`

定义启动、停止、状态查询边界。状态包含：

- `Stopped`
- `Starting`
- `Running(localUrl, advertisedUrl, port)`
- `Error(message)`

控制器负责启动 `HostServerService`，等待服务就绪，并向 UI 暴露 `StateFlow`。它不
实现房间业务。

### `host/HostServerService`

Android 前台服务，负责：

- 建立低重要度通知渠道并显示持续通知。
- 获取 Wi-Fi lock，降低锁屏后网络服务被暂停的概率。
- 启动和停止 Ktor/CIO。
- 在服务销毁时关闭 WebSocket、释放锁和清理临时文件。

Manifest 声明 `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_CONNECTED_DEVICE`、
`WAKE_LOCK` 和 `CHANGE_WIFI_STATE`。服务类型使用 `connectedDevice`。

### `host/HostAddressResolver`

从活动网络接口选择可供其他手机访问的非 loopback IPv4 地址。选择规则：

1. 排除 loopback、link-local、multicast 和 IPv6。
2. 优先 Wi-Fi/热点常见接口。
3. 没有可达地址时拒绝启动，并提示用户连接 Wi-Fi 或开启热点。

服务监听所有接口，Host 客户端固定使用 loopback；邀请使用 resolver 返回的地址。

### `host/server/HostServer`

Ktor application module，保持当前公共协议：

- `GET /health`
- `GET /api/time`
- `/api/rooms` 创建、加入、快照和离开
- 播放列表、上传、下载
- Host 播放控制
- `/ws/rooms/{roomId}`

错误体、模型字段和 WebSocket envelope 与现有文档一致。未识别事件仍由客户端忽略。

### `host/server/HostRoomStore`

首期服务一次只托管一个活动房间。业务状态由线程安全内存 store 管理；音频文件保存于
`filesDir/host-server/audio`，上传临时文件保存于 `cacheDir/host-server/uploads`。
房间停止后清理业务状态和临时文件，但保留按 SHA-256 命名的音频文件以支持后续秒传。

该选择避免把现有客户端缓存数据库与服务器权威状态混在一起。首期不恢复房间，因此
不需要为服务器状态增加 Room schema。

### `host/server/HostRoomHub`

按房间维护 WebSocket 会话，发送统一 `{ type, payload, serverTimeMs }` envelope。
加入时发送权威快照，成员与播放列表变化时广播增量，播放期间周期广播 `SYNC`。

## 数据与并发规则

- 创建房间产生随机 `roomId`、六位房间码和随机 join token。
- 所有成员共享房间 join token；WebSocket 仍同时校验 `userId` 与 token。
- Store 的状态修改由单个 `Mutex` 串行化，保证播放列表 `orderIndex` 连续唯一。
- Host 权限由 `room.hostUserId == request.userId` 校验。
- 上传流式写入临时文件并计算 SHA-256；校验成功后原子移动到 hash 文件。
- 下载只允许 READY Track。
- PLAY、SEEK、NEXT 使用服务器时间加固定缓冲生成统一执行时间；PAUSE 立即生效。
- Host 服务时钟直接使用 Unix epoch 毫秒，与电脑后端语义一致。

## 邀请与 BLE

现有 `JoinLink` 已包含 `serverUrl`，不改变 URI 字段。Host 模式只确保生成邀请时使用
`advertisedUrl`。

BLE Service Data 升级为版本化二进制载荷：

```text
version(1) | ipv4(4) | port(2, unsigned big-endian) | roomCode(6 ASCII)
```

总载荷为 13 字节，可放入传统 BLE 广播。扫描结果从房间码集合升级为可加入邀请集合；
旧的 6 字节房间码载荷继续解析，并使用当前设置中的外部服务器地址作为兼容回退。

## 错误处理

- 无可达 IPv4：不启动服务，首页显示开启热点或连接同一 Wi-Fi 的操作提示。
- 端口占用或 Ktor 启动失败：停止前台服务并显示错误，不切换客户端服务器地址。
- Host 服务意外停止：成员 WebSocket 断开并按现有策略重连；通知消失表明托管已终止。
- 上传失败或 hash 不匹配：删除临时文件并返回现有错误码。
- 成员访问 loopback、错误 IP 或不同网络：连接失败，邀请页显示 Host 地址供人工核对。
- BLE 权限或能力不可用：二维码、深链、NFC 和手动地址仍可使用。

## 安全与限制

- 首期使用局域网明文 HTTP，与现有局域网调试模式一致。
- join token 防止仅知道 roomId 的设备加入，但不提供公网级认证或加密。
- 服务只在用户明确选择手机托管后启动，并始终显示前台通知。
- 不自动开启热点；Android 对第三方 App 开启系统热点有限制，用户需在系统设置中操作。
- Host 手机休眠、系统省电策略和热点厂商实现可能影响长时间连接，文档中需明确说明。

## 测试与验收

自动化测试：

- Host 地址筛选与 URL 生成单元测试。
- Host store 的创建、加入、权限、并发顺序、播放状态和关闭测试。
- Ktor test application 覆盖 REST、WebSocket、上传、下载和错误格式。
- BLE 新旧载荷编解码测试。
- HomeController 手机托管与失败回滚测试。
- Android 全量 `testDebugUnitTest`、`lintDebug`、`assembleDebug`。

双模拟器验收：

1. 不启动电脑后端。
2. A 启动手机托管并创建房间，确认前台通知和可达地址。
3. B 使用 A 的邀请地址加入并实时显示成员。
4. A/B 上传音频，另一端自动下载并校验。
5. Host 执行播放、暂停、seek 和 next，确认双端同步。
6. B 断线后重连恢复。
7. Host 停止托管，确认成员无法继续连接且房间关闭。

完成证据记录到 `EXECUTION_LOG.md` 和 `docs/test-report.md`，并保留可安装 Debug APK。
