package app.biliflow

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream

class LiveWireTest {
    private fun message(text: String) = LiveWire.packet(5, """{"cmd":"DANMU_MSG:4:0:2","info":[[0,1,25,16711680],"$text",[123,"viewer"]]}""".toByteArray())
    private fun compressed(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(); DeflaterOutputStream(out).use { it.write(bytes) }; return out.toByteArray()
    }
    @Test fun authAndHeartbeatHeadersFollowWireOrder() {
        val packet = LiveWire.packet(7, "{}".toByteArray()); val header = ByteBuffer.wrap(packet)
        assertEquals(18, header.int); assertEquals(16, header.short.toInt()); assertEquals(1, header.short.toInt()); assertEquals(7, header.int)
        assertEquals(listOf(LiveWireEvent.Auth(0)), LiveWire.decode(LiveWire.packet(8, """{"code":0}""".toByteArray())))
        assertTrue(LiveWire.decode(LiveWire.packet(3, byteArrayOf(0, 0, 0, 10))).isEmpty())
    }
    @Test fun compressedBatchKeepsRealBulletContentAndStyle() {
        val events = LiveWire.decode(LiveWire.packet(5, compressed(message("one") + message("two")), 2)).filterIsInstance<LiveWireEvent.Bullet>()
        assertEquals(listOf("one", "two"), events.map { it.text }); assertEquals(0xFF0000, events[0].color); assertEquals("viewer", events[0].sender)
    }
    @Test(expected = IllegalArgumentException::class) fun truncatedPacketsAreRejected() { LiveWire.decode(message("x").dropLast(1).toByteArray()) }
    @Test(expected = IllegalArgumentException::class) fun decompressionCannotGrowBeyondLimit() { LiveWire.decode(LiveWire.packet(5, compressed(ByteArray(4 * 1024 * 1024 + 1)), 2)) }
    @Test fun giftsAndOtherMessagesDoNotBecomeFakeDanmaku() {
        assertTrue(LiveWire.decode(LiveWire.packet(5, """{"cmd":"SEND_GIFT","data":{"message":"gift"}}""".toByteArray())).isEmpty())
    }
}
