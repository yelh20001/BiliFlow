package app.biliflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

val LocalWatchLaterAction = staticCompositionLocalOf<((Video) -> Unit)?> { null }
data class WatchLaterEntry(val aid: Long, val video: Video, val progress: Int, val playable: Boolean)
fun parseWatchLater(data: JSONObject): List<WatchLaterEntry> = data.optJSONArray("list")?.objects()?.mapNotNull { item ->
    val video = parseVideo(item)
    if (video.id.isBlank() || item.optLong("aid") <= 0) null else WatchLaterEntry(item.optLong("aid"), video,
        item.optInt("progress"), item.optInt("state") == 0)
}?.distinctBy { it.aid }.orEmpty()
suspend fun BiliApi.watchLater() = parseWatchLater(get("/x/v2/history/toview"))
suspend fun BiliApi.addWatchLater(video: Video) {
    val aid = detail(video.id).aid
    require(aid > 0) { "无法获取视频编号" }
    post("/x/v2/history/toview/add", mapOf("aid" to "$aid"))
    awaitWatchLater { list -> list.any { it.aid == aid } }
}
suspend fun BiliApi.removeWatchLater(aid: Long) {
    post("/x/v2/history/toview/del", mapOf("aid" to "$aid"))
    awaitWatchLater { list -> list.none { it.aid == aid } }
}
suspend fun BiliApi.clearWatchLater(playedOnly: Boolean) {
    if (playedOnly) post("/x/v2/history/toview/del", mapOf("viewed" to "true"))
    else post("/x/v2/history/toview/clear", emptyMap())
    awaitWatchLater { list -> if (playedOnly) list.none { it.progress != 0 } else list.isEmpty() }
}
private suspend fun BiliApi.awaitWatchLater(confirmed: (List<WatchLaterEntry>) -> Boolean) {
    awaitWatchLaterUpdate(::watchLater, confirmed)
}
internal suspend fun awaitWatchLaterUpdate(load: suspend () -> List<WatchLaterEntry>, confirmed: (List<WatchLaterEntry>) -> Boolean,
    pause: suspend () -> Unit = { delay(350) }) {
    // The write endpoint can acknowledge before the list replica sees the change.
    repeat(8) { attempt ->
        if (attempt > 0) pause()
        if (confirmed(load())) return
    }
    error("操作已提交，账号列表暂未更新，请稍后刷新确认")
}

/** All mutations are serialized; only server-confirmed snapshots are displayed. */
class WatchLaterStore(private val scope: CoroutineScope,
    private val account: () -> Long?, private val notify: (String) -> Unit, private val updated: (Set<String>) -> Unit,
    private val load: suspend () -> List<WatchLaterEntry>, private val addRemote: suspend (Video) -> Unit,
    private val removeRemote: suspend (Long) -> Unit, private val clearRemote: suspend (Boolean) -> Unit) {
    var entries by mutableStateOf<List<WatchLaterEntry>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    private val mutex = Mutex()
    private var owner: Long? = null
    private var epoch = 0
    private val jobs = mutableSetOf<Job>()
    fun reset(id: Long?) {
        if (owner == id) return
        ++epoch; jobs.toList().forEach { it.cancel() }; jobs.clear()
        owner = id; entries = emptyList(); error = null; busy = false
    }
    private fun run(success: String? = null, action: suspend () -> Unit = {}) {
        val id = account() ?: return notify("请先登录账号")
        reset(id)
        val generation = epoch
        val job = scope.launch(start = CoroutineStart.LAZY) {
            mutex.withLock {
                if (account() != id || generation != epoch) return@withLock
                busy = true; error = null
                try {
                    action()
                    val fresh = load()
                    if (account() == id && generation == epoch) { entries = fresh; updated(fresh.map { it.video.id }.toSet()); success?.let(notify) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (account() == id && generation == epoch) { error = networkMessage(e); notify(error!!) } }
                finally { if (account() == id && generation == epoch) busy = false }
            }
        }
        jobs += job; job.invokeOnCompletion { jobs.remove(job) }; job.start()
    }
    fun refresh() = run()
    fun add(video: Video) = run("已加入稍后再看") { addRemote(video) }
    fun remove(aid: Long) = run("已从稍后再看移除") { removeRemote(aid) }
    fun clear(playedOnly: Boolean) = run(if (playedOnly) "已删除已播放视频" else "已清空稍后再看") { clearRemote(playedOnly) }
}

@Composable fun WatchLaterScreen(model: MainViewModel, play: (List<Video>, Video) -> Unit) {
    val store = model.watchLater
    LaunchedEffect(model.account?.id, model.playback.minimized) { if (model.account != null) store.refresh() }
    val playable = store.entries.filter { it.playable }.map { it.video }
    LazyColumn(Modifier.fillMaxSize().testTag("watch_later_list"), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("账号同步 · ${store.entries.size} 个视频", style = MaterialTheme.typography.titleMedium)
                Text("按列表顺序连播，多 P 视频播完全部分集后切换下一个。", style = MaterialTheme.typography.bodySmall)
                Row {
                    Button(onClick = { playable.firstOrNull()?.let { play(playable, it) } }, enabled = playable.isNotEmpty() && !store.busy) { Text("播放全部") }
                    TextButton(onClick = store::refresh, enabled = !store.busy) { Text("刷新") }
                }
                FlowRow {
                    TextButton(onClick = { store.clear(true) }, enabled = store.entries.isNotEmpty() && !store.busy) { Text("删除已播放") }
                    TextButton(onClick = { store.clear(false) }, enabled = store.entries.isNotEmpty() && !store.busy) { Text("清空列表") }
                }
            }
        }
        if (store.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) }
        store.error?.let { message -> item { ErrorCard(message, store::refresh) } }
        if (model.account == null) item { Text("登录后查看账号稍后再看", Modifier.padding(20.dp)) }
        else if (!store.busy && store.entries.isEmpty() && store.error == null) item { Text("长按视频卡片即可添加稍后再看", Modifier.padding(20.dp)) }
        items(store.entries, key = { it.aid }) { entry ->
            Column(Modifier.animateItem()) {
                VideoCard(entry.video, { if (entry.playable) play(playable, entry.video) })
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (!entry.playable) "视频已失效" else if (entry.progress == -1) "已播放完" else if (entry.progress > 0) "播放至 ${duration(entry.progress)}" else "未播放", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { store.remove(entry.aid) }, enabled = !store.busy) { Text("移除") }
                }
            }
        }
    }
}
