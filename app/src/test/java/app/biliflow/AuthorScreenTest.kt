package app.biliflow

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AuthorScreenTest {
    @Test fun parsesAuthorUploadsAndPagination() {
        val data = JSONObject("""{"list":{"vlist":[{"bvid":"BV123abc","title":"video","length":"3:21","author":"creator","play":12}]},"page":{"pn":1,"ps":20,"count":21}}""")
        val result = parseAuthorVideos(data, 42)
        assertEquals("BV123abc", result.videos.single().id)
        assertEquals("3:21", result.videos.single().duration)
        assertEquals(42L, result.videos.single().authorId)
        assertTrue(result.more)
    }
}
