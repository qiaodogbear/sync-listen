package com.synclisten.cloud

import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class CloudTrack(
    val source: String,
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String = "",
    val durationMs: Long? = null,
    val mediaId: String = "",
    val songType: Int = 0,
    val availableQualities: List<String> = emptyList(),
    val coverUrl: String = "",
)

@Serializable
data class CloudLyrics(val text: String)

@Serializable
data class CloudTrackReference(
    val track: CloudTrack,
    val format: String,
    val bitrate: Int? = null,
    val resolvedTitle: String = "",
    val resolvedArtist: String = "",
)

fun CloudTrackReference.isValid(): Boolean =
    track.source in setOf("netease", "qq", "kugou", "kuwo") &&
        track.id.isNotBlank() && track.id.length <= 128 && track.title.isNotBlank() && track.title.length <= 500 &&
        track.artists.size <= 30 && track.artists.all { it.length <= 500 } && track.album.length <= 500 &&
        (track.durationMs == null || track.durationMs >= 0) && track.mediaId.length <= 128 && track.songType in 0..1000 &&
        track.availableQualities.size <= 20 && track.availableQualities.all { it.length <= 40 } &&
        track.coverUrl.length <= 2048 && format.length <= 40 && (bitrate == null || bitrate > 0) &&
        resolvedTitle.length <= 500 && resolvedArtist.length <= 500

fun CloudTrackReference.selectCandidate(resolution: CloudResolution): CloudCandidate {
    if (track.source != resolution.track.source || track.id != resolution.track.id || track.mediaId != resolution.track.mediaId) {
        throw CloudMusicException("重新解析后的曲目标识不同")
    }
    if (resolvedTitle != resolution.resolvedTitle || resolvedArtist != resolution.resolvedArtist) {
        throw CloudMusicException("重新解析后的曲目信息发生变化，请重新选择")
    }
    return resolution.candidates.firstOrNull { it.format == format && it.bitrate == bitrate }
        ?: throw CloudMusicException("所选音质不可用，请重新选择")
}

@Serializable
data class CloudCandidate(val url: String, val bitrate: Int?, val format: String) {
    override fun toString(): String = "CloudCandidate(url=<redacted>, bitrate=$bitrate, format=$format)"
}

@Serializable
data class CloudResolution(
    val track: CloudTrack,
    val candidates: List<CloudCandidate>,
    val resolvedTitle: String,
    val resolvedArtist: String,
    val method: String,
)

enum class MetadataMatch { UNKNOWN, CONSISTENT, MISMATCH }

private fun normalizeMetadata(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
    .lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ").trim()

/** A consistent title does not prove that the audio is the same recording. */
fun assessResolvedTitle(resolution: CloudResolution): MetadataMatch {
    val requested = normalizeMetadata(resolution.track.title)
    var resolved = normalizeMetadata(resolution.resolvedTitle)
    if (requested.isEmpty() || resolved.isEmpty()) return MetadataMatch.UNKNOWN
    for (artist in resolution.track.artists) {
        val prefix = normalizeMetadata(artist)
        if (prefix.isNotEmpty() && resolved.startsWith("$prefix - ")) {
            resolved = resolved.removePrefix("$prefix - ")
            break
        }
    }
    return if (requested == resolved) MetadataMatch.CONSISTENT else MetadataMatch.MISMATCH
}

fun assessResolvedArtists(resolution: CloudResolution): MetadataMatch {
    val requested = resolution.track.artists.map(::normalizeMetadata).filter(String::isNotEmpty)
    val resolved = resolution.resolvedArtist.split(Regex("[/、,，&;；]"))
        .map(::normalizeMetadata).filter(String::isNotEmpty)
    if (requested.isEmpty() || resolved.isEmpty()) return MetadataMatch.UNKNOWN
    return if (requested.any { it in resolved }) MetadataMatch.CONSISTENT else MetadataMatch.MISMATCH
}

class CloudMusicException(message: String) : Exception(message)

class CloudMusicClient(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()) {
    private val json = Json { ignoreUnknownKeys = true }
    private val qqUrl = "https://u.y.qq.com/cgi-bin/musicu.fcg"
    private val service = "music.search.SearchCgiService"

    private fun obj(value: JsonElement?): JsonObject = value as? JsonObject ?: JsonObject(emptyMap())
    private fun arr(value: JsonElement?): JsonArray = value as? JsonArray ?: JsonArray(emptyList())
    private fun text(value: JsonElement?): String = (value as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun num(value: JsonElement?): Long? = (value as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun request(url: String, body: JsonObject? = null): String {
        try {
            val builder = Request.Builder().url(url)
            if (url == qqUrl) builder.header("Referer", "https://y.qq.com/").header("User-Agent", "Mozilla/5.0")
            if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
            return client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) throw CloudMusicException("音乐服务 HTTP ${response.code}")
                response.body?.string() ?: throw CloudMusicException("音乐服务返回空响应")
            }
        } catch (e: CloudMusicException) { throw e }
        catch (_: Exception) { throw CloudMusicException("音乐服务不可用或请求超时") }
    }
    private fun parse(value: String): JsonObject = try { json.parseToJsonElement(value).jsonObject }
        catch (_: Exception) { throw CloudMusicException("音乐服务响应格式无效") }
    private fun gateway(type: String, path: String): JsonObject {
        val body = buildJsonObject { put("musicType", type); put("apiParam", path) }
        val encrypted = request("http://api-music.1fooai.com/api/musicApi.php?app=txmusic", body)
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec("xSdddRXNm0P5689HI34TqJNzDb666666".toByteArray(), "AES"), IvParameterSpec("dgjsJhf58aFlOk15".toByteArray()))
            parse(String(cipher.doFinal(Base64.getDecoder().decode(encrypted.trim())), Charsets.UTF_8))
        } catch (_: Exception) { throw CloudMusicException("音乐服务响应解密失败") }
    }

    fun search(source: String, keyword: String, page: Int = 1): List<CloudTrack> {
        if (source !in listOf("netease", "qq", "kugou", "kuwo") || keyword.isBlank() || keyword.length > 200 || page !in 1..10000) throw CloudMusicException("搜索参数无效")
        val data = when (source) {
            "netease" -> gateway("wy", "/cloudsearch?keywords=${encode(keyword)}&offset=${(page - 1) * 30}")
            "kuwo" -> gateway("kw", "/kuwo/search/searchMusicBykeyWord?key=${encode(keyword)}&pn=$page")
            "kugou" -> parse(request("https://songsearch.kugou.com/song_search_v2?platform=AndroidFilter&iscorrection=1&keyword=${encode(keyword)}&page=$page&hifiquality=0&pagesize=100&PrivilegeFilter=0"))
            else -> parse(request(qqUrl, buildJsonObject {
                putJsonObject("comm") { put("ct", 19); put("cv", 1845) }
                putJsonObject(service) { put("method", "DoSearchForQQMusicDesktop"); put("module", service); putJsonObject("param") { put("query", keyword); put("num_per_page", 30); put("page_num", page) } }
            }))
        }
        val rows = when (source) {
            "netease" -> { if (num(data["code"]) != 200L) throw CloudMusicException("网易搜索失败"); obj(data["result"])["songs"] }
            "kuwo" -> { if (data["code"] != null && num(data["code"]) != 200L) throw CloudMusicException("酷我搜索失败"); obj(data["data"])["list"] }
            "kugou" -> { if (num(data["status"]) != 1L) throw CloudMusicException("酷狗搜索失败"); obj(data["data"])["lists"] }
            else -> { val result = obj(data[service]); if (num(data["code"]) != 0L || num(result["code"]) != 0L) throw CloudMusicException("QQ 搜索失败"); obj(obj(obj(result["data"])["body"])["song"])["list"] }
        }
        if (rows !is JsonArray) throw CloudMusicException("搜索响应格式无效")
        return rows.mapNotNull { element ->
            val row = obj(element)
            val track = when (source) {
                "netease" -> CloudTrack(source, text(row["id"]), text(row["name"]), arr(row["ar"]).map { text(obj(it)["name"]) }, text(obj(row["al"])["name"]), num(row["dt"]))
                "qq" -> CloudTrack(source, text(row["mid"]), text(row["title"]).ifEmpty { text(row["name"]) }, arr(row["singer"]).map { text(obj(it)["name"]) }, text(obj(row["album"])["name"]), num(row["interval"])?.times(1000), text(obj(row["file"])["media_mid"]), num(row["type"])?.toInt() ?: 0)
                "kugou" -> CloudTrack(source, text(row["FileHash"]), text(row["SongName"]), listOf(text(row["SingerName"])), text(row["AlbumName"]), num(row["Duration"])?.times(1000))
                else -> CloudTrack(source, text(row["rid"]), text(row["name"]), listOf(text(row["artist"])), text(row["album"]), num(row["duration"])?.times(1000))
            }
            val fields = when (source) {
                "qq" -> listOf("mp3-128" to obj(row["file"])["size_128mp3"], "mp3-320" to obj(row["file"])["size_320mp3"], "flac" to obj(row["file"])["size_flac"], "aac-192" to obj(row["file"])["size_192aac"])
                "kugou" -> listOf("standard" to row["FileSize"], "high" to row["HQFileSize"], "lossless" to row["SQFileSize"])
                "netease" -> listOf("standard" to obj(row["l"])["size"], "higher" to obj(row["m"])["size"], "high" to obj(row["h"])["size"], "lossless" to obj(row["sq"])["size"], "hires" to obj(row["hr"])["size"])
                else -> emptyList()
            }
            val cover = when (source) {
                "netease" -> text(obj(row["al"])["picUrl"])
                "kuwo" -> text(row["albumpic"])
                else -> ""
            }
            track.copy(availableQualities = fields.filter { (num(it.second) ?: 0) > 0 }.map { it.first }, coverUrl = cover).takeIf { it.id.isNotEmpty() && it.title.isNotEmpty() }
        }
    }

    fun lyrics(track: CloudTrack): CloudLyrics {
        if (track.source != "netease") throw CloudMusicException("该来源的歌词暂不可用")
        if (!track.id.matches(Regex("[0-9]{1,32}"))) throw CloudMusicException("曲目标识无效")
        val data = gateway("wy", "/lyric?id=${encode(track.id)}")
        if (num(data["code"]) != 200L) throw CloudMusicException("歌词获取失败")
        return CloudLyrics(text(obj(data["lrc"])["lyric"]).take(200_000))
    }

    fun resolve(track: CloudTrack): CloudResolution {
        if (track.id.isEmpty() || track.id.length > 128) throw CloudMusicException("曲目标识无效")
        if (track.source == "qq") return resolveQq(track)
        val id = encode(track.id)
        fun query(type: String, path: String): CloudResolution {
            val body = gateway(type, path)
            if (num(body["code"]) != 200L || body["data"] !is JsonArray) throw CloudMusicException("地址解析失败")
            val candidates = arr(body["data"]).mapNotNull { entry ->
                val item = obj(entry); val url = text(item["url"])
                val parsed = url.toHttpUrlOrNull()
                if (parsed == null || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) null
                else CloudCandidate(url, num(item["br"])?.toInt(), text(item["format"]).ifEmpty { text(item["type"]) })
            }
            return CloudResolution(track, candidates, text(body["gm"]).ifEmpty { text(body["name"]) }, text(body["gs"]).ifEmpty { text(body["arname"]) }, path.substringBefore('?'))
        }
        val primary = when (track.source) {
            "netease" -> try { query("wy2", "/song/url/v2?id=$id") } catch (_: CloudMusicException) { null }
            "kugou" -> query("kg", "/song/urls?hash=$id")
            "kuwo" -> query("kw", "/song/urls?rid=$id")
            else -> throw CloudMusicException("不支持的音乐来源")
        }
        if (primary != null && primary.candidates.isNotEmpty()) return primary
        if (track.source == "netease") {
            val fallback = query("wy", "/song/url/v1?id=$id&level=standard")
            if (fallback.candidates.isNotEmpty()) return fallback
        }
        throw CloudMusicException("没有可用播放地址")
    }

    private fun resolveQq(track: CloudTrack): CloudResolution {
        if (!track.id.matches(Regex("[A-Za-z0-9]+")) || !track.mediaId.matches(Regex("[A-Za-z0-9]+"))) throw CloudMusicException("QQ 曲目标识不完整")
        val names = listOf("M500${track.mediaId}.mp3", "M800${track.mediaId}.mp3", "F000${track.mediaId}.flac", "C400${track.mediaId}.m4a")
        val guid = java.util.UUID.randomUUID().mostSignificantBits.ushr(1).toString().take(10)
        val payload = buildJsonObject {
            putJsonObject("req_0") { put("module", "vkey.GetVkeyServer"); put("method", "CgiGetVkey"); putJsonObject("param") {
                putJsonArray("filename") { names.forEach { add(it) } }; put("guid", guid)
                putJsonArray("songmid") { names.forEach { _ -> add(track.id) } }; putJsonArray("songtype") { names.forEach { _ -> add(track.songType) } }
                put("uin", "0"); put("loginflag", 1); put("platform", "20")
            } }
            putJsonObject("comm") { put("uin", 0); put("format", "json"); put("ct", 19); put("cv", 0) }
        }
        val response = parse(request(qqUrl, payload)); val serviceResponse = obj(response["req_0"])
        if (num(response["code"]) != 0L || num(serviceResponse["code"]) != 0L) throw CloudMusicException("QQ 地址解析失败")
        val data = obj(serviceResponse["data"])
        val servers = arr(data["sip"]).map { text(it) }.mapNotNull { it.toHttpUrlOrNull() }.filter { it.username.isEmpty() && it.password.isEmpty() }.sortedBy { it.scheme != "https" }
        val candidates = arr(data["midurlinfo"]).mapNotNull { entry ->
            val row = obj(entry); val index = names.indexOf(text(row["filename"])); val path = text(row["purl"])
            if (index < 0 || text(row["songmid"]) != track.id || num(row["result"]) != 0L || path.isEmpty() || path.startsWith('/') || path.startsWith('\\') || Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(path) || servers.isEmpty()) return@mapNotNull null
            val base = servers.first(); val url = base.resolve(path) ?: return@mapNotNull null
            if (url.scheme != base.scheme || url.host != base.host || url.port != base.port) return@mapNotNull null
            CloudCandidate(url.toString(), listOf(128, 320, null, null)[index], listOf("mp3", "mp3", "flac", "m4a")[index])
        }
        if (candidates.isEmpty()) throw CloudMusicException("QQ 没有返回匹配曲目的地址")
        return CloudResolution(track, candidates, "", "", "qq-direct-vkey")
    }
}
