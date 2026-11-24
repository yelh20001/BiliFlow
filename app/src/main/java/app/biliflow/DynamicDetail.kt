@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.biliflow

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

sealed interface DynamicDestination {
    data class VideoPage(val video: Video) : DynamicDestination
    data class PostPage(val id: String) : DynamicDestination
}
fun dynamicDestination(post: DynamicPost): DynamicDestination = post.original?.let { DynamicDestination.PostPage(it.id) }
    ?: post.video?.let { DynamicDestination.VideoPage(it) } ?: DynamicDestination.PostPage(post.id)

data class DynamicBlock(val text: String = "", val picture: String = "", val ratio: Float = 1f, val code: Boolean = false)
data class DynamicDocument(val post: DynamicPost, val blocks: List<DynamicBlock>, val fullError: String? = null)
fun dynamicPreview(post: DynamicPost) = DynamicDocument(post, buildList {
    if (post.text.isNotBlank()) add(DynamicBlock(text = post.text))
    post.pictures.forEach { add(DynamicBlock(picture = it)) }
})
private fun opusWords(nodes: JSONArray?): String = nodes?.objects().orEmpty().joinToString("") { node ->
    node.optJSONObject("word")?.optString("words") ?: node.optJSONObject("rich")?.optString("text")
        ?: node.optJSONObject("formula")?.optString("latex_content") ?: node.optString("text")
}
fun parseOpusDocument(data: JSONObject, original: DynamicPost): DynamicDocument {
    val item = data.optJSONObject("item") ?: data
    val modules = item.optJSONArray("modules")?.objects().orEmpty()
    val author = modules.firstNotNullOfOrNull { it.optJSONObject("module_author") }
    val title = modules.firstNotNullOfOrNull { it.optJSONObject("module_title")?.optString("text") } ?: original.articleTitle
    val post = original.copy(author = author?.optString("name")?.ifBlank { original.author } ?: original.author,
        avatar = author?.optString("face")?.takeIf { it.isNotBlank() }?.let(::secureUrl) ?: original.avatar, articleTitle = title)
    val blocks = buildList {
        modules.forEach { module ->
            module.optJSONObject("module_content")?.optJSONArray("paragraphs")?.objects().orEmpty().forEach { paragraph ->
                val text = opusWords(paragraph.optJSONObject("text")?.optJSONArray("nodes"))
                if (text.isNotEmpty()) add(DynamicBlock(text = text))
                paragraph.optJSONObject("list")?.optJSONArray("items")?.objects().orEmpty().forEach { entry ->
                    add(DynamicBlock(text = (if (entry.optInt("order") > 0) "${entry.optInt("order")}. " else "• ") + opusWords(entry.optJSONArray("nodes"))))
                }
                paragraph.optJSONObject("code")?.optString("content")?.takeIf { it.isNotEmpty() }?.let { add(DynamicBlock(text = it.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&"), code = true)) }
                paragraph.optJSONObject("pic")?.optJSONArray("pics")?.objects().orEmpty().forEach { pic ->
                    val url = secureUrl(pic.optString("url")); val w = pic.optDouble("width", 1.0); val h = pic.optDouble("height", 1.0)
                    if (url.isNotBlank()) add(DynamicBlock(picture = url, ratio = if (h > 0 && w > 0) (w / h).toFloat() else 1f))
                }
                paragraph.optJSONObject("line")?.optJSONObject("pic")?.optString("url")?.takeIf { it.isNotBlank() }?.let { add(DynamicBlock(picture = secureUrl(it))) }
                paragraph.optJSONObject("link_card")?.optJSONObject("card")?.let { card ->
                    card.keys().asSequence().mapNotNull { card.optJSONObject(it) }.forEach { link ->
                        link.optString("title").ifBlank { link.optString("text") }.takeIf { it.isNotBlank() }?.let { add(DynamicBlock(text = it)) }
                    }
                }
            }
        }
    }
    require(blocks.isNotEmpty()) { "完整图文暂时无法载入，请重试" }
    return DynamicDocument(post, blocks)
}
suspend fun BiliApi.dynamicDocument(id: String): DynamicDocument {
    require(id.isNotBlank() && id.all(Char::isDigit)) { "原动态不存在或已删除" }
    val data = get("/x/polymer/web-dynamic/v1/detail", mapOf("id" to id, "platform" to "web", "web_location" to "333.1368", "features" to "itemOpusStyle,opusBigCover,onlyfansVote,decorationCard,ugcDelete,commentsNewVersion"))
    val post = parseDynamic(data.getJSONObject("item"))
    if (post.opusId.isBlank()) return dynamicPreview(post)
    return try {
        ensureBrowserIdentity()
        parseOpusDocument(get("/x/polymer/web-dynamic/v1/opus/detail", mapOf("id" to post.opusId, "features" to "htmlNewStyle,onlyfansVote,onlyfansAssetsV2,decorationCard,ugcDelete")), post)
    } catch (e: CancellationException) { throw e } catch (e: Exception) { dynamicPreview(post).copy(fullError = networkMessage(e)) }
}

@Composable fun NativeDynamicDetail(model: MainViewModel, id: String, onBack: () -> Unit, onVideo: (Video) -> Unit, onOriginal: (String) -> Unit, onWeb: (String) -> Unit) {
    val seed = remember(id) { model.dynamicPosts.asSequence().flatMap { sequenceOf(it, it.original).filterNotNull() }.firstOrNull { it.id == id } }
    var document by remember(id) { mutableStateOf(seed?.let(::dynamicPreview)) }
    var loading by remember(id) { mutableStateOf(true) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var image by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(id, refresh) {
        loading = true; error = null
        try { document = model.api.androidDynamicDocument(id) }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = networkMessage(e) }
        finally { loading = false }
    }
    Scaffold(modifier = Modifier.fillMaxSize().testTag("native_dynamic_detail"), topBar = {
        TopAppBar(title = { Text("动态详情") }, navigationIcon = { IconButton(onClick = onBack) { InterfaceIcon(Icons.AutoMirrored.Rounded.ArrowBack, "返回动态") } }, actions = {
            IconButton(onClick = { refresh++ }, enabled = !loading) { SpinningRefreshIcon(loading, "刷新动态详情") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("native_dynamic_id_$id"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { message -> item { ErrorCard(message, { refresh++ }) } }
            document?.let { doc ->
                val post = doc.post
                item {
                    Column(Modifier.testTag(if (!loading && error == null && doc.fullError == null) "dynamic_document_complete" else "dynamic_document_pending")) {
                    Row(Modifier.fillMaxWidth().testTag("dynamic_body_blocks_count_${doc.blocks.size}"), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(post.avatar, "动态作者头像", Modifier.size(44.dp).clip(CircleShape))
                        Column(Modifier.padding(start = 12.dp)) { Text(post.author, fontWeight = FontWeight.Bold); Text(if (post.publishedAt > 0) displayDate(post.publishedAt) else post.time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    }
                }
                if (post.articleTitle.isNotBlank()) item { SelectionContainer { Text(post.articleTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) } }
                itemsIndexed(doc.blocks, key = { index, _ -> "block-$index" }) { _, block ->
                    if (block.picture.isNotBlank()) AsyncImage(block.picture, "完整动态配图", Modifier.fillMaxWidth().aspectRatio(block.ratio.coerceIn(.05f, 5f)).clip(RoundedCornerShape(12.dp)).clickable { image = block.picture }, contentScale = ContentScale.Fit)
                    else SelectionContainer { Text(block.text, Modifier.fillMaxWidth().then(if (block.code) Modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp)).padding(16.dp) else Modifier), style = MaterialTheme.typography.bodyLarge) }
                }
                doc.fullError?.let { message -> item { ErrorCard("完整内容加载失败：$message", { refresh++ }) } }
                post.video?.let { video -> item { VideoCard(video, { onVideo(video) }) } }
                post.original?.let { original -> item {
                    OutlinedButton(onClick = { onOriginal(original.id) }, modifier = Modifier.fillMaxWidth()) { Text("查看被转发的原动态") }
                    Text(original.text, Modifier.padding(top = 8.dp), maxLines = 6)
                } }
                items(post.attachments) { attachment ->
                    val url = attachment.url.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.then(if (url != null) Modifier.clickable { onWeb(url) } else Modifier)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text(attachment.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            if (attachment.title.isNotBlank()) Text(attachment.title, fontWeight = FontWeight.SemiBold)
                            if (attachment.description.isNotBlank()) Text(attachment.description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item { Text("${count(post.forwards)} 转发   ${count(post.comments)} 评论   ${count(post.likes)} 赞", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    image?.takeUnless { model.playback.pipPresentation }?.let { source -> Dialog(onDismissRequest = { image = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val scope = rememberCoroutineScope()
        val enter = remember(source) { Animatable(0f) }
        LaunchedEffect(source) { enter.animateTo(1f, tween(210, easing = FastOutSlowInEasing)) }
        var zoom by remember(source) { mutableFloatStateOf(1f) }; var pan by remember(source) { mutableStateOf(Offset.Zero) }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = enter.value))
            .pointerInput(source) {
                detectTapGestures(onDoubleTap = {
                    val startZoom = zoom
                    val targetZoom = if (zoom > 1.2f) 1f else 2.5f
                    val startPan = pan
                    scope.launch {
                        animate(0f, 1f, animationSpec = spring(dampingRatio = 0.85f, stiffness = 420f)) { t, _ ->
                            zoom = startZoom + (targetZoom - startZoom) * t
                            if (targetZoom == 1f) pan = startPan * (1f - t)
                        }
                    }
                })
            }
            .pointerInput(source) { detectTransformGestures { _, delta, scale, _ -> zoom = (zoom * scale).coerceIn(1f, 8f); pan = if (zoom > 1) pan + delta else Offset.Zero } }) {
            AsyncImage(source, "原图", Modifier.fillMaxSize().graphicsLayer {
                alpha = enter.value
                val s = (0.92f + 0.08f * enter.value) * zoom
                scaleX = s; scaleY = s; translationX = pan.x; translationY = pan.y
            }, contentScale = ContentScale.Fit)
            IconButton(onClick = { image = null }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().graphicsLayer { alpha = enter.value }) { InterfaceIcon(Icons.Rounded.Close, "关闭原图", tint = Color.White) }
        }
    } }
}
