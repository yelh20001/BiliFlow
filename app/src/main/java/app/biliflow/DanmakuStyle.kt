package app.biliflow

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

data class DanmakuStyle(val opacity: Float = 1f, val outlineDp: Float = 1.5f, val fontScale: Float = 1f,
    val speed: Float = 1f, val area: Float = .5f, val bottomEnabled: Boolean = true,
    val scrollingEnabled: Boolean = true, val topEnabled: Boolean = true) {
    fun normalized() = copy(opacity = opacity.valid(1f, 0f..1f), outlineDp = outlineDp.valid(1.5f, 0f..4f),
        fontScale = fontScale.valid(1f, .5f..2f), speed = speed.valid(1f, .5f..2f), area = area.valid(.5f, .25f..1f))
    val scrollingLifetime get() = (8000 / normalized().speed).toLong()
    val fixedLifetime get() = (4000 / normalized().speed).toLong()
    fun allows(mode: Int) = when (mode) { 4 -> bottomEnabled; 5 -> topEnabled; else -> scrollingEnabled }
    fun lanes(height: Float, fontPx: Float) = ((height * normalized().area - fontPx * .4f) / (fontPx * 1.5f)).toInt().coerceAtLeast(0)
}
private fun Float.valid(fallback: Float, range: ClosedFloatingPointRange<Float>) = if (isFinite()) coerceIn(range.start, range.endInclusive) else fallback
fun readDanmakuStyle(prefs: SharedPreferences) = DanmakuStyle(prefs.getFloat("dm_opacity", 1f), prefs.getFloat("dm_outline", 1.5f),
    prefs.getFloat("dm_font", 1f), prefs.getFloat("dm_speed", 1f), prefs.getFloat("dm_area", .5f), prefs.getBoolean("dm_bottom", true),
    prefs.getBoolean("dm_scrolling", true), prefs.getBoolean("dm_top", true)).normalized()
fun saveDanmakuStyle(prefs: SharedPreferences, style: DanmakuStyle) { with(style.normalized()) {
    prefs.edit().putFloat("dm_opacity", opacity).putFloat("dm_outline", outlineDp).putFloat("dm_font", fontScale)
        .putFloat("dm_speed", speed).putFloat("dm_area", area).putBoolean("dm_bottom", bottomEnabled)
        .putBoolean("dm_scrolling", scrollingEnabled).putBoolean("dm_top", topEnabled).apply()
} }

@Composable fun DanmakuSettings(model: MainViewModel) {
    val style = model.danmakuStyle
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text("弹幕样式", style = MaterialTheme.typography.titleMedium)
        StyleSlider("透明度", "${(style.opacity * 100).roundToInt()}%（越低越透明）", "dm_opacity", style.opacity, 0f..1f) { model.updateDanmakuStyle(style.copy(opacity = it)) }
        StyleSlider("描边粗细", "${style.outlineDp.roundToInt()} dp", "dm_outline", style.outlineDp, 0f..4f) { model.updateDanmakuStyle(style.copy(outlineDp = it)) }
        StyleSlider("字体大小", "${(style.fontScale * 100).roundToInt()}%", "dm_font", style.fontScale, .5f..2f) { model.updateDanmakuStyle(style.copy(fontScale = it)) }
        StyleSlider("弹幕速度", "${(style.speed * 100).roundToInt()}%", "dm_speed", style.speed, .5f..2f) { model.updateDanmakuStyle(style.copy(speed = it)) }
        StyleSlider("显示区域", "${(style.area * 100).roundToInt()}%", "dm_area", style.area, .25f..1f) { model.updateDanmakuStyle(style.copy(area = it)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("滚动弹幕")
            Switch(style.scrollingEnabled, { model.updateDanmakuStyle(style.copy(scrollingEnabled = it)) }, Modifier.testTag("dm_scrolling"))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("顶部弹幕")
            Switch(style.topEnabled, { model.updateDanmakuStyle(style.copy(topEnabled = it)) }, Modifier.testTag("dm_top"))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("底部弹幕")
            Switch(style.bottomEnabled, { model.updateDanmakuStyle(style.copy(bottomEnabled = it)) }, Modifier.testTag("dm_bottom"))
        }
        Text("三种类型可独立开启；只保留底部弹幕时，关闭滚动和顶部即可。播放器总开关控制全部弹幕。视频与直播共用样式。", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { model.updateDanmakuStyle(DanmakuStyle()) }) { Text("恢复默认弹幕样式") }
    }
}
@Composable private fun StyleSlider(title: String, value: String, tag: String, current: Float, range: ClosedFloatingPointRange<Float>, change: (Float) -> Unit) {
    Text("$title · $value", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
    Slider(current, change, valueRange = range, modifier = Modifier.fillMaxWidth().testTag(tag))
}
