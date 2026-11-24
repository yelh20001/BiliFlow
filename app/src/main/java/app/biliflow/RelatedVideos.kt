package app.biliflow

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

suspend fun BiliApi.relatedVideos(id: String): List<Video> {
    val response = request("https://api.bilibili.com/x/web-interface/archive/related?bvid=${Uri.encode(id)}")
    return response.optJSONArray("data")?.objects().orEmpty().map(::parseVideo).filter { it.id.isNotBlank() && it.id != id }.distinctBy { it.id }
}

@Stable class RelatedVideosState(private val scope: CoroutineScope, initial: List<Video> = emptyList(), initialVisible: Int = 8, private val fetch: suspend () -> List<Video>) {
    var videos by mutableStateOf(initial); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var visible by mutableIntStateOf(initialVisible); private set
    private var job: Job? = null
    fun pause() { job?.cancel(); loading = false }
    fun load() {
        if (loading) return
        loading = true; error = null
        job = scope.launch {
            try { videos = fetch(); visible = 8 }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = networkMessage(e) }
            finally { loading = false }
        }
    }
    fun revealMore() { visible = (visible + 8).coerceAtMost(videos.size) }
}
@Composable fun rememberRelatedVideos(api: BiliApi, id: String, enabled: Boolean = true, initial: List<Video> = emptyList(), initialVisible: Int = 8): RelatedVideosState {
    val scope = rememberCoroutineScope()
    val state = remember(api, id) { RelatedVideosState(scope, initial, initialVisible) { api.relatedVideos(id) } }
    LaunchedEffect(state, enabled) { if (enabled && state.videos.isEmpty()) state.load() else if (!enabled) state.pause() }
    return state
}
@Composable fun AutoRevealRelated(list: LazyListState, state: RelatedVideosState) {
    LaunchedEffect(list, state) {
        snapshotFlow { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) to list.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (total > 0 && last >= total - 3) state.revealMore() }
    }
}
fun LazyListScope.relatedItems(state: RelatedVideosState, open: (Video) -> Unit) {
    item("related_header") {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp).testTag("related_videos_count_${state.videos.size}"), verticalAlignment = Alignment.CenterVertically) {
            Text("相关推荐", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = state::load, enabled = !state.loading) { SpinningRefreshIcon(state.loading, "刷新相关推荐") }
        }
    }
    items(state.videos.take(state.visible), key = { "related_${it.id}" }) { value -> Box(Modifier.padding(vertical = 8.dp).animateItem()) { VideoCard(value, { open(value) }) } }
    if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp)) }
    state.error?.let { message -> item { ErrorCard(message, state::load) } }
    if (!state.loading && state.videos.isEmpty() && state.error == null) item { Text("这个视频暂时没有相关推荐", Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall) }
}
