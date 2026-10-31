package com.synclisten.cloud

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudMusicTest {
    @Test fun rejectsCoverRequestsToUnrelatedHosts() {
        org.junit.Assert.assertNull(CloudCover.fetch("http://127.0.0.1:12345/image"))
        org.junit.Assert.assertNull(CloudCover.fetch("https://music.126.net.evil.example/a.jpg"))
        org.junit.Assert.assertNull(CloudCover.fetch("https://user:password@p3.music.126.net/a.jpg"))
    }
    @Test fun lyricsAreLimitedToConfirmedNeteaseEndpoint() {
        val music = client(encrypt("""{"code":200,"lrc":{"lyric":"[00:01.00]line"}}"""))
        assertEquals("[00:01.00]line", music.lyrics(CloudTrack("netease", "123", "Test", emptyList())).text)
        assertThrows(CloudMusicException::class.java) { music.lyrics(CloudTrack("qq", "song", "Test", emptyList())) }
    }
    @Test fun candidateStringDoesNotExposeTemporaryUrl() {
        val candidate = CloudCandidate("https://example.test/music?token=secret", 128, "mp3")
        org.junit.Assert.assertFalse(candidate.toString().contains("secret"))
    }
    @Test fun detectsAResolvedVersionMismatchWithoutClaimingIdentity() {
        val track = CloudTrack("qq", "song", "Testing (Lofi Remix)", listOf("Artist"))
        assertEquals(MetadataMatch.UNKNOWN, assessResolvedTitle(CloudResolution(track, emptyList(), "", "", "qq")))
        assertEquals(MetadataMatch.MISMATCH, assessResolvedTitle(CloudResolution(track, emptyList(), "Testing", "Artist", "gateway")))
        assertEquals(MetadataMatch.CONSISTENT, assessResolvedTitle(CloudResolution(track, emptyList(), "Artist - Testing (Lofi Remix)", "Artist", "gateway")))
        assertEquals(MetadataMatch.MISMATCH, assessResolvedArtists(CloudResolution(track, emptyList(), "Testing (Lofi Remix)", "Another Artist", "gateway")))
        assertEquals(MetadataMatch.CONSISTENT, assessResolvedArtists(CloudResolution(track, emptyList(), "", "Artist / Guest", "gateway")))
        assertEquals(MetadataMatch.UNKNOWN, assessResolvedArtists(CloudResolution(track, emptyList(), "", "", "gateway")))
    }

    @Test fun cloudReferenceRequiresMatchingIdentityMetadataAndQuality() {
        val track = CloudTrack("qq", "song", "Song", listOf("Artist"), mediaId = "media")
        val reference = CloudTrackReference(track, "mp3", 128, "Song", "Artist")
        val low = CloudCandidate("https://example.invalid/low", 128, "mp3")
        val high = CloudCandidate("https://example.invalid/high", 320, "mp3")
        val resolved = CloudResolution(track, listOf(high, low), "Song", "Artist", "test")
        assertEquals(low, reference.selectCandidate(resolved))
        assertThrows(CloudMusicException::class.java) { reference.selectCandidate(resolved.copy(track = track.copy(id = "other"))) }
        assertThrows(CloudMusicException::class.java) { reference.selectCandidate(resolved.copy(resolvedArtist = "Other")) }
        assertThrows(CloudMusicException::class.java) { reference.selectCandidate(resolved.copy(candidates = listOf(high))) }
    }
    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec("xSdddRXNm0P5689HI34TqJNzDb666666".toByteArray(), "AES"), IvParameterSpec("dgjsJhf58aFlOk15".toByteArray()))
        return Base64.getEncoder().encodeToString(cipher.doFinal(value.toByteArray()))
    }

    private fun client(vararg responses: String): CloudMusicClient {
        val queue = ArrayDeque(responses.toList())
        return CloudMusicClient(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(queue.removeFirst().toResponseBody("application/json".toMediaType())).build()
        }.build())
    }

    @Test fun neteaseFallback() {
        val music = client(encrypt("""{"code":200,"data":[]}"""), encrypt("""{"code":200,"data":[{"url":"https://example.test/audio","br":128,"format":"mp3"}]}"""))
        val track = CloudTrack("netease", "123", "Test", listOf("A"))
        val result = music.resolve(track)
        assertEquals("/song/url/v1", result.method)
        assertEquals(128, result.candidates.single().bitrate)
    }

    @Test fun qqRejectsWrongSongAndExternalPath() {
        val music = client("""{"code":0,"req_0":{"code":0,"data":{"sip":["https://example.test/"],"midurlinfo":[{"songmid":"other","filename":"M500media.mp3","result":0,"purl":"a"},{"songmid":"song","filename":"M800media.mp3","result":0,"purl":"//evil.test/audio"}]}}}""")
        assertThrows(CloudMusicException::class.java) { music.resolve(CloudTrack("qq", "song", "Test", listOf("A"), mediaId = "media")) }
    }

    @Test fun kuwoSearchWithoutCode() {
        val music = client(encrypt("""{"data":{"list":[{"rid":123,"name":"Test","artist":"A"}]}}"""))
        assertEquals("123", music.search("kuwo", "test").single().id)
    }

    @Test fun qqAcceptsMatchingSongAndMedia() {
        val music = client("""{"code":0,"req_0":{"code":0,"data":{"sip":["https://example.test/"],"midurlinfo":[{"songmid":"song","filename":"M500media.mp3","result":0,"purl":"audio?key=secret"}]}}}""")
        val result = music.resolve(CloudTrack("qq", "song", "Test", listOf("A"), mediaId = "media"))
        assertEquals(1, result.candidates.size)
        assertEquals("", result.resolvedTitle)
    }
}
