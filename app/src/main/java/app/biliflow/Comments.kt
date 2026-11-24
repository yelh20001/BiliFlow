@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.biliflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged

data class CommentsSnapshot(val items: List<Comment> = emptyList(), val page: Int = 0, val count: Int = 0, val more: Boolean = true, val refreshCount: Int = 0)
class CommentsState(private val scope: CoroutineScope, initial: CommentsSnapshot = CommentsSnapshot(), private val fetch: suspend (Int) -> CommentPage) {
    var items by mutableStateOf(initial.items); private set
    var page by mutableIntStateOf(initial.page); private set
    var count by mutableIntStateOf(initial.count); private set
    var loading by mutableStateOf(false); private set
    var refreshing by mutableStateOf(false); private set
    var more by mutableStateOf(initial.more); private set
    var error by mutableStateOf<String?>(null); private set
    var refreshCount by mutableIntStateOf(initial.refreshCount); private set
    private var job: Job? = null
    private var generation = 0
    fun snapshot() = CommentsSnapshot(items, page, count, more, refreshCount)
    fun pause() { job?.cancel(); ++generation; loading = false; refreshing = false }
    fun load(refresh: Boolean = false) {
        if (!refresh && (loading || !more)) return
        job?.cancel(); val version = ++generation
        loading = true; refreshing = refresh && page > 0; error = null
        val next = if (refresh) 1 else page + 1
        job = scope.launch {
            try {
                val result = fetch(next)
                if (version == generation) {
                    items = ((if (refresh) emptyList() else items) + result.items).distinctBy { it.id }
                    page = next; count = result.count; more = result.more
                    if (refresh) refreshCount++
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (version == generation) error = networkMessage(e) }
            finally { if (version == generation) { loading = false; refreshing = false } }
        }
    }
}

@Composable fun rememberComments(api: BiliApi, aid: Long, root: Long? = null, enabled: Boolean = true, initial: CommentsSnapshot = CommentsSnapshot()): CommentsState {
    val scope = rememberCoroutineScope()
    val state = remember(api, aid, root) { CommentsState(scope, initial) { api.comments(aid, it, root) } }
    LaunchedEffect(state, enabled) { if (enabled && aid > 0 && state.page == 0) state.load(true) else if (!enabled) state.pause() }
    return state
}

@Composable fun AutoLoadComments(list: LazyListState, state: CommentsState) {
    LaunchedEffect(list, state) {
        snapshotFlow { Triple(list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1, list.layoutInfo.totalItemsCount, state.loading) }
            .distinctUntilChanged().collect { (last, total, loading) ->
                if (!loading && state.page > 0 && total > 0 && last >= total - 4 && state.error == null) state.load()
            }
    }
}

fun LazyListScope.commentItems(state: CommentsState, onReplies: ((Comment) -> Unit)? = null) {
    item("comments_header") {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp).testTag("comments_page_${state.page}_count_${state.items.size}_refresh_${state.refreshCount}"), verticalAlignment = Alignment.CenterVertically) {
            Text("评论 · ${state.count}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { state.load(true) }, enabled = !state.loading) { SpinningRefreshIcon(state.loading, "刷新评论") }
        }
    }
    items(state.items, key = { "comment_${it.id}" }) { value -> Box(Modifier.animateItem()) { CommentRow(value, onReplies?.let { { it(value) } }) } }
    if (state.loading) item("comments_loading") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp)) }
    state.error?.let { error -> item("comments_error") { ErrorCard(error, { state.load(state.page == 0) }) } }
    if (!state.loading && state.error == null) item("comments_end") {
        Text(if (state.items.isEmpty()) "还没有评论" else if (!state.more) "已经到底了" else "继续向下查看评论", Modifier.fillMaxWidth().padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable fun CommentSheet(api: BiliApi, aid: Long, root: Comment, dismiss: () -> Unit) {
    val state = rememberComments(api, aid, root.id)
    val list = rememberLazyListState()
    AutoLoadComments(list, state)
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("回复 ${root.name}", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { state.load(true) }, modifier = Modifier.fillMaxWidth().fillMaxHeight(.8f)) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item("root") { CommentRow(root, showPreview = false) }
                commentItems(state)
            }
        }
    }
}
