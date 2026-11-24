@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.biliflow

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable fun CoinDialog(engine: PlaybackController, aid: Long, api: BiliApi, dismiss: () -> Unit) {
    var amount by remember { mutableIntStateOf(1) }
    var alsoLike by remember { mutableStateOf(true) }
    val remaining = ((engine.detail?.coinLimit ?: 2) - engine.relation.coins).coerceAtLeast(0)
    AlertDialog(onDismissRequest = dismiss, title = { Text("给喜欢的内容投币") }, text = {
        Column {
            Text(if (remaining == 0) "此视频已达到投币上限" else "本次将使用账号中的硬币")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                (1..remaining).forEach { value -> FilterChip(selected = amount == value, onClick = { amount = value }, label = { Text("$value 枚") }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(alsoLike, { alsoLike = it }); Text("同时点赞") }
        }
    }, confirmButton = {
        TextButton(onClick = { engine.action({ it.copy(coins = it.coins + amount, liked = it.liked || alsoLike) }) { api.coin(aid, amount, alsoLike); dismiss() } }, enabled = remaining > 0 && !engine.actionBusy) { InterfaceLabel("确认投 $amount 枚") }
    }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable fun FavoriteSheet(api: BiliApi, accountId: Long, aid: Long, engine: PlaybackController, dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var folders by remember { mutableStateOf<List<FavoriteFolder>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var before by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var create by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var generation by remember { mutableIntStateOf(0) }
    LaunchedEffect(aid, generation) {
        loading = true; error = null
        try { folders = api.favoriteFolders(accountId, aid); before = folders.filter { it.selected }.map { it.id }.toSet(); selected = before }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = networkMessage(e) }
        finally { loading = false }
    }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .85f)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("收藏到账号收藏夹", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { create = true }) { InterfaceIcon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Text("新建") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp))
        error?.let { ErrorCard(it, { generation++ }) }
        LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 380.dp)) {
            items(folders, key = { it.id }) { folder ->
                ListItem(headlineContent = { Text(folder.title) }, supportingContent = { Text("${folder.count} 个视频") },
                    trailingContent = { Checkbox(folder.id in selected, null) }, modifier = Modifier.clickable {
                        selected = if (folder.id in selected) selected - folder.id else selected + folder.id
                    })
            }
            if (!loading && folders.isEmpty() && error == null) item { Text("还没有收藏夹，点击右上角新建", Modifier.padding(24.dp)) }
        }
        Button(onClick = {
            if (before == selected) dismiss() else engine.action({ it.copy(favorite = selected.isNotEmpty()) }) { api.favorite(aid, before, selected); dismiss() }
        }, enabled = !loading && error == null && !engine.actionBusy, modifier = Modifier.fillMaxWidth().padding(20.dp)) { InterfaceLabel("保存到所选收藏夹") }
        }
    }
    if (create) AlertDialog(onDismissRequest = { create = false }, title = { Text("新建收藏夹") }, text = {
        OutlinedTextField(name, { name = it.take(20) }, singleLine = true, label = { Text("收藏夹名称") })
    }, confirmButton = { TextButton(onClick = {
        scope.launch {
            try { loading = true; api.createFolder(name); create = false; name = ""; generation++ }
            catch (e: CancellationException) { throw e } catch (e: Exception) { error = networkMessage(e); loading = false }
        }
    }, enabled = name.isNotBlank() && !loading) { Text("创建") } }, dismissButton = { TextButton(onClick = { create = false }) { Text("取消") } })
}

@Composable fun CommentMessage(comment: Comment) {
    val keys = comment.emotes.keys.sortedByDescending { it.length }
    if (keys.isEmpty()) { Text(comment.text, style = MaterialTheme.typography.bodyMedium); return }
    val pattern = remember(keys) { Regex(keys.joinToString("|") { Regex.escape(it) }) }
    val value = remember(comment.text, keys) { buildAnnotatedString {
        var cursor = 0
        pattern.findAll(comment.text).forEach { match -> append(comment.text.substring(cursor, match.range.first)); appendInlineContent(match.value, match.value); cursor = match.range.last + 1 }
        append(comment.text.substring(cursor))
    } }
    val inline = comment.emotes.mapValues { (_, url) -> InlineTextContent(Placeholder(22.sp, 22.sp, PlaceholderVerticalAlign.Center)) { AsyncImage(url, it, Modifier.fillMaxSize()) } }
    Text(value, inlineContent = inline, style = MaterialTheme.typography.bodyMedium)
}

@Composable fun CommentRow(comment: Comment, onReplies: (() -> Unit)? = null, showPreview: Boolean = true) {
    var viewing by remember(comment.id) { mutableIntStateOf(-1) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
        AsyncImage(comment.avatar, "评论者头像", Modifier.size(34.dp).clip(CircleShape))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(comment.name, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp)); CommentMessage(comment)
            if (comment.pictures.isNotEmpty()) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    comment.pictures.chunked(3).forEachIndexed { row, pictures ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            pictures.forEachIndexed { column, picture ->
                                val index = row * 3 + column
                                var loaded by remember(picture.url) { mutableStateOf(false) }
                                AsyncImage(picture.url, "评论图片 ${index + 1}", Modifier.size(82.dp).clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant).clickable { viewing = index }.testTag(if (loaded) "comment_picture_loaded_$index" else "comment_picture_$index"),
                                    contentScale = ContentScale.Crop, onSuccess = { loaded = true }, onError = { loaded = false })
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp)); Text("${displayDate(comment.time)}  ·  ${count(comment.likes)} 赞", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (showPreview) comment.preview.take(2).forEach { preview -> Text("${preview.name}：${preview.text}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp), maxLines = 2) }
            if (comment.replies > 0 && onReplies != null) TextButton(onClick = onReplies, contentPadding = PaddingValues(0.dp)) { Text("查看 ${comment.replies} 条回复") }
        }
    }
    if (viewing >= 0 && viewing < comment.pictures.size) CommentPictureViewer(comment.pictures, viewing) { viewing = -1 }
}

@Composable private fun CommentPictureViewer(pictures: List<CommentPicture>, initial: Int, close: () -> Unit) {
    val pager = rememberPagerState(initialPage = initial, pageCount = { pictures.size })
    val scope = rememberCoroutineScope()
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(210, easing = FastOutSlowInEasing)) }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = enter.value)).testTag("comment_picture_viewer")) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().graphicsLayer {
                alpha = enter.value
                val s = 0.92f + 0.08f * enter.value
                scaleX = s; scaleY = s
            }) { page ->
                var zoom by remember(page) { mutableFloatStateOf(1f) }
                var pan by remember(page) { mutableStateOf(Offset.Zero) }
                LaunchedEffect(pager.currentPage) { if (pager.currentPage != page) { zoom = 1f; pan = Offset.Zero } }
                val transform = rememberTransformableState { scale, delta, _ ->
                    zoom = (zoom * scale).coerceIn(1f, 8f)
                    pan = if (zoom > 1f) pan + delta else Offset.Zero
                }
                AsyncImage(pictures[page].url, "评论原图 ${page + 1}", Modifier.fillMaxSize()
                    .pointerInput(page) {
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
                    .transformable(state = transform, canPan = { zoom > 1f })
                    .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y }, contentScale = ContentScale.Fit)
            }
            AnimatedVisibility(visible = pager.currentPage > 0, enter = fadeIn(tween(150)), exit = fadeOut(tween(150)), modifier = Modifier.align(Alignment.CenterStart)) {
                IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) {
                    InterfaceIcon(Icons.AutoMirrored.Rounded.ArrowBack, "上一张评论图片", tint = Color.White)
                }
            }
            AnimatedVisibility(visible = pager.currentPage < pictures.lastIndex, enter = fadeIn(tween(150)), exit = fadeOut(tween(150)), modifier = Modifier.align(Alignment.CenterEnd)) {
                IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) {
                    InterfaceIcon(Icons.AutoMirrored.Rounded.ArrowForward, "下一张评论图片", tint = Color.White)
                }
            }
            Text("${pager.currentPage + 1} / ${pictures.size}", color = Color.White, modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(16.dp).graphicsLayer { alpha = enter.value })
            IconButton(onClick = close, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().graphicsLayer { alpha = enter.value }) { InterfaceIcon(Icons.Rounded.Close, "关闭评论图片", tint = Color.White) }
        }
    }
}
