package app.biliflow

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

data class AccountFolder(val id: Long, val title: String, val count: Int)
data class AccountVideosPage(val videos: List<Video>, val more: Boolean)

suspend fun BiliApi.accountFolders(id: Long): List<AccountFolder> =
    get("/x/v3/fav/folder/created/list-all", mapOf("up_mid" to "$id"))
        .optJSONArray("list")?.objects()?.map { AccountFolder(it.optLong("id"), it.optString("title"), it.optInt("media_count")) }.orEmpty()

suspend fun BiliApi.accountVideos(kind: String, id: Long, page: Int, folder: Long? = null): AccountVideosPage {
    if (folder != null) {
        val data = get("/x/v3/fav/resource/list", mapOf("media_id" to "$folder", "pn" to "$page", "ps" to "20", "order" to "mtime"))
        val videos = data.optJSONArray("medias")?.objects()?.mapNotNull { item ->
            val bvid = item.optString("bvid").ifBlank { item.optLong("id").takeIf { it > 0 }?.let { runCatching { AppProtocol.bvid(it) }.getOrNull() }.orEmpty() }
            if (bvid.isBlank() || item.optInt("type", 2) != 2) null else Video(bvid, item.optString("title"), secureUrl(item.optString("cover")), item.optJSONObject("upper")?.optString("name").orEmpty(), item.optJSONObject("cnt_info")?.optLong("play") ?: 0, duration(item.optInt("duration")), authorId = item.optJSONObject("upper")?.optLong("mid") ?: 0)
        }.orEmpty()
        return AccountVideosPage(videos, data.optBoolean("has_more"))
    }
    if (kind == "已投币的视频") {
        val data = appGet("/x/v2/space/coinarc", mapOf("vmid" to "$id", "pn" to "$page", "ps" to "20"))
        val items = data.optJSONArray("item")?.objects().orEmpty()
        return AccountVideosPage(items.mapNotNull(AppProtocol::video), page * 20 < data.optInt("count"))
    }
    val data = get("/x/space/like/video", mapOf("vmid" to "$id", "pn" to "$page", "ps" to "20"))
    val items = data.optJSONArray("list")?.objects().orEmpty()
    return AccountVideosPage(items.map(::parseVideo).filter { it.id.isNotBlank() }, data.optBoolean("has_more", items.size >= 20))
}

@Composable fun AccountLibraryScreen(title: String, model: MainViewModel, open: (Video) -> Unit) {
    val account = model.account
    var folder by remember(title, account?.id) { mutableStateOf<AccountFolder?>(null) }
    var folders by remember(title, account?.id) { mutableStateOf<List<AccountFolder>>(emptyList()) }
    var videos by remember(title, account?.id, folder?.id) { mutableStateOf<List<Video>>(emptyList()) }
    var page by remember(title, account?.id, folder?.id) { mutableIntStateOf(1) }
    var more by remember(title, account?.id, folder?.id) { mutableStateOf(false) }
    var loading by remember(title, account?.id, folder?.id) { mutableStateOf(true) }
    var error by remember(title, account?.id, folder?.id) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    BackHandler(enabled = folder != null) { folder = null }
    LaunchedEffect(title, account?.id, folder?.id, page, retry) {
        if (account == null) { loading = false; return@LaunchedEffect }
        loading = true; error = null
        try {
            if (title == "账号收藏夹" && folder == null) folders = model.api.accountFolders(account.id)
            else {
                val result = model.api.accountVideos(title, account.id, page, folder?.id)
                videos = ((if (page == 1) emptyList() else videos) + result.videos).distinctBy { it.id }
                more = result.more
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = networkMessage(e) }
        finally { loading = false }
    }
    val count = if (title == "账号收藏夹" && folder == null) folders.size else videos.size
    AnimatedContent(
        targetState = folder,
        transitionSpec = {
            val forward = targetState != null
            val dir = if (forward) 1 else -1
            (slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { dir * it / 4 } + fadeIn(tween(200))) togetherWith
                (slideOutHorizontally(tween(200, easing = FastOutSlowInEasing)) { -dir * it / 5 } + fadeOut(tween(150)))
        },
        label = "account folder transition"
    ) { currentFolder ->
        LazyColumn(modifier = Modifier.testTag(if (loading) "account_library_loading" else if (error != null) "account_library_error" else "account_library_loaded_$count"), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            if (account == null) item { Text("登录后查看账号内容", Modifier.padding(24.dp)) }
            if (currentFolder != null) item { TextButton(onClick = { folder = null }) { Text("返回收藏夹 · ${currentFolder.title}") } }
            if (title == "账号收藏夹" && currentFolder == null) items(folders, key = { it.id }) { value ->
                ListItem(headlineContent = { Text(value.title) }, supportingContent = { Text("${value.count} 个视频") }, modifier = Modifier.fillMaxWidth().animateItem(), trailingContent = { TextButton(onClick = { folder = value }) { Text("打开") } })
            } else items(videos, key = { it.id }) { video -> VideoCard(video, { open(video) }, modifier = Modifier.animateItem()) }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp)) }
            error?.let { message -> item { ErrorCard(message, { retry++ }) } }
            if (!loading && error == null && account != null && title == "账号收藏夹" && currentFolder == null && folders.isEmpty()) item { Text("暂无收藏夹", Modifier.padding(24.dp)) }
            if (!loading && error == null && account != null && (title != "账号收藏夹" || currentFolder != null) && videos.isEmpty()) item { Text("暂无视频", Modifier.padding(24.dp)) }
            if (!loading && error == null && more) item { TextButton(onClick = { page++ }, modifier = Modifier.padding(horizontal = 20.dp)) { Text("加载更多") } }
        }
    }
}
