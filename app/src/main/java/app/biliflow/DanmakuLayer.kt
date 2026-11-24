package app.biliflow

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlinx.coroutines.delay

private data class PositionedDanmaku(val value: Danmaku, val lane: Int, val width: Float, val lifetime: Long)

/** ExoPlayer's reported position can update less often than a high-refresh display. */
internal class DanmakuFrameClock {
    private var lastFrame = 0L
    private var smoothMs = 0.0
    fun reset() { lastFrame = 0L }
    fun sample(positionMs: Long, frameNanos: Long, speed: Float): Long {
        if (lastFrame == 0L || frameNanos <= lastFrame) {
            smoothMs = positionMs.toDouble()
        } else {
            smoothMs += (frameNanos - lastFrame) / 1_000_000.0 * speed
            val error = positionMs - smoothMs
            if (kotlin.math.abs(error) > 150.0) smoothMs = positionMs.toDouble()
            else smoothMs += (error * .08).coerceIn(-1.0, 1.0)
        }
        lastFrame = frameNanos
        return smoothMs.toLong().coerceAtLeast(0)
    }
}

/** Fade to transparent before the player changes to the compact layout. */
internal fun miniDanmakuAlpha(progress: Float): Float = 1f - ((progress - .2f) / .55f).coerceIn(0f, 1f)

@Composable fun DanmakuLayer(engine: PlaybackController, modifier: Modifier = Modifier) {
    var clock by remember { mutableLongStateOf(engine.position) }
    val density = LocalDensity.current
    val frameClock = remember(engine) { DanmakuFrameClock() }
    val style = engine.danmakuStyle.normalized()
    val lookback = maxOf(style.scrollingLifetime, style.fixedLifetime)
    val sizePx = with(density) { (if (engine.fullScreen) 20.dp else 16.dp).toPx() } * style.fontScale
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD } }
    val outline = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; this.style = Paint.Style.STROKE; color = android.graphics.Color.BLACK; strokeWidth = 3f } }
    LaunchedEffect(engine.playing, engine.danmakuEnabled, lookback) {
        frameClock.reset(); clock = engine.danmakuPosition()
        if (engine.playing && engine.danmakuEnabled) while (true) {
            val now = engine.danmakuPosition()
            val values = engine.visibleDanmaku
            var low = 0; var high = values.size
            while (low < high) { val mid = (low + high) ushr 1; if (values[mid].progress < now - lookback) low = mid + 1 else high = mid }
            if (low >= values.size || values[low].progress > now) { frameClock.reset(); clock = now; delay(80) }
            // One draw per display vsync: 120 Hz panels must not be throttled to 60 fps.
            else withFrameNanos { frame -> clock = if (engine.isLive) engine.danmakuPosition() else frameClock.sample(engine.danmakuPosition(), frame, engine.player.playbackParameters.speed) }
        }
    }
    LaunchedEffect(engine) { snapshotFlow { engine.position to engine.playing }.collect { (position, playing) -> if (!playing) clock = if (engine.isLive) engine.danmakuPosition() else position } }
    var width by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableFloatStateOf(0f) }
    val values = engine.visibleDanmaku
    val positioned = remember(values, width, height, sizePx, style) {
        val lanes = style.lanes(height, sizePx)
        val nextFree = LongArray(lanes)
        val lastEnds = LongArray(lanes)
        val fixed = LongArray(lanes)
        val bottom = LongArray(lanes)
        paint.textSize = sizePx
        values.mapNotNull { dm ->
            if (!style.allows(dm.mode)) return@mapNotNull null
            val textWidth = paint.measureText(dm.text)
            if (dm.mode in setOf(4, 5)) {
                val occupied = if (dm.mode == 4) bottom else fixed
                val lane = occupied.indices.firstOrNull { occupied[it] <= dm.progress } ?: return@mapNotNull null
                occupied[lane] = dm.progress + style.fixedLifetime
                PositionedDanmaku(dm, lane, textWidth, style.fixedLifetime)
            } else {
                val life = style.scrollingLifetime
                val lane = nextFree.indices.firstOrNull {
                    nextFree[it] <= dm.progress && lastEnds[it] - (life * width / (width + textWidth).coerceAtLeast(1f)).toLong() <= dm.progress
                } ?: return@mapNotNull null
                nextFree[lane] = dm.progress + (life * (textWidth + sizePx) / (width + textWidth).coerceAtLeast(1f)).toLong()
                lastEnds[lane] = dm.progress + life
                PositionedDanmaku(dm, lane, textWidth, life)
            }
        }
    }
    Canvas(modifier.fillMaxSize().onSizeChanged { width = it.width.toFloat(); height = it.height.toFloat() }.testTag(if (engine.isLive) "live_danmaku_overlay_count_${values.size}" else "danmaku_overlay_count_${values.size}")) {
        paint.textSize = sizePx; outline.textSize = sizePx
        paint.alpha = (style.opacity * 255).toInt()
        outline.alpha = paint.alpha; outline.strokeWidth = style.outlineDp.dp.toPx()
        val line = sizePx * 1.5f
        fun lowerBound(time: Long): Int {
            var low = 0; var high = positioned.size
            while (low < high) { val mid = (low + high) ushr 1; if (positioned[mid].value.progress < time) low = mid + 1 else high = mid }
            return low
        }
        for (index in lowerBound(clock - lookback) until lowerBound(clock + 1)) {
            val dm = positioned[index]
            val elapsed = clock - dm.value.progress
            if (elapsed !in 0..dm.lifetime) continue
            val ratio = elapsed.toFloat() / dm.lifetime
            val x = when (dm.value.mode) {
                4, 5 -> (size.width - dm.width) / 2
                6 -> -dm.width + (size.width + dm.width) * ratio
                else -> size.width - (size.width + dm.width) * ratio
            }
            val y = if (dm.value.mode == 4) size.height - sizePx * .4f - dm.lane * line else line * (dm.lane + 1)
            if (y < sizePx || y > size.height - sizePx * .3f) continue
            paint.color = android.graphics.Color.rgb((dm.value.color shr 16) and 255, (dm.value.color shr 8) and 255, dm.value.color and 255)
            paint.alpha = (style.opacity * 255).toInt()
            if (style.outlineDp > 0f) drawContext.canvas.nativeCanvas.drawText(dm.value.text, x, y, outline)
            drawContext.canvas.nativeCanvas.drawText(dm.value.text, x, y, paint)
        }
    }
}
