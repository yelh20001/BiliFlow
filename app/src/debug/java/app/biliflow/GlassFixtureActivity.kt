package app.biliflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/** Deterministic input for temporal blur QA; contains no account or network content. */
class GlassFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { BiliTheme("white") {
            MaterialTheme(colorScheme = if (intent.getBooleanExtra("dark", false)) darkColorScheme() else MaterialTheme.colorScheme) {
            val haze = remember { HazeState() }
            var hidden by remember { mutableStateOf(false) }
            var phase by remember { mutableIntStateOf(0) }
            val progress by animateFloatAsState(if (hidden) 1f else 0f, tween(220), label = "fixture glass visibility")
            val heightPx = with(LocalDensity.current) { 128.dp.toPx() }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().hazeSource(haze).background(Color(0xFF183957))) {
                    Canvas(Modifier.fillMaxSize()) {
                        val band = 64.dp.toPx()
                        for (i in 0..(size.width / band).toInt()) {
                            drawRect(if ((i + phase) % 2 == 0) Color(0xFF224A88) else Color(0xFFB45124),
                                Offset(i * band, 0f), Size(band, size.height))
                        }
                    }
                }
                TopBarGlass(haze, 224.dp, { heightPx * (1f - progress) }, { 1f - progress })
                Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp)) {
                    Button(onClick = { hidden = !hidden }) { Text(if (hidden) "显示顶栏" else "隐藏顶栏") }
                    Button(onClick = { phase++ }) { Text("切换底图") }
                }
            }
            }
        } }
    }
}
