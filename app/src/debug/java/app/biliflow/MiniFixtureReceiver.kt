package app.biliflow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import java.io.File

/** Debug-only local video input. Exercises the real app window and player surface. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MiniFixtureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val engine = PlaybackRegistry.current ?: return
        when (intent.getStringExtra("op")) {
            "load" -> {
                val name = if (intent.getBooleanExtra("portrait", false)) "mini-portrait.mp4" else "mini-landscape.mp4"
                engine.player.setMediaItem(MediaItem.fromUri(File(context.getExternalFilesDir(null), name).toURI().toString()))
                engine.player.repeatMode = Player.REPEAT_MODE_ONE
                engine.player.prepare(); engine.player.play()
            }
            "pause" -> engine.player.pause()
        }
    }
}
