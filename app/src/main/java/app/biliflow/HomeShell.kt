@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.biliflow

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch

@Composable fun HomeShell(model: MainViewModel, tab: Int, onTab: (Int) -> Unit, openVideo: (Video) -> Unit, openLive: (Long) -> Unit,
    login: () -> Unit, web: (String) -> Unit, library: (String) -> Unit, settings: () -> Unit,
    searchOpen: Boolean, setSearchOpen: (Boolean) -> Unit, input: String, setInput: (String) -> Unit, onBottomBarHeight: (Int) -> Unit = {}, openDynamic: (String) -> Unit) {
    val haze = remember { HazeState() }
    val list = rememberLazyStaggeredGridState()
    val rootDynamicList = rememberLazyListState()
    var dynamicList by remember { mutableStateOf(rootDynamicList) }
    val pageStates = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val statusHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val initialHeaderHeight = 64.dp + statusHeight
    var headerPx by remember { mutableFloatStateOf(with(density) { initialHeaderHeight.toPx() }) }
    val topPadding = with(density) { headerPx.toDp() }
    var bars by remember { mutableStateOf(true) }
    val currentTab by rememberUpdatedState(tab)
    val searching by rememberUpdatedState(searchOpen)
    val connection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (currentTab == 2 || (currentTab == 1 && !searching)) {
                    if (consumed.y < -2f) bars = false
                    if (consumed.y > 2f) bars = true
                }
                return Offset.Zero
            }
        }
    }
    val atTop by remember { derivedStateOf { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0 } }
    val dynamicAtTop by remember { derivedStateOf { dynamicList.firstVisibleItemIndex == 0 && dynamicList.firstVisibleItemScrollOffset == 0 } }
    LaunchedEffect(tab, searchOpen, model.dynamicAuthor?.id) { bars = true }
    LaunchedEffect(atTop, dynamicAtTop, tab) { if ((tab == 1 && atTop) || (tab == 2 && dynamicAtTop)) bars = true }
    LaunchedEffect(model.query, model.popularMode) { list.scrollToItem(0); bars = true }
    val hidden by animateFloatAsState(if (bars || tab !in 1..2) 0f else 1f, tween(220), label = "immersive bars")
    val title = when (tab) { 1 -> videoFeedTitle(model); 2 -> model.dynamicAuthor?.let { "${it.name}的动态" } ?: "关注动态"; else -> Destination.entries[tab].label }
    Box(Modifier.fillMaxSize()) {
        // Record an opaque base together with the pages: cross-fades must not
        // temporarily expose a transparent input to either glass surface.
        Box(Modifier.fillMaxSize().nestedScroll(connection).hazeSource(haze).background(MaterialTheme.colorScheme.background)) {
            AnimatedContent(targetState = tab, modifier = Modifier.fillMaxSize(), contentKey = { it }, label = "bottom navigation page",
                transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    ((slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { direction * it / 6 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(240, easing = FastOutSlowInEasing)) { -direction * it / 8 } + fadeOut(tween(180))))
                        .using(SizeTransform(clip = false))
                }) { page ->
                pageStates.SaveableStateProvider(page) {
                    Box(Modifier.fillMaxSize().testTag("home_page_$page").then(if (page != tab) Modifier.clearAndSetSemantics { } else Modifier)) {
                        when (page) {
                            0 -> NativeLiveScreen(model, openLive, login, topPadding)
                            1 -> VideoFeed(model, openVideo, login, list, topPadding)
                            2 -> key(model.account?.id) { NativeDynamicScreen(model, openVideo, web, login, maxOf(statusHeight, topPadding * (1f - hidden)), rootDynamicList, openDynamic,
                                active = page == tab, onActiveList = { dynamicList = it }) }
                            else -> Box(Modifier.fillMaxSize().padding(top = topPadding)) { ProfileScreen(model, library, settings, login) }
                        }
                    }
                }
            }
        }
        // Keep Haze's position, capture size and scale stable during scrolling.
        // Only the output clip changes; the toolbar content can move independently.
        val blurHeight = initialHeaderHeight + 96.dp * density.fontScale.coerceAtLeast(1f)
        TopBarGlass(haze, maxOf(blurHeight, topPadding), visibleHeightPx = { headerPx * (1f - hidden) }, opacity = { 1f - hidden },
            modifier = Modifier.align(Alignment.TopCenter).testTag("home_top_bar_blur"))
        Column(Modifier.fillMaxWidth().align(Alignment.TopCenter).testTag("home_top_bar").onSizeChanged { headerPx = it.height.toFloat() }
            .graphicsLayer { translationY = -headerPx * hidden; alpha = 1f - hidden }) {
            TopAppBar(title = {
                AnimatedContent(
                    targetState = title,
                    transitionSpec = {
                        (fadeIn(tween(200, easing = FastOutSlowInEasing)) +
                            slideInVertically(tween(220, easing = FastOutSlowInEasing)) { it / 5 }) togetherWith
                            (fadeOut(tween(150)) + slideOutVertically(tween(170)) { -it / 5 })
                    },
                    label = "home top bar title"
                ) { currentTitle ->
                    Column {
                        InterfaceTitle(currentTitle, model.interfaceHdr)
                    }
                }
            }, actions = {
                AnimatedVisibility(visible = tab == 1 || tab == 2, enter = AppMotion.FastActionEnter, exit = AppMotion.FastActionExit) {
                    val refreshing = if (tab == 1) model.feed.loading else model.dynamicLoading
                    IconButton(onClick = { if (tab == 1) model.loadFeed() else model.loadDynamic() }, enabled = !refreshing) {
                        SpinningRefreshIcon(refreshing, if (tab == 1) "刷新" else "刷新动态")
                    }
                }
                AnimatedVisibility(visible = tab == 1, enter = AppMotion.FastActionEnter, exit = AppMotion.FastActionExit) {
                    IconButton(onClick = { setSearchOpen(!searchOpen) }) { InterfaceIcon(Icons.Rounded.Search, "搜索视频") }
                }
                IconButton(onClick = settings) { InterfaceIcon(Icons.Rounded.Tune, "外观设置") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent))
            AnimatedVisibility(
                searchOpen && tab == 1,
                enter = expandVertically(tween(240, easing = FastOutSlowInEasing)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(200, easing = FastOutSlowInEasing)) + fadeOut(tween(160))
            ) {
                OutlinedTextField(input, setInput, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), placeholder = { Text("搜索视频、UP 主或 BV 号") },
                    singleLine = true, shape = androidx.compose.foundation.shape.CircleShape, leadingIcon = { InterfaceIcon(Icons.Rounded.Search, null) },
                    trailingIcon = { IconButton(onClick = { keyboard?.hide(); model.search(input) }) { InterfaceIcon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "开始搜索") } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); model.search(input) }))
            }
        }
        FloatingBottomBar(tab, { selected ->
            bars = true
            if (tab == 1 && selected == 1) {
                if (atTop) model.loadFeed() else scope.launch { list.animateScrollToItem(0) }
            } else if (tab == 2 && selected == 2) {
                if (dynamicAtTop) model.loadDynamic() else scope.launch { dynamicList.animateScrollToItem(0) }
            } else onTab(selected)
        }, haze, Modifier.align(Alignment.BottomCenter).testTag("home_bottom_bar").onSizeChanged { onBottomBarHeight(it.height) }.graphicsLayer { translationY = with(density) { 130.dp.toPx() } * hidden; alpha = 1f - hidden })
    }
}
