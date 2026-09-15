# 架构与演进

更新：2026-09-16，适用于 v0.3.0。可编辑图源：[architecture.drawio](images/architecture.drawio)。

![系统架构](images/architecture.png)

## 真实依赖边界

| 模块 | 职责 | 不负责什么 |
|---|---|---|
| android-app | Compose/Hilt 页面；Retrofit/OkHttp；Room 缓存；WorkManager；Media3；Ktor 手机托管与恢复 | 不依赖 root shared 模块，不捕获其他音乐 App 的声音 |
| backend | Fastify REST、WebSocket、SQLite、流式文件存储与清理工具 | 非云服务，不内置账号、TLS、全局配额 |
| shared | Desktop 使用的 DTO、设置、身份、网络、同步策略、内存 Ktor 服务端 | 其中的服务端不具备 Android 的 Room 恢复能力 |
| desktop | Compose Desktop、Java Sound 播放、下载校验与桌面会话生命周期 | 当前界面不提供本机托管入口 |
| protocol | 按服务端 origin 隔离的设备凭据算法 | 不包含平台存储或网络实现 |

根 Gradle 构建 shared/desktop；android-app 是独立 Gradle 构建。protocol 的 Kotlin 源目录同时编译进两侧。不要把“目录叫 shared”误认为 Android 已经使用它。

## 数据流

1. DataStore（Android）或原子 JSON 设置文件（Desktop）保存 userId、昵称和设备随机 secret。
2. IdentityManager 按规范化服务端 origin 派生凭据。创建/加入 REST 请求绑定该设备身份。
3. RoomRepository 取得房间、成员、joinToken。RoomWebSocket 在握手时携带设备凭据及邀请令牌。
4. 服务端 ROOM_JOINED 提供完整权威快照；后续事件更新成员、列表与播放状态。
5. 上传先读取实际字节、计算 SHA-256，服务端再流式校验；相同 hash 复用物理文件，曲目仍有独立 ID 和顺序。
6. 下载到临时文件，核对长度和 SHA-256 后才提交缓存索引。播放器只能看到已验证文件。
7. 房主或管理员发送控制命令，服务端决定执行时间；客户端以本地缓存播放并根据 SYNC 修正。

房主手机也走同一 REST/WebSocket 路径，只是地址为 127.0.0.1:38571。其他设备使用其可达 LAN 地址。Node 后端是可选替代，不是手机模式的依赖。

## 一致性与安全

- 房间邀请和成员身份分离。知晓房间码不允许冒充已存在的 Host；重新加入已有 userId 必须验证原凭据。
- 服务端对播放、删歌、重排、改角色做权限检查；UI 按最新成员角色更新。
- 服务端在锁/事务内分配列表顺序。Node 重排先使用临时负索引，避免 SQLite 唯一约束冲突。
- Android HostRoomStore 的写操作在 Room 事务内提交；失败时还原内存镜像，避免内存和磁盘出现两个版本。
- Android schema v2 持久化成员凭据摘要；旧版无凭据房间无法安全恢复，升级时使旧恢复标记失效。
- 下载和上传有单文件上限；没有用户级总配额。HTTP/WS 明文，仅适用于可信网络。
- 多个 WebSocket 同时代表一个用户时，只有最后一个连接关闭才标记离线。

## 生命周期与恢复

HostServerService 为前台托管服务，IO 启停串行化。划掉任务不等于显式关闭房间；但系统或用户强制停止仍可能中断服务。

异常停止后，下次主动打开 App 可恢复原房间、成员、曲目和持久化播放状态，恢复后统一暂停。恢复点来自最近一次持久化命令，不保证精确到被杀进程前一刻；不支持主机迁移。显式结束房间会移除恢复标记。

客户端会话作用域负责 WebSocket、时钟刷新和缓存任务的订阅。离开房间或关闭桌面窗口时取消会话，不保留旧房间事件。401/403/404/410 属于终止性错误，不无限重连。普通断网按退避策略重连并获取新快照。

## 同步策略

用低 RTT 的 /api/time 样本估算 serverOffsetMs。PLAY/SEEK/NEXT 带未来 executeAtServerTimeMs，服务端在执行点之前不广播抢先推进的 SYNC。

Android 在小偏差时使用轻微变速，大偏差 seek。Desktop 当前不支持可靠实时变速，使用有界 seek 修正，不伪装成已经变速。缓存未就绪时保留待处理同步状态，校验完成后重试准备，避免永久停在等待缓存。

同步指标测量的是软件时间和播放器位置，不包含扬声器、蓝牙或声卡输出延迟。

## 演进顺序

1. 稳定性：成员后台播放的 MediaSession/前台服务；磁盘不足与下载队列失败恢复；定期恢复检查点；真机与长时测试。
2. 易用性：成员就绪门槛、可理解的错误与重试、网络变更后的重新邀请、桌面音频兼容矩阵。
3. 公共核心：先用协议合同测试约束 Node / 两个 Ktor 实现，再迁移纯 DTO、校验和同步算法。平台 IO、数据库与服务保持适配器边界。
4. UI：在行为稳定之后统一导航和状态，补可访问性、字号与小屏测试，整理主题组件。避免一次性移动所有文件而失去可回归性。
