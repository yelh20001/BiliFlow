package app.biliflow

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LiveAndFollowingTest {
    @Test fun portalAcceptsArrayAndPaginatedObjectWithoutChangingUpdateOrder() {
        val list = """[{"mid":2,"uname":"最新视频","has_update":1,"face":"//i0.hdslb.com/a"},{"mid":1,"uname":"新动态","has_update":true}]"""
        val old = parseDynamicAuthors(JSONObject("""{"up_list":$list}"""))
        val current = parseDynamicAuthors(JSONObject("""{"up_list":{"items":$list,"has_more":true,"offset":"a"}}"""))
        assertEquals(old, current); assertEquals(listOf(2L, 1L), current.map { it.id }); assertTrue(current.all { it.updated })
    }
    @Test fun liveStreamUsesAvcHlsAndPreservesSignedQuery() {
        val stream = parseLiveStream(JSONObject("""{"playurl_info":{"playurl":{"g_qn_desc":[{"qn":10000,"desc":"原画"}],"stream":[{"protocol_name":"http_stream","format":[]},{"protocol_name":"http_hls","format":[{"format_name":"fmp4","codec":[{"codec_name":"hevc","base_url":"/wrong"},{"codec_name":"avc","current_qn":10000,"accept_qn":[10000,150],"base_url":"/live/index.m3u8","url_info":[{"host":"https://live.example.com","extra":"?token=a&b=c"}]}]}]}]}}}"""))
        assertEquals("https://live.example.com/live/index.m3u8?token=a&b=c", stream.url)
        assertEquals(10000, stream.quality); assertEquals(listOf(10000, 150), stream.qualities.map { it.id })
    }
    @Test(expected = IllegalStateException::class) fun offlineRoomDoesNotReturnFabricatedStream() { parseLiveStream(JSONObject("""{"live_status":0}""")) }
}
