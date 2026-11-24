package app.biliflow

import android.os.Build
import android.view.RoundedCorner
import android.view.ViewTreeObserver
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView

data class DisplayCorners(val topLeft: Float = 0f, val topRight: Float = 0f, val bottomRight: Float = 0f, val bottomLeft: Float = 0f) {
    fun compensated(scale: Float): DisplayCorners {
        val factor = scale.coerceAtLeast(.1f)
        return DisplayCorners(topLeft / factor, topRight / factor, bottomRight / factor, bottomLeft / factor)
    }
}

/** Physical pixels, not a guessed dp radius; observe layout without replacing Compose's inset listener. */
@Composable fun rememberDisplayCorners(): DisplayCorners {
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    var corners by remember(view) { mutableStateOf(DisplayCorners()) }
    DisposableEffect(view, configuration.orientation, configuration.densityDpi) {
        fun update() {
            if (Build.VERSION.SDK_INT >= 31) {
                val insets = view.rootWindowInsets ?: return
                fun radius(position: Int) = insets.getRoundedCorner(position)?.radius?.toFloat() ?: 0f
                corners = DisplayCorners(radius(RoundedCorner.POSITION_TOP_LEFT), radius(RoundedCorner.POSITION_TOP_RIGHT),
                    radius(RoundedCorner.POSITION_BOTTOM_RIGHT), radius(RoundedCorner.POSITION_BOTTOM_LEFT))
            }
        }
        val listener = ViewTreeObserver.OnGlobalLayoutListener { update() }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        view.post { update() }
        onDispose { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return corners
}
