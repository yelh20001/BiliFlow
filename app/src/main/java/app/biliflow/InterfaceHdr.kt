package app.biliflow

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Gainmap
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val LocalInterfaceHdr = staticCompositionLocalOf { false }

/** Sheets and dialogs own a separate Window from the activity. */
@Composable private fun SyncInterfaceHdrDialog() {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window
    val hdr = LocalInterfaceHdr.current && Build.VERSION.SDK_INT >= 34 &&
        view.display?.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true
    SideEffect {
        window?.let {
            it.colorMode = if (hdr) android.content.pm.ActivityInfo.COLOR_MODE_HDR else android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT
            if (Build.VERSION.SDK_INT >= 35) it.setDesiredHdrHeadroom(if (hdr) 4f else 1f)
        }
    }
}

@androidx.annotation.RequiresApi(34)
private fun Bitmap.withInterfaceGainmap(): Bitmap = apply {
    val gain = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
    gainmap = Gainmap(gain).apply {
        setRatioMin(1f, 1f, 1f); setRatioMax(4f, 4f, 4f)
        setGamma(1f, 1f, 1f); setEpsilonSdr(0f, 0f, 0f); setEpsilonHdr(0f, 0f, 0f)
        setDisplayRatioForFullHdr(4f); setMinDisplayRatioForHdrTransition(1f)
    }
}

/** Keep vector geometry, theme tint and accessibility; only add HDR luminance. */
@Composable fun InterfaceIcon(imageVector: ImageVector, contentDescription: String?, modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current, hdr: Boolean = LocalInterfaceHdr.current) {
    SyncInterfaceHdrDialog()
    if (!hdr || Build.VERSION.SDK_INT < 34 || tint == Color.Unspecified || tint.alpha < .9f) {
        Icon(imageVector, contentDescription, modifier, tint)
        return
    }
    val painter = rememberVectorPainter(imageVector)
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val width = with(density) { imageVector.defaultWidth.roundToPx().coerceAtLeast(1) }
    val height = with(density) { imageVector.defaultHeight.roundToPx().coerceAtLeast(1) }
    val bitmap = remember(imageVector, painter, density, direction, tint, width, height) {
        val base = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(density, direction, ComposeCanvas(base.asImageBitmap()), Size(width.toFloat(), height.toFloat())) {
            with(painter) { draw(size, colorFilter = ColorFilter.tint(tint)) }
        }
        base.withInterfaceGainmap().asImageBitmap()
    }
    Image(bitmap, contentDescription, modifier.size(imageVector.defaultWidth, imageVector.defaultHeight))
}

/** Short control labels retain Compose shaping, font scaling and theme colors. */
@Composable fun InterfaceLabel(text: String, modifier: Modifier = Modifier, color: Color = LocalContentColor.current,
    fontSize: TextUnit = TextUnit.Unspecified, fontWeight: FontWeight? = null, lineHeight: TextUnit = TextUnit.Unspecified,
    hdr: Boolean = LocalInterfaceHdr.current) {
    SyncInterfaceHdrDialog()
    if (!hdr || Build.VERSION.SDK_INT < 34 || color.alpha < .9f) {
        Text(text, modifier, color, fontSize = fontSize, fontWeight = fontWeight, lineHeight = lineHeight)
        return
    }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val style = LocalTextStyle.current.merge(TextStyle(color = color, fontSize = fontSize, fontWeight = fontWeight, lineHeight = lineHeight))
    val measurer = rememberTextMeasurer()
    val layout = measurer.measure(AnnotatedString(text), style, maxLines = 1, overflow = TextOverflow.Ellipsis)
    val bitmap = remember(layout, density, direction) {
        val base = Bitmap.createBitmap(layout.size.width.coerceAtLeast(1), layout.size.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(density, direction, ComposeCanvas(base.asImageBitmap()), Size(base.width.toFloat(), base.height.toFloat())) { drawText(layout) }
        base.withInterfaceGainmap().asImageBitmap()
    }
    Image(bitmap, text, modifier.size(with(density) { layout.size.width.toDp() }, with(density) { layout.size.height.toDp() }))
}

/** A real gain-map highlight, with the platform providing SDR fallback. */
@Composable fun InterfaceTitle(text: String, hdr: Boolean) {
    if (!hdr || Build.VERSION.SDK_INT < 34) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 23.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    val density = LocalDensity.current
    val color = MaterialTheme.colorScheme.primary.toArgb()
    BoxWithConstraints(Modifier.fillMaxWidth().height(32.dp)) {
        val width = with(density) { maxWidth.roundToPx().coerceIn(1, 4096) }
        val height = with(density) { 32.dp.roundToPx().coerceAtLeast(1) }
        val textSize = with(density) { 23.sp.toPx() }
        val bitmap = remember(text, color, width, height, textSize) {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                val paint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color; this.textSize = textSize; typeface = Typeface.DEFAULT_BOLD
                }
                val label = android.text.TextUtils.ellipsize(text, paint, width.toFloat(), android.text.TextUtils.TruncateAt.END).toString()
                Canvas(this).drawText(label, 0f, (height - paint.fontMetrics.ascent - paint.fontMetrics.descent) / 2f, paint)
            }.withInterfaceGainmap().asImageBitmap()
        }
        Image(bitmap, text, Modifier.fillMaxWidth().height(32.dp))
    }
}
