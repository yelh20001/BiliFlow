@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.biliflow

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest

class MainActivity : PlaybackActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { val model = mainModel; BiliTheme(model.theme) {
            CompositionLocalProvider(LocalInterfaceHdr provides model.interfaceHdr) { BiliApp(model) }
        } }
    }
}

@Composable fun BiliTheme(mode: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val themeActivity = LocalActivity.current
    val dark = mode == "system" && isSystemInDarkTheme()
    SideEffect {
        themeActivity?.let { activity ->
            androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val colors = when {
        mode == "white" -> lightColorScheme(primary = Color(0xFF426887), onPrimary = Color.White,
            primaryContainer = Color(0xFFE9EFF5), onPrimaryContainer = Color(0xFF20394F),
            secondary = Color(0xFF526575), onSecondaryContainer = Color(0xFF20394F),
            background = Color.White, surface = Color.White, surfaceContainer = Color(0xFFF5F6F8),
            surfaceContainerLow = Color(0xFFF8F9FB), secondaryContainer = Color(0xFFE9EFF5))
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFFA1C9F2), background = Color(0xFF101418), surface = Color(0xFF101418))
        else -> lightColorScheme(primary = Color(0xFF426887), background = Color(0xFFF8FAFC), surface = Color(0xFFF8FAFC))
    }
    MaterialTheme(colorScheme = colors, shapes = Shapes(medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp)), content = content)
}

enum class Destination(val label: String, val icon: ImageVector) {
    Live("直播", Icons.Rounded.Sensors), Video("视频", Icons.Rounded.PlayCircle),
    Dynamic("动态", Icons.Rounded.Forum), Me("我的", Icons.Rounded.PersonOutline)
}

@Composable fun BiliApp(model: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(1) }
    val videoStack = rememberSaveable(saver = VideoBackStack.StateSaver) { VideoBackStack() }
    val selected = videoStack.current?.video
    val coverTransition = remember { CoverTransitionState() }
    val homeExit = remember { Animatable(0f) }
    val navigationScope = rememberCoroutineScope()
    var returningHome by remember { mutableStateOf(false) }
    val savedScenes = rememberSaveableStateHolder()
    var previousKeys by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(videoStack.entries) {
        val keys = videoStack.entries.map { it.key }.toSet()
        (previousKeys - keys).forEach(savedScenes::removeState)
        previousKeys = keys
    }
    var selectedLive by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedAuthor by rememberSaveable { mutableStateOf<Long?>(null) }
    var authorBehindVideo by rememberSaveable { mutableStateOf(false) }
    var webUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var library by rememberSaveable { mutableStateOf<String?>(null) }
    var dynamicRoutes by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    var loginOpen by rememberSaveable { mutableStateOf(false) }
    var webLogin by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val activity = LocalActivity.current as? PlaybackActivity
    val density = LocalDensity.current
    val miniTouchSlop = LocalViewConfiguration.current.touchSlop
    val engine = model.playback
    SideEffect { engine.miniDraggingEnabled = model.miniDragging }
    LaunchedEffect(selected?.id, selectedLive) { if (selected == null && selectedLive == null) engine.close() }
    val minimized = engine.minimized
    var bottomBarHeight by remember { mutableStateOf(92.dp) }
    LaunchedEffect(minimized) { if (minimized) coverTransition.cancel() }
    val playerVisible = (selected != null || selectedLive != null) && !loginOpen && webUrl == null && (selectedAuthor == null || authorBehindVideo)
    SideEffect { activity?.videoPageVisible(playerVisible) }
    val openVideo: (Video) -> Unit = { engine.clearQueue(); engine.restoreFromMini(); selectedLive = null; coverTransition.start(it); videoStack.open(it); model.record(it) }
    val openRelated: (Video) -> Unit = { if (videoStack.current?.video?.id != it.id) { engine.clearQueue(); coverTransition.start(it); videoStack.push(it); model.record(it) } }
    LaunchedEffect(engine.queue.current?.id) {
        engine.queue.current?.let { video ->
            selectedLive = null
            if (videoStack.current?.video?.id != video.id) { coverTransition.cancel(); videoStack.open(video); model.record(video) }
        }
    }
    val playQueue: (List<Video>, Video) -> Unit = { videos, video ->
        engine.restoreFromMini(); selectedLive = null; engine.startQueue(videos, video.id)
        if (videoStack.current?.video?.id != video.id) { coverTransition.start(video); videoStack.open(video); model.record(video) }
    }
    val backVideo: () -> Unit = { coverTransition.cancel(); engine.clearQueue(); videoStack.pop() }
    // Dismiss only the playback layer. The page under it owns its own navigation.
    val videoHome: () -> Unit = { coverTransition.cancel(); engine.clearQueue(); videoStack.clear(); selectedLive = null }
    val animatedVideoHome: () -> Unit = {
        if (minimized) videoHome()
        else if (!returningHome) {
            returningHome = true
            navigationScope.launch {
                try {
                    homeExit.snapTo(0f)
                    homeExit.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
                    withFrameNanos { }
                    videoHome()
                } finally { homeExit.snapTo(0f); returningHome = false }
            }
        }
    }
    val openLive: (Long) -> Unit = { coverTransition.cancel(); videoStack.clear(); dynamicRoutes = emptyList(); selectedLive = it }
    val openDynamic: (String) -> Unit = { id -> if (dynamicRoutes.lastOrNull() != id) dynamicRoutes = (dynamicRoutes + id).takeLast(16) }
    val openWeb: (String) -> Unit = { url ->
        val room = Regex("https://live\\.bilibili\\.com/(\\d+)(?:[/?#].*)?").matchEntire(url)?.groupValues?.get(1)?.toLongOrNull()
        if (room != null) openLive(room) else webUrl = url
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) model.refreshAccount()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(model.account?.id, model.appAuthorized, webLogin, model.qrState.qr?.url) {
        if (model.account != null && model.appAuthorized) {
            loginOpen = false
            if (webLogin) { webUrl = null; webLogin = false }
        } else if (webLogin && model.account != null && !model.qrState.expired) {
            model.qrState.qr?.url?.let { webUrl = it }
        }
    }
    val closeWeb = { webUrl = null; webLogin = false; model.refreshAccount(); Unit }
    CompositionLocalProvider(LocalCoverTransition provides coverTransition, LocalAuthorAvatars provides model.authorAvatars,
        LocalWatchLaterAction provides { video -> if (model.account == null) loginOpen = true else model.watchLater.add(video) }) {
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).semantics { testTagsAsResourceId = true }) {
    val screenWidth = maxWidth; val screenHeight = maxHeight
    val miniTopInset = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
    PredictiveBackSurface(enabled = (selected == null || minimized) && selectedLive == null && dynamicRoutes.isEmpty() && webUrl == null && library == null && !loginOpen && !settings && (searchOpen || tab != 1), resetKey = tab to searchOpen, retainedDestination = true, onBack = {
        if (searchOpen) { searchOpen = false; input = ""; model.search("") } else tab = 1
    }) {
        HomeShell(model, tab, { tab = it }, openVideo, openLive, { loginOpen = true }, openWeb, { library = it }, { settings = true },
            searchOpen, { searchOpen = it }, input, { input = it }, onBottomBarHeight = { if (!engine.pipPresentation) bottomBarHeight = with(density) { it.toDp() } }, openDynamic = openDynamic)
    }
    AnimatedOverlayRoute(library) { currentLibrary ->
        PredictiveBackSurface(enabled = library != null, onBack = { library = null }) {
            Scaffold(topBar = {
                TopAppBar(title = { Text(currentLibrary) }, navigationIcon = { IconButton(onClick = { library = null }) { InterfaceIcon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } })
            }) { padding -> Box(Modifier.fillMaxSize().padding(padding)) {
                if (currentLibrary == "稍后再看") WatchLaterScreen(model, playQueue)
                else if (currentLibrary in setOf("账号收藏夹", "已点赞的视频", "已投币的视频")) AccountLibraryScreen(currentLibrary, model, openVideo)
                else LibraryScreen(currentLibrary, model, openVideo)
            } }
        }
    }
    AnimatedOverlayRoute(dynamicRoutes.lastOrNull()) { id -> key("dynamic-$id") {
        val visible = dynamicRoutes.isNotEmpty() && (selected == null || minimized)
        Box(if (visible) Modifier.fillMaxSize() else Modifier.fillMaxSize().clearAndSetSemantics { }) {
            PredictiveBackSurface(enabled = visible && selectedLive == null, onBack = { dynamicRoutes = dynamicRoutes.dropLast(1) }) {
                NativeDynamicDetail(model, id, { dynamicRoutes = dynamicRoutes.dropLast(1) }, openVideo, openDynamic, openWeb)
            }
        }
    } }
    AnimatedOverlayRoute(selectedAuthor, modifier = Modifier.fillMaxSize().zIndex(if (authorBehindVideo) 0f else 1f)) { authorId ->
        val authorVisible = selectedAuthor != null && (!authorBehindVideo || selected == null || minimized)
        Box(Modifier.fillMaxSize().then(if (authorVisible) Modifier else Modifier.clearAndSetSemantics { })) {
            PredictiveBackSurface(enabled = authorVisible, onBack = { selectedAuthor = null; authorBehindVideo = false }) {
                AuthorScreen(authorId, model, back = { selectedAuthor = null; authorBehindVideo = false }, openVideo = { video ->
                    authorBehindVideo = true
                    openVideo(video)
                })
            }
        }
    }
    videoStack.entries.takeLast(2).forEach { route -> key(route.key, route.key == videoStack.current?.key) {
        val active = route.key == videoStack.current?.key
        val floating = active && minimized && !engine.inPip
        var wasPip by remember { mutableStateOf(engine.inPip) }
        LaunchedEffect(engine.inPip) { wasPip = engine.inPip }
        val mini = miniLayout(screenWidth.value, screenHeight.value, bottomBarHeight.value, miniTopInset.value, engine.videoAspectRatio, engine.miniPositionX, engine.miniPositionY)
        val miniWidth = mini.width.dp
        val miniHeight = mini.height.dp
        SideEffect {
            if (active) with(density) {
                val x = mini.left.dp.toPx()
                val y = mini.top.dp.toPx()
                engine.miniTarget = Rect(x, y, x + miniWidth.toPx(), y + miniHeight.toPx())
                engine.miniMovementBounds = Rect(mini.minLeft.dp.toPx(), mini.minTop.dp.toPx(), mini.maxLeft.dp.toPx(), mini.maxTop.dp.toPx())
            }
        }
        val miniMotion = updateTransition(floating, label = "in app mini player")
        SideEffect { if (active) engine.miniCanDrag = floating && miniMotion.currentState == miniMotion.targetState }
        LaunchedEffect(active, miniMotion.currentState, miniMotion.targetState, miniMotion.isRunning) {
            if (active && !miniMotion.isRunning && !miniMotion.currentState && !miniMotion.targetState) engine.miniRestoring = false
        }
        val motionSpec: @Composable Transition.Segment<Boolean>.() -> FiniteAnimationSpec<Dp> = {
            if (engine.pipPresentation || wasPip || engine.miniHandoff || initialState == targetState) snap() else tween(320, easing = FastOutSlowInEasing)
        }
        val width by miniMotion.animateDp(transitionSpec = motionSpec, label = "mini width") { if (it) miniWidth else screenWidth }
        val height by miniMotion.animateDp(transitionSpec = motionSpec, label = "mini height") { if (it) miniHeight else screenHeight }
        val left by miniMotion.animateDp(transitionSpec = motionSpec, label = "mini left") { if (it) mini.left.dp else 0.dp }
        val top by miniMotion.animateDp(transitionSpec = motionSpec, label = "mini top") { if (it) mini.top.dp else 0.dp }
        val radius by miniMotion.animateDp(transitionSpec = motionSpec, label = "mini corners") { if (it) 16.dp else 0.dp }
        // A snap transition still exposes its old value until the transition updates.
        // At gesture handoff use the final bounds immediately, in the same frame that
        // the player switches to compact controls.
        val handoff = floating && engine.miniHandoff
        val actualWidth = if (handoff) miniWidth else width
        val actualHeight = if (handoff) miniHeight else height
        val actualLeft = if (handoff) mini.left.dp else left
        val actualTop = if (handoff) mini.top.dp else top
        val actualRadius = if (handoff) 16.dp else radius
        val entering = remember { active && coverTransition.flight?.video?.id == route.video.id }
        val plainEnter = remember { active && !entering && !minimized && route.key !in previousKeys }
        val sceneOpacity = remember { Animatable(if (entering || plainEnter) 0f else 1f) }
        val sceneOffset = remember { Animatable(if (plainEnter) 1f else 0f) }
        LaunchedEffect(Unit) {
            if (entering) sceneOpacity.animateTo(1f, tween(220))
            else if (plainEnter) {
                launch { sceneOpacity.animateTo(1f, tween(220, easing = FastOutSlowInEasing)) }
                launch { sceneOffset.animateTo(0f, tween(260, easing = FastOutSlowInEasing)) }
            }
        }
        savedScenes.SaveableStateProvider(route.key) {
        Box((if (active) Modifier.offset(x = actualLeft, y = actualTop).size(actualWidth, actualHeight).clip(RoundedCornerShape(actualRadius)).testTag(if (floating) "in_app_mini_player" else "video_stack_depth_${videoStack.entries.size}") else Modifier.size(if (minimized) 0.dp else screenWidth, if (minimized) 0.dp else screenHeight).clipToBounds().clearAndSetSemantics { }).shareMiniPointerInput(floating && !model.miniDragging, miniTouchSlop) { point, bounds ->
            val cornerButton = with(density) { 36.dp.toPx() }
            val centerButton = with(density) { 22.dp.toPx() }
            when {
                point.y <= cornerButton && point.x >= bounds.width - cornerButton -> animatedVideoHome()
                point.y <= cornerButton && point.x <= cornerButton -> engine.restoreFromMini()
                kotlin.math.abs(point.x - bounds.width / 2f) <= centerButton && kotlin.math.abs(point.y - bounds.height / 2f) <= centerButton -> engine.toggle()
                else -> engine.restoreFromMini()
            }
        }.graphicsLayer {
            // Reveal the feed during a downward handoff, including nested videos.
            // Retain the previous route without leaving its detail page behind the mini player.
            alpha = if (!active && (returningHome || minimized || engine.miniDragProgress > 0f)) 0f else sceneOpacity.value * (if (active) 1f - homeExit.value else 1f)
            if (active) translationX = size.width * (.18f * homeExit.value + .12f * sceneOffset.value)
        }) {
        PredictiveBackSurface(enabled = active && !minimized && !model.playback.fullScreen && !model.playback.pipPresentation && !loginOpen && webUrl == null && (selectedAuthor == null || authorBehindVideo), onBack = backVideo,
            color = if (floating || engine.miniDragProgress > 0f) Color.Transparent else MaterialTheme.colorScheme.background) {
            VideoScreen(route.video, model, onBack = backVideo, onLogin = { loginOpen = true }, onAuthor = { authorBehindVideo = false; selectedAuthor = it }, onRelated = openRelated, onHome = animatedVideoHome, active = active, routeKey = route.key)
        }
        }
        }
    }
    }
    AnimatedOverlayRoute(selectedLive) { roomId ->
        PredictiveBackSurface(enabled = selectedLive != null && !model.playback.fullScreen && !model.playback.pipPresentation, onBack = { selectedLive = null }) {
            LiveScreen(roomId, model, onBack = { selectedLive = null })
        }
    }
    AnimatedOverlayRoute(if (loginOpen) Unit else null, modifier = Modifier.fillMaxSize().zIndex(2f), enter = AppMotion.ModalEnter, exit = AppMotion.ModalExit) {
        PredictiveBackSurface(enabled = loginOpen, onBack = { loginOpen = false }) {
            LoginScreen(model, { loginOpen = false }) {
                webLogin = true
                webUrl = "https://passport.bilibili.com/h5-app/passport/login?gourl=https%3A%2F%2Fwww.bilibili.com"
            }
        }
    }
    AnimatedOverlayRoute(webUrl, modifier = Modifier.fillMaxSize().zIndex(2f)) { currentUrl ->
        PredictiveBackSurface(enabled = webUrl != null, onBack = closeWeb) {
            Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                key(currentUrl) { OfficialPage(currentUrl, onBack = closeWeb, login = webLogin, model = model) }
            }
        }
    }
    Box(Modifier.fillMaxSize().zIndex(3f)) { CoverFlightOverlay(coverTransition) }
    }
    }
    if (settings && !engine.pipPresentation) ModalBottomSheet(onDismissRequest = { settings = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 36.dp).semantics { testTagsAsResourceId = true }) {
            Text("让界面，成为你的风格", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("外观设置", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            listOf("system" to "跟随系统 · 原生取色", "white" to "纯白 · 清爽明亮").forEach { (key, title) ->
                val selectedTheme = model.theme == key
                val containerColor by animateColorAsState(if (selectedTheme) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, tween(200), label = "theme option")
                Surface(onClick = { model.updateTheme(key) }, shape = RoundedCornerShape(20.dp), color = containerColor,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        InterfaceIcon(if (key == "system") Icons.Rounded.Palette else Icons.Rounded.LightMode, null)
                        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                            Text(title, fontWeight = FontWeight.SemiBold)
                            Text(if (key == "system") "Android 12+ 使用壁纸配色，明暗随系统" else "始终使用白色背景与柔和蓝灰色", style = MaterialTheme.typography.bodySmall)
                        }
                        RadioButton(selected = model.theme == key, onClick = null)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("播放设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            DanmakuSettings(model)
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("小窗拖动"); Text("允许拖动应用内小窗，关闭后保留当前位置", style = MaterialTheme.typography.bodySmall) }
                Switch(model.miniDragging, model::updateMiniDragging, Modifier.testTag("mini_dragging"))
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("首页双排排列", Modifier.weight(1f)); Switch(model.doubleColumn, model::updateDoubleColumn)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("界面 HDR 显示")
                    Text("Android 14 及以上为标题、图标、导航与操作按钮添加 HDR 高光；视频 HDR 独立输出", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(model.interfaceHdr, model::updateInterfaceHdr, Modifier.testTag("interface_hdr"))
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("左右滑动调整进度", Modifier.weight(1f)); Switch(model.horizontalSeek, model::updateHorizontalSeek)
            }
            var qualityMenu by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                OutlinedButton(onClick = { qualityMenu = true }, modifier = Modifier.fillMaxWidth()) { InterfaceLabel("首选清晰度：${qualityLabel(model.preferredQuality)}") }
                DropdownMenu(expanded = qualityMenu, onDismissRequest = { qualityMenu = false }, modifier = Modifier.heightIn(max = 340.dp)) {
                    preferredQualities.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, onClick = { model.updatePreferredQuality(option.id); qualityMenu = false }) }
                }
            }
            Text("新视频使用首选清晰度，实际画质取决于账号权限与视频片源", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("画质变更提醒")
                    Text("打开视频时，提示首选画质的自动调整", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(model.qualityChangeNotice, model::updateQualityChangeNotice, Modifier.testTag("quality_change_notice"))
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("离开应用时小窗播放", Modifier.weight(1f)); Switch(model.pipEnabled, model::updatePipEnabled)
            }
            Spacer(Modifier.height(16.dp))
            Text("BiliFlow ${BuildConfig.VERSION_NAME} · 非官方客户端", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable fun SectionHeading(title: String, subtitle: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.invoke()
    }
}

@Composable fun VideoFeed(model: MainViewModel, onVideo: (Video) -> Unit, onLogin: () -> Unit, listState: LazyStaggeredGridState, topPadding: Dp) {
    val state = model.feed
    val width = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val columns = feedColumns(width, model.doubleColumn)
    val compact = columns > 1
    val context = LocalContext.current
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(model.feedMessage) { model.feedMessage?.let { snack.showSnackbar(it); model.clearFeedMessage() } }
    LaunchedEffect(listState, model.doubleColumn) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }.filter { it.startsWith("BV") } }
            .collect { model.markRecommendationsSeen(it) }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val feed = model.feed
            if (feed.loading || feed.error != null || !feed.more || feed.videos.isEmpty()) {
                null
            } else {
                val tail = feed.videos.takeLast(4)
                feed.page.takeIf {
                    listState.layoutInfo.visibleItemsInfo.any { item -> tail.any { it.id == item.key } }
                }
            }
        }.collect { page ->
            if (page != null) model.loadFeed(true)
        }
    }
    Box(Modifier.fillMaxSize()) {
    LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Fixed(columns), state = listState, modifier = Modifier.testTag("video_feed_page_${state.page}_count_${state.videos.size}"), contentPadding = PaddingValues(start = if (compact) 12.dp else 0.dp, end = if (compact) 12.dp else 0.dp, top = topPadding + 8.dp, bottom = 108.dp), verticalItemSpacing = 16.dp, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item(span = StaggeredGridItemSpan.FullLine) {
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !model.popularMode && model.query.isBlank(), onClick = { model.search("") }, label = { Text("推荐") })
                FilterChip(selected = model.popularMode, onClick = model::showPopular, label = { Text("热门") })
                if (!model.appAuthorized) SuggestionChip(onClick = onLogin, label = { Text(if (model.account == null) "登录账号" else "完成授权") }, icon = { InterfaceIcon(Icons.Rounded.PersonOutline, null, Modifier.size(16.dp)) })
            }
        }
        items(state.videos.size, key = { state.videos[it].id }, contentType = { "video" }) { index ->
            val video = state.videos[index]
            VideoCard(video, { onVideo(video) }, compact = compact, onDislike = if (model.query.isBlank() && !model.popularMode) { { model.dislike(video) } } else null, modifier = Modifier.animateItem())
        }
        if (state.loading) item(span = StaggeredGridItemSpan.FullLine) { LoadingCards(state.videos.isEmpty(), compact = compact) }
        if (state.error != null) item(span = StaggeredGridItemSpan.FullLine) {
            ErrorCard(state.error, { model.loadFeed(next = state.videos.isNotEmpty()) }) {
                openExternal(context, if (model.query.isBlank()) "https://www.bilibili.com" else "https://search.bilibili.com/all?keyword=${Uri.encode(model.query)}")
            }
        }
        if (!state.loading && state.error == null) item(span = StaggeredGridItemSpan.FullLine) {
            if (state.videos.isEmpty()) EmptyState(Icons.Rounded.SearchOff, "还没有找到相关视频", "换一个关键词再试试看")
            else if (!state.more) Text("已经看到这里的全部内容", Modifier.fillMaxWidth().padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
    SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp))
    }
}

fun videoFeedTitle(model: MainViewModel) = if (model.query.isNotBlank()) "搜索结果" else if (model.popularMode) "全站热门" else "为你推荐"
fun videoFeedSubtitle(model: MainViewModel) = if (model.query.isNotBlank()) "“${model.query}”" else if (model.popularMode) "此刻热门 · 独立于账号推荐" else if (model.appAuthorized) "${model.account?.name ?: "此账号"} 的安卓 App 推荐" else "安卓 App 游客推荐 · 登录关联你的兴趣"

@Composable fun Cover(url: String, title: String, modifier: Modifier = Modifier, crossfade: Boolean = true) {
    val context = LocalContext.current
    val request = remember(context, url, crossfade) { ImageRequest.Builder(context).data(url).addHeader("Referer", "https://www.bilibili.com/").crossfade(if (crossfade) 220 else 0).build() }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        InterfaceIcon(Icons.Rounded.PlayCircleOutline, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f))
        AsyncImage(model = request,
            contentDescription = title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable fun VideoCard(video: Video, onClick: () -> Unit, compact: Boolean = false, onDislike: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val watchLater = LocalWatchLaterAction.current
    val transition = LocalCoverTransition.current
    val anchor = remember(video.id) { CoverAnchor() }
    val coverCoordinates = remember(video.id) { arrayOfNulls<LayoutCoordinates>(1) }
    var menu by remember(video.id) { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.padding(horizontal = if (compact) 0.dp else 20.dp).fillMaxWidth().pressScale(interactionSource).clip(RoundedCornerShape(22.dp)).combinedClickable(
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            onClick = { anchor.bounds = coverCoordinates[0]?.takeIf { it.isAttached }?.boundsInRoot(); transition?.capture(video, anchor.bounds); onClick() },
            onLongClick = { menu = true }
        )) {
        Column {
            Box(Modifier.graphicsLayer { alpha = if (transition?.flight?.let { it.video.id == video.id && it.from == anchor.bounds } == true) 0f else 1f }) {
                Cover(video.cover, video.title, Modifier.fillMaxWidth().aspectRatio(16f / 9f).onPlaced { coverCoordinates[0] = it })
                if (compact) Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(30.dp)) { VideoAuthorAvatar(video, Modifier.fillMaxSize()) }
                Surface(color = Color.Black.copy(alpha = .68f), shape = RoundedCornerShape(7.dp), modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)) {
                    Text(video.duration, color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                }
            }
            Row(Modifier.padding(if (compact) 8.dp else 14.dp), verticalAlignment = Alignment.Top) {
                if (!compact) Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(36.dp)) {
                    VideoAuthorAvatar(video, Modifier.fillMaxSize())
                }
                Column(Modifier.weight(1f).padding(start = if (compact) 0.dp else 11.dp)) {
                    Text(video.title, maxLines = if (compact) 4 else 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(7.dp))
                    Text(remember(video.author, video.views) { "${video.author}  ·  ${count(video.views)} 次观看" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    watchLater?.let { add -> DropdownMenuItem(text = { Text("稍后再看") }, onClick = { menu = false; add(video) }) }
                    if (onDislike != null) DropdownMenuItem(text = { Text("不感兴趣") }, onClick = { menu = false; onDislike() })
                }
            }
        }
    }
}

@Composable fun LoadingCards(first: Boolean, compact: Boolean = false) {
    if (!first) { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp) }; return }
    val transition = rememberInfiniteTransition(label = "loading")
    val alpha by transition.animateFloat(.3f, .7f, infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "pulse")
    Row(Modifier.fillMaxWidth().padding(horizontal = if (compact) 0.dp else 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(if (compact) 2 else 1) { column ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(if (compact) 4 else 2) { index ->
                    Column(Modifier.clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).graphicsLayer { this.alpha = alpha }.background(MaterialTheme.colorScheme.surfaceContainerHighest))
                        Box(Modifier.padding(if (compact) 8.dp else 16.dp).fillMaxWidth(.8f).height(if (compact) (if ((column + index) % 2 == 0) 40.dp else 60.dp) else 18.dp).clip(if (compact) RoundedCornerShape(6.dp) else CircleShape).graphicsLayer { this.alpha = alpha }.background(MaterialTheme.colorScheme.surfaceContainerHighest))
                    }
                }
            }
        }
    }
}

@Composable fun ErrorCard(message: String, retry: () -> Unit, official: (() -> Unit)? = null) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(20.dp)) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            InterfaceIcon(Icons.Rounded.CloudOff, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp)); Text(message, style = MaterialTheme.typography.bodyMedium)
            Row { TextButton(onClick = retry) { Text("重试") }; if (official != null) TextButton(onClick = official) { Text("官方页面") } }
        }
    }
}

@Composable fun EmptyState(icon: ImageVector, title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(80.dp)) {
            Box(contentAlignment = Alignment.Center) { InterfaceIcon(icon, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer) }
        }
        Spacer(Modifier.height(24.dp)); Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp)); Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun ProfileScreen(model: MainViewModel, library: (String) -> Unit, settings: () -> Unit, login: () -> Unit) {
    var confirmLogout by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 128.dp)) {
        Spacer(Modifier.height(20.dp))
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(26.dp)) {
                if (model.account != null) AsyncImage(model.account!!.avatar, "账号头像", Modifier.size(54.dp).clip(CircleShape))
                else InterfaceIcon(Icons.Rounded.AccountCircle, null, Modifier.size(50.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.height(18.dp))
                Text(model.account?.name ?: "你好，探索者", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.height(8.dp))
                Text("每一份热爱，都值得收藏。", color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.height(20.dp))
                Text("${model.favorites.size} 个收藏     ${model.history.size} 条足迹", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.height(14.dp))
                if (!model.appAuthorized) Button(onClick = login) { Text(if (model.account == null) "登录哔哩哔哩" else "完成 App 推荐授权") }
                else Text("App 授权已连接 · 首页使用账号推荐", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Spacer(Modifier.height(24.dp))
        MenuRow(Icons.Rounded.BookmarkBorder, "我的收藏", "保存在此设备", { library("我的收藏") })
        MenuRow(Icons.Rounded.WatchLater, "稍后再看", "账号同步 · 播放列表", { if (model.account == null) login() else library("稍后再看") })
        MenuRow(Icons.Rounded.Folder, "账号收藏夹", "查看账号创建的收藏夹", { if (model.account == null) login() else library("账号收藏夹") })
        MenuRow(Icons.Rounded.ThumbUp, "已点赞的视频", "查看账号点赞记录", { if (model.account == null) login() else library("已点赞的视频") })
        MenuRow(Icons.Rounded.Paid, "已投币的视频", "查看账号投币记录", { if (model.account == null) login() else library("已投币的视频") })
        MenuRow(Icons.Rounded.History, "观看历史", "最近浏览的 100 个视频", { library("观看历史") })
        MenuRow(Icons.Rounded.Palette, "外观与主题", if (model.theme == "white") "纯白主题" else "跟随系统 · 原生取色", settings)
        if (model.account != null) MenuRow(Icons.Rounded.Logout, "退出登录", "移除此设备上的账号会话", { confirmLogout = true })
        model.accountError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
        Spacer(Modifier.height(24.dp))
        Text("BiliFlow", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("轻盈、专注，让内容回到中心。\n非官方客户端 · ${BuildConfig.VERSION_NAME}\n本地收藏与账号收藏分开保存。", modifier = Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text("退出此账号？") }, text = { Text("本地收藏和历史会保留。") },
        confirmButton = { TextButton(onClick = { model.logout(); confirmLogout = false }) { Text("退出登录") } }, dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } })
}

@Composable fun MenuRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(onClick = onClick, interactionSource = interactionSource, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).pressScale(interactionSource, 0.985f)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            InterfaceIcon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(title, fontWeight = FontWeight.Medium); Spacer(Modifier.height(3.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            InterfaceIcon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable fun LibraryScreen(title: String, model: MainViewModel, open: (Video) -> Unit) {
    val videos = if (title == "我的收藏") model.favorites else model.history
    var confirm by remember { mutableStateOf(false) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        if (title == "观看历史" && videos.isNotEmpty()) item { TextButton(onClick = { confirm = true }, modifier = Modifier.padding(horizontal = 20.dp)) { Text("清空观看历史") } }
        if (videos.isEmpty()) item { EmptyState(if (title == "我的收藏") Icons.Rounded.BookmarkBorder else Icons.Rounded.History,
            "这里，等待你的第一份喜欢", if (title == "我的收藏") "打开视频，点击收藏即可保存在这里" else "浏览过的视频会出现在这里") }
        items(videos, key = { it.id }) { VideoCard(it, { open(it) }, modifier = Modifier.animateItem()) }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("清空观看历史？") }, text = { Text("将删除此设备上的所有观看记录。") },
        confirmButton = { TextButton(onClick = { model.clearHistory(); confirm = false }) { Text("清空") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
}

fun openExternal(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { android.widget.Toast.makeText(context, "没有可用的浏览器", android.widget.Toast.LENGTH_SHORT).show() }
}
