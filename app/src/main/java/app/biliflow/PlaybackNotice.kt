package app.biliflow

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import dev.chrisbanes.haze.*

/** A stable, high-contrast notice palette, independent of wallpaper and theme. */
@Composable fun PlaybackNoticeHost(state: SnackbarHostState, haze: HazeState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier.padding(horizontal = 20.dp).padding(bottom = 36.dp).widthIn(max = 520.dp)) { data ->
        val shape = RoundedCornerShape(24.dp)
        Surface(shape = shape, color = Color.Transparent, border = BorderStroke(.5.dp, Color.White.copy(alpha = .12f)),
            modifier = Modifier.clip(shape).hazeEffect(haze, HazeStyle(backgroundColor = Color(0xFF26313B),
                tint = HazeTint(Color(0xFF26313B).copy(alpha = .84f)), blurRadius = 24.dp, noiseFactor = 0f,
                fallbackTint = HazeTint(Color(0xFF26313B).copy(alpha = .97f)))).testTag("playback_notice")) {
            Snackbar(data, containerColor = Color.Transparent, contentColor = Color.White, shape = shape,
                actionColor = Color(0xFFB8DCFF), dismissActionContentColor = Color.White)
        }
    }
}
