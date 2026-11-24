package app.biliflow

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class BiliApplication : Application(), coil.ImageLoaderFactory {
    override fun newImageLoader() = coil.ImageLoader.Builder(this).components {
        if (android.os.Build.VERSION.SDK_INT >= 28) add(coil.decode.ImageDecoderDecoder.Factory())
        else add(coil.decode.GifDecoder.Factory())
    }.build()
}

data class Video(val id: String, val title: String, val cover: String, val author: String,
    val views: Long = 0, val duration: String = "", val description: String = "", val authorId: Long = 0, val authorFace: String = "") {
    fun json() = JSONObject().put("bvid", id).put("title", title).put("pic", cover)
        .put("author", author).put("play", views).put("duration", duration).put("desc", description).put("mid", authorId).put("upic", authorFace)
}
data class LiveRoom(val id: Long, val title: String, val cover: String, val author: String, val online: Long)
data class Part(val cid: Long, val title: String)
data class VideoDetail(val video: Video, val parts: List<Part>, val aid: Long = 0, val authorId: Long = 0,
    val authorFace: String = "", val publishedAt: Long = 0, val likes: Long = 0, val coins: Long = 0, val collected: Long = 0, val coinLimit: Int = 2)
data class Account(val id: Long, val name: String, val avatar: String)
data class LoginQr(val url: String, val key: String)
data class QrState(val qr: LoginQr? = null, val loading: Boolean = false, val status: String = "", val expired: Boolean = false, val error: String? = null)
data class FeedState(val videos: List<Video> = emptyList(), val loading: Boolean = false,
    val error: String? = null, val page: Int = 1, val more: Boolean = true)

internal fun unseenRecommendations(videos: List<Video>, seen: Set<String>, current: List<Video>): List<Video> {
    val currentIds = current.mapTo(HashSet()) { it.id }
    return videos.filterNot { it.id in seen || it.id in currentIds }.distinctBy { it.id }
}

internal suspend fun loadUnseenRecommendations(
    next: Boolean,
    seen: Set<String>,
    current: List<Video>,
    recommend: suspend (next: Boolean) -> List<Video>,
): List<Video> {
    // If the cursor keeps yielding repeats, start a fresh recommendation sequence.
    for (append in listOf(next, true, true, false, true)) {
        val videos = unseenRecommendations(recommend(append), seen, current)
        if (videos.isNotEmpty()) return videos
    }
    return emptyList()
}

fun networkMessage(error: Exception): String = when (error) {
    is java.io.InterruptedIOException -> "网络请求超时，请重试"
    is java.net.UnknownHostException -> "无法连接服务器，请检查网络后重试"
    is javax.net.ssl.SSLException -> "安全连接未能建立，请稍后重试"
    else -> error.message ?: "网络连接失败，请重试"
}

fun secureUrl(value: String): String = when {
    value.startsWith("//") -> "https:$value"
    value.startsWith("http://") -> value.replaceFirst("http://", "https://")
    else -> value
}
fun duration(seconds: Int): String = if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
fun count(value: Long): String = when {
    value >= 100_000_000 -> "%.1f亿".format(value / 100_000_000.0)
    value >= 10_000 -> "%.1f万".format(value / 10_000.0)
    else -> value.toString()
}
fun parseVideo(o: JSONObject): Video {
    val d = o.opt("duration")
    return Video(o.optString("bvid"), o.optString("title").replace(Regex("<[^>]*>"), "")
        .replace("&amp;", "&").replace("&quot;", "\""), secureUrl(o.optString("pic")),
        o.optJSONObject("owner")?.optString("name") ?: o.optString("author"),
        o.optJSONObject("stat")?.optLong("view") ?: o.optLong("play"),
        if (d is Number) duration(d.toInt()) else d?.toString().orEmpty(), o.optString("desc"),
        o.optJSONObject("owner")?.optLong("mid") ?: o.optLong("mid"),
        secureUrl(o.optJSONObject("owner")?.optString("face")?.takeIf { it.isNotBlank() } ?: o.optString("upic")))
}

class BiliApi(private val session: AccountCookies, private val authorization: AppAuthorization, private val deviceId: String) {
    companion object { const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36" }
    private val client = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS)
        .cookieJar(session).build()
    internal suspend fun appGet(path: String, parameters: Map<String, String>): JSONObject {
        val token = authorization.accessKey ?: error("请先完成 App 授权")
        val query = AppProtocol.sign(parameters + mapOf("access_key" to token, "mobi_app" to "android", "platform" to "android", "build" to AppProtocol.BUILD, "ts" to "${System.currentTimeMillis() / 1000}"))
        return request("https://app.bilibili.com$path?$query", app = true).getJSONObject("data")
    }
    internal suspend fun appDynamicGrpc(method: String, payload: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        require(method in setOf("DynAll", "DynAllPersonal", "DynAllUpdOffset", "DynDetail"))
        val token = authorization.accessKey ?: error("请先完成 App 授权")
        val metadata = ProtoWriter().string(1, token).string(2, "android").number(4, AppProtocol.BUILD.toLong())
            .string(5, "bilih5").string(6, deviceId).string(7, "android").build()
        val device = ProtoWriter().number(1, 1).number(2, AppProtocol.BUILD.toLong()).string(3, deviceId)
            .string(4, "android").string(5, "android").string(7, "bilih5")
            .string(8, android.os.Build.MANUFACTURER).string(9, android.os.Build.MODEL).string(10, android.os.Build.VERSION.RELEASE).build()
        fun encoded(value: ByteArray) = android.util.Base64.encodeToString(value, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        val framed = java.nio.ByteBuffer.allocate(payload.size + 5).put(0.toByte()).putInt(payload.size).put(payload).array()
        val req = Request.Builder().url("https://grpc.biliapi.net/bilibili.app.dynamic.v2.Dynamic/$method")
            .header("User-Agent", AppProtocol.UA).header("Authorization", "identify_v1 $token")
            .header("x-bili-metadata-bin", encoded(metadata)).header("x-bili-device-bin", encoded(device))
            .header("app-key", "android").header("buvid", deviceId).header("env", "prod")
            .header("te", "trailers").header("grpc-accept-encoding", "gzip")
            .post(framed.toRequestBody("application/grpc".toMediaType())).build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) error("安卓动态服务暂不可用（HTTP ${response.code}）")
            val body = response.body?.bytes() ?: error("安卓动态服务返回空内容")
            val status = response.trailers()["grpc-status"] ?: response.header("grpc-status")
            if (status != null && status != "0") error("安卓动态服务返回错误（$status）")
            if (method == "DynAllUpdOffset" && status == null) error("安卓动态服务未确认已读状态")
            if (method == "DynAllUpdOffset" && body.isEmpty() && status == "0") return@withContext ByteArray(0)
            require(body.size >= 5) { "安卓动态服务返回空内容" }
            val length = java.nio.ByteBuffer.wrap(body, 1, 4).int
            require(length >= 0 && length <= body.size - 5 && length <= 8 * 1024 * 1024) { "安卓动态数据长度错误" }
            val data = body.copyOfRange(5, 5 + length)
            if (body[0].toInt() == 1) java.util.zip.GZIPInputStream(data.inputStream()).use { it.readBytes() } else data
        }
    }
    internal fun liveSocketClient(): OkHttpClient = client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    internal fun liveSocketIdentity(): Pair<Long, String> {
        val cookies = session.loadForRequest("https://live.bilibili.com/".toHttpUrl())
        return (cookies.firstOrNull { it.name == "DedeUserID" }?.value?.toLongOrNull() ?: 0L) to
            (cookies.firstOrNull { it.name == "buvid3" }?.value ?: deviceId)
    }
    internal suspend fun ensureBrowserIdentity() {
        val host = "https://www.bilibili.com/".toHttpUrl()
        if (session.loadForRequest(host).any { it.name == "buvid3" && it.value.isNotBlank() }) return
        val data = request("https://api.bilibili.com/x/frontend/finger/spi").getJSONObject("data")
        val values = listOf("buvid3" to data.optString("b_3"), "buvid4" to data.optString("b_4")).filter { it.second.isNotBlank() }
        session.saveFromResponse(host, values.map { (name, value) -> Cookie.Builder().name(name).value(value).domain("bilibili.com").path("/").secure().expiresAt(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000).build() })
    }
    internal suspend fun signedWebParams(params: Map<String, String>): String {
        if (mixin == null || System.currentTimeMillis() - keysTime > 3_600_000) account()
        return Wbi.sign(params, mixin ?: error("暂时无法获取直播签名"))
    }
    internal suspend fun request(url: String, allowLoggedOut: Boolean = false, body: String? = null, app: Boolean = false, allowedCodes: Set<Int> = emptySet()): JSONObject = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).header("User-Agent", if (app) AppProtocol.UA else UA)
            .header("Referer", "https://www.bilibili.com/").apply {
                if (app) header("buvid", deviceId)
                if (body != null) post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            }.build()).execute().use { response ->
            if (!response.isSuccessful) error("服务器暂时不可用（HTTP ${response.code}）")
            val body = JSONObject(response.body?.string() ?: error("服务器返回了空内容"))
            val code = body.optInt("code")
            if (code != 0 && code !in allowedCodes && !(allowLoggedOut && code == -101)) error(when (code) {
                -352, -412 -> "请求被 B 站暂时限制，请稍后重试或打开官方页面"
                -101 -> "此内容需要在 B 站登录后查看"
                else -> "${body.optString("message", "请求失败")}（$code）"
            })
            body
        }
    }
    internal fun csrf(): String = session.loadForRequest("https://api.bilibili.com/".toHttpUrl()).firstOrNull { it.name == "bili_jct" }?.value
        ?: error("请先登录账号后再操作")
    internal suspend fun bytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).header("User-Agent", UA).header("Referer", "https://www.bilibili.com/").build()).execute().use {
            if (!it.isSuccessful) error("弹幕加载失败（HTTP ${it.code}）")
            val data = it.body?.bytes() ?: error("返回内容为空")
            require(data.size <= 16 * 1024 * 1024) { "弹幕数据过大" }; data
        }
    }
    private var mixin: String? = null
    private var keysTime = 0L
    suspend fun account(): Account? {
        val data = request("https://api.bilibili.com/x/web-interface/nav", true).getJSONObject("data")
        data.optJSONObject("wbi_img")?.let { images ->
            mixin = Wbi.mixin(images.getString("img_url").substringAfterLast('/').substringBefore('.'), images.getString("sub_url").substringAfterLast('/').substringBefore('.'))
            keysTime = System.currentTimeMillis()
        }
        return if (data.optBoolean("isLogin")) Account(data.getLong("mid"), data.optString("uname"), secureUrl(data.optString("face"))) else null
    }
    private var lastFeedIndex = 0L
    private val feedMetadata = mutableMapOf<String, Triple<String, String, String>>()
    suspend fun dislike(video: Video) {
        val (id, idx, goto) = feedMetadata[video.id] ?: error("此视频缺少推荐信息，请刷新后重试")
        val token = authorization.accessKey ?: error("请先登录并完成 App 授权")
        val params = mapOf("access_key" to token, "id" to id, "idx" to idx, "goto" to goto,
            "reason_id" to "1", "device" to "phone", "mobi_app" to "android", "platform" to "android", "build" to AppProtocol.BUILD,
            "ts" to "${System.currentTimeMillis() / 1000}")
        request("https://app.bilibili.com/x/feed/dislike?${AppProtocol.sign(params)}", app = true)
    }
    suspend fun reportHistory(info: VideoDetail, cid: Long, position: Long) {
        val body = okhttp3.FormBody.Builder().add("aid", "${info.aid}").add("bvid", info.video.id).add("cid", "$cid")
            .add("played_time", "${position / 1000}").add("realtime", "${position / 1000}")
            .add("start_ts", "${System.currentTimeMillis() / 1000}").add("type", "3").add("dt", "2").add("play_type", "0").add("csrf", csrf()).build()
        val buffer = okio.Buffer(); body.writeTo(buffer)
        request("https://api.bilibili.com/x/click-interface/web/heartbeat", body = buffer.readUtf8())
    }
    suspend fun recommend(index: Int, next: Boolean): List<Video> {
        val params = mutableMapOf("build" to AppProtocol.BUILD, "mobi_app" to "android", "platform" to "android", "device" to "phone", "network" to "wifi",
            "idx" to (if (next) lastFeedIndex else 0L).toString(), "pull" to (if (next) "0" else "1"), "display_id" to index.toString(),
            "pn" to index.toString(), "ps" to "20", "column" to "4", "ts" to (System.currentTimeMillis() / 1000).toString())
        authorization.accessKey?.let { params["access_key"] = it }
        val data = request("https://app.bilibili.com/x/v2/feed/index?${AppProtocol.sign(params)}", app = true).getJSONObject("data")
        val items = data.optJSONArray("items") ?: error("App 推荐接口暂时不可用，请刷新重试")
        val cursor = (items.length() - 1 downTo 0).firstNotNullOfOrNull { index ->
            items.optJSONObject(index)?.optLong("idx")?.takeIf { it > 0 }
        }
        lastFeedIndex = cursor ?: if (next) lastFeedIndex else 0L
        return (0 until items.length()).mapNotNull {
            val item = items.getJSONObject(it)
            AppProtocol.video(item)?.also { video -> feedMetadata[video.id] = Triple(item.optString("param"), item.optString("idx"), item.optString("goto")) }
        }
    }
    suspend fun currentAccount(): Account? {
        return if (authorization.accessKey != null) appAccount() else account()
    }
    private suspend fun appAccount(): Account {
        val token = authorization.accessKey ?: error("App 授权已过期，请重新登录")
        val params = mapOf("access_key" to token, "build" to AppProtocol.BUILD, "mobi_app" to "android", "platform" to "android", "ts" to (System.currentTimeMillis() / 1000).toString())
        val data = request("https://app.bilibili.com/x/v2/account/myinfo?${AppProtocol.sign(params, login = true)}", app = true).getJSONObject("data")
        return Account(data.getLong("mid"), data.optString("name").ifBlank { data.optString("uname") }, secureUrl(data.optString("face")))
    }
    suspend fun createQr(): LoginQr {
        val params = mapOf("local_id" to "0", "ts" to (System.currentTimeMillis() / 1000).toString(), "mobi_app" to "android")
        val data = request("https://passport.bilibili.com/x/passport-tv-login/qrcode/auth_code", body = AppProtocol.sign(params, login = true), app = true).getJSONObject("data")
        return LoginQr(data.getString("url"), data.getString("auth_code"))
    }
    suspend fun pollQr(key: String): Int {
        val params = mapOf("local_id" to "0", "auth_code" to key, "ts" to (System.currentTimeMillis() / 1000).toString())
        val response = request("https://passport.bilibili.com/x/passport-tv-login/qrcode/poll", body = AppProtocol.sign(params, login = true), app = true,
            allowedCodes = setOf(86038, 86039, 86090))
        if (response.getInt("code") == 0) {
            val data = response.getJSONObject("data")
            authorization.save(data)
            data.optJSONObject("cookie_info")?.optJSONArray("cookies")?.let(session::importAppCookies)
        }
        return response.getInt("code")
    }
    fun cancelPending() { client.dispatcher.cancelAll() }
    suspend fun popular(page: Int): List<Video> {
        val array = request("https://api.bilibili.com/x/web-interface/popular?pn=$page&ps=20")
            .getJSONObject("data").getJSONArray("list")
        return (0 until array.length()).map { parseVideo(array.getJSONObject(it)) }.filter { it.id.isNotBlank() }
    }
    suspend fun search(query: String, page: Int): List<Video> {
        // Anonymous browser cookies are required by the public search endpoint.
        withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url("https://www.bilibili.com/").header("User-Agent", UA).build()).execute().close()
        }
        if (mixin == null || System.currentTimeMillis() - keysTime > 3_600_000) account()
        val signed = Wbi.sign(mapOf("search_type" to "video", "keyword" to query, "page" to page.toString()), mixin ?: error("搜索签名暂时不可用"))
        val data = request("https://api.bilibili.com/x/web-interface/wbi/search/type?$signed").getJSONObject("data").optJSONArray("result") ?: JSONArray()
        return (0 until data.length()).map { parseVideo(data.getJSONObject(it)) }.filter { it.id.isNotBlank() }
    }
    suspend fun detail(id: String): VideoDetail {
        require(Regex("BV[0-9A-Za-z]+").matches(id))
        val data = request("https://api.bilibili.com/x/web-interface/view?bvid=$id").getJSONObject("data")
        val pages = data.getJSONArray("pages")
        return VideoDetail(parseVideo(data), (0 until pages.length()).map {
            val p = pages.getJSONObject(it); Part(p.getLong("cid"), p.getString("part"))
        }, data.optLong("aid"), data.optJSONObject("owner")?.optLong("mid") ?: 0,
            secureUrl(data.optJSONObject("owner")?.optString("face").orEmpty()), data.optLong("pubdate"),
            data.optJSONObject("stat")?.optLong("like") ?: 0, data.optJSONObject("stat")?.optLong("coin") ?: 0, data.optJSONObject("stat")?.optLong("favorite") ?: 0,
            if (data.optInt("copyright", 1) == 1) 2 else 1)
    }
    suspend fun play(id: String, cid: Long): List<String> {
        require(Regex("BV[0-9A-Za-z]+").matches(id))
        val data = request("https://api.bilibili.com/x/player/playurl?bvid=$id&cid=$cid&qn=32&fnval=0&platform=html5&high_quality=1").getJSONObject("data")
        val urls = data.optJSONArray("durl") ?: error("此视频暂不支持直接播放，请打开官方页面")
        val result = (0 until urls.length()).map { secureUrl(urls.getJSONObject(it).getString("url")) }
        if (result.isEmpty()) error("未获得播放地址，请打开官方页面")
        return result
    }
    suspend fun live(): List<LiveRoom> {
        val list = request("https://api.live.bilibili.com/room/v1/room/get_user_recommend?page=1&page_size=20").getJSONArray("data")
        return (0 until list.length()).map {
            val o = list.getJSONObject(it)
            LiveRoom(o.getLong("roomid"), o.optString("title"), secureUrl(o.optString("user_cover").ifBlank { o.optString("system_cover") }), o.optString("uname"), o.optLong("online"))
        }
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("biliflow", Context.MODE_PRIVATE)
    private val session = AccountCookies(SessionVault(application))
    private val authorization = AppAuthorization(SessionVault(application, "app_authorization"))
    private val deviceId = prefs.getString("device_id", null) ?: java.util.UUID.randomUUID().toString().replace("-", "").also { prefs.edit().putString("device_id", it).apply() }
    val api = BiliApi(session, authorization, deviceId)
    val authorAvatars = AuthorAvatars(viewModelScope, api::videoAuthor)
    val videoScenes = VideoSceneCache()
    var preferredQuality by mutableIntStateOf(prefs.getInt("preferred_quality", 0)); private set
    var qualityChangeNotice by mutableStateOf(prefs.getBoolean("quality_change_notice", false)); private set
    var doubleColumn by mutableStateOf(prefs.getBoolean("double_column", false)); private set
    var interfaceHdr by mutableStateOf(prefs.getBoolean("interface_hdr", false)); private set
    var horizontalSeek by mutableStateOf(prefs.getBoolean("horizontal_seek", true)); private set
    var danmakuStyle by mutableStateOf(readDanmakuStyle(prefs)); private set
    fun updateDanmakuStyle(value: DanmakuStyle) { danmakuStyle = value.normalized(); playback.danmakuStyle = danmakuStyle; saveDanmakuStyle(prefs, danmakuStyle) }
    var miniDragging by mutableStateOf(prefs.getBoolean("mini_dragging", false)); private set
    var feedMessage by mutableStateOf<String?>(null); private set
    private val seenRecommendations = LinkedHashSet(prefs.getStringSet("seen_recommendations", emptySet()) ?: emptySet())
    var pipEnabled by mutableStateOf(prefs.getBoolean("pip_enabled", true)); private set
    private val playbackHistory = PlaybackHistory(application)
    val playback = PlaybackController(application, api, viewModelScope, playbackHistory, { preferredQuality }, { qualityChangeNotice })
    val watchLater = WatchLaterStore(viewModelScope, { account?.id },
        { android.widget.Toast.makeText(application, it, android.widget.Toast.LENGTH_SHORT).show() }, playback::retainQueue,
        api::watchLater, api::addWatchLater, api::removeWatchLater, api::clearWatchLater)
    var appAuthorized by mutableStateOf(authorization.accessKey != null); private set
    var account by mutableStateOf<Account?>(null); private set
    var accountLoading by mutableStateOf(false); private set
    var accountError by mutableStateOf<String?>(null); private set
    var qrState by mutableStateOf(QrState()); private set
    var popularMode by mutableStateOf(false); private set
    var theme by mutableStateOf(prefs.getString("theme", "system") ?: "system"); private set
    var favorites by mutableStateOf(readList("favorites")); private set
    var history by mutableStateOf(readList("history")); private set
    var feed by mutableStateOf(FeedState()); private set
    var query by mutableStateOf(""); private set
    var rooms by mutableStateOf<List<LiveRoom>>(emptyList()); private set
    var liveLoading by mutableStateOf(false); private set
    var liveError by mutableStateOf<String?>(null); private set
    var followedRooms by mutableStateOf<List<LiveRoom>>(emptyList()); private set
    var followedLiveLoading by mutableStateOf(false); private set
    var followedLiveError by mutableStateOf<String?>(null); private set
    var dynamicPosts by mutableStateOf<List<DynamicPost>>(emptyList()); private set
    var dynamicLoading by mutableStateOf(false); private set
    var dynamicError by mutableStateOf<String?>(null); private set
    var dynamicMore by mutableStateOf(true); private set
    var dynamicAuthor by mutableStateOf<FollowedAuthor?>(null); private set
    var dynamicReadError by mutableStateOf<String?>(null); private set
    private var dynamicReadJob: Job? = null
    private var pendingDynamicRead: Pair<FollowedAuthor, String>? = null
    var recentAuthors by mutableStateOf<List<FollowedAuthor>>(emptyList()); private set
    var followingAuthors by mutableStateOf<List<FollowedAuthor>>(emptyList()); private set
    var authorsLoading by mutableStateOf(false); private set
    var authorsError by mutableStateOf<String?>(null); private set
    var authorsMore by mutableStateOf(true); private set
    private var authorsPage = 0
    private var authorsJob: Job? = null
    val dynamicAuthors get() = (recentAuthors + followingAuthors).distinctBy { it.id }
    private var dynamicOffset = ""
    private var dynamicLoaded = false
    private val dynamicFeeds = DynamicFeedCache()
    fun dynamicFeed(authorId: Long? = dynamicAuthor?.id): DynamicFeedSnapshot = if (authorId == dynamicAuthor?.id)
        DynamicFeedSnapshot(dynamicPosts, dynamicOffset, dynamicMore, dynamicLoaded, dynamicLoading, dynamicError)
        else dynamicFeeds.get(authorId)
    private var dynamicJob: Job? = null
    var dynamicEpoch by mutableIntStateOf(0); private set
    private var feedJob: Job? = null
    private var qrJob: Job? = null
    private var authJob: Job? = null
    private var recommendIndex = 0
    init { playback.horizontalSeek = horizontalSeek; playback.danmakuStyle = danmakuStyle; session.exportToWeb(); refreshAccount(); loadFeed() }
    fun refreshAccount(importWeb: Boolean = true, reportMissing: Boolean = false) {
        if (importWeb) session.importWebSession()
        if (accountLoading) return
        accountLoading = true; accountError = null
        authJob = viewModelScope.launch {
            try {
                val previous = account?.id
                account = api.currentAccount()
                if (previous != account?.id) { watchLater.reset(account?.id); playback.clearQueue() }
                appAuthorized = authorization.accessKey != null
                if (account != null) session.exportToWeb()
                if (previous != account?.id) { watchLater.reset(account?.id); playback.clearQueue(); query = ""; popularMode = false; loadFeed(); dynamicPosts = emptyList(); resetDynamicAuthors(); loadFollowedLive() }
                if (reportMissing && account == null) accountError = "尚未检测到登录，请在官方页面完成验证后再试"
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { if (reportMissing) accountError = networkMessage(e) }
            finally { accountLoading = false }
        }
    }
    fun startQrLogin() {
        qrJob?.cancel(); accountError = null; qrState = QrState(loading = true)
        qrJob = viewModelScope.launch {
            try {
                val qr = api.createQr(); qrState = QrState(qr, status = "使用哔哩哔哩客户端扫描二维码")
                val deadline = System.currentTimeMillis() + 180_000
                while (System.currentTimeMillis() < deadline) {
                    delay(2000)
                    val status = try { api.pollQr(qr.key) } catch (e: java.io.IOException) {
                        qrState = qrState.copy(status = "网络暂时不稳定，正在重试…")
                        continue
                    }
                    when (status) {
                        0 -> {
                            val verified = api.currentAccount() ?: error("登录尚未生效，请重试")
                            if (account?.id != verified.id) { watchLater.reset(verified.id); playback.clearQueue() }
                            session.exportToWeb(); account = verified; appAuthorized = true; query = ""; popularMode = false; loadFeed(); loadFollowedLive()
                            qrState = qrState.copy(status = "登录成功"); return@launch
                        }
                        86038 -> { qrState = qrState.copy(expired = true, status = "二维码已过期，请刷新"); return@launch }
                        86090 -> qrState = qrState.copy(status = "扫码成功，请在手机上确认登录")
                        86101, 86039 -> qrState = qrState.copy(status = "使用哔哩哔哩客户端扫描二维码")
                        else -> error("扫码状态异常，请刷新二维码")
                    }
                }
                qrState = qrState.copy(expired = true, status = "二维码已过期，请刷新")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { qrState = qrState.copy(loading = false, error = networkMessage(e)) }
        }
    }
    fun stopQrLogin() { qrJob?.cancel() }
    fun logout() {
        watchLater.reset(null); playback.clearQueue()
        watchLater.reset(null); playback.clearQueue()
        videoScenes.clear()
        qrJob?.cancel(); authJob?.cancel(); accountLoading = false
        dynamicJob?.cancel(); dynamicReadJob?.cancel(); pendingDynamicRead = null; dynamicReadError = null; dynamicEpoch++; dynamicPosts = emptyList(); dynamicOffset = ""; dynamicMore = true; dynamicLoading = false; dynamicError = null; followedRooms = emptyList(); followedLiveError = null; resetDynamicAuthors()
        api.cancelPending(); authorization.clear(); session.clear(); account = null; appAuthorized = false; accountError = null; query = ""; popularMode = false; loadFeed()
    }
    fun updateTheme(value: String) { theme = value; prefs.edit().putString("theme", value).apply() }
    fun updatePreferredQuality(value: Int) { preferredQuality = value; prefs.edit().putInt("preferred_quality", value).apply() }
    fun updateQualityChangeNotice(value: Boolean) { qualityChangeNotice = value; prefs.edit().putBoolean("quality_change_notice", value).apply() }
    fun updateMiniDragging(value: Boolean) { miniDragging = value; prefs.edit().putBoolean("mini_dragging", value).apply() }
    fun updateDoubleColumn(value: Boolean) { doubleColumn = value; prefs.edit().putBoolean("double_column", value).apply() }
    fun updateInterfaceHdr(value: Boolean) { interfaceHdr = value; prefs.edit().putBoolean("interface_hdr", value).apply(); playback.systemStateChanged() }
    fun updateHorizontalSeek(value: Boolean) { horizontalSeek = value; playback.horizontalSeek = value; prefs.edit().putBoolean("horizontal_seek", value).apply() }
    private var seenSaveJob: Job? = null
    private var seenDirty = false
    fun markRecommendationsSeen(ids: List<String>) {
        if (query.isNotBlank() || popularMode || ids.isEmpty()) return
        if (!seenRecommendations.addAll(ids)) return
        seenDirty = true
        if (seenSaveJob?.isActive == true) return
        seenSaveJob = viewModelScope.launch {
            while (seenDirty) {
                delay(1000)
                val snapshot = seenRecommendations.toSet()
                seenDirty = false
                withContext(Dispatchers.IO) {
                    prefs.edit().putStringSet("seen_recommendations", snapshot).apply()
                }
            }
        }
    }
    fun dislike(video: Video) {
        if (account == null || !appAuthorized) { feedMessage = "请先登录并完成 App 授权"; return }
        viewModelScope.launch {
            try { api.dislike(video); markRecommendationsSeen(listOf(video.id)); feed = feed.copy(videos = feed.videos.filterNot { it.id == video.id }); feedMessage = "已向账号提交不感兴趣" }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { feedMessage = "提交失败：${networkMessage(e)}" }
        }
    }
    fun clearFeedMessage() { feedMessage = null }
    fun updatePipEnabled(value: Boolean) { pipEnabled = value; prefs.edit().putBoolean("pip_enabled", value).apply() }
    fun loadDynamic(next: Boolean = false) {
        if (account == null) { dynamicPosts = emptyList(); dynamicError = null; return }
        if (next && (dynamicLoading || !dynamicMore)) return
        val generation = ++dynamicEpoch; val accountId = account?.id; val author = dynamicAuthor; val authorId = author?.id
        if (!next && (authorId == null || dynamicAuthors.isEmpty())) loadDynamicAuthors()
        dynamicJob?.cancel(); dynamicLoading = true; dynamicError = null
        val before = if (next) dynamicPosts else emptyList()
        dynamicJob = viewModelScope.launch {
            try {
                val result = api.androidDynamic(if (next) dynamicOffset else "", authorId, author?.footprint.orEmpty(), author?.personalExtra.orEmpty())
                if (account?.id == accountId && generation == dynamicEpoch) {
                    dynamicPosts = (before + result.posts).distinctBy { it.id }; dynamicOffset = result.offset; dynamicMore = result.more
                    dynamicLoaded = true
                    if (!next && authorId == null) recentAuthors = result.authors
                    if (!next && author != null) { pendingDynamicRead = author to result.readOffset; retryDynamicReadSync() }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { if (generation == dynamicEpoch) dynamicError = networkMessage(e) }
            finally { if (generation == dynamicEpoch) dynamicLoading = false }
        }
    }
    private fun resetDynamicAuthors() {
        dynamicFeeds.clear(); dynamicLoaded = false
        dynamicReadJob?.cancel(); pendingDynamicRead = null; dynamicReadError = null
        authorsJob?.cancel(); authorsLoading = false; dynamicAuthor = null; recentAuthors = emptyList(); followingAuthors = emptyList(); authorsPage = 0; authorsMore = true; authorsError = null
    }
    fun selectDynamicAuthor(author: FollowedAuthor?) {
        if (dynamicAuthor?.id == author?.id) return
        dynamicFeeds.put(dynamicAuthor?.id, dynamicFeed())
        dynamicJob?.cancel(); dynamicEpoch++
        dynamicReadJob?.cancel(); pendingDynamicRead = null; dynamicReadError = null
        val cached = dynamicFeeds.get(author?.id)
        dynamicAuthor = author; dynamicPosts = cached.posts; dynamicOffset = cached.offset; dynamicMore = cached.more
        dynamicLoaded = cached.loaded; dynamicLoading = false; dynamicError = cached.error
        if (!cached.loaded || author?.updated == true) loadDynamic()
    }
    fun retryDynamicReadSync() {
        val (author, readOffset) = pendingDynamicRead ?: return
        val accountId = account?.id ?: return
        dynamicReadJob?.cancel(); dynamicReadError = null
        dynamicReadJob = viewModelScope.launch {
            try {
                api.markAndroidDynamicRead(author, readOffset)
                if (account?.id == accountId) {
                    recentAuthors = recentAuthors.map { if (it.id == author.id) it.copy(updated = false) else it }
                    followingAuthors = followingAuthors.map { if (it.id == author.id) it.copy(updated = false) else it }
                    if (dynamicAuthor?.id == author.id) dynamicAuthor = dynamicAuthor?.copy(updated = false)
                    if (pendingDynamicRead?.first?.id == author.id) pendingDynamicRead = null
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { if (account?.id == accountId && dynamicAuthor?.id == author.id) dynamicReadError = networkMessage(e) }
        }
    }
    fun loadDynamicAuthors(next: Boolean = false) {
        val id = account?.id ?: return
        if (authorsLoading || (next && !authorsMore)) return
        authorsLoading = true; authorsError = null
        val page = if (next) authorsPage + 1 else 1
        authorsJob = viewModelScope.launch {
            try {
                val response = api.followingAuthors(id, page)
                if (account?.id == id) { followingAuthors = ((if (next) followingAuthors else emptyList()) + response.authors).distinctBy { it.id }; authorsPage = page; authorsMore = response.more }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { if (account?.id == id) authorsError = networkMessage(e) }
            finally { if (account?.id == id) authorsLoading = false }
        }
    }
    fun loadFollowedLive() {
        if (account == null) { followedRooms = emptyList(); return }
        if (followedLiveLoading) return
        followedLiveLoading = true; followedLiveError = null
        val accountId = account?.id
        viewModelScope.launch {
            try { val result = api.followedLive(); if (account?.id == accountId) followedRooms = result }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { followedLiveError = networkMessage(e) }
            finally { followedLiveLoading = false }
        }
    }
    private fun readList(key: String): List<Video> = runCatching {
        val array = JSONArray(prefs.getString(key, "[]"))
        (0 until array.length()).map { parseVideo(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())
    private fun save(key: String, videos: List<Video>) { prefs.edit().putString(key, JSONArray(videos.map { it.json() }).toString()).apply() }
    fun toggleFavorite(video: Video) {
        favorites = if (favorites.any { it.id == video.id }) favorites.filterNot { it.id == video.id } else listOf(video) + favorites
        save("favorites", favorites)
    }
    fun record(video: Video) { history = (listOf(video) + history.filterNot { it.id == video.id }).take(100); save("history", history) }
    fun clearHistory() { history = emptyList(); save("history", history) }
    fun search(value: String) { query = value.trim(); popularMode = false; loadFeed() }
    fun showPopular() { query = ""; popularMode = true; loadFeed() }
    fun loadFeed(next: Boolean = false) {
        if (next && (feed.loading || !feed.more)) return
        feedJob?.cancel()
        val page = if (next) feed.page + 1 else 1
        val old = if (next) feed.videos else emptyList()
        feed = FeedState(old, true, page = if (next) feed.page else 1)
        feedJob = viewModelScope.launch {
            try {
                val videos = when {
                    query.isNotBlank() -> api.search(query, page)
                    popularMode -> api.popular(page)
                    account != null && !appAuthorized -> error("请完成 App 推荐授权，以获取此账号的安卓推荐流")
                    else -> loadUnseenRecommendations(next, seenRecommendations, old) { append ->
                        api.recommend(++recommendIndex, append)
                    }.ifEmpty {
                        error("暂时没有新的推荐视频，请重试")
                    }
                }
                feed = FeedState((feed.videos + videos).distinctBy { it.id }, page = page, more = videos.isNotEmpty())
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { feed = feed.copy(loading = false, error = networkMessage(e)) }
        }
    }
    fun loadLive() {
        if (liveLoading) return
        liveLoading = true; liveError = null
        loadFollowedLive()
        viewModelScope.launch {
            try { rooms = api.live() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { liveError = networkMessage(e) }
            finally { liveLoading = false }
        }
    }
    override fun onCleared() { if (seenDirty) prefs.edit().putStringSet("seen_recommendations", seenRecommendations.toSet()).apply(); playback.release(); super.onCleared() }
}
