package app.biliflow

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class PlaybackMark(val videoId: String, val cid: Long, val part: Int, val position: Long, val duration: Long, val updated: Long) {
    fun resumePosition(): Long = if (duration > 0 && position >= duration - 3000) 0 else position.coerceAtLeast(0)
}
class PlaybackHistory(context: Context) {
    private val prefs = context.getSharedPreferences("playback_progress", Context.MODE_PRIVATE)
    private val marks = runCatching {
        JSONArray(prefs.getString("marks", "[]")).objects().map {
            PlaybackMark(it.getString("video"), it.getLong("cid"), it.optInt("part"), it.optLong("position"), it.optLong("duration"), it.optLong("updated"))
        }.toMutableList()
    }.getOrDefault(mutableListOf())
    fun latest(video: String) = marks.filter { it.videoId == video }.maxByOrNull { it.updated }
    fun get(video: String, cid: Long) = marks.find { it.videoId == video && it.cid == cid }
    fun save(mark: PlaybackMark) {
        marks.removeAll { it.videoId == mark.videoId && it.cid == mark.cid }; marks.add(0, mark)
        while (marks.size > 500) marks.removeAt(marks.lastIndex)
        prefs.edit().putString("marks", JSONArray(marks.map {
            JSONObject().put("video", it.videoId).put("cid", it.cid).put("part", it.part).put("position", it.position).put("duration", it.duration).put("updated", it.updated)
        }).toString()).apply()
    }
}

object StreamManifest {
    fun xml(value: String): String = value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&apos;")
    fun chooseVideo(group: List<JSONObject>, hardwareSupported: ((JSONObject) -> Boolean)? = null): JSONObject {
        val hardware = hardwareSupported?.let { support -> group.filter(support) }.orEmpty()
        return if (hardware.isNotEmpty()) hardware.minBy { it.optLong("bandwidth").takeIf { bandwidth -> bandwidth > 0 } ?: Long.MAX_VALUE }
        else group.sortedWith(compareBy<JSONObject> { if (it.optInt("codecid") == 7) 0 else 1 }.thenByDescending { it.optLong("bandwidth") }).first()
    }
    fun build(data: JSONObject, preferred: Int, hardwareSupported: ((JSONObject) -> Boolean)? = null): String? {
        val dash = data.optJSONObject("dash") ?: return null
        val allVideos = dash.optJSONArray("video")?.objects().orEmpty()
        if (allVideos.isEmpty()) return null
        val quality = if (preferred == 0) 0 else if (allVideos.any { it.optInt("id") == preferred }) preferred else data.optInt("quality")
        val videos = allVideos.filter { quality == 0 || it.optInt("id") == quality }.ifEmpty { allVideos }
            .groupBy { it.optInt("id") }.values.map { chooseVideo(it, hardwareSupported) }
        val audio = dash.optJSONArray("audio")?.objects().orEmpty().maxByOrNull { it.optLong("bandwidth") }
        val seconds = data.optLong("timelength").takeIf { it > 0 }?.div(1000.0) ?: dash.optDouble("duration", 1.0)
        fun representation(item: JSONObject, type: String): String {
            val url = secureUrl(item.optString("baseUrl").ifBlank { item.optString("base_url") })
            val segment = item.optJSONObject("SegmentBase") ?: item.optJSONObject("segment_base") ?: JSONObject()
            val init = segment.optString("Initialization").ifBlank { segment.optString("initialization") }
            val range = segment.optString("indexRange").ifBlank { segment.optString("index_range") }
            val mime = item.optString("mimeType").ifBlank { item.optString("mime_type").ifBlank { "$type/mp4" } }
            val dimensions = if (type == "video") " width=\"${item.optInt("width")}\" height=\"${item.optInt("height")}\" frameRate=\"${xml(item.optString("frameRate").ifBlank { item.optString("frame_rate", "30") })}\"" else " audioSamplingRate=\"48000\""
            return "<Representation id=\"${type}-${item.optInt("id")}-${item.optInt("codecid")}\" bandwidth=\"${item.optLong("bandwidth", 128000)}\" mimeType=\"${xml(mime)}\" codecs=\"${xml(item.optString("codecs"))}\"$dimensions><BaseURL>${xml(url)}</BaseURL><SegmentBase${if (range.isNotBlank()) " indexRange=\"${xml(range)}\"" else ""}>${if (init.isNotBlank()) "<Initialization range=\"${xml(init)}\"/>" else ""}</SegmentBase></Representation>"
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" mediaPresentationDuration=\"PT${String.format(Locale.US, "%.3f", seconds)}S\" minBufferTime=\"PT1.5S\" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\"><Period><AdaptationSet contentType=\"video\" segmentAlignment=\"true\">${videos.joinToString("") { representation(it, "video") }}</AdaptationSet>${audio?.let { "<AdaptationSet contentType=\"audio\">${representation(it, "audio")}</AdaptationSet>" }.orEmpty()}</Period></MPD>"
    }
}

data class Danmaku(val id: Long, val progress: Long, val mode: Int, val size: Int, val color: Int, val text: String)
object DanmakuProto {
    private class Reader(val data: ByteArray, var index: Int = 0, val end: Int = data.size) {
        fun number(): Long {
            var result = 0L
            for (shift in 0..63 step 7) {
                require(index < end) { "弹幕数据不完整" }
                val value = data[index++].toInt() and 255
                result = result or ((value and 127).toLong() shl shift)
                if (value and 128 == 0) return result
            }
            error("弹幕数据格式错误")
        }
        fun bytes(): ByteArray { val length = number().toInt(); require(length >= 0 && length <= end - index); return data.copyOfRange(index, index + length).also { index += length } }
        fun skip(type: Int) {
            when (type) { 0 -> number(); 1 -> index += 8; 2 -> { val size = number().toInt(); require(size >= 0 && size <= end - index); index += size }; 5 -> index += 4; else -> error("不支持的弹幕数据") }
            require(index <= end)
        }
    }
    fun decode(data: ByteArray): List<Danmaku> {
        val reader = Reader(data); val result = mutableListOf<Danmaku>()
        while (reader.index < reader.end) {
            val tag = reader.number().toInt()
            if (tag ushr 3 != 1 || tag and 7 != 2) { reader.skip(tag and 7); continue }
            val item = Reader(reader.bytes())
            var id = 0L; var progress = 0L; var mode = 1; var size = 25; var color = 0xFFFFFF; var text = ""
            while (item.index < item.end) {
                val field = item.number().toInt(); val wire = field and 7
                when {
                    field ushr 3 == 1 && wire == 0 -> id = item.number()
                    field ushr 3 == 2 && wire == 0 -> progress = item.number()
                    field ushr 3 == 3 && wire == 0 -> mode = item.number().toInt()
                    field ushr 3 == 4 && wire == 0 -> size = item.number().toInt()
                    field ushr 3 == 5 && wire == 0 -> color = item.number().toInt()
                    field ushr 3 == 7 && wire == 2 -> text = String(item.bytes(), Charsets.UTF_8)
                    else -> item.skip(wire)
                }
            }
            if (text.isNotBlank() && mode in setOf(1, 4, 5, 6)) result.add(Danmaku(id, progress, mode, size.coerceIn(12, 36), color, text.take(180)))
        }
        return result.sortedBy { it.progress }
    }
}
