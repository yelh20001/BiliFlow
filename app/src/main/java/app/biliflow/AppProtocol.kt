package app.biliflow

import java.net.URLEncoder
import java.security.MessageDigest
import org.json.JSONObject

/** Public client signing constants, distinct from a user's private access token. */
object AppProtocol {
    const val LOGIN_KEY = "783bbb7264451d82"
    private const val LOGIN_SECRET = "2653583c8873dea268ab9386918b1d65"
    const val FEED_KEY = "1d8b6e7d45233436"
    private const val FEED_SECRET = "560c52ccd288fed045859ed18bffd973"
    const val BUILD = "8130300"
    const val UA = "Mozilla/5.0 BiliDroid/8.13.0 (compatible; BiliFlow/0.2) os/android mobi_app/android build/8130300 channel/bilih5 network/2"
    fun sign(params: Map<String, String>, login: Boolean = false): String {
        val key = if (login) LOGIN_KEY else FEED_KEY
        val secret = if (login) LOGIN_SECRET else FEED_SECRET
        val query = (params + ("appkey" to key)).toSortedMap().entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val digest = MessageDigest.getInstance("MD5").digest((query + secret).toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return "$query&sign=$digest"
    }
    fun bvid(aid: Long): String {
        require(aid in 1 until (1L shl 51))
        val alphabet = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf"
        val result = "BV1000000000".toCharArray()
        var value = ((1L shl 51) or aid) xor 23442827791579L
        var index = 11
        while (value > 0) { result[index--] = alphabet[(value % 58).toInt()]; value /= 58 }
        result[3] = result[9].also { result[9] = result[3] }
        result[4] = result[7].also { result[7] = result[4] }
        return String(result)
    }
    fun video(item: JSONObject): Video? {
        if (item.optString("goto") !in setOf("av", "vertical_av")) return null
        val aid = item.optString("param").toLongOrNull() ?: item.optJSONObject("args")?.optLong("aid") ?: return null
        val id = item.optString("bvid").ifBlank { runCatching { bvid(aid) }.getOrNull() ?: return null }
        val text = item.optString("cover_left_text_1").replace(",", "")
        val numeric = Regex("[0-9.]+").find(text)?.value?.toDoubleOrNull() ?: 0.0
        val views = (numeric * when { '亿' in text -> 100_000_000; '万' in text -> 10_000; else -> 1 }).toLong()
        return Video(id, item.optString("title"), secureUrl(item.optString("cover")),
            item.optJSONObject("args")?.optString("up_name") ?: item.optString("desc"), views,
            item.optString("cover_right_text").ifBlank { duration(item.optJSONObject("player_args")?.optInt("duration") ?: 0) },
            authorId = item.optJSONObject("args")?.optLong("up_id") ?: 0,
            authorFace = secureUrl(item.optJSONObject("args")?.optString("up_face").orEmpty().ifBlank { item.optJSONObject("owner")?.optString("face").orEmpty() }))
    }
}

class AppAuthorization(private val vault: SessionVault) {
    private var data = runCatching { JSONObject(vault.read() ?: "{}") }.getOrDefault(JSONObject())
    val accessKey: String? get() = data.optString("access_token").takeIf { it.isNotBlank() && data.optLong("expires_at") > System.currentTimeMillis() }
    fun save(response: JSONObject) {
        val token = response.optJSONObject("token_info") ?: response
        val access = token.getString("access_token")
        require(access.isNotBlank()) { "未获得 App 授权，请重试" }
        data = JSONObject().put("access_token", access).put("refresh_token", token.optString("refresh_token"))
            .put("mid", token.optLong("mid")).put("expires_at", System.currentTimeMillis() + token.optLong("expires_in", 15_552_000) * 1000)
        vault.write(data.toString())
    }
    fun clear() { data = JSONObject(); vault.clear() }
}
