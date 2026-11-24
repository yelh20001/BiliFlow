package app.biliflow

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppProtocolTest {
    @Test fun bvidConversionSupportsLegacyAndLargeCurrentIds() {
        assertEquals("BV17x411w7KC", AppProtocol.bvid(170001))
        assertEquals("BV1L9Uoa9EUx", AppProtocol.bvid(111298867365120L))
    }
    @Test fun appVideoMapsNativeCardToPlayableIdAndMetadata() {
        val video = AppProtocol.video(JSONObject("""{"goto":"av","param":"170001","cover":"http://i0.hdslb.com/c.jpg","title":"测试","args":{"up_name":"UP"},"cover_left_text_1":"12.2万","cover_right_text":"9:10"}"""))!!
        assertEquals("BV17x411w7KC", video.id)
        assertEquals(122000L, video.views)
        assertEquals("9:10", video.duration)
        assertEquals("UP", video.author)
        assertEquals("https://i0.hdslb.com/c.jpg", video.cover)
    }
    @Test fun nonVideoCardsAreNotPresentedAsPlayableVideos() {
        assertNull(AppProtocol.video(JSONObject("""{"goto":"live","param":"123"}""")))
    }
    @Test fun appCardRetainsAuthorIdentityAndAvatarWhenProvided() {
        val video = AppProtocol.video(JSONObject("""{"goto":"av","param":"170001","args":{"up_id":42,"up_name":"UP","up_face":"//i0.hdslb.com/face.jpg"}}"""))!!
        assertEquals(42L, video.authorId); assertEquals("https://i0.hdslb.com/face.jpg", video.authorFace)
    }
    @Test fun appSignatureHasCanonicalParameterOrder() {
        assertEquals("appkey=783bbb7264451d82&local_id=0&ts=0&sign=90cd11c754010d9c83f0a57c51c90f99",
            AppProtocol.sign(mapOf("local_id" to "0", "ts" to "0"), login = true))
    }
}
