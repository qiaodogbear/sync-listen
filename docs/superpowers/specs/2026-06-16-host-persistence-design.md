# Host 手机托管房间持久化与一键恢复设计

> 日期：2026-06-16
> 基于：`docs/superpowers/specs/2026-06-14-android-host-server-design.md`
> 父任务：阶段 A —— 稳定性与自动恢复

## 目标

为手机 Host 模式增加房间持久化能力，使 Host 进程被系统终止、App 崩溃或手机重启后，
用户重新打开 App 可以一键恢复原房间。恢复后的房间沿用原房间码、播放列表和缓存文件，
播放状态恢复为暂停，所有成员需重新通过 WebSocket 连接。

## 状态生命周期

```
                    ┌─────────────────┐
                    │   NO_ACTIVE     │  ← 初始状态 / 已清理
                    └────────┬────────┘
                             │ createRoom()
                             ▼
                    ┌─────────────────┐
                    │     ACTIVE      │  ← 运行中，RecoveryMarker 存在
                    └────┬───────┬────┘
                         │       │
          用户主动关闭    │       │  进程异常终止
          (stopHosting/   │       │  (进程被杀/崩溃/系统回收)
           Host离开)      │       │
                         ▼       ▼
              ┌─────────────┐  ┌──────────────┐
              │ CLOSED_BY_  │  │ DISCONNECTED │
              │ _USER       │  │              │
              │ (无标记)    │  │ (Recovery    │
              │ 不可恢复    │  │  Marker 存在) │
              └─────────────┘  │ 可恢复       │
                               └──────┬───────┘
                                      │ 用户恢复
                                      ▼
                               ┌─────────────┐
                               │   ACTIVE     │  ← recoverRoom() 重建内存状态
                               └─────────────┘
                                      │
                                      │ 用户点击"忽略"
                                      ▼
                               ┌─────────────┐
                               │ NO_ACTIVE   │  ← 删除 RecoveryMarker + 清理持久化数据
                               └─────────────┘
```

### 状态转移规则

| 当前状态 | 触发事件 | 新状态 | RecoveryMarker |
|----------|----------|--------|----------------|
| NO_ACTIVE | 用户创建房间 | ACTIVE | 创建 |
| ACTIVE | 用户点击停止托管/离开 | CLOSED_BY_USER | 删除 |
| ACTIVE | 进程异常终止 | DISCONNECTED | 保留 |
| DISCONNECTED | 用户点击"恢复房间" | ACTIVE | 保留 |
| DISCONNECTED | 用户点击"忽略" | NO_ACTIVE | 删除 |

### 显式关闭 vs 异常终止判别

**显式关闭**（不可恢复）：
- 用户点击房间页"停止托管"
- Host 在房间内点击"离开"
- `HostServerService` 收到 `ACTION_STOP` intent

**异常终止**（可恢复）：
- 系统因内存压力杀死进程
- 用户从最近任务滑动移除（触发 `onTaskRemoved`）
- App 崩溃
- 手机重启
- 电池耗尽关机

判别实现：显式关闭路径调用 `closeAndCleanup()` → 删除 RecoveryMarker；
异常终止路径调用 `emergencyShutdown()` → 仅标记 `room.status = DISCONNECTED`，
RecoveryMarker 保留。

## 持久化数据模型

### Room 数据库：`HostPersistenceDatabase`

数据库文件：`{filesDir}/host-server/host-persistence.db`
独立于 `SyncListenDatabase`（CacheDatabase），职责清晰分离。

### 实体定义

```kotlin
@Entity(tableName = "host_room")
data class HostRoomEntity(
    @PrimaryKey val roomId: String,
    val roomCode: String,
    val name: String,
    val hostUserId: String,
    val status: String,         // "ACTIVE" | "DISCONNECTED"
    val joinToken: String,
    val createdAt: Long,
)

@Entity(
    tableName = "host_members",
    primaryKeys = ["roomId", "userId"],
)
data class HostMemberEntity(
    val roomId: String,
    val userId: String,
    val displayName: String,
    val role: String,           // "HOST" | "MEMBER"
    val connected: Boolean,     // 恢复时统一设为 false
    val joinedAt: Long,
)

@Entity(
    tableName = "host_tracks",
    primaryKeys = ["trackId"],
    foreignKeys = [ForeignKey(
        entity = HostRoomEntity::class,
        parentColumns = ["roomId"],
        childColumns = ["roomId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("roomId")],
)
data class HostTrackEntity(
    val trackId: String,
    val roomId: String,
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val fileName: String,
    val fileSize: Long,
    val fileHash: String,
    val storagePath: String,
    val uploaderId: String,
    val uploaderName: String,
    val orderIndex: Int,
    val status: String,         // "READY" | "UPLOADING" | "FAILED"
    val createdAt: Long,
)

@Entity(tableName = "host_playback")
data class HostPlaybackEntity(
    @PrimaryKey val roomId: String,
    val trackId: String?,
    val positionMs: Long,
    val isPlaying: Boolean,     // 恢复时固定为 false
    val serverTimeMs: Long,
    val executeAtServerTimeMs: Long?,
)

@Entity(tableName = "recovery_marker")
data class RecoveryMarkerEntity(
    @PrimaryKey val roomId: String,
    val hostUserId: String,
    val roomName: String,
    val roomCode: String,
    val memberCount: Int,
    val trackCount: Int,
    val disconnectedAt: Long,
)
```

### DAO 操作

```kotlin
@Dao
interface HostDao {
    // 房间
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoom(room: HostRoomEntity)
    @Query("SELECT * FROM host_room LIMIT 1")
    suspend fun getRoom(): HostRoomEntity?
    @Query("DELETE FROM host_room")
    suspend fun deleteRoom()

    // 成员
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMember(member: HostMemberEntity)
    @Query("SELECT * FROM host_members WHERE roomId = :roomId")
    suspend fun getMembers(roomId: String): List<HostMemberEntity>
    @Query("DELETE FROM host_members WHERE roomId = :roomId AND userId = :userId")
    suspend fun deleteMember(roomId: String, userId: String)
    @Query("UPDATE host_members SET connected = :connected WHERE roomId = :roomId")
    suspend fun updateAllConnected(roomId: String, connected: Boolean)

    // 曲目
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrack(track: HostTrackEntity)
    @Query("SELECT * FROM host_tracks WHERE roomId = :roomId ORDER BY orderIndex")
    suspend fun getTracks(roomId: String): List<HostTrackEntity>
    @Query("DELETE FROM host_tracks WHERE trackId = :trackId")
    suspend fun deleteTrack(trackId: String)

    // 播放状态
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlayback(playback: HostPlaybackEntity)
    @Query("SELECT * FROM host_playback WHERE roomId = :roomId")
    suspend fun getPlayback(roomId: String): HostPlaybackEntity?

    // 恢复标记
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecoveryMarker(marker: RecoveryMarkerEntity)
    @Query("SELECT * FROM recovery_marker LIMIT 1")
    suspend fun getRecoveryMarker(): RecoveryMarkerEntity?
    @Query("DELETE FROM recovery_marker")
    suspend fun deleteRecoveryMarker()

    // 全量清理
    @Query("DELETE FROM host_tracks")
    suspend fun deleteAllTracks()
    @Query("DELETE FROM host_members")
    suspend fun deleteAllMembers()
    @Query("DELETE FROM host_playback")
    suspend fun deleteAllPlayback()
}
```

## HostRoomStore 重构

### 当前 vs 重构后

| 方面 | 当前 | 重构后 |
|------|------|--------|
| 存储 | 内存 HashMap/List | Room 数据库 + 内存缓存 |
| 构造函数 | `(clock, idFactory, roomCodeFactory, leadTimeMs)` | 增加 `(dao: HostDao)` |
| createRoom | 仅内存 | DB write-through |
| join/leave | 仅内存 | DB write-through |
| addReadyTrack | 仅内存 | DB write-through |
| play/pause/seek/next | 仅内存 | DB write-through |
| close | 清理内存 | split → `closeAndCleanup()` / `emergencyShutdown()` |
| 新增 | - | `loadRecoverableRoom()` / `recoverRoom()` |

### 恢复方法签名

```kotlin
data class HostRecoverySnapshot(
    val roomId: String,
    val roomName: String,
    val roomCode: String,
    val memberCount: Int,
    val trackCount: Int,
    val currentTrackTitle: String?,
)

suspend fun loadRecoverableRoom(): HostRecoverySnapshot? {
    // 1. 查询 recovery_marker
    // 2. 如果存在且 room.status == DISCONNECTED → 返回快照
    // 3. 否则返回 null
}

suspend fun recoverRoom(): RoomSnapshot {
    // 1. 从 host_room 加载房间 → status 改为 ACTIVE
    // 2. 从 host_members 加载成员 → connected 全部设为 false
    // 3. 从 host_tracks 加载曲目
    // 4. 从 host_playback 加载播放状态 → isPlaying 固定为 false
    // 5. 重建内存缓存
    // 6. 返回 RoomSnapshot
}
```

### 写操作模式

所有修改操作采用 write-through：

```
1. 获取 Mutex
2. 写入 Room 数据库（事务）
3. 更新内存缓存
4. 释放 Mutex
```

已有 `@Dao suspend` 函数天然运行在 Room 的事务池上，Mutex 在 `HostRoomStore` 层串行化业务逻辑。

## HostServerService 修改

### 新增 Intent Action

```kotlin
const val ACTION_RECOVER = "com.synclisten.app.host.RECOVER"
const val ACTION_DISMISS_RECOVERY = "com.synclisten.app.host.DISMISS_RECOVERY"
```

### 关闭路径调整

```kotlin
// 用户主动停止（显式关闭 → 不可恢复）
private fun stopHosting() {
    runBlocking { store?.closeAndCleanup() }  // 删除 RecoveryMarker + 清理实体
    shutdown()
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
}

// 异常终止（可恢复）
override fun onDestroy() {
    runBlocking { store?.emergencyShutdown() }  // 仅标记 DISCONNECTED，保留 RecoveryMarker
    // 释放 Wi-Fi lock 等非持久资源
    super.onDestroy()
}

override fun onTaskRemoved(rootIntent: Intent?) {
    runBlocking { store?.emergencyShutdown() }  // 滑动移除 → 可恢复
    super.onTaskRemoved(rootIntent)
}
```

### recovery 路径

```kotlin
private fun recoverHosting(advertisedUrl: String?) {
    // 与 startHosting 类似，但 HostRoomStore 调用 recoverRoom() 而非 createRoom()
    // Ktor 服务启动后，store 从 DB 加载状态到内存
}
```

## 首页恢复卡片

### 数据流

```
App 启动
  → HomeViewModel.init
  → HostRecoveryManager.checkRecoverable()  // 同步查询 DB
  → 如果有 RecoveryMarker → state.recoverableRoom = HostRecoverySnapshot
  → UI 展示 RecoveryCard
```

### RecoveryCard Composable

```kotlin
@Composable
fun RecoveryCard(
    snapshot: HostRecoverySnapshot,
    onRecover: () -> Unit,
    onDismiss: () -> Unit,
)
```

展示内容：
- 标题："发现上次托管的房间"
- 房间名、房间码
- 成员数、歌曲数
- "恢复房间" 按钮（filled）
- "忽略" 按钮（text）

### 恢复流程

```
1. 用户点击 "恢复房间"
2. HomeViewModel.recoverHostedRoom()
3. HostAddressResolver 重新解析当前可达 IPv4
4. 如果地址变化 → 提示 "网络已变化，成员需使用新地址加入"
5. 启动 HostServerService（ACTION_RECOVER + advertisedUrl）
6. HostRoomStore.recoverRoom() 从 DB 重建内存
7. 进入房间页
```

## 测试策略

### 单元测试

- `HostDao` 测试：CRUD 操作、RecoveryMarker upsert/delete
- `HostRoomStore` 测试：创建→持久化→模拟重启→恢复、显式关闭清除标记、异常终止保留标记
- `HomeController` 测试：恢复卡片可见性、恢复/忽略操作

### 集成测试（模拟器）

1. 双模拟器 Host 托管 → 创建房间 → 加入成员 → 上传歌曲
2. `adb shell am force-stop` 杀进程
3. 重启 App → 验证恢复卡片显示
4. 恢复 → 验证房间码、歌曲、成员状态
5. 播放控制 → 验证 Member 同步
6. 显式停止 → 杀进程 → 重启 → 验证无恢复卡片

## 兼容性与回退

- 本设计仅修改 `host/` 包，不影响电脑后端模式
- 不改变 REST/WebSocket 协议，Member 端无感知
- 数据库 schema version 从 1 开始，预留迁移路径
- 旧的纯内存 HostRoomStore 构造函数保留为测试用，生产通过 Hilt 注入 DAO 版本
- 首次安装（无数据库）行为不变：无 RecoveryMarker，首页正常显示创建/加入
