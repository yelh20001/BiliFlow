package app.biliflow

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.InflaterInputStream

sealed interface LiveWireEvent {
    data class Auth(val code: Int) : LiveWireEvent
    data class Bullet(val text: String, val sender: String, val color: Int, val mode: Int, val size: Int) : LiveWireEvent
}
object LiveWire {
    private const val LIMIT = 4 * 1024 * 1024
    fun packet(operation: Int, body: ByteArray = byteArrayOf(), version: Int = 1): ByteArray {
        require(body.size <= LIMIT - 16)
        return ByteBuffer.allocate(16 + body.size).order(ByteOrder.BIG_ENDIAN).putInt(16 + body.size).putShort(16).putShort(version.toShort())
            .putInt(operation).putInt(1).put(body).array()
    }
    fun decode(bytes: ByteArray, depth: Int = 0): List<LiveWireEvent> {
        require(bytes.size <= LIMIT && depth <= 4)
        val events = mutableListOf<LiveWireEvent>(); var offset = 0
        while (offset < bytes.size) {
            require(bytes.size - offset >= 16)
            val header = ByteBuffer.wrap(bytes, offset, 16).order(ByteOrder.BIG_ENDIAN)
            val length = header.int; val headerLength = header.short.toInt() and 65535; val version = header.short.toInt() and 65535; val operation = header.int
            require(length >= 16 && headerLength in 16..length && length <= bytes.size - offset)
            val body = bytes.copyOfRange(offset + headerLength, offset + length)
            if (operation == 5 && version == 2) {
                val inflated = ByteArrayOutputStream()
                InflaterInputStream(ByteArrayInputStream(body)).use { input ->
                    val block = ByteArray(8192)
                    while (true) { val count = input.read(block); if (count < 0) break; require(count <= LIMIT - inflated.size()); inflated.write(block, 0, count) }
                }
                events += decode(inflated.toByteArray(), depth + 1)
            } else if (operation == 8 && version <= 1) {
                events += LiveWireEvent.Auth(JSONObject(body.toString(Charsets.UTF_8).ifBlank { "{}" }).optInt("code", -1))
            } else if (operation == 5 && version <= 1) {
                val message = JSONObject(body.toString(Charsets.UTF_8))
                if (message.optString("cmd").substringBefore(':') == "DANMU_MSG") {
                    val info = message.optJSONArray("info")
                    val style = info?.optJSONArray(0)
                    val text = info?.optString(1).orEmpty().take(180)
                    if (text.isNotBlank()) events += LiveWireEvent.Bullet(text, info?.optJSONArray(2)?.optString(1).orEmpty(),
                        style?.optInt(3, 0xFFFFFF) ?: 0xFFFFFF, (style?.optInt(1) ?: 1).let { if (it in setOf(1, 4, 5, 6)) it else 1 }, style?.optInt(2, 25) ?: 25)
                }
            }
            offset += length
        }
        return events
    }
}
