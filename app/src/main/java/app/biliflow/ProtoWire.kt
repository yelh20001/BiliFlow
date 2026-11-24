package app.biliflow

import java.io.ByteArrayOutputStream

/** Minimal bounded protobuf reader for the app dynamic protocol. */
internal class ProtoMessage(bytes: ByteArray) {
    private val fields = mutableMapOf<Int, MutableList<Any>>()
    init {
        var pos = 0
        fun varint(): Long {
            var value = 0L; var shift = 0
            while (pos < bytes.size && shift < 64) {
                val b = bytes[pos++].toInt() and 255
                value = value or ((b and 127).toLong() shl shift)
                if (b and 128 == 0) return value
                shift += 7
            }
            throw IllegalArgumentException("动态数据格式错误")
        }
        while (pos < bytes.size) {
            val key = varint().toInt(); val field = key ushr 3
            require(field > 0) { "动态数据格式错误" }
            val value: Any = when (key and 7) {
                0 -> varint()
                1 -> { require(bytes.size - pos >= 8); bytes.copyOfRange(pos, pos + 8).also { pos += 8 } }
                2 -> { val size = varint(); require(size >= 0 && size <= bytes.size - pos); bytes.copyOfRange(pos, pos + size.toInt()).also { pos += size.toInt() } }
                5 -> { require(bytes.size - pos >= 4); bytes.copyOfRange(pos, pos + 4).also { pos += 4 } }
                else -> throw IllegalArgumentException("不支持的动态数据类型")
            }
            fields.getOrPut(field) { mutableListOf() }.add(value)
        }
    }
    fun long(id: Int): Long = fields[id]?.lastOrNull() as? Long ?: 0L
    fun bytes(id: Int): ByteArray? = fields[id]?.lastOrNull() as? ByteArray
    fun text(id: Int): String = bytes(id)?.toString(Charsets.UTF_8).orEmpty()
    fun strings(id: Int): List<String> = fields[id].orEmpty().mapNotNull { (it as? ByteArray)?.toString(Charsets.UTF_8) }
    fun message(id: Int): ProtoMessage? = bytes(id)?.let(::ProtoMessage)
    fun messages(id: Int): List<ProtoMessage> = fields[id].orEmpty().mapNotNull { (it as? ByteArray)?.let(::ProtoMessage) }
}

internal class ProtoWriter {
    private val out = ByteArrayOutputStream()
    private fun raw(value: Long) { var n = value; while (n and -128L != 0L) { out.write(((n and 127L) or 128L).toInt()); n = n ushr 7 }; out.write(n.toInt()) }
    fun number(id: Int, value: Long): ProtoWriter { if (value != 0L) { raw((id * 8).toLong()); raw(value) }; return this }
    fun string(id: Int, value: String): ProtoWriter { if (value.isNotEmpty()) bytes(id, value.toByteArray(Charsets.UTF_8)); return this }
    fun bytes(id: Int, value: ByteArray): ProtoWriter { raw((id * 8 + 2).toLong()); raw(value.size.toLong()); out.write(value); return this }
    fun build(): ByteArray = out.toByteArray()
}
