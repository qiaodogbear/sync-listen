# 已知问题与限制

- 当前是本地/可信网络原型：无账号、TLS、速率限制或持久会话认证，不应直接暴露公网。
- 加入令牌为房间级令牌；上传元数据和离房接口尚未使用独立用户认证。
- Host 离开会关闭房间，不支持 Host 迁移或多 Host。
- 手机托管房间状态通过独立 Room 数据库持久化；Host 进程异常终止后，重新打开 App
  会显示恢复卡片，可一键恢复原房间（房间码、播放列表和缓存文件保留，播放状态恢复为暂停）。
  用户主动停止托管或离开房间后不保留可恢复数据。
- 手机托管依赖同一 Wi-Fi 或 Host 热点的局域网互通。热点客户端隔离、厂商省电策略、
  VPN、防火墙或切换网络可能中断连接；建议允许通知并避免限制 Host App 后台运行。
- 手机托管当前使用明文 HTTP/WebSocket 和固定 TCP 38571，仅适用于可信局域网。
- 后端音频存储在单机本地磁盘，无云存储、配额和长期房间清理调度。
- Android 进程被系统终止后不会自动恢复当前房间会话；仅手机托管 Host 支持一键恢复
  （主动打开 App → 首页恢复卡片），普通成员仍需重新加入。
- NFC 仅通过 API 35 模拟器注入 `NDEF_DISCOVERED` 验收，真实 NFC Tag 射频读取仍建议在发布前补验。
- 无头模拟器无法让一个虚拟相机物理对准另一个屏幕；二维码通过真实编码/解码和统一加入链路验收。
- Node.js 24 的 `node:sqlite` 会输出 ExperimentalWarning。
- `npm ci` 当前对开发工具依赖报告 5 个 high severity 漏洞；生产依赖
  `npm audit --omit=dev --audit-level=high` 为 0。升级开发工具依赖时需完整回归。
- Windows 中文路径下 Gradle 单测类路径可能异常，使用 ASCII `subst` 映射。
- 当前网络环境下 Robolectric runtime artifact 下载挂起；Room schema 由 KSP 构建验证，
  缓存策略使用纯 JVM Fake DAO 测试。
- 同步目标是“大致同步”，不是样本级同步；网络和设备调度会产生瞬时误差。

## P2 Backlog

- 完全离线房间、Wi-Fi Direct、Nearby Connections、Mesh。
- 实时音频流、第三方音乐 App 音频捕获。
- iOS、账号、收费、社交和版权校验。
