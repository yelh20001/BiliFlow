package app.biliflow

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SocialDataTest {
    @Test fun videoDynamicUsesNativeVideoIdAndOfficialAuthorModules() {
        val post = parseDynamic(JSONObject("""{"id_str":"123","modules":{"module_author":{"name":"UP","face":"//i0.hdslb.com/a.jpg","mid":42,"pub_time":"昨天"},"module_dynamic":{"desc":{"text":"新视频"},"major":{"archive":{"bvid":"BV17x411w7KC","title":"标题","cover":"http://i0.hdslb.com/c.jpg","duration_text":"3:40"}}},"module_stat":{"like":{"count":7}}}}"""))
        assertEquals("新视频", post.text)
        assertEquals("BV17x411w7KC", post.video!!.id)
        assertEquals("UP", post.video.author)
        assertEquals(7L, post.likes)
        assertEquals("https://i0.hdslb.com/a.jpg", post.avatar)
    }
    @Test fun opusDynamicShowsItsTextAndImages() {
        val post = parseDynamic(JSONObject("""{"id_str":"123","modules":{"module_author":{"name":"UP"},"module_dynamic":{"major":{"opus":{"title":"图文","summary":{"text":"正文"},"pics":[{"url":"//i0.hdslb.com/1.jpg"}]}}}}}"""))
        assertEquals("正文", post.text)
        assertEquals("图文", post.articleTitle)
        assertEquals(listOf("https://i0.hdslb.com/1.jpg"), post.pictures)
    }
    @Test fun commentsKeepAuthorEmojiAndChildReplyPreview() {
        val comment = parseComment(JSONObject("""{"rpid":9,"member":{"uname":"观众","avatar":"//i0.hdslb.com/a.jpg"},"content":{"message":"不错[赞]","emote":{"[赞]":{"url":"//i0.hdslb.com/e.png"}}},"rcount":2,"replies":[{"rpid":10,"member":{"uname":"UP"},"content":{"message":"谢谢"}}]}"""))
        assertEquals("观众", comment.name)
        assertEquals(2, comment.replies)
        assertEquals("https://i0.hdslb.com/e.png", comment.emotes["[赞]"])
        assertEquals("谢谢", comment.preview[0].text)
    }
    @Test fun commentsParsePicturesInOrderIncludingChildReplies() {
        val comment = parseComment(JSONObject("""{"rpid":9,"content":{"message":"带图","pictures":[{"img_src":"//i0.hdslb.com/1.jpg","img_width":640,"img_height":480},{"img_src":"http://i0.hdslb.com/2.png"},{"img_src":""}]},"replies":[{"rpid":10,"content":{"pictures":[{"img_src":"//i0.hdslb.com/reply.jpg"}]}}]}"""))
        assertEquals(listOf("https://i0.hdslb.com/1.jpg", "https://i0.hdslb.com/2.png"), comment.pictures.map { it.url })
        assertEquals(640f, comment.pictures[0].width, .01f)
        assertEquals(480f, comment.pictures[0].height, .01f)
        assertEquals("https://i0.hdslb.com/reply.jpg", comment.preview[0].pictures.single().url)
        assertTrue(parseComment(JSONObject("{} ")).pictures.isEmpty())
    }
}
