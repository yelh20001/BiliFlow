package app.biliflow

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import javax.xml.parsers.DocumentBuilderFactory

class PlaybackDataTest {
    @Test fun progressRestoresMidVideoButResetsCompletedVideos() {
        assertEquals(45_000L, PlaybackMark("BV1", 1, 0, 45_000, 90_000, 1).resumePosition())
        assertEquals(0L, PlaybackMark("BV1", 1, 0, 89_000, 90_000, 1).resumePosition())
        assertEquals(0L, PlaybackMark("BV1", 1, 0, -10, 90_000, 1).resumePosition())
    }
    private fun streams() = JSONObject("""{"timelength":90000,"quality":80,"dash":{"video":[{"id":80,"codecid":7,"baseUrl":"https://cdn.example/v.m4s?a=1&b=2","mimeType":"video/mp4","codecs":"avc1.640028","bandwidth":2000000,"width":1920,"height":1080,"SegmentBase":{"Initialization":"0-100","indexRange":"101-200"}},{"id":32,"codecid":7,"baseUrl":"https://cdn.example/low.m4s","mimeType":"video/mp4","codecs":"avc1.64001e","bandwidth":400000,"width":854,"height":480,"SegmentBase":{"Initialization":"0-100","indexRange":"101-200"}}],"audio":[{"id":30280,"baseUrl":"https://cdn.example/a.m4s","mimeType":"audio/mp4","codecs":"mp4a.40.2","bandwidth":192000,"SegmentBase":{"Initialization":"0-50","indexRange":"51-100"}}]}}""")
    @Test fun dashManifestKeepsVideoAndAudioWithEscapedSignedUrls() {
        val manifest = StreamManifest.build(streams(), 0)!!
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest.byteInputStream())
        assertEquals(3, document.getElementsByTagName("Representation").length)
        assertEquals("PT90.000S", document.documentElement.getAttribute("mediaPresentationDuration"))
        assertEquals("https://cdn.example/v.m4s?a=1&b=2", document.getElementsByTagName("BaseURL").item(0).textContent)
    }
    @Test fun explicitQualityKeepsOneVideoRepresentationAndAudio() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(StreamManifest.build(streams(), 32)!!.byteInputStream())
        assertEquals(2, document.getElementsByTagName("Representation").length)
        assertTrue(document.getElementsByTagName("Representation").item(0).attributes.getNamedItem("id").nodeValue.startsWith("video-32-"))
    }
    @Test fun progressiveStreamsDoNotCreateAnInvalidDashManifest() { assertNull(StreamManifest.build(JSONObject("""{"durl":[{"url":"https://example.com/a.mp4"}]}"""), 0)) }
    private fun number(value: Long): ByteArray {
        val output = ByteArrayOutputStream(); var n = value
        do { val b = (n and 127).toInt(); n = n ushr 7; output.write(if (n > 0) b or 128 else b) } while (n > 0)
        return output.toByteArray()
    }
    @Test fun protobufDanmakuDecodesTimingColorAndUtf8AndSkipsUnknownFields() {
        val text = "弹幕测试".toByteArray()
        val item = number(8) + number(123) + number(16) + number(12345) + number(24) + number(5) + number(32) + number(25) + number(40) + number(0xFF66CC) + number(58) + number(text.size.toLong()) + text + number(104) + number(99)
        val result = DanmakuProto.decode(number(10) + number(item.size.toLong()) + item)
        assertEquals(1, result.size)
        assertEquals("弹幕测试", result[0].text)
        assertEquals(12345L, result[0].progress)
        assertEquals(0xFF66CC, result[0].color)
        assertEquals(5, result[0].mode)
    }
    @Test(expected = IllegalArgumentException::class) fun truncatedDanmakuDoesNotReadBeyondBuffer() { DanmakuProto.decode(byteArrayOf(10, 50, 8)) }
}
