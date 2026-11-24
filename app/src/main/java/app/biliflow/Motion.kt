package app.biliflow

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics

object AppMotion {
    val PageEnter: EnterTransition =
        slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { it / 4 } +
            fadeIn(tween(220, easing = FastOutSlowInEasing))

    val PageExit: ExitTransition =
        slideOutHorizontally(tween(210, easing = FastOutSlowInEasing)) { it / 5 } +
            fadeOut(tween(170, easing = FastOutSlowInEasing))

    val ModalEnter: EnterTransition =
        slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it / 6 } +
            fadeIn(tween(220, easing = FastOutSlowInEasing))

    val ModalExit: ExitTransition =
        slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { it / 8 } +
            fadeOut(tween(160, easing = FastOutSlowInEasing))

    val FastActionEnter: EnterTransition =
        fadeIn(tween(180, easing = FastOutSlowInEasing)) +
            scaleIn(tween(180, easing = FastOutSlowInEasing), initialScale = 0.84f)

    val FastActionExit: ExitTransition =
        fadeOut(tween(140, easing = FastOutSlowInEasing)) +
            scaleOut(tween(140, easing = FastOutSlowInEasing), targetScale = 0.84f)
}

/**
 * Animates a nullable overlay route into and out of the composition while retaining
 * the last non-null route key during its exit animation. Exiting routes clear semantics
 * immediately so automated checks and accessibility focus stay on the active screen.
 */
@Composable
fun <T> AnimatedOverlayRoute(
    target: T?,
    modifier: Modifier = Modifier,
    enter: EnterTransition = AppMotion.PageEnter,
    exit: ExitTransition = AppMotion.PageExit,
    content: @Composable (T) -> Unit
) {
    var retained by remember { mutableStateOf(target) }
    if (target != null) retained = target
    AnimatedVisibility(
        visible = target != null,
        modifier = modifier,
        enter = enter,
        exit = exit
    ) {
        retained?.let { value ->
            Box(if (target != null) Modifier.fillMaxSize() else Modifier.fillMaxSize().clearAndSetSemantics { }) {
                content(value)
            }
        }
    }
}

/** Smoothly spins while [loading] is true, then eases to the next full revolution. */
@Composable
fun SpinningRefreshIcon(
    loading: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified
) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(loading) {
        if (loading) {
            while (true) {
                rotation.animateTo(
                    targetValue = rotation.value + 360f,
                    animationSpec = tween(durationMillis = 820, easing = LinearEasing)
                )
            }
        } else {
            val remainder = rotation.value % 360f
            if (remainder > 1f) {
                val remaining = 360f - remainder
                val duration = ((remaining / 360f) * 380f).toInt().coerceIn(110, 380)
                rotation.animateTo(
                    targetValue = rotation.value + remaining,
                    animationSpec = tween(durationMillis = duration, easing = FastOutSlowInEasing)
                )
            }
            rotation.snapTo(0f)
        }
    }
    val iconModifier = modifier.graphicsLayer { rotationZ = rotation.value }
    if (tint == Color.Unspecified) {
        InterfaceIcon(Icons.Rounded.Refresh, contentDescription, iconModifier)
    } else {
        InterfaceIcon(Icons.Rounded.Refresh, contentDescription, iconModifier, tint = tint)
    }
}

/** Subtle spring scale-down on press for cards and interactive rows. */
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.975f
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 620f),
        label = "press scale"
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
