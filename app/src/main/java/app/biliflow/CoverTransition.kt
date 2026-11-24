package app.biliflow

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import java.util.UUID

data class CoverFlight(val key: String, val video: Video, val from: Rect)
class CoverAnchor { var bounds: Rect? = null }
@Stable class CoverTransitionState {
    private var pending: Pair<String, Rect>? = null
    var flight by mutableStateOf<CoverFlight?>(null); private set
    var destination by mutableStateOf<Rect?>(null); private set
    fun capture(video: Video, rect: Rect?) { pending = rect?.takeIf { it.width > 1 && it.height > 1 }?.let { video.id to it } }
    fun start(video: Video) {
        val source = pending?.takeIf { it.first == video.id }?.second
        pending = null; destination = null
        flight = source?.let { CoverFlight(UUID.randomUUID().toString(), video, it) }
    }
    fun reportPlayer(id: String?, bounds: Rect) {
        if (id == flight?.video?.id && destination == null && bounds.width > 1 && bounds.height > 1) destination = bounds
    }
    fun finish(key: String) { if (flight?.key == key) { flight = null; destination = null } }
    fun cancel() { flight = null; destination = null; pending = null }
}
val LocalCoverTransition = staticCompositionLocalOf<CoverTransitionState?> { null }

/** One cached poster moves between measured rectangles; no video screenshots or extra decoder. */
@Composable fun CoverFlightOverlay(state: CoverTransitionState) {
    val flight = state.flight ?: return
    key(flight.key) {
        val progress = remember { Animatable(0f) }
        val opacity = remember { Animatable(1f) }
        val target = state.destination
        LaunchedEffect(flight.key, target) {
            if (target == null) return@LaunchedEffect
            progress.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) {
                if (BuildConfig.DEBUG) android.util.Log.d("CoverFlight", "${flight.key} phase=${(value * 100).roundToInt()}")
            }
            withFrameNanos { }
            opacity.animateTo(0f, tween(120))
            state.finish(flight.key)
        }
        val to = target ?: flight.from
        val p = progress.value
        fun mix(a: Float, b: Float) = a + (b - a) * p
        val left = mix(flight.from.left, to.left)
        val top = mix(flight.from.top, to.top)
        val width = mix(flight.from.width, to.width)
        val height = mix(flight.from.height, to.height)
        val density = LocalDensity.current
        // Keep the measured destination opaque even while the detail page itself fades in.
        target?.let { bounds ->
            Box(Modifier.offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
                .graphicsLayer { alpha = opacity.value }.background(Color.Black).testTag("cover_target_black"))
        }
        Cover(flight.video.cover, "视频封面过渡", Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(with(density) { width.toDp() }, with(density) { height.toDp() })
            .graphicsLayer { alpha = opacity.value }.clip(RoundedCornerShape(topStart = (22f * (1f - p)).dp, topEnd = (22f * (1f - p)).dp, bottomStart = 0.dp, bottomEnd = 0.dp))
            .testTag("cover_flight_${(p * 100).roundToInt()}")
            .pointerInput(flight.key) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }, crossfade = false)
    }
}
