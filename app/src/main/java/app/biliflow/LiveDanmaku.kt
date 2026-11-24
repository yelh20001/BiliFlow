package app.biliflow

import android.os.SystemClock
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

private data class LiveSocketConfig(val token: String, val urls: List<String>, val user: Long, val device: String)
private suspend fun BiliApi.liveSocketConfig(room: Long): LiveSocketConfig {
    val params = signedWebParams(mapOf("id" to "$room", "type" to "0", "web_location" to "444.8"))
    val data = request("https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?$params").getJSONObject("data")
    val token = data.optString("token"); require(token.isNotBlank())
    val urls = data.optJSONArray("host_list")?.objects().orEmpty().mapNotNull {
        val host = it.optString("host"); val port = it.optInt("wss_port", 443)
        if (host.matches(Regex("[A-Za-z0-9.-]+\\.chat\\.bilibili\\.com")) && port in 1..65535) "wss://$host:$port/sub" else null
    }.distinct().take(4)
    require(urls.isNotEmpty())
    val (user, device) = liveSocketIdentity()
    return LiveSocketConfig(token, urls, user, device)
}

/** Real receive-only connection. Tokens and message bodies never enter diagnostics. */
class LiveDanmaku(private val api: BiliApi, private val scope: CoroutineScope) {
    var bullets by mutableStateOf<List<Danmaku>>(emptyList()); private set
    var chat by mutableStateOf<List<LiveChat>>(emptyList()); private set
    var connected by mutableStateOf(false); private set
    var received by mutableLongStateOf(0); private set
    var status by mutableStateOf("播放后连接实时弹幕"); private set
    private val client by lazy { api.liveSocketClient() }
    private var room: Long? = null
    private var epoch = 0
    private var job: Job? = null
    private var serial = 0L
    fun start(id: Long) {
        if (room == id && job?.isActive == true) return
        stop(); room = id; received = 0; status = "正在连接实时弹幕…"
        val generation = epoch
        job = scope.launch {
            var retry = 0
            while (isActive && room == id && generation == epoch) {
                try {
                    val config = api.liveSocketConfig(id)
                    for (url in config.urls) { ensureActive(); session(id, generation, config, url) }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                if (generation != epoch || room != id) break
                connected = false; status = "实时弹幕暂不可用，正在重连…"
                delay((2000L shl retry.coerceAtMost(4)).coerceAtMost(30_000)); retry++
            }
        }
    }
    fun stop(message: String = "播放后连接实时弹幕") {
        ++epoch; job?.cancel(); job = null; room = null; connected = false; bullets = emptyList(); chat = emptyList(); status = message
    }
    private suspend fun session(id: Long, generation: Int, config: LiveSocketConfig, url: String) = coroutineScope {
        val authenticated = CompletableDeferred<Boolean>()
        val ended = CompletableDeferred<Unit>()
        val lastReceived = AtomicLong(SystemClock.elapsedRealtime())
        val socket = client.newWebSocket(Request.Builder().url(url).header("User-Agent", BiliApi.UA).header("Origin", "https://live.bilibili.com")
            .header("Referer", "https://live.bilibili.com/").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val auth = JSONObject().put("uid", config.user).put("roomid", id).put("protover", 2).put("platform", "web")
                    .put("type", 2).put("buvid", config.device).put("key", config.token)
                webSocket.send(LiveWire.packet(7, auth.toString().toByteArray()).toByteString())
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                lastReceived.set(SystemClock.elapsedRealtime())
                val events = runCatching { LiveWire.decode(bytes.toByteArray()) }.getOrDefault(emptyList())
                events.filterIsInstance<LiveWireEvent.Auth>().forEach { authenticated.complete(it.code == 0) }
                val incoming = events.filterIsInstance<LiveWireEvent.Bullet>()
                if (incoming.isNotEmpty()) scope.launch {
                    if (generation != epoch || room != id) return@launch
                    val now = SystemClock.elapsedRealtime()
                    received += incoming.size
                    val additions = incoming.take(80).map { message -> Danmaku(++serial, now, message.mode, message.size.coerceIn(12, 36), message.color, message.text) }
                    bullets = (bullets.filter { now - it.progress < 10_000 } + additions).takeLast(240)
                    chat = (chat + incoming.take(80).map { LiveChat("socket-${++serial}", it.sender, it.text) }).takeLast(80)
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null); ended.complete(Unit); authenticated.complete(false) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { ended.complete(Unit); authenticated.complete(false) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { ended.complete(Unit); authenticated.complete(false) }
        })
        var heartbeat: Job? = null
        try {
            if (!withTimeout(12_000) { authenticated.await() }) return@coroutineScope
            if (generation != epoch) return@coroutineScope
            connected = true; status = "实时弹幕已连接"
            heartbeat = launch {
                while (isActive) {
                    socket.send(LiveWire.packet(2, "[object Object]".toByteArray()).toByteString())
                    delay(30_000)
                    if (SystemClock.elapsedRealtime() - lastReceived.get() > 75_000) { ended.complete(Unit); break }
                }
            }
            ended.await()
        } finally { heartbeat?.cancel(); socket.cancel(); if (generation == epoch) connected = false }
    }
}
