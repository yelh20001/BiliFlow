@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.biliflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

data class AuthorProfile(val name: String, val face: String, val signature: String, val followers: Long)
data class AuthorVideos(val videos: List<Video>, val more: Boolean)

internal fun parseAuthorVideos(data: JSONObject, authorId: Long): AuthorVideos {
    val list = data.optJSONObject("list")?.optJSONArray("vlist")?.objects().orEmpty()
    val videos = list.map { item ->
        parseVideo(item).copy(duration = item.optString("length"), authorId = authorId)
    }.filter { it.id.isNotBlank() }
    val page = data.optJSONObject("page")
    return AuthorVideos(videos, page != null && page.optInt("pn") * page.optInt("ps") < page.optInt("count"))
}

private suspend fun BiliApi.authorProfile(id: Long): AuthorProfile {
    val data = get("/x/web-interface/card", mapOf("mid" to "$id"))
    val card = data.getJSONObject("card")
    return AuthorProfile(card.optString("name"), secureUrl(card.optString("face")), card.optString("sign"), data.optLong("follower"))
}

private suspend fun BiliApi.authorVideos(id: Long, page: Int): AuthorVideos {
    val query = signedWebParams(mapOf("mid" to "$id", "pn" to "$page", "ps" to "20", "order" to "pubdate", "order_avoided" to "true", "platform" to "web"))
    return parseAuthorVideos(request("https://api.bilibili.com/x/space/wbi/arc/search?$query").getJSONObject("data"), id)
}

@Composable fun AuthorScreen(id: Long, model: MainViewModel, back: () -> Unit, openVideo: (Video) -> Unit) {
    var profile by remember(id) { mutableStateOf<AuthorProfile?>(null) }
    var profileError by remember(id) { mutableStateOf<String?>(null) }
    var videos by remember(id) { mutableStateOf<List<Video>>(emptyList()) }
    var page by remember(id) { mutableIntStateOf(1) }
    var more by remember(id) { mutableStateOf(false) }
    var loading by remember(id) { mutableStateOf(true) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var retry by remember(id) { mutableIntStateOf(0) }
    LaunchedEffect(id, retry) {
        try { profile = model.api.authorProfile(id); profileError = null }
        catch (e: CancellationException) { throw e } catch (e: Exception) { profileError = networkMessage(e) }
    }
    LaunchedEffect(id, page, retry) {
        loading = true; error = null
        try {
            val result = model.api.authorVideos(id, page)
            videos = ((if (page == 1) emptyList() else videos) + result.videos).distinctBy { it.id }
            more = result.more
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = networkMessage(e) }
        finally { loading = false }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("UP 主主页") }, navigationIcon = { IconButton(onClick = back) { InterfaceIcon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("author_profile"), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(profile?.face, "UP 主头像", Modifier.size(64.dp).clip(CircleShape))
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(profile?.name?.ifBlank { "UP 主 $id" } ?: "UP 主 $id", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(profile?.let { "${count(it.followers)} 粉丝" } ?: (profileError ?: "资料加载中…"), style = MaterialTheme.typography.bodySmall)
                        profile?.signature?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                    }
                }
            }
            item { Text("投稿视频", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(videos, key = { it.id }) { video -> VideoCard(video, { openVideo(video) }, modifier = Modifier.animateItem()) }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp)) }
            error?.let { message -> item { ErrorCard(message, { retry++ }) } }
            if (!loading && error == null && videos.isEmpty()) item { Text("暂无投稿视频", Modifier.padding(20.dp)) }
            if (!loading && error == null && more) item { TextButton(onClick = { page++ }, Modifier.padding(horizontal = 20.dp)) { Text("加载更多") } }
        }
    }
}
