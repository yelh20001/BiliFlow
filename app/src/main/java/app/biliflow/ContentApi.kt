package app.biliflow

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class Quality(val id: Int, val label: String, val available: Boolean = true)
val preferredQualities = listOf(Quality(0, "自动"), Quality(127, "8K"), Quality(126, "杜比视界"), Quality(125, "HDR"), Quality(120, "4K"), Quality(116, "1080P 60帧"),
    Quality(112, "1080P 高码率"), Quality(80, "1080P"), Quality(74, "720P 60帧"), Quality(64, "720P"), Quality(32, "480P"), Quality(16, "360P"))
fun qualityLabel(id: Int) = preferredQualities.find { it.id == id }?.label ?: when (id) { 125 -> "HDR"; 126 -> "杜比视界"; else -> "清晰度 $id" }
data class PlayStreams(val data: JSONObject, val qualities: List<Quality>, val actualQuality: Int)
data class VideoInteraction(val liked: Boolean = false, val coins: Int = 0, val favorite: Boolean = false, val following: Boolean = false)

internal fun JSONObject.flag(key: String): Boolean = when (val value = opt(key)) {
    is Boolean -> value
    is Number -> value.toInt() != 0
    is String -> value == "1" || value.equals("true", ignoreCase = true)
    else -> false
}
fun parseInteraction(data: JSONObject) = VideoInteraction(data.flag("like"), data.optInt("coin"), data.flag("favorite"), data.flag("attention"))
data class FavoriteFolder(val id: Long, val title: String, val selected: Boolean, val count: Int)
data class CommentPicture(val url: String, val width: Float = 0f, val height: Float = 0f)
data class Comment(val id: Long, val name: String, val avatar: String, val text: String, val likes: Long, val time: Long, val replies: Int,
    val preview: List<Comment> = emptyList(), val emotes: Map<String, String> = emptyMap(), val pictures: List<CommentPicture> = emptyList())
data class CommentPage(val items: List<Comment>, val more: Boolean, val count: Int)
data class DynamicAttachment(val label: String, val title: String = "", val description: String = "", val cover: String = "", val url: String = "")
data class DynamicPost(val id: String, val author: String, val avatar: String, val authorId: Long, val time: String, val text: String,
    val pictures: List<String> = emptyList(), val video: Video? = null, val original: DynamicPost? = null,
    val likes: Long = 0, val comments: Long = 0, val forwards: Long = 0, val articleTitle: String = "", val liveRoom: Long = 0, val publishedAt: Long = 0, val opusId: String = "",
    val attachments: List<DynamicAttachment> = emptyList(), val appBlocks: List<DynamicBlock> = emptyList(), val appType: Long = 0)
data class DynamicPage(val posts: List<DynamicPost>, val offset: String, val more: Boolean, val authors: List<FollowedAuthor> = emptyList(), val readOffset: String = "")

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
private fun webUrl(path: String, params: Map<String, String> = emptyMap()): String {
    val url = (if (path.startsWith("https://")) path else "https://api.bilibili.com$path").toHttpUrl().newBuilder()
    params.forEach { (key, value) -> url.addQueryParameter(key, value) }
    return url.build().toString()
}
internal suspend fun BiliApi.get(path: String, params: Map<String, String> = emptyMap()) = request(webUrl(path, params)).getJSONObject("data")
internal suspend fun BiliApi.post(path: String, params: Map<String, String>, allowedCodes: Set<Int> = emptySet()) {
    val body = (params + ("csrf" to csrf())).entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
    request(webUrl(path), body = body, allowedCodes = allowedCodes)
}
suspend fun BiliApi.streams(id: String, cid: Long, quality: Int): PlayStreams {
    val data = get("/x/player/playurl", mapOf("bvid" to id, "cid" to "$cid", "qn" to (if (quality == 0) "127" else "$quality"),
        "fnval" to "4048", "fnver" to "0", "fourk" to "1", "platform" to "pc", "high_quality" to "1"))
    val ids = data.optJSONObject("dash")?.optJSONArray("video")?.objects()?.map { it.optInt("id") }?.toSet()
        ?: setOf(data.optInt("quality", 32))
    val formats = data.optJSONArray("support_formats")?.objects()?.map {
        val idValue = it.optInt("quality")
        Quality(idValue, it.optString("new_description").ifBlank { it.optString("display_desc").ifBlank { qualityLabel(idValue) } }, idValue in ids)
    }.orEmpty().ifEmpty { ids.sortedDescending().map { Quality(it, qualityLabel(it)) } }
    return PlayStreams(data, listOf(Quality(0, "自动")) + formats.distinctBy { it.id }, data.optInt("quality", ids.maxOrNull() ?: 32))
}
suspend fun BiliApi.followerCount(id: Long) = get("/x/relation/stat", mapOf("vmid" to "$id")).optLong("follower")
suspend fun BiliApi.interaction(id: String): VideoInteraction {
    val data = get("/x/web-interface/archive/relation", mapOf("bvid" to id))
    return parseInteraction(data)
}
suspend fun BiliApi.follow(id: Long, following: Boolean) = post("/x/relation/modify", mapOf("fid" to "$id", "act" to if (following) "1" else "2", "re_src" to "11"))
suspend fun BiliApi.like(id: String, liked: Boolean) = post("/x/web-interface/archive/like", mapOf("bvid" to id, "like" to if (liked) "1" else "2"), if (liked) setOf(65006) else emptySet())
suspend fun BiliApi.coin(aid: Long, amount: Int, alsoLike: Boolean) {
    require(amount in 1..2)
    post("/x/web-interface/coin/add", mapOf("aid" to "$aid", "multiply" to "$amount", "select_like" to if (alsoLike) "1" else "0"))
}
suspend fun BiliApi.favoriteFolders(accountId: Long, aid: Long): List<FavoriteFolder> {
    val data = get("/x/v3/fav/folder/created/list-all", mapOf("up_mid" to "$accountId", "rid" to "$aid", "type" to "2"))
    return data.optJSONArray("list")?.objects()?.map { FavoriteFolder(it.getLong("id"), it.optString("title"), it.optInt("fav_state") == 1, it.optInt("media_count")) }.orEmpty()
}
suspend fun BiliApi.favorite(aid: Long, before: Set<Long>, after: Set<Long>) {
    post("/x/v3/fav/resource/deal", mapOf("rid" to "$aid", "type" to "2", "add_media_ids" to (after - before).joinToString(","), "del_media_ids" to (before - after).joinToString(",")))
}
suspend fun BiliApi.createFolder(title: String) {
    require(title.isNotBlank())
    post("/x/v3/fav/folder/add", mapOf("title" to title.trim(), "privacy" to "0", "intro" to ""))
}

fun parseComment(item: JSONObject, includePreview: Boolean = true): Comment {
    val member = item.optJSONObject("member") ?: JSONObject()
    val content = item.optJSONObject("content") ?: JSONObject()
    val emotes = content.optJSONObject("emote")
    val mapping = emotes?.keys()?.asSequence()?.associateWith { secureUrl(emotes.optJSONObject(it)?.optString("url").orEmpty()) }.orEmpty()
    return Comment(item.optLong("rpid"), member.optString("uname"), secureUrl(member.optString("avatar")), content.optString("message"),
        item.optLong("like"), item.optLong("ctime"), item.optInt("rcount"),
        if (includePreview) item.optJSONArray("replies")?.objects()?.map { parseComment(it, false) }.orEmpty() else emptyList(), mapping,
        content.optJSONArray("pictures")?.objects()?.mapNotNull { picture ->
            secureUrl(picture.optString("img_src")).takeIf { it.isNotBlank() }?.let { CommentPicture(it, picture.optDouble("img_width", 0.0).toFloat(), picture.optDouble("img_height", 0.0).toFloat()) }
        }.orEmpty())
}
suspend fun BiliApi.comments(aid: Long, page: Int, root: Long? = null): CommentPage {
    val params = mutableMapOf("type" to "1", "oid" to "$aid", "pn" to "$page", "ps" to "20", "sort" to "2")
    if (root != null) params["root"] = "$root"
    val data = get(if (root == null) "/x/v2/reply" else "/x/v2/reply/reply", params)
    val regular = data.optJSONArray("replies")?.objects()?.map(::parseComment).orEmpty()
    val pinned = if (root == null && page == 1) data.optJSONArray("top_replies")?.objects()?.map(::parseComment).orEmpty() else emptyList()
    val count = data.optJSONObject("page")?.optInt("count") ?: data.optJSONObject("cursor")?.optInt("all_count") ?: regular.size
    return CommentPage((pinned + regular).distinctBy { it.id }, regular.size >= 20 && page * 20 < count, count)
}

fun parseDynamic(item: JSONObject, includeOriginal: Boolean = true): DynamicPost {
    val modules = item.optJSONObject("modules") ?: JSONObject()
    val author = modules.optJSONObject("module_author") ?: JSONObject()
    val body = modules.optJSONObject("module_dynamic") ?: JSONObject()
    val major = body.optJSONObject("major") ?: JSONObject()
    val stats = modules.optJSONObject("module_stat") ?: JSONObject()
    val archive = major.optJSONObject("archive")
    val opus = major.optJSONObject("opus")
    val pgc = major.optJSONObject("pgc")
    val common = major.optJSONObject("common") ?: major.optJSONObject("music")
    val pictures = (major.optJSONObject("draw")?.optJSONArray("items") ?: opus?.optJSONArray("pics"))?.objects()?.map {
        secureUrl(it.optString("src").ifBlank { it.optString("url") })
    }.orEmpty().filter { it.isNotBlank() }.ifEmpty { (pgc?.optString("cover") ?: common?.optString("cover"))?.takeIf { it.isNotBlank() }?.let { listOf(secureUrl(it)) }.orEmpty() }
    val video = archive?.let {
        val id = it.optString("bvid")
        if (id.isBlank()) null else Video(id, it.optString("title"), secureUrl(it.optString("cover")), author.optString("name"),
            it.optJSONObject("stat")?.optString("play")?.toLongOrNull() ?: 0, it.optString("duration_text"),
            authorId = author.optLong("mid"), authorFace = secureUrl(author.optString("face")))
    }
    val live = major.optJSONObject("live_rcmd")?.optString("content")?.let { runCatching { JSONObject(it).optJSONObject("live_play_info") }.getOrNull() }
    val article = major.optJSONObject("article")
    return DynamicPost(item.optString("id_str"), author.optString("name"), secureUrl(author.optString("face")), author.optLong("mid"),
        author.optString("pub_time"), body.optJSONObject("desc")?.optString("text")?.ifBlank { null }
            ?: opus?.optJSONObject("summary")?.optString("text") ?: live?.optString("title") ?: "",
        pictures, video, if (includeOriginal) item.optJSONObject("orig")?.let { parseDynamic(it, false) } else null,
        stats.optJSONObject("like")?.optLong("count") ?: 0, stats.optJSONObject("comment")?.optLong("count") ?: 0,
        stats.optJSONObject("forward")?.optLong("count") ?: 0, article?.optString("title") ?: opus?.optString("title") ?: pgc?.optString("title") ?: common?.optString("title").orEmpty(), live?.optLong("room_id") ?: 0, author.optLong("pub_ts"),
        if (opus != null) Regex("/opus/(\\d+)").find(opus.optString("jump_url"))?.groupValues?.get(1) ?: item.optString("id_str") else "")
}
suspend fun BiliApi.dynamic(offset: String, authorId: Long? = null): DynamicPage {
    val params = mapOf("type" to "all", "offset" to offset, "platform" to "web",
        "features" to "itemOpusStyle,listOnlyfans,opusBigCover", "web_location" to "333.1365")
    val data = get(if (authorId == null) "/x/polymer/web-dynamic/v1/feed/all" else "/x/polymer/web-dynamic/v1/feed/space",
        if (authorId == null) params else params + ("host_mid" to "$authorId"))
    return DynamicPage(data.optJSONArray("items")?.objects()?.filter { it.optBoolean("visible", true) }?.map(::parseDynamic).orEmpty(),
        data.optString("offset"), data.optBoolean("has_more"))
}
suspend fun BiliApi.followedLive(): List<LiveRoom> {
    val data = get("https://api.live.bilibili.com/xlive/web-ucenter/v1/xfetter/GetWebList", mapOf("hit_ab" to "true"))
    return (data.optJSONArray("rooms") ?: data.optJSONArray("list"))?.objects().orEmpty().filter { it.optInt("live_status", 1) == 1 }.map {
        val cover = it.optString("cover_from_user").takeIf { value -> value.startsWith("http") } ?: it.optString("keyframe").ifBlank { it.optString("face") }
        LiveRoom(it.optLong("room_id"), it.optString("title"), secureUrl(cover), it.optString("uname"), it.optLong("online"))
    }.distinctBy { it.id }
}
suspend fun BiliApi.danmaku(cid: Long, segment: Int): List<Danmaku> = DanmakuProto.decode(bytes(
    "https://api.bilibili.com/x/v2/dm/web/seg.so?type=1&oid=$cid&segment_index=$segment"))
