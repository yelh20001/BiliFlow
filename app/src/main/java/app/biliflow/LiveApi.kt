package app.biliflow

import org.json.JSONObject

data class LiveDetail(val id: Long, val title: String, val authorId: Long, val author: String, val avatar: String,
    val online: Long, val area: String, val live: Boolean, val description: String)
data class LiveStream(val url: String, val quality: Int, val qualities: List<Quality>)
data class LiveChat(val id: String, val name: String, val text: String)
fun liveQualityLabel(id: Int) = when (id) { 30000 -> "杜比"; 20000 -> "4K"; 10000 -> "原画"; 400 -> "蓝光"; 250 -> "超清"; 150 -> "高清"; 80 -> "流畅"; else -> "清晰度 $id" }
fun parseLiveStream(data: JSONObject): LiveStream {
    val play = data.optJSONObject("playurl_info")?.optJSONObject("playurl") ?: error("主播暂未开播，或当前直播无法播放")
    val descriptions = play.optJSONArray("g_qn_desc")?.objects().orEmpty().associate { it.optInt("qn") to it.optString("desc") }
    val streams = play.optJSONArray("stream")?.objects().orEmpty().filter { it.optString("protocol_name") == "http_hls" }
    for (stream in streams) {
        val formats = stream.optJSONArray("format")?.objects().orEmpty().sortedBy { if (it.optString("format_name") == "fmp4") 0 else 1 }
        for (format in formats) for (codec in format.optJSONArray("codec")?.objects().orEmpty().filter { it.optString("codec_name") == "avc" }) {
            val base = codec.optString("base_url")
            val host = codec.optJSONArray("url_info")?.objects()?.firstOrNull { it.optString("host").startsWith("http") } ?: continue
            if (base.isBlank()) continue
            val current = codec.optInt("current_qn", 150)
            val available = codec.optJSONArray("accept_qn")
            val qualities = (0 until (available?.length() ?: 0)).map { available!!.optInt(it) }.distinct().sortedDescending()
                .map { Quality(it, descriptions[it]?.takeIf(String::isNotBlank) ?: liveQualityLabel(it)) }
            return LiveStream(secureUrl(host.optString("host") + base + host.optString("extra")), current, qualities.ifEmpty { listOf(Quality(current, liveQualityLabel(current))) })
        }
    }
    error("当前直播没有可用的 HLS 视频流，请稍后重试")
}
suspend fun BiliApi.liveDetail(roomId: Long): LiveDetail {
    val data = get("https://api.live.bilibili.com/xlive/web-room/v1/index/getInfoByRoom", mapOf("room_id" to "$roomId"))
    val room = data.getJSONObject("room_info")
    val author = data.optJSONObject("anchor_info")?.optJSONObject("base_info") ?: JSONObject()
    return LiveDetail(room.optLong("room_id", roomId), room.optString("title"), room.optLong("uid"), author.optString("uname"), secureUrl(author.optString("face")),
        room.optLong("online"), room.optString("area_name"), room.optInt("live_status") == 1, room.optString("description").replace(Regex("<[^>]*>"), "").replace("&nbsp;", " "))
}
suspend fun BiliApi.liveStream(roomId: Long, quality: Int = 10000): LiveStream = parseLiveStream(get(
    "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo", mapOf("room_id" to "$roomId", "protocol" to "1", "format" to "1,2", "codec" to "0", "qn" to "$quality", "platform" to "web", "ptype" to "8")))
suspend fun BiliApi.liveChat(roomId: Long): List<LiveChat> {
    val data = get("https://api.live.bilibili.com/xlive/web-room/v1/dM/gethistory", mapOf("roomid" to "$roomId"))
    return data.optJSONArray("room")?.objects().orEmpty().map { LiveChat(it.optString("timeline") + it.optString("uid") + it.optString("text"), it.optString("nickname"), it.optString("text")) }.distinctBy { it.id }
}
