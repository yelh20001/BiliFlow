package app.biliflow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect

/** Stable capture bounds; scroll animation changes only the final clip and opacity. */
@Composable fun TopBarGlass(haze: HazeState, captureHeight: Dp, visibleHeightPx: () -> Float,
    opacity: () -> Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(captureHeight)
        .graphicsLayer { alpha = opacity() }
        .drawWithContent {
            val bottom = visibleHeightPx().coerceIn(0f, size.height)
            if (bottom > 0f) clipRect(bottom = bottom) { this@drawWithContent.drawContent() }
        }
        .background(MaterialTheme.colorScheme.surface.copy(alpha = .82f))
        .hazeEffect(haze, glassStyle()) { inputScale = HazeInputScale.None })
}
