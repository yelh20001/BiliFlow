package app.biliflow

import org.json.JSONArray
import org.json.JSONObject

data class FollowedAuthor(val id: Long, val name: String, val avatar: String, val updated: Boolean = false,
    val footprint: String = "", val personalExtra: String = "")
data class FollowingPage(val authors: List<FollowedAuthor>, val more: Boolean)
fun parseDynamicAuthors(data: JSONObject): List<FollowedAuthor> {
    val raw = data.opt("up_list")
    val items = (raw as? JSONArray) ?: (raw as? JSONObject)?.optJSONArray("items")
    return items?.objects().orEmpty().map { FollowedAuthor(it.optLong("mid"), it.optString("uname").ifBlank { it.optString("name") },
        secureUrl(it.optString("face")), it.flag("has_update")) }.filter { it.id > 0 }.distinctBy { it.id }
}
suspend fun BiliApi.recentDynamicAuthors(): List<FollowedAuthor> = parseDynamicAuthors(get("/x/polymer/web-dynamic/v1/portal", mapOf("up_list_more" to "0")))
suspend fun BiliApi.followingAuthors(accountId: Long, page: Int): FollowingPage {
    val data = get("/x/relation/followings", mapOf("vmid" to "$accountId", "pn" to "$page", "ps" to "50", "order" to "desc", "order_type" to "attention"))
    val list = data.optJSONArray("list")?.objects().orEmpty().map { FollowedAuthor(it.optLong("mid"), it.optString("uname"), secureUrl(it.optString("face"))) }
    return FollowingPage(list, list.size == 50 && page * 50 < data.optInt("total"))
}
