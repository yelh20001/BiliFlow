package app.biliflow

import org.junit.Assert.*
import org.junit.Test

class AndroidDynamicTest {
    private fun msg(vararg fields: Pair<Int, ByteArray>): ByteArray = ProtoWriter().apply { fields.forEach { (id, value) -> bytes(id, value) } }.build()
    private fun item(id: String, body: ByteArray, type: Long = 7): ByteArray {
        val user = ProtoWriter().number(1, 42).string(2, "作者").string(3, "//i0.hdslb.com/a.jpg").build()
        val author = ProtoWriter().number(1, 42).string(2, "昨天").bytes(3, user).build()
        val desc = ProtoWriter().string(3, "正文").build()
        val meta = ProtoWriter().string(1, id).build()
        return ProtoWriter().number(1, type).bytes(3, msg(2 to author)).bytes(3, msg(4 to desc)).bytes(3, msg(5 to body)).bytes(4, meta).build()
    }
    @Test fun parsesAppCardsPaginationAndUpdateDots() {
        val picture = ProtoWriter().string(1, "//i0.hdslb.com/p.jpg").number(2, 1200).number(3, 800).build()
        val post = item("123", msg(7 to msg(1 to picture)))
        val author = ProtoWriter().number(1, 1).string(2, "//i0.hdslb.com/u.jpg").string(3, "关注者").number(4, 7).string(17, "extra").build()
        val list = ProtoWriter().bytes(1, post).string(3, "cursor").number(5, 1).build()
        val upList = ProtoWriter().bytes(2, author).string(3, "footprint").build()
        val result = parseAndroidDynamicPage(msg(1 to list, 2 to upList), false)
        assertEquals("cursor", result.offset); assertTrue(result.more)
        assertEquals("123", result.posts.single().id); assertEquals("正文", result.posts.single().text)
        assertEquals(listOf("https://i0.hdslb.com/p.jpg"), result.posts.single().pictures)
        assertEquals(42L, result.posts.single().authorId)
        assertTrue(result.authors.single().updated); assertEquals(7L, result.authors.single().id)
        assertEquals("footprint", result.authors.single().footprint); assertEquals("extra", result.authors.single().personalExtra)
    }
    @Test fun videoForwardAndUnsupportedItemsRemainInOrder() {
        val archive = ProtoWriter().string(1, "视频").number(7, 170001).number(20, 120).build()
        val video = item("10", msg(2 to archive), 2)
        val forward = item("11", msg(6 to msg(1 to video)), 1)
        val other = ProtoWriter().number(1, 25).bytes(4, ProtoWriter().string(1, "12").build()).build()
        val result = parseAndroidDynamicPage(ProtoWriter().bytes(1, video).bytes(1, forward).bytes(1, other).string(2, "next").number(3, 1).string(4, "read-marker").build(), true)
        assertEquals(listOf("10", "11", "12"), result.posts.map { it.id })
        assertEquals(AppProtocol.bvid(170001), result.posts[0].video?.id)
        assertEquals("10", result.posts[1].original?.id)
        assertTrue(result.posts[2].attachments.isNotEmpty()); assertTrue(result.more); assertEquals("read-marker", result.readOffset)
    }
    @Test fun malformedLengthsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ProtoMessage(byteArrayOf(10, 5, 1)) }
    }
}
