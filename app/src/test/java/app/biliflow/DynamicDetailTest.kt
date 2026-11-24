package app.biliflow

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DynamicDetailTest {
    private fun post(id: String) = DynamicPost(id, "UP", "", 42, "today", "preview")
    @Test fun viewingVideoOpensPlayerAndViewingTextOpensNativePost() {
        val video = Video("BV17x411w7KC", "video", "", "UP")
        assertEquals(DynamicDestination.VideoPage(video), dynamicDestination(post("10").copy(video = video)))
        assertEquals(DynamicDestination.PostPage("10"), dynamicDestination(post("10")))
    }
    @Test fun viewingForwardTargetsTheOriginalDynamicEvenIfItContainsVideo() {
        val video = Video("BV17x411w7KC", "video", "", "UP")
        assertEquals(DynamicDestination.PostPage("20"), dynamicDestination(post("10").copy(video = video, original = post("20").copy(video = video))))
    }
    @Test fun opusUsesCompleteOrderedTextPicturesAndCodeInsteadOfSummary() {
        val long = "complete body ".repeat(600)
        val text = JSONObject().put("text", JSONObject().put("nodes", org.json.JSONArray().put(JSONObject().put("word", JSONObject().put("words", long))).put(JSONObject().put("rich", JSONObject().put("text", "@name")))))
        val picture = JSONObject("""{"pic":{"pics":[{"url":"//i0.hdslb.com/image.jpg","width":1200,"height":800}]}}""")
        val code = JSONObject("""{"code":{"content":"a &lt; b &amp;&amp; c"}}""")
        val module = JSONObject().put("module_content", JSONObject().put("paragraphs", org.json.JSONArray().put(text).put(picture).put(code)))
        val data = JSONObject().put("item", JSONObject().put("modules", org.json.JSONArray().put(module)))
        val full = parseOpusDocument(data, post("10"))
        assertEquals(3, full.blocks.size); assertEquals(long + "@name", full.blocks[0].text)
        assertEquals("https://i0.hdslb.com/image.jpg", full.blocks[1].picture); assertEquals(1.5f, full.blocks[1].ratio, .0001f)
        assertEquals("a < b && c", full.blocks[2].text); assertTrue(full.blocks[2].code)
    }
    @Test fun fullPostDoesNotLimitPicturesToTheFeedNineImagePreview() {
        val pictures = (1..12).map { "https://example.com/$it.jpg" }
        assertEquals(pictures, dynamicPreview(post("10").copy(pictures = pictures)).blocks.filter { it.picture.isNotEmpty() }.map { it.picture })
    }
}
