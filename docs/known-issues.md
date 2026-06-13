# 已知问题与限制

- 当前是本地/可信网络原型：无账号、TLS、速率限制或持久会话认证，不应直接暴露公网。
- 加入令牌为房间级令牌；上传元数据和离房接口尚未使用独立用户认证。
- Host 离开会关闭房间，不支持 Host 迁移或多 Host。
- 后端音频存储在单机本地磁盘，无云存储、配额和长期房间清理调度。
- Android 进程被系统终止后不会自动恢复当前房间会话，需要重新加入。
- Android Emulator 无法完成两台真实 BLE/NFC 硬件的射频交互；BLE 真机互相发现仍需补验。
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
