package app.biliflow

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable fun NativeDynamicScreen(model: MainViewModel, onVideo: (Video) -> Unit, onWeb: (String) -> Unit, onLogin: () -> Unit,
    topPadding: Dp, list: LazyListState, onDynamic: (String) -> Unit, active: Boolean = true, onActiveList: (LazyListState) -> Unit = {}) {
    var authors by remember(model.account?.id) { mutableStateOf(mergeDynamicAuthorOrder(emptyList(), model.dynamicAuthors, model.dynamicAuthor)) }
    val pager = rememberPagerState(initialPage = authors.indexOfFirst { it.id == model.dynamicAuthor?.id }.let { if (it < 0) 0 else it + 1 },
        pageCount = { authors.size + 1 })
    val holder = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    LaunchedEffect(model.dynamicAuthors, model.dynamicAuthor?.id) {
        authors = if (pager.settledPage == 0 && !pager.isScrollInProgress && model.dynamicAuthor == null) model.dynamicAuthors
            else mergeDynamicAuthorOrder(authors, model.dynamicAuthors, model.dynamicAuthor)
    }
    LaunchedEffect(active, model.account?.id) {
        if (active && model.account != null && !model.dynamicFeed().loaded && !model.dynamicLoading && model.dynamicError == null) model.loadDynamic()
    }
    LaunchedEffect(active, model.dynamicAuthor?.id) {
        val target = authors.indexOfFirst { it.id == model.dynamicAuthor?.id }.let { if (it < 0) 0 else it + 1 }
        if (active && !pager.isScrollInProgress && pager.settledPage != target) pager.scrollToPage(target)
    }
    LaunchedEffect(pager, active) {
        if (!active) return@LaunchedEffect
        snapshotFlow { pager.settledPage to pager.isScrollInProgress }.distinctUntilChanged().collect { (page, scrolling) ->
            if (!scrolling) model.selectDynamicAuthor(authors.getOrNull(page - 1))
        }
    }
    val select: (FollowedAuthor?) -> Unit = { author ->
        val target = authors.indexOfFirst { it.id == author?.id }.let { if (it < 0) 0 else it + 1 }
        scope.launch { pager.animateScrollToPage(target) }
    }
    Column(Modifier.fillMaxSize().padding(top = topPadding)) {
    if (model.account != null) DynamicAuthorSelector(model, authors, select)
    HorizontalPager(state = pager, key = { page -> authors.getOrNull(page - 1)?.id ?: 0L }, beyondViewportPageCount = 1,
        userScrollEnabled = active && model.account != null && authors.isNotEmpty(), modifier = Modifier.weight(1f).fillMaxWidth().testTag("dynamic_author_pager")) { page ->
        val author = authors.getOrNull(page - 1)
        holder.SaveableStateProvider(author?.id ?: 0L) {
            val pageList = if (author == null) list else rememberLazyListState()
            val pageActive = active && pager.settledPage == page && !pager.isScrollInProgress && model.dynamicAuthor?.id == author?.id
            SideEffect { if (pageActive) onActiveList(pageList) }
            Box(Modifier.fillMaxSize().testTag("dynamic_author_page_${author?.id ?: 0L}")
                .then(if (pager.currentPage != page) Modifier.clearAndSetSemantics { } else Modifier)) {
                DynamicAuthorPage(model, author, model.dynamicFeed(author?.id), onVideo, onWeb, onLogin, pageList, onDynamic, pageActive, { select(null) })
            }
        }
    }
    }
}

@Composable private fun DynamicAuthorSelector(model: MainViewModel, authors: List<FollowedAuthor>, select: (FollowedAuthor?) -> Unit) {
    val authorRow = rememberLazyListState()
    val selectedId = model.dynamicAuthor?.id
    LaunchedEffect(selectedId) {
        val index = authors.indexOfFirst { it.id == selectedId } + 1
        val visible = authorRow.layoutInfo.visibleItemsInfo
        if (selectedId == null) authorRow.animateScrollToItem(0)
        else if (index > 0 && visible.none { it.key == selectedId }) {
            if (visible.isEmpty()) authorRow.scrollToItem((index - 2).coerceAtLeast(0))
            else authorRow.animateScrollToItem((index - 2).coerceAtLeast(0))
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("dynamic_author_selector")) {
        LazyRow(state = authorRow, contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.testTag("followed_authors_count_${authors.size}")) {
            item {
                val allSelected = selectedId == null
                val allColor by animateColorAsState(if (allSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, tween(200), label = "all author color")
                val allScale by animateFloatAsState(if (allSelected) 1.06f else 1f, spring(dampingRatio = 0.72f, stiffness = 500f), label = "all author scale")
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clickable { select(null) }) {
                    Surface(shape = CircleShape, color = allColor, modifier = Modifier.size(54.dp).graphicsLayer { scaleX = allScale; scaleY = allScale }) {
                        Box(contentAlignment = Alignment.Center) { InterfaceIcon(Icons.Rounded.DynamicFeed, "全部关注动态") }
                    }
                    Text("全部", Modifier.padding(top = 7.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
            items(authors, key = { it.id }) { candidate ->
                val isSelected = selectedId == candidate.id
                val borderWidth by animateDpAsState(if (isSelected) 3.dp else 1.5.dp, spring(stiffness = 460f), label = "author border width")
                val borderColor by animateColorAsState(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, tween(200), label = "author border color")
                val avatarScale by animateFloatAsState(if (isSelected) 1.06f else 1f, spring(dampingRatio = 0.72f, stiffness = 500f), label = "author avatar scale")
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clickable { select(candidate) }.testTag("followed_author_${candidate.id}")) {
                    Box(Modifier.size(54.dp).graphicsLayer { scaleX = avatarScale; scaleY = avatarScale }) {
                        Surface(shape = CircleShape, border = BorderStroke(borderWidth, borderColor), modifier = Modifier.fillMaxSize()) {
                            AsyncImage(candidate.avatar, "关注的 UP 主", Modifier.padding(3.dp).clip(CircleShape))
                        }
                        androidx.compose.animation.AnimatedVisibility(
                            visible = candidate.updated,
                            enter = scaleIn(spring(dampingRatio = 0.65f, stiffness = 500f)) + fadeIn(tween(150)),
                            exit = scaleOut(tween(180)) + fadeOut(tween(150)),
                            modifier = Modifier.align(Alignment.TopEnd)
                        ) {
                            Box(Modifier.size(12.dp).background(MaterialTheme.colorScheme.surface, CircleShape).padding(2.dp).background(MaterialTheme.colorScheme.primary, CircleShape).testTag("author_update_dot"))
                        }
                    }
                    Text(candidate.name, Modifier.padding(top = 7.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (model.authorsMore) item { TextButton(onClick = { model.loadDynamicAuthors(true) }, enabled = !model.authorsLoading) { Text("更多关注") } }
        }
        if (model.authorsLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp))
    }
}

@Composable private fun DynamicAuthorPage(model: MainViewModel, author: FollowedAuthor?, feed: DynamicFeedSnapshot,
    onVideo: (Video) -> Unit, onWeb: (String) -> Unit, onLogin: () -> Unit, list: LazyListState,
    onDynamic: (String) -> Unit, active: Boolean, onAll: () -> Unit) {
    LaunchedEffect(list, model.account?.id, active) {
        if (!active) return@LaunchedEffect
        snapshotFlow { val info = list.layoutInfo; Triple(info.visibleItemsInfo.lastOrNull()?.index ?: 0, info.totalItemsCount, model.dynamicLoading) }
            .distinctUntilChanged().collect { (last, total, loading) ->
                if (!loading && total > 1 && last >= total - 3 && model.dynamicError == null && model.dynamicMore) model.loadDynamic(true)
            }
    }
    LazyColumn(state = list, modifier = Modifier.testTag("dynamic_list_request_${model.dynamicEpoch}"), contentPadding = PaddingValues(top = 12.dp, bottom = 108.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            if (model.account == null) {
                EmptyState(Icons.Rounded.Forum, "登录后查看关注动态", "连接你的账号，发现 UP 主的新内容")
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Button(onClick = onLogin) { Text("登录账号") } }
            } else if (!model.appAuthorized) {
                EmptyState(Icons.Rounded.Forum, "完成 App 授权后查看动态", "安卓动态需要使用账号的 App 授权")
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Button(onClick = onLogin) { Text("完成 App 授权") } }
            }
        }
        if (author != null) item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onAll) { Text("全部关注") }
            }
        }
        model.authorsError?.let { message -> item { ErrorCard(message, { if (active) model.loadDynamicAuthors() }) } }
        if (model.dynamicAuthor?.id == author?.id) model.dynamicReadError?.let { message -> item { ErrorCard("已读状态同步失败：$message", model::retryDynamicReadSync) } }
        items(feed.posts, key = { it.id }) { post -> DynamicCard(post, onVideo, onWeb, onDynamic, modifier = Modifier.animateItem()) }
        if (feed.loading || (!feed.loaded && feed.error == null && model.account != null)) item { LoadingCards(feed.posts.isEmpty()) }
        feed.error?.let { error -> item { ErrorCard(error, { if (active) model.loadDynamic(feed.posts.isNotEmpty()) }) } }
        if (model.account != null && feed.loaded && !feed.loading && feed.error == null && feed.posts.isEmpty()) item { EmptyState(Icons.Rounded.Forum, "暂时没有新动态", "关注更多喜欢的 UP 主，或稍后刷新") }
    }
}

@Composable private fun DynamicCard(post: DynamicPost, onVideo: (Video) -> Unit, onWeb: (String) -> Unit, onDynamic: (String) -> Unit, nested: Boolean = false, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(if (nested) 16.dp else 22.dp), color = if (nested) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().then(if (!nested) Modifier.padding(horizontal = 16.dp) else Modifier)
            .testTag("dynamic_card_${if (nested) "original" else if (post.original != null) "forward" else if (post.video != null) "video" else "post"}_${post.id}")) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(post.avatar, "UP 主头像", Modifier.size(if (nested) 30.dp else 42.dp).clip(CircleShape))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(post.author, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                    Text(post.time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (post.text.isNotBlank()) {
                var expanded by remember(post.id) { mutableStateOf(false) }
                Column(Modifier.animateContentSize(spring(dampingRatio = 0.85f, stiffness = 380f))) {
                    Text(post.text, modifier = Modifier.padding(top = 12.dp), maxLines = if (expanded) Int.MAX_VALUE else 8, overflow = TextOverflow.Ellipsis)
                    if (post.text.length > 240) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开") }
                }
            }
            if (post.articleTitle.isNotBlank()) Text(post.articleTitle, Modifier.padding(vertical = 10.dp), fontWeight = FontWeight.SemiBold)
            if (post.pictures.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                if (post.pictures.size == 1) AsyncImage(post.pictures.first(), "动态配图", Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp).clip(RoundedCornerShape(14.dp)).clickable { onDynamic(post.id) }, contentScale = ContentScale.Fit)
                else Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    post.pictures.take(9).chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            row.forEach { picture -> AsyncImage(picture, "动态配图", Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(8.dp)).clickable { onDynamic(post.id) }, contentScale = ContentScale.Crop) }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            post.video?.let { video ->
                Spacer(Modifier.height(12.dp))
                VideoCard(video, { onVideo(video) }, compact = true)
            }
            post.original?.let { original -> Spacer(Modifier.height(12.dp)); DynamicCard(original, onVideo, onWeb, onDynamic, true) }
            post.attachments.forEach { attachment ->
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).then(if (attachment.url.startsWith("https://") || attachment.url.startsWith("http://")) Modifier.clickable { onWeb(attachment.url) } else Modifier)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (attachment.cover.isNotBlank()) AsyncImage(attachment.cover, null, Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                        Column(Modifier.weight(1f).padding(start = if (attachment.cover.isNotBlank()) 10.dp else 0.dp)) {
                            Text(attachment.label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                            if (attachment.title.isNotBlank()) Text(attachment.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            if (attachment.description.isNotBlank()) Text(attachment.description, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                    }
                }
            }
            if (post.liveRoom > 0) FilledTonalButton(onClick = { onWeb("https://live.bilibili.com/${post.liveRoom}") }, modifier = Modifier.padding(top = 12.dp)) { Text("进入直播间") }
            if (!nested) Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("${count(post.forwards)} 转发", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${count(post.comments)} 评论", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${count(post.likes)} 赞", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val target = dynamicDestination(post)
                TextButton(enabled = post.id.all(Char::isDigit) && post.id.isNotEmpty(), onClick = { when (target) {
                    is DynamicDestination.VideoPage -> onVideo(target.video)
                    is DynamicDestination.PostPage -> onDynamic(target.id)
                } }, modifier = Modifier.testTag(if (post.original != null) "dynamic_view_original" else if (post.video != null) "dynamic_view_video" else "dynamic_view_post")) { Text("查看") }
            }
        }
    }
}

@Composable fun NativeLiveScreen(model: MainViewModel, open: (Long) -> Unit, onLogin: () -> Unit, topPadding: Dp) {
    LaunchedEffect(model.account?.id) { if (model.rooms.isEmpty()) model.loadLive() else model.loadFollowedLive() }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(top = topPadding + 12.dp, bottom = 108.dp)) {
        item {
            SectionHeading("关注的直播", if (model.account == null) "登录后看关注 UP 主的直播" else "${model.followedRooms.size} 位关注的 UP 主正在直播") {
                IconButton(onClick = { model.loadFollowedLive() }, enabled = !model.followedLiveLoading) { SpinningRefreshIcon(model.followedLiveLoading, "刷新关注直播") }
            }
            when {
                model.account == null -> TextButton(onClick = onLogin, modifier = Modifier.padding(horizontal = 20.dp)) { Text("登录查看关注直播") }
                model.followedLiveLoading && model.followedRooms.isEmpty() -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp))
                model.followedLiveError != null -> ErrorCard(model.followedLiveError!!, model::loadFollowedLive)
                model.followedRooms.isEmpty() -> Text("关注的 UP 主暂时没有开播", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(model.followedRooms, key = { it.id }) { room ->
                        val interactionSource = remember { MutableInteractionSource() }
                        Surface(onClick = { open(room.id) }, interactionSource = interactionSource, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(156.dp).animateItem().pressScale(interactionSource)) {
                            Column {
                                Cover(room.cover, room.title, Modifier.fillMaxWidth().height(88.dp))
                                Column(Modifier.padding(10.dp)) {
                                    Text("● ${room.author}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(room.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
        item { SectionHeading("正在发生的精彩", "发现新的直播间") { IconButton(onClick = model::loadLive, enabled = !model.liveLoading) { SpinningRefreshIcon(model.liveLoading, "刷新直播") } } }
        items(model.rooms, key = { it.id }) { room ->
            val interactionSource = remember { MutableInteractionSource() }
            Surface(onClick = { open(room.id) }, interactionSource = interactionSource, shape = RoundedCornerShape(22.dp), modifier = Modifier.padding(horizontal = 20.dp).animateItem().pressScale(interactionSource), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column {
                    Cover(room.cover, room.title, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    Column(Modifier.padding(16.dp)) {
                        Text(room.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${room.author} · 人气 ${count(room.online)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        if (model.liveLoading) item { LoadingCards(model.rooms.isEmpty()) }
        model.liveError?.let { error -> item { ErrorCard(error, model::loadLive) } }
    }
}
