package app.biliflow

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class VideoRoute(val key: String, val video: Video)

@Stable class VideoBackStack(initial: List<VideoRoute> = emptyList()) {
    var entries by mutableStateOf(initial.takeLast(32)); private set
    val current get() = entries.lastOrNull()
    val previous get() = entries.getOrNull(entries.size - 2)
    fun open(video: Video) { entries = listOf(VideoRoute(UUID.randomUUID().toString(), video)) }
    fun push(video: Video): Boolean {
        if (current?.video?.id == video.id) return false
        entries = (entries + VideoRoute(UUID.randomUUID().toString(), video)).takeLast(32)
        return true
    }
    fun pop() { entries = entries.dropLast(1) }
    fun clear() { entries = emptyList() }
    fun encode(): String = JSONArray(entries.map { JSONObject().put("key", it.key).put("video", it.video.copy(description = it.video.description.take(1000)).json()) }).toString()
    companion object {
        fun decode(raw: String): VideoBackStack = VideoBackStack(runCatching {
            JSONArray(raw).objects().map { VideoRoute(it.getString("key"), parseVideo(it.getJSONObject("video"))) }.distinctBy { it.key }
        }.getOrDefault(emptyList()))
        val StateSaver = Saver<VideoBackStack, String>(save = { it.encode() }, restore = { decode(it) })
    }
}
