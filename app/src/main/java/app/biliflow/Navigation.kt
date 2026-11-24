package app.biliflow

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** The previous destination remains composed underneath, including on a cancelled gesture. */
@Composable fun PredictiveBackSurface(enabled: Boolean = true, onBack: () -> Unit, resetKey: Any? = Unit, retainedDestination: Boolean = false, color: Color = MaterialTheme.colorScheme.background, content: @Composable () -> Unit) {
    val progress = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }
    var committed by remember { mutableStateOf(false) }
    LaunchedEffect(resetKey) { if (committed && retainedDestination) { progress.animateTo(0f, tween(160)); exit.snapTo(0f); committed = false } }
    var edge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    val callback by rememberUpdatedState(onBack)
    val displayCorners = rememberDisplayCorners()
    PredictiveBackHandler(enabled) { events ->
        var started = false
        try {
            events.collect { event -> started = true; edge = event.swipeEdge; progress.snapTo(event.progress) }
            if (started && !retainedDestination) {
                committed = true
                // Keep the same playing TextureView until the last exit frame is presented.
                // Do not snap gesture progress to 1, clear the player, or reset its layer on release.
                withContext(NonCancellable) {
                    exit.animateTo(1f, tween(220))
                    withFrameNanos { }; withFrameNanos { }
                    callback()
                }
            } else {
                if (started) committed = true
                callback()
            }
        } catch (cancelled: CancellationException) {
            if (!committed) withContext(NonCancellable) { progress.animateTo(0f, spring(dampingRatio = .85f, stiffness = 600f)) }
            throw cancelled
        }
    }
    Surface(Modifier.fillMaxSize().graphicsLayer {
        val p = progress.value
        val settle = exit.value
        alpha = 1f - settle
        scaleX = (1f - .09f * p) * (1f - .025f * settle); scaleY = scaleX
        translationX = size.width * (.13f * p + (1.05f - .13f * p) * settle) * if (edge == BackEventCompat.EDGE_LEFT) 1 else -1
        val radii = displayCorners.compensated(scaleX)
        shape = AbsoluteRoundedCornerShape(CornerSize(radii.topLeft), CornerSize(radii.topRight), CornerSize(radii.bottomRight), CornerSize(radii.bottomLeft))
        clip = p > 0f
        shadowElevation = 16.dp.toPx() * p * (1f - settle)
    }, color = color) { content() }
}

@Composable fun glassStyle() = HazeStyle(backgroundColor = MaterialTheme.colorScheme.surface,
    tint = HazeTint(MaterialTheme.colorScheme.surface.copy(alpha = .82f)), blurRadius = 24.dp, noiseFactor = 0f,
    fallbackTint = HazeTint(MaterialTheme.colorScheme.surface.copy(alpha = .94f)))

@Composable fun FloatingBottomBar(selected: Int, onSelect: (Int) -> Unit, haze: HazeState, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp).padding(bottom = 12.dp), contentAlignment = Alignment.BottomCenter) {
        Surface(shape = RoundedCornerShape(32.dp), color = Color.Transparent,
            shadowElevation = 0.dp, tonalElevation = 0.dp,
            modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth().clip(RoundedCornerShape(32.dp)).hazeEffect(haze, glassStyle()) { inputScale = dev.chrisbanes.haze.HazeInputScale.None }) {
            BoxWithConstraints(Modifier.height(56.dp).padding(4.dp)) {
                val itemWidth = maxWidth / 4
                val offset by animateDpAsState(itemWidth * selected, spring(dampingRatio = .8f, stiffness = 440f), label = "floating selection")
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(32.dp),
                    modifier = Modifier.offset(x = offset).width(itemWidth).fillMaxHeight()) {}
                Row(Modifier.fillMaxSize()) {
                    Destination.entries.forEachIndexed { index, destination ->
                        val active = selected == index
                        val scale by animateFloatAsState(if (active) 1.08f else 1f, spring(dampingRatio = .65f), label = "tab scale")
                        Column(Modifier.weight(1f).fillMaxHeight().selectable(active, role = Role.Tab,
                            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { onSelect(index) }),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            val color by animateColorAsState(if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, tween(180), label = "tab color")
                            InterfaceIcon(destination.icon, null, Modifier.size(21.dp).graphicsLayer { scaleX = scale; scaleY = scale }, tint = color, hdr = active && LocalInterfaceHdr.current)
                            Spacer(Modifier.height(2.dp))
                            InterfaceLabel(destination.label, fontSize = 11.sp, lineHeight = 13.sp, color = color, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, hdr = active && LocalInterfaceHdr.current)
                        }
                    }
                }
            }
        }
    }
}
