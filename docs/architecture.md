# 架构

## 总览

Sync Listen 使用中心服务器协调房间和文件，不传输实时音频流。Android 设备先下载并
校验同一文件，再各自在本地播放。

```text
Compose UI -> ViewModel -> RoomRepository/RoomWebSocket
                      -> DownloadQueueManager -> WorkManager -> Room cache index
                      -> PlayerController -> Media3
Fastify routes -> SQLite
               -> local audio storage
               -> RoomHub -> WebSocket clients
Android HostServerService -> embedded Ktor/CIO -> HostRoomStore/HostRoomHub
```

## 后端

- REST 管理房间、成员、播放列表、文件和 Host 播放命令。
- `RoomHub` 维护房间 WebSocket 客户端，广播成员、列表和播放状态。
- SQLite 保存房间、成员、Track 和播放状态；`BEGIN IMMEDIATE` 分配唯一连续顺序。
- 上传流式计算 SHA-256；同 hash Track 共享一个物理文件。
- PLAY/SEEK/NEXT 由服务端分配未来执行时间；播放期间周期广播 SYNC。

## Android

- Compose/Hilt 提供页面与依赖注入；DataStore 保存临时身份和服务器设置。
- Retrofit 处理 REST，OkHttp WebSocket 处理快照、广播和重连。
- WorkManager 使用单一优先级链下载 READY Track：当前、下一首、其他。
- Room 缓存索引按 Track 引用，物理文件按 hash 去重，下载完成后校验 SHA-256。
- Media3 仅播放 VERIFIED 本地文件。
- 手机托管模式由前台 `HostServerService` 启动内嵌 Ktor/CIO，监听 `0.0.0.0:38571`。
  Host 自身通过 loopback 使用同一 REST/WebSocket 客户端路径，成员使用 Host 可达 IPv4。
- `HostRoomStore` 在内存中维护单活动房间、成员、播放列表和播放状态；音频文件保存在
  App 私有目录并按 SHA-256 去重。Host 停止托管后不恢复房间。

## 同步策略

客户端使用低 RTT `/api/time` 样本估算服务器时间偏移。计划命令等待统一执行时间；
周期 SYNC 计算期望位置：

- 误差小于 80ms：保持或恢复 1.0x。
- 80-300ms：使用 1.02x 或 0.98x 收敛。
- 大于 300ms：seek；大于 1000ms 记录强制重同步。
- 断线时继续本地播放，重连后以权威快照恢复。

## 邀请

- 二维码、深链和 NFC 使用严格解析的 `synclisten://join` URI。
- BLE 使用 Sync Listen 128-bit Service UUID 标识协议。新版 13 字节 Service Data 携带
  版本、IPv4、端口和 6 字节房间码，并兼容旧版仅房间码载荷；不包含令牌或音频。
- 所有加入方式最终使用相同 REST 加入和 WebSocket 恢复流程。
