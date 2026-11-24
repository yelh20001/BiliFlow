@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.biliflow

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.*
import kotlin.math.abs

class PlayerMotion(private val scope: CoroutineScope) {
    var offset by mutableFloatStateOf(0f); private set
    var dragging by mutableStateOf(false); private set
    private var settle: Job? = null
    fun begin() { settle?.cancel(); dragging = true }
    fun drag(distance: Float, height: Float) { offset = (distance * .55f).coerceIn(-height * .55f, height * .55f) }
    fun release(engine: PlaybackController, target: Boolean? = null) {
        dragging = false
        val from = offset
        settle?.cancel()
        if (target != null) engine.updateFullScreen(target)
        settle = scope.launch {
            animate(from, 0f, animationSpec = spring(dampingRatio = .9f, stiffness = 380f)) { value, _ -> offset = value }
        }
    }
    fun releaseToMini() {
        dragging = false
        val from = offset
        settle?.cancel()
        settle = scope.launch {
            animate(from, 0f, animationSpec = tween(320, easing = FastOutSlowInEasing)) { value, _ -> offset = value }
        }
    }
}

/** The PlayerView stays at one composition location across drag, rotation and PiP. */
@Composable fun PlaybackFrame(engine: PlaybackController, onBack: () -> Unit, quality: () -> Unit,
    active: Boolean = true, poster: Video? = null, toolbar: @Composable () -> Unit, details: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val motion = remember { PlayerMotion(scope) }
    // Keep the source rectangle stable while the system animates into PiP. The
    // system crops this layout using sourceRectHint until the mode change completes.
    val immersive = active && (engine.fullScreen || engine.inPip)
    val pip = active && engine.inPip
    val compact = active && engine.minimized && !pip
    val hideChrome = active && (engine.pipPresentation || engine.minimized)
    val coverTransition = LocalCoverTransition.current
    val topInset by animateDpAsState(if (immersive || compact) 0.dp else WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding(), tween(220), label = "player status inset")
    val bottomInset by animateDpAsState(if (immersive || compact) 0.dp else WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding(), tween(220), label = "player navigation inset")
    BoxWithConstraints(Modifier.fillMaxSize().background(if (compact) Color.Transparent else if (immersive) Color.Black else androidx.compose.material3.MaterialTheme.colorScheme.background.copy(alpha = 1f - engine.miniDragProgress))
        .padding(top = if (pip || compact) 0.dp else topInset, bottom = if (pip || compact) 0.dp else bottomInset)) {
        val animatedToolbar by animateDpAsState(if (immersive || compact) 0.dp else 64.dp, spring(stiffness = 420f), label = "player toolbar")
        val toolbarHeight = if (pip || compact) 0.dp else animatedToolbar
        val wide = !immersive && !compact && useSideBySidePlayer(maxWidth.value.toInt(), maxHeight.value.toInt())
        val playerWidth = if (wide) maxWidth * .62f else maxWidth
        val target = when {
            immersive || compact -> maxHeight
            wide -> (maxHeight - toolbarHeight).coerceAtLeast(0.dp)
            else -> (playerWidth * 9f / 16f).coerceAtMost((maxHeight - if (compact) 0.dp else 64.dp).coerceAtLeast(0.dp))
        }
        val height by animateDpAsState(target, if (compact) tween(320, easing = FastOutSlowInEasing) else spring(dampingRatio = .95f, stiffness = 420f), label = "player viewport")
        val fade = (abs(motion.offset) / 500f).coerceIn(0f, .45f)
        val availableHeight = maxHeight
        val availableWidth = maxWidth
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(toolbarHeight).clipToBounds().graphicsLayer { alpha = if (hideChrome) 0f else (1f - fade) * (1f - engine.miniDragProgress) }) { toolbar() }
            // Both children remain at the same composition location. Hidden details keep
            // their last real viewport, so a zero-sized mini layout cannot reset the pager
            // or clamp a comment list's scroll position to its first item.
            val lastDetailsSize = remember { arrayOf(IntSize(1, 1)) }
            val viewport = (if (pip || compact) availableHeight else height).coerceAtMost((availableHeight - toolbarHeight).coerceAtLeast(0.dp))
            val playerModifier = Modifier.fillMaxSize().zIndex(1f)
                .onGloballyPositioned { if (active && !immersive) coverTransition?.reportPlayer(poster?.id, it.boundsInRoot()) }
            Layout(content = {
                Box { if (active) PlayerSurface(engine, playerModifier, onBack, quality, motion)
                    else poster?.let { Cover(it.cover, it.title, playerModifier, crossfade = false) } }
                Box(Modifier.graphicsLayer { alpha = (1f - fade) * (1f - engine.miniDragProgress) }.clipToBounds()) { details() }
            }, modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds()) { children, constraints ->
                val w = constraints.maxWidth; val h = constraints.maxHeight
                val pw = if (wide) (w * .62f).toInt() else w
                // Fullscreen must use the animated viewport too. Only PiP/mini
                // follow the enclosing window directly during their handoff.
                val ph = if (compact || pip) h else viewport.roundToPx().coerceIn(0, h)
                val hidden = immersive || hideChrome || engine.miniRestoring
                val detailSize = if (hidden) lastDetailsSize[0] else IntSize(if (wide) w - pw else w, if (wide) h else h - ph)
                if (!hidden && detailSize.width > 0 && detailSize.height > 0) lastDetailsSize[0] = detailSize
                val player = children[0].measure(Constraints.fixed(pw, ph))
                val detail = children[1].measure(Constraints.fixed(detailSize.width.coerceAtLeast(1), detailSize.height.coerceAtLeast(1)))
                layout(w, h) {
                    if (!hidden) detail.placeRelative(if (wide) pw else 0, if (wide) 0 else ph)
                    player.placeRelative(0, 0)
                }
            }
        }
    }
}
