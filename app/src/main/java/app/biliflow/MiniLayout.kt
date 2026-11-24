package app.biliflow

import kotlin.math.sqrt

data class MiniLayout(val width: Float, val height: Float, val left: Float, val top: Float,
    val minLeft: Float, val maxLeft: Float, val minTop: Float, val maxTop: Float)

fun miniLayout(screenWidth: Float, screenHeight: Float, bottom: Float, topInset: Float, aspect: Float,
    x: Float = 1f, y: Float = 1f): MiniLayout {
    val ratio = aspect.takeIf { it.isFinite() && it > 0f } ?: (16f / 9f)
    val margin = minOf(16f, screenWidth / 8f)
    val minTop = topInset.coerceIn(0f, screenHeight)
    val availableHeight = (screenHeight - bottom - 12f - minTop).coerceAtLeast(1f)
    val maxWidth = (screenWidth - margin * 2).coerceAtLeast(1f)
    val maxHeight = minOf(screenHeight * .45f, availableHeight)
    val baselineWidth = minOf(screenWidth * .62f, 320f)
    // Equal area for portrait, square and landscape videos. Reserve enough space
    // for either common orientation even on a short landscape viewport.
    val area = minOf(baselineWidth * baselineWidth * 9f / 16f,
        maxHeight * maxHeight * 9f / 16f, maxWidth * maxWidth * 9f / 16f)
    val desiredWidth = sqrt(area * ratio)
    val desiredHeight = sqrt(area / ratio)
    val fit = minOf(1f, maxWidth / desiredWidth, maxHeight / desiredHeight)
    val width = desiredWidth * fit
    val height = desiredHeight * fit
    val maxLeft = (screenWidth - width - margin).coerceAtLeast(margin)
    val maxTop = (screenHeight - bottom - 12f - height).coerceAtLeast(minTop)
    return MiniLayout(width, height, margin + (maxLeft - margin) * x.coerceIn(0f, 1f), minTop + (maxTop - minTop) * y.coerceIn(0f, 1f), margin, maxLeft, minTop, maxTop)
}
