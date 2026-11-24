package app.biliflow

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DataTest {
    @Test fun recommendationsExcludePreviouslyDisplayedAndCurrentVideos() {
        fun video(id: String) = Video(id, id, "", "")
        val persistedSeen = (0..10_000).map { "BV$it" }.toSet()
        val result = unseenRecommendations(
            listOf(video("BV0"), video("BV10000"), video("BVcurrent"), video("BVnew"), video("BVnew")),
            persistedSeen, listOf(video("BVcurrent")))
        assertEquals(listOf("BVnew"), result.map { it.id })
    }
    @Test fun repeatedRecommendationPagesRestartTheCursorAndContinuePaging() = runBlocking {
        val viewed = Video("BVseen", "", "", "")
        val current = Video("BVcurrent", "", "", "")
        val fresh = Video("BVnew", "", "", "")
        val requests = mutableListOf<Boolean>()
        val result = loadUnseenRecommendations(true, setOf(viewed.id), listOf(current)) { next ->
            requests.add(next)
            if (requests.size == 5) listOf(viewed, fresh, fresh) else listOf(viewed, current)
        }
        assertEquals(listOf(true, true, true, false, true), requests)
        assertEquals(listOf(fresh), result)
    }
    @Test fun emptyRecommendationBatchesStopAfterABoundedNumberOfRequests() = runBlocking {
        val requests = mutableListOf<Boolean>()
        val result = loadUnseenRecommendations(false, emptySet(), emptyList()) { next ->
            requests.add(next)
            emptyList()
        }
        assertEquals(listOf(false, true, true, false, true), requests)
        assertTrue(result.isEmpty())
    }
    @Test fun popularResponseParsesAndUpgradesCoverToHttps() {
        val video = parseVideo(JSONObject("""{"bvid":"BV123abc","title":"标题","pic":"http://i0.hdslb.com/cover.jpg","duration":3661,"owner":{"name":"UP","mid":42,"face":"//i0.hdslb.com/face.jpg"},"stat":{"view":12345}}"""))
        assertEquals("https://i0.hdslb.com/cover.jpg", video.cover)
        assertEquals("1:01:01", video.duration)
        assertEquals(12345L, video.views)
        assertEquals("UP", video.author)
        assertEquals(42L, video.authorId); assertEquals("https://i0.hdslb.com/face.jpg", video.authorFace)
    }
    @Test fun searchResponseRemovesHighlightMarkup() {
        val video = parseVideo(JSONObject("""{"bvid":"BV123abc","title":"<em class=\"keyword\">测试</em>&amp;音乐","pic":"//i0.hdslb.com/c.jpg","duration":"05:09","author":"音乐家","play":42}"""))
        assertEquals("测试&音乐", video.title)
        assertEquals("05:09", video.duration)
        assertEquals("https://i0.hdslb.com/c.jpg", video.cover)
    }
    @Test fun locallySavedVideoRoundTripsWithoutLosingMetadata() {
        val original = Video("BV123abc", "收藏", "https://example.com/cover.jpg", "UP主", 98765, "4:32", "简介", 42, "https://example.com/face.jpg")
        assertEquals(original, parseVideo(JSONObject(original.json().toString())))
    }
    @Test fun durationsKeepZeroPadding() {
        assertEquals("0:00", duration(0))
        assertEquals("1:05", duration(65))
        assertEquals("1:00:00", duration(3600))
    }
}
