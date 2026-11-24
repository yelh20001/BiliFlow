package app.biliflow

import java.net.URLEncoder
import java.security.MessageDigest

object Wbi {
    private val table = intArrayOf(46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13,37,48,7,16,24,55,40,61,26,17,0,1,60,51,30,4,22,25,54,21,56,59,6,63,57,62,11,36,20,34,44,52)
    fun mixin(img: String, sub: String): String {
        val raw = img + sub
        require(raw.length == 64) { "推荐签名信息无效，请重试" }
        return table.take(32).map { raw[it] }.joinToString("")
    }
    fun sign(params: Map<String, String>, mixin: String, timestamp: Long = System.currentTimeMillis() / 1000): String {
        fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("*", "%2A")
        val query = (params + ("wts" to timestamp.toString())).toSortedMap().entries.joinToString("&") { (key, value) ->
            encode(key) + "=" + encode(value.filterNot { it in "!'()*" })
        }
        val digest = MessageDigest.getInstance("MD5").digest((query + mixin).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return "$query&w_rid=$digest"
    }
}
