@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.biliflow

import android.content.Intent
import android.graphics.Bitmap
import android.view.TextureView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.testTag as semanticsTestTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

fun displayDate(timestamp: Long): String = if (timestamp <= 0) "" else DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(timestamp))
fun playbackTime(ms: Long): String = duration((ms.coerceAtLeast(0) / 1000).toInt())

@Composable fun VideoScreen(video: Video, model: MainViewModel, onBack: () -> Unit, onLogin: () -> Unit = {},
    onAuthor: (Long) -> Unit = {}, onRelated: (Video) -> Unit = {}, onHome: () -> Unit = onBack, active: Boolean = true, routeKey: String = video.id) {
    val engine = model.playback
    val context = LocalContext.current
    val activity = LocalActivity.current as? PlaybackActivity
    var qualitySheet by remember { mutableStateOf(false) }
    var coinSheet by remember { mutableStateOf(false) }
    var folderSheet by remember { mutableStateOf(false) }
    var expandedDescription by rememberSaveable(video.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val initial = remember(routeKey) { model.videoScenes.get(routeKey) }
    var previous by remember(routeKey) { mutableStateOf(initial) }
    val ready = active && engine.video?.id == video.id
    val info = if (ready) engine.detail ?: previous.detail else previous.detail
    val relation = if (ready && !engine.relationLoading) engine.relation else previous.interaction
    val fans = if (ready) engine.fans ?: previous.fans else previous.fans
    LaunchedEffect(video.id, active) { if (active) engine.open(video) }
    LaunchedEffect(model.account?.id, active) { if (active && model.account != null) engine.refreshRelation() }
    BackHandler(active && engine.fullScreen && !engine.inPip) { engine.updateFullScreen(false) }
    LaunchedEffect(active, if (active) engine.fullScreen else false, if (active) engine.inPip else false, model.pipEnabled) { if (active) activity?.syncPlayerWindow() }
    val snack = remember { SnackbarHostState() }
    val noticeHaze = remember { HazeState() }
    val notice = if (active) engine.notice else null
    LaunchedEffect(notice) { notice?.let { snack.showSnackbar(it); engine.notice = null } }
    val pages = rememberPagerState(pageCount = { 2 })
    val browsing = active && !engine.minimized && !engine.pipPresentation && !engine.miniRestoring
    val comments = rememberComments(model.api, info?.aid ?: 0, enabled = browsing && pages.currentPage == 1, initial = initial.comments)
    val related = rememberRelatedVideos(model.api, video.id, enabled = browsing && pages.currentPage == 0, initial = initial.related, initialVisible = initial.relatedVisible)
    val detailList = rememberLazyListState()
    val commentList = rememberLazyListState()
    var replyTo by remember { mutableStateOf<Comment?>(null) }
    if (browsing && pages.currentPage == 1) AutoLoadComments(commentList, comments)
    if (browsing && pages.currentPage == 0) AutoRevealRelated(detailList, related)
    val currentScene = if (ready) VideoScene(info, fans, relation, comments.snapshot(), related.videos, related.visible) else null
    SideEffect { currentScene?.let { scene -> if (scene != previous) { previous = scene; model.videoScenes.put(routeKey, scene) } } }
    val fullscreen = active && (engine.fullScreen || engine.inPip)
    Box(Modifier.fillMaxSize().background(if (engine.minimized && !engine.inPip) Color.Transparent else if (fullscreen || engine.inPip) Color.Black else MaterialTheme.colorScheme.background.copy(alpha = 1f - engine.miniDragProgress))) {
        Box(Modifier.fillMaxSize().hazeSource(noticeHaze)) {
        PlaybackFrame(engine, { if (engine.minimized) onHome() else onBack() }, { qualitySheet = true }, active = active, poster = video, toolbar = {
            TopAppBar(windowInsets = WindowInsets(0, 0, 0, 0), title = { Text("正在观看", style = MaterialTheme.typography.titleMedium) }, navigationIcon = {
                IconButton(onClick = onBack) { InterfaceIcon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
            }, actions = {
                IconButton(onClick = onHome) { InterfaceIcon(Icons.Rounded.Home, "返回浏览页面") }
                val addWatchLater = LocalWatchLaterAction.current
                IconButton(onClick = { addWatchLater?.invoke(info?.video ?: video) }, enabled = active && !model.watchLater.busy) { InterfaceIcon(Icons.Rounded.WatchLater, "稍后再看") }
                val saved = model.favorites.any { it.id == video.id }
                val bookmarkScale = remember { Animatable(1f) }
                var previousSaved by remember(video.id) { mutableStateOf(saved) }
                LaunchedEffect(saved) {
                    if (saved && !previousSaved) {
                        bookmarkScale.snapTo(0.82f)
                        bookmarkScale.animateTo(1.24f, spring(dampingRatio = 0.45f, stiffness = 620f))
                        bookmarkScale.animateTo(1f, spring(dampingRatio = 0.65f, stiffness = 460f))
                    }
                    previousSaved = saved
                }
                val bookmarkTint by animateColorAsState(if (saved) MaterialTheme.colorScheme.primary else LocalContentColor.current, tween(180), label = "bookmark tint")
                IconButton(onClick = { model.toggleFavorite(info?.video ?: video) }) {
                    AnimatedContent(
                        targetState = saved,
                        transitionSpec = { (scaleIn(tween(150), 0.75f) + fadeIn(tween(130))) togetherWith (scaleOut(tween(120), 0.75f) + fadeOut(tween(100))) },
                        label = "bookmark icon"
                    ) { isSaved ->
                        InterfaceIcon(if (isSaved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, "本地收藏", Modifier.graphicsLayer { scaleX = bookmarkScale.value; scaleY = bookmarkScale.value }, tint = bookmarkTint)
                    }
                }
                IconButton(onClick = {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${video.title}\nhttps://www.bilibili.com/video/${video.id}") }, "分享视频"))
                }) { InterfaceIcon(Icons.Rounded.Share, "分享") }
            })
        }) {
            Column(Modifier.fillMaxSize()) {
                PrimaryTabRow(selectedTabIndex = pages.currentPage) {
                    Tab(selected = pages.currentPage == 0, onClick = { scope.launch { pages.animateScrollToPage(0) } }, text = { InterfaceLabel("简介与推荐", hdr = pages.currentPage == 0 && LocalInterfaceHdr.current) })
                    Tab(selected = pages.currentPage == 1, onClick = { scope.launch { pages.animateScrollToPage(1) } }, text = { InterfaceLabel(if (comments.count > 0) "评论 · ${comments.count}" else "评论", hdr = pages.currentPage == 1 && LocalInterfaceHdr.current) })
                }
                HorizontalPager(state = pages, modifier = Modifier.weight(1f).testTag("video_details_page_${pages.currentPage}"), userScrollEnabled = active) { page ->
                if (page == 0) PullToRefreshBox(isRefreshing = related.loading && related.videos.isNotEmpty(), onRefresh = related::load, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), state = detailList, contentPadding = PaddingValues(bottom = 24.dp)) {
                if (active && engine.queue.current != null) item {
                    val queue = engine.queue
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp).testTag("playback_queue")) {
                        Text("稍后再看 · ${queue.index + 1}/${queue.items.size}", style = MaterialTheme.typography.titleSmall)
                        Row {
                            TextButton(onClick = { engine.moveQueue(-1) }, enabled = queue.index > 0) { Text("上一个") }
                            TextButton(onClick = { engine.moveQueue(1) }, enabled = queue.index + 1 < queue.items.size) { Text("下一个") }
                            TextButton(onClick = engine::clearQueue) { Text("退出连播") }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(queue.items, key = { it.id }) { item -> FilterChip(selected = queue.current?.id == item.id, onClick = { engine.selectQueue(item.id) }, label = { Text(item.title, maxLines = 1, modifier = Modifier.widthIn(max = 180.dp), overflow = TextOverflow.Ellipsis) }) }
                        }
                    }
                }
                (if (active) engine.error else null)?.let { message -> item { ErrorCard(message, engine::retry) { openExternal(context, "https://www.bilibili.com/video/${video.id}") } } }
                item {
                    Column(Modifier.padding(20.dp)) {
                        Text(info?.video?.title ?: video.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        Text("${count(video.views)} 次观看${info?.publishedAt?.takeIf { it > 0 }?.let { "  ·  ${displayDate(it)}" }.orEmpty()}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(20.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(info?.authorFace, "作者头像", Modifier.size(48.dp).clip(CircleShape).clickable(enabled = (info?.authorId ?: 0) > 0) { info?.authorId?.let(onAuthor) })
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(info?.video?.author ?: video.author, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(fans?.let { "${count(it)} 粉丝" } ?: "粉丝数加载中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val followContainer by animateColorAsState(if (relation.following) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, tween(200), label = "follow container")
                            OutlinedButton(onClick = {
                                if (model.account == null) onLogin() else info?.let { data ->
                                    val follow = !relation.following
                                    engine.action({ it.copy(following = follow) }) { model.api.follow(data.authorId, follow) }
                                }
                            }, enabled = info != null && active && ready && !engine.actionBusy && !engine.relationLoading && info.authorId != model.account?.id,
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = followContainer),
                                modifier = Modifier.animateContentSize(spring(dampingRatio = 0.8f, stiffness = 420f))) {
                                AnimatedContent(
                                    targetState = relation.following,
                                    transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                                    label = "follow label"
                                ) { following ->
                                    InterfaceLabel(if (following) "已关注" else "关注")
                                }
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            InteractionButton("like", if (relation.liked) Icons.Rounded.ThumbUp else Icons.Outlined.ThumbUp, if (relation.liked) "已点赞" else "点赞", relation.liked, active && ready && !engine.actionBusy && !engine.relationLoading) {
                                if (model.account == null) onLogin() else {
                                    val liked = !relation.liked
                                    engine.action({ it.copy(liked = liked) }) { model.api.like(video.id, liked) }
                                }
                            }
                            InteractionButton("coin", if (relation.coins > 0) Icons.Rounded.Paid else Icons.Outlined.Paid, if (relation.coins > 0) "已投 ${relation.coins} 枚" else "投币", relation.coins > 0, active && ready && !engine.actionBusy && !engine.relationLoading) {
                                if (model.account == null) onLogin() else coinSheet = true
                            }
                            InteractionButton("favorite", if (relation.favorite) Icons.Rounded.Star else Icons.Outlined.StarBorder, if (relation.favorite) "已收藏" else "账号收藏", relation.favorite, active && ready && !engine.actionBusy && !engine.relationLoading) {
                                if (model.account == null) onLogin() else folderSheet = true
                            }
                        }
                    }
                }
                val parts = info?.parts.orEmpty()
                if (parts.size > 1) item {
                    Text("选集 · ${parts.size}", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleMedium)
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(parts) { index, part -> FilterChip(selected = engine.part == index, onClick = { engine.selectPart(index) }, label = { Text("P${index + 1} ${part.title.take(22)}") }) }
                    }
                }
                item {
                    Column(Modifier.padding(horizontal = 20.dp).animateContentSize(spring(dampingRatio = 0.85f, stiffness = 380f))) {
                        val description = (info?.video?.description ?: video.description).ifBlank { "UP 主还没有填写简介" }
                        Text(description, maxLines = if (expandedDescription) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (description.length > 80) TextButton(onClick = { expandedDescription = !expandedDescription }) { Text(if (expandedDescription) "收起简介" else "展开简介") }
                    }
                }
                relatedItems(related, onRelated)
            }
            }
                else PullToRefreshBox(isRefreshing = comments.refreshing, onRefresh = { comments.load(true) }, modifier = Modifier.fillMaxSize().testTag("comments_feed_page_${comments.page}_count_${comments.items.size}_refresh_${comments.refreshCount}")) {
                    LazyColumn(Modifier.fillMaxSize().semantics { semanticsTestTag = "video_comment_position_${commentList.firstVisibleItemIndex}_${commentList.firstVisibleItemScrollOffset}" }, state = commentList, contentPadding = PaddingValues(bottom = 24.dp)) { if (info != null) commentItems(comments) { replyTo = it } }
                }
                }
            }
        }
        }
        if (active && !engine.pipPresentation && !engine.minimized) PlaybackNoticeHost(snack, noticeHaze, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
    if (active && qualitySheet && !engine.pipPresentation) ModalBottomSheet(onDismissRequest = { qualitySheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("清晰度", Modifier.padding(horizontal = 24.dp, vertical = 10.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("首选：${qualityLabel(model.preferredQuality)} · 仅展示账号可获取的画质", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.heightIn(max = 420.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
            items(engine.qualities, key = { it.id }) { quality ->
                ListItem(headlineContent = { Text(quality.label) }, supportingContent = { if (!quality.available) Text("当前账号不可用") },
                    trailingContent = { RadioButton(selected = engine.quality == quality.id, onClick = null, enabled = quality.available) },
                    modifier = Modifier.fillMaxWidth().clickable(enabled = quality.available) { engine.selectQuality(quality.id); qualitySheet = false }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            }
        }
    }
    if (active && !engine.pipPresentation && coinSheet && info != null) CoinDialog(engine, info.aid, model.api) { coinSheet = false }
    if (active && !engine.pipPresentation && folderSheet && info != null && model.account != null) FavoriteSheet(model.api, model.account!!.id, info.aid, engine) { folderSheet = false }
    if (active && !engine.pipPresentation) replyTo?.let { root -> if (info != null) CommentSheet(model.api, info.aid, root) { replyTo = null } }
}

@Composable private fun InteractionButton(kind: String, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, enabled: Boolean, action: () -> Unit) {
    val pop = remember(kind) { Animatable(1f) }
    var previous by remember(kind) { mutableStateOf(selected) }
    LaunchedEffect(selected) {
        if (selected && !previous) {
            pop.snapTo(0.8f)
            pop.animateTo(1.26f, spring(dampingRatio = 0.42f, stiffness = 620f))
            pop.animateTo(1f, spring(dampingRatio = 0.65f, stiffness = 450f))
        }
        previous = selected
    }
    val tint by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, tween(180), label = "interaction tint")
    TextButton(onClick = action, enabled = enabled, modifier = Modifier.testTag("interaction_${kind}_${if (selected) "selected" else "idle"}").semantics { this.selected = selected }) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedContent(
                targetState = icon,
                transitionSpec = { (scaleIn(tween(150), 0.78f) + fadeIn(tween(130))) togetherWith (scaleOut(tween(120), 0.78f) + fadeOut(tween(100))) },
                label = "interaction icon"
            ) { currentIcon ->
                InterfaceIcon(currentIcon, null, Modifier.size(26.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }, tint = tint)
            }
            Spacer(Modifier.height(6.dp)); InterfaceLabel(label, fontSize = 12.sp, hdr = enabled && LocalInterfaceHdr.current)
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun PlayerSurface(engine: PlaybackController, modifier: Modifier, onBack: () -> Unit, quality: () -> Unit, motion: PlayerMotion) {
    val context = LocalContext.current
    val activity = LocalActivity.current as? PlaybackActivity
    val compact = engine.minimized && !engine.inPip
    var controls by remember { mutableStateOf(true) }
    var gesture by remember { mutableStateOf<String?>(null) }
    var held by remember { mutableStateOf(false) }
    var slider by remember { mutableStateOf<Float?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var dragStart by remember { mutableLongStateOf(0) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var playerBounds by remember { mutableStateOf(Rect.Zero) }
    var nativePlayerView by remember { mutableStateOf<PlayerView?>(null) }
    var handoffFrame by remember { mutableStateOf<Bitmap?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(engine.miniDragProgress) {
        if (engine.miniDragProgress >= .99f && handoffFrame == null && !engine.pipPresentation) {
            handoffFrame = (nativePlayerView?.videoSurfaceView as? TextureView)?.bitmap
        }
        if (engine.miniDragProgress == 0f && !compact && !engine.minimized) handoffFrame = null
    }
    LaunchedEffect(compact) {
        if (compact && handoffFrame != null) {
            val texture = nativePlayerView?.videoSurfaceView as? TextureView
            val initialTimestamp = texture?.surfaceTexture?.timestamp ?: 0L
            // Keep the last frame only until the resized texture delivers a new one.
            // A paused player has no new frame, so just allow its layout to settle.
            withTimeoutOrNull(100) {
                do {
                    withFrameNanos { }
                } while (engine.playing && initialTimestamp != 0L && (texture?.surfaceTexture?.timestamp ?: 0L) <= initialTimestamp)
            }
            handoffFrame = null
        }
    }
    var clearGesture: kotlinx.coroutines.Job? by remember { mutableStateOf(null) }
    fun feedback(text: String) { gesture = text; clearGesture?.cancel(); clearGesture = scope.launch { delay(850); gesture = null } }
    LaunchedEffect(controls, engine.playing, dragging, engine.inPip) { if (controls && engine.playing && !dragging && !engine.inPip) { delay(3500); controls = false } }
    Box(modifier.onGloballyPositioned { playerBounds = it.boundsInRoot() }.testTag("video_player").semantics { contentDescription = "视频播放器" }.pointerInput(engine.inPip, compact) {
        if (engine.inPip) return@pointerInput
        detectTapGestures(onTap = { if (compact) engine.restoreFromMini() else controls = !controls }, onDoubleTap = { point ->
            if (compact) { engine.restoreFromMini(); return@detectTapGestures }
            when { !engine.isLive && point.x < size.width * .35f -> { engine.skip(-10_000); feedback("后退 10 秒") }
                !engine.isLive && point.x > size.width * .65f -> { engine.skip(10_000); feedback("前进 10 秒") }
                else -> { engine.toggle(); controls = true } }
        }, onLongPress = { if (!engine.isLive) { held = true; engine.player.setPlaybackSpeed(2f); clearGesture?.cancel(); gesture = "2× 倍速播放" } }, onPress = {
            try { tryAwaitRelease() } finally { if (held) { held = false; engine.player.setPlaybackSpeed(1f); gesture = null } }
        })
    }.pointerInput(engine.fullScreen, engine.inPip, compact, engine.horizontalSeek, engine.miniDraggingEnabled) {
        if (engine.inPip) return@pointerInput
        if (compact) {
            if (!engine.miniDraggingEnabled) return@pointerInput
            detectDragGestures { change, amount -> change.consume(); engine.moveMiniBy(amount.x, amount.y) }
            return@pointerInput
        }
        try { detectDragGestures(onDragStart = { dragging = true; controls = false; dragX = 0f; dragY = 0f; dragStart = engine.currentPosition(); motion.begin() },
            onDrag = { change, amount ->
                dragX += amount.x; dragY += amount.y
                if (abs(dragY) > abs(dragX) && abs(dragY) > 16f) {
                    change.consume()
                    if (dragY > 0 && !engine.fullScreen && !engine.isLive && engine.miniTarget.top > playerBounds.top) {
                        motion.drag(0f, size.height.toFloat())
                        engine.dragToMini(dragY / (engine.miniTarget.top - playerBounds.top))
                    } else { if (engine.miniDragProgress > 0f) engine.dragToMini(0f); motion.drag(dragY, size.height.toFloat()) }
                } else if (engine.horizontalSeek && !engine.isLive && abs(dragX) > abs(dragY) && abs(dragX) > 24f) {
                    change.consume()
                    slider = (dragStart + dragX / size.width * 120_000).coerceIn(0f, engine.duration.toFloat().coerceAtLeast(1f))
                    gesture = playbackTime(slider!!.toLong())
                }
            }, onDragEnd = {
                val target = if (slider == null && abs(dragY) > 80f && abs(dragY) > abs(dragX)) {
                    when { dragY < 0 && !engine.fullScreen -> true; dragY > 0 && engine.fullScreen -> false; else -> null }
                } else null
                if (slider != null) engine.seek(slider!!.toLong())
                val minimize = slider == null && dragY > 80f && abs(dragY) > abs(dragX) && !engine.fullScreen && !engine.isLive && target == null
                if (minimize || engine.miniDragProgress > 0f) { motion.releaseToMini(); engine.finishMiniDrag(minimize, scope) } else motion.release(engine, target)
                slider = null; dragging = false; gesture = null
            }, onDragCancel = { if (engine.miniDragProgress > 0f) engine.finishMiniDrag(false, scope) else motion.release(engine); slider = null; dragging = false; gesture = null })
        } finally { if (dragging) { if (engine.miniDragProgress > 0f) engine.finishMiniDrag(false, scope) else motion.release(engine); slider = null; dragging = false; gesture = null } }
    }) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            val progress = if (engine.pipPresentation || compact) 0f else engine.miniDragProgress
            val offset = if (engine.pipPresentation || progress > 0f) 0f else motion.offset
            translationY = offset
            val fraction = (abs(offset) / size.height.coerceAtLeast(1f)).coerceIn(0f, 1f)
            transformOrigin = TransformOrigin(0f, 0f)
            if (progress > 0f && playerBounds.width > 0f && playerBounds.height > 0f) {
                val videoWidth = minOf(playerBounds.width, playerBounds.height * engine.videoAspectRatio)
                val videoHeight = videoWidth / engine.videoAspectRatio
                val insetX = (playerBounds.width - videoWidth) / 2f
                val insetY = (playerBounds.height - videoHeight) / 2f
                val targetScale = engine.miniTarget.width / videoWidth
                scaleX = 1f + (targetScale - 1f) * progress; scaleY = scaleX
                translationX = (engine.miniTarget.left - playerBounds.left - insetX * targetScale) * progress
                translationY = (engine.miniTarget.top - playerBounds.top - insetY * targetScale) * progress
                shape = MiniPlayerShape(insetX * progress, insetY * progress, 16.dp.toPx() * progress / scaleX)
            } else {
                scaleX = 1f - fraction * .08f; scaleY = scaleX
                shape = RoundedCornerShape((fraction * 20f).dp)
            }
            clip = fraction > .001f || progress > .001f
        }.background(Color.Black).testTag(if (motion.dragging && (abs(motion.offset) > 1 || engine.miniDragProgress > 0f)) "player_motion_dragging" else "player_motion_rest")) {
        key(engine.hdrSurface || (engine.isLive && android.os.Build.VERSION.SDK_INT >= 34)) {
        AndroidView(factory = { ctx ->
            // SDR keeps the composed TextureView during mini transitions;
            // HDR requires a SurfaceView to preserve the display's HDR output path.
            val layout = if (engine.hdrSurface || (engine.isLive && android.os.Build.VERSION.SDK_INT >= 34)) R.layout.player_surface_view else R.layout.player_view
            (android.view.LayoutInflater.from(ctx).inflate(layout, android.widget.FrameLayout(ctx), false) as PlayerView).apply {
                nativePlayerView = this
                player = engine.player; useController = false; keepScreenOn = true
                setKeepContentOnPlayerReset(true)
                setEnableComposeSurfaceSyncWorkaround(true)
                addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> post { activity?.videoBounds(videoSurfaceView ?: this) } }
                videoSurfaceView?.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ -> activity?.videoBounds(view) }
            }
        }, modifier = Modifier.fillMaxSize(), update = { view -> view.keepScreenOn = engine.playing },
            onRelease = { it.player = null; it.keepScreenOn = false; nativePlayerView = null })
        }
        if (compact && !engine.pipPresentation) handoffFrame?.let { frame ->
            Image(frame.asImageBitmap(), null, Modifier.fillMaxSize().background(Color.Black), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
        }
        if (engine.danmakuEnabled && !engine.pipPresentation && !compact) {
            DanmakuLayer(engine, Modifier.clipToBounds().graphicsLayer { alpha = miniDanmakuAlpha(engine.miniDragProgress) })
        }
        AnimatedVisibility(visible = (engine.loading || engine.buffering) && !engine.pipPresentation, enter = fadeIn(tween(160)), exit = fadeOut(tween(160)), modifier = Modifier.align(Alignment.Center)) {
            CircularProgressIndicator(Modifier.size(30.dp), color = Color.White, strokeWidth = 2.dp)
        }
        var lastGesture by remember { mutableStateOf("") }
        if (gesture != null) lastGesture = gesture!!
        AnimatedVisibility(
            visible = gesture != null && !engine.pipPresentation,
            enter = fadeIn(tween(120)) + scaleIn(tween(150, easing = FastOutSlowInEasing), initialScale = 0.88f),
            exit = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.92f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(color = Color.Black.copy(alpha = .68f), shape = RoundedCornerShape(16.dp)) {
                Text(lastGesture, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
            }
        }
        AnimatedVisibility(visible = compact && !engine.pipPresentation, enter = fadeIn(tween(180)), exit = ExitTransition.None, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().testTag("mini_player_controls")) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .35f), Color.Transparent, Color.Black.copy(alpha = .25f)))))
                IconButton(onClick = engine::restoreFromMini, modifier = Modifier.align(Alignment.TopStart).size(36.dp)) { InterfaceIcon(Icons.Rounded.OpenInFull, "展开小窗", tint = Color.White, modifier = Modifier.size(20.dp)) }
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopEnd).size(36.dp)) { InterfaceIcon(Icons.Rounded.Close, "关闭小窗", tint = Color.White, modifier = Modifier.size(20.dp)) }
                IconButton(onClick = engine::toggle, modifier = Modifier.align(Alignment.Center).size(44.dp)) {
                    AnimatedContent(
                        targetState = engine.playing,
                        transitionSpec = { (scaleIn(tween(140), 0.78f) + fadeIn(tween(120))) togetherWith (scaleOut(tween(120), 0.78f) + fadeOut(tween(100))) },
                        label = "mini play pause"
                    ) { playing ->
                        InterfaceIcon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "暂停小窗" else "播放小窗", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                }
            }
        }
        AnimatedVisibility(visible = controls && !engine.pipPresentation && !compact, enter = fadeIn(tween(180)), exit = if (engine.pipPresentation || compact) ExitTransition.None else fadeOut(tween(180)), modifier = Modifier.fillMaxSize().testTag("player_controls")) {
            Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .6f), Color.Transparent, Color.Black.copy(alpha = .8f)))))
            if (engine.fullScreen) Row(Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility.only(WindowInsetsSides.Top)).windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { engine.updateFullScreen(false) }) { InterfaceIcon(Icons.AutoMirrored.Rounded.ArrowBack, "退出全屏", tint = Color.White) }
                Text(engine.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!engine.isLive) IconButton(onClick = { engine.skip(-10_000); feedback("后退 10 秒") }) { InterfaceIcon(Icons.Rounded.Replay10, "后退 10 秒", Modifier.size(32.dp), tint = Color.White) }
                IconButton(onClick = { engine.toggle(); controls = true }, modifier = Modifier.size(60.dp)) {
                    AnimatedContent(
                        targetState = engine.playing,
                        transitionSpec = { (scaleIn(tween(140), 0.78f) + fadeIn(tween(120))) togetherWith (scaleOut(tween(120), 0.78f) + fadeOut(tween(100))) },
                        label = "main play pause"
                    ) { playing ->
                        InterfaceIcon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "暂停" else "播放", Modifier.size(48.dp), tint = Color.White)
                    }
                }
                if (!engine.isLive) IconButton(onClick = { engine.skip(10_000); feedback("前进 10 秒") }) { InterfaceIcon(Icons.Rounded.Forward10, "前进 10 秒", Modifier.size(32.dp), tint = Color.White) }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)).padding(horizontal = 12.dp).padding(bottom = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (engine.isLive) "● 直播中" else "${playbackTime(slider?.toLong() ?: engine.position)} / ${playbackTime(engine.duration)}", color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = quality, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(if (engine.isLive) liveQualityLabel(engine.quality) else (if (engine.actualQuality == 126 && !engine.dolbyVisionSupported) "杜比兼容" else if (engine.quality == 0) "自动" else qualityLabel(engine.quality)) + (if (engine.hdrPlaying) " · HDR" else ""), color = Color.White, fontSize = 12.sp) }
                    IconButton(onClick = engine::toggleDanmaku, modifier = Modifier.size(38.dp)) { InterfaceIcon(if (engine.danmakuEnabled) Icons.Rounded.Subtitles else Icons.Rounded.SubtitlesOff, if (engine.danmakuEnabled) "关闭弹幕" else "开启弹幕", tint = Color.White, modifier = Modifier.size(20.dp)) }
                    IconButton(onClick = { activity?.enterPip() }, modifier = Modifier.size(38.dp)) { InterfaceIcon(Icons.Rounded.PictureInPictureAlt, "画中画", tint = Color.White, modifier = Modifier.size(20.dp)) }
                    IconButton(onClick = { engine.updateFullScreen(!engine.fullScreen) }, modifier = Modifier.size(38.dp)) { InterfaceIcon(if (engine.fullScreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, if (engine.fullScreen) "退出全屏" else "全屏", tint = Color.White) }
                }
                val thumbWidth by animateDpAsState(if (dragging) 6.dp else 4.dp, spring(stiffness = 500f), label = "seek thumb width")
                val thumbHeight by animateDpAsState(if (dragging) 24.dp else 20.dp, spring(stiffness = 500f), label = "seek thumb height")
                if (!engine.isLive) Slider(value = (slider ?: engine.position.toFloat()).coerceIn(0f, engine.duration.toFloat().coerceAtLeast(1f)), onValueChange = { slider = it; dragging = true },
                    valueRange = 0f..engine.duration.toFloat().coerceAtLeast(1f), onValueChangeFinished = { slider?.let { engine.seek(it.toLong()) }; slider = null; dragging = false },
                    modifier = Modifier.fillMaxWidth().height(32.dp).testTag("playback_seekbar"),
                    thumb = { Spacer(Modifier.size(thumbWidth, thumbHeight).clip(RoundedCornerShape(3.dp)).background(Color.White).testTag("playback_seek_thumb")) },
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = .35f)))
            }
        }
        }
        }
    }
}
