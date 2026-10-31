# 云音乐获取与下载

`CloudMusicClient` 提供网易、QQ、酷狗和酷我的搜索与地址解析；`CloudTrack` 保留来源标识、曲名、歌手、专辑、时长和可获得的音质信息。`CloudResolution` 分别保留搜索曲目、解析元数据和临时地址。搜索和解析使用阻塞网络请求，调用方应在 IO 线程执行。

`CloudDownloader.download` 接收用户确认的解析结果，保存前检查音频头和长度，计算 SHA-256 并回读校验；候选失效时可重新解析一次，并检查来源、曲目标识和媒体标识。调用方负责确认曲目版本、选择目标文件和展示进度。地址会过期，不应作为持久标识或写入日志。

Kotlin 代码依赖 kotlinx.serialization、kotlinx.coroutines、OkHttp；`CloudRoutes.kt` 另依赖 Ktor 服务端，使用方须在外层验证房间成员身份。Node 实现位于 `backend/src/cloud/music.ts`，使用 Node 内置加密与 HTTP API，不提供下载器。项目现有 Gradle 和 Node 测试包含固定响应及下载校验测试。

第三方服务可能不可用、返回试听片段或不同版本；匹配元数据和文件头不能证明录音身份或完整性。仅使用有权访问的资源，不支持 DRM 绕过。网易、酷狗和酷我的聚合服务使用明文 HTTP，不能保证传输完整性；QQ 直接解析的可用性取决于服务端限制。
