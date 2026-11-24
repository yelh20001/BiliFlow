package app.biliflow

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.PictureInPictureUiState
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

open class PlaybackActivity : ComponentActivity() {
    val mainModel: MainViewModel by viewModels()
    private var videoVisible = false
    private var videoBounds = Rect()
    private var pipParams = PictureInPictureParams.Builder().build()
    private var pipHandoff = false
    private var stopped = false
    fun videoPageVisible(value: Boolean) {
        if (videoVisible == value) return
        videoVisible = value
        if (!value) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        syncPlayerWindow()
    }
    fun videoBounds(view: View) {
        if (mainModel.playback.pipPresentation) return
        val rect = Rect()
        if (view.getGlobalVisibleRect(rect) && rect.width() > 0 && rect.height() > 0 && rect != videoBounds) {
            videoBounds = rect
            updatePip()
        }
    }
    fun syncPlayerWindow() {
        if (isDestroyed || isFinishing) return
        val engine = mainModel.playback
        engine.updateOutputDisplay(window.decorView.display)
        val hdrUi = mainModel.interfaceHdr && window.decorView.display?.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true
        window.colorMode = if (hdrUi) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
        if (Build.VERSION.SDK_INT >= 35) window.setDesiredHdrHeadroom(if (hdrUi) 4f else 1f)
        val refresh = if (videoVisible && engine.playing && engine.danmakuEnabled && !engine.pipPresentation && !engine.minimized) 120f else 0f
        window.attributes.let { params ->
            if (params.preferredRefreshRate != refresh) { params.preferredRefreshRate = refresh; window.attributes = params }
        }
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        if (!engine.pipPresentation && videoVisible && engine.fullScreen) {
            requestedOrientation = if (engine.player.videoSize.height > engine.player.videoSize.width) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
        } else if (!engine.pipPresentation) {
            if (videoVisible) requestedOrientation = if (resources.configuration.smallestScreenWidthDp >= 600) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            insets.show(WindowInsetsCompat.Type.systemBars())
        }
        updatePip()
    }
    private fun canPip(): Boolean = videoVisible && mainModel.playback.player.mediaItemCount > 0 && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    private fun mayPip(): Boolean = canPip() && mainModel.pipEnabled && mainModel.playback.player.playWhenReady
    fun updatePip() {
        if (isFinishing || isDestroyed || !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        val engine = mainModel.playback
        val video = engine.player.videoSize
        val ratio = if (video.width > 0 && video.height > 0) (video.width.toFloat() * video.pixelWidthHeightRatio / video.height).coerceIn(.42f, 2.39f) else 16f / 9
        fun action(name: String, title: String, icon: Int, id: Int): RemoteAction {
            val intent = Intent(this, PipActionReceiver::class.java).setAction(name)
            return RemoteAction(Icon.createWithResource(this, icon), title, title,
                PendingIntent.getBroadcast(this, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        val params = PictureInPictureParams.Builder().setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
            .setActions(buildList {
                if (!engine.isLive) add(action("back", "后退 10 秒", android.R.drawable.ic_media_rew, 1))
                add(action("toggle", if (engine.playing) "暂停" else "播放", if (engine.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, 2))
                if (!engine.isLive) add(action("forward", "前进 10 秒", android.R.drawable.ic_media_ff, 3))
            })
        if (videoBounds.width() > 0 && videoBounds.height() > 0) params.setSourceRectHint(videoBounds)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setAutoEnterEnabled(mayPip())
            // Let the system cover the video surface while Compose and PlayerView
            // relayout for the new PiP size.
            params.setSeamlessResizeEnabled(false)
        }
        pipParams = params.build()
        runCatching { setPictureInPictureParams(pipParams) }
    }
    fun enterPip() {
        if (!canPip()) return
        updatePip()
        mainModel.playback.beginPipTransition()
        if (!runCatching { enterPictureInPictureMode(pipParams) }.getOrDefault(false)) {
            mainModel.playback.cancelPipTransition()
            mainModel.playback.notice = "系统未允许画中画，请在系统应用设置中启用"
        }
    }
    override fun onStart() {
        super.onStart()
        stopped = false
        mainModel.playback.systemStateChanged = { syncPlayerWindow() }
    }
    override fun onUserLeaveHint() {
        pipHandoff = mayPip()
        if (pipHandoff) mainModel.playback.beginPipTransition()
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && pipHandoff) enterPip()
    }
    @androidx.annotation.RequiresApi(31)
    override fun onPictureInPictureUiStateChanged(pipState: PictureInPictureUiState) {
        super.onPictureInPictureUiStateChanged(pipState)
        if (Build.VERSION.SDK_INT >= 35 && pipState.isTransitioningToPip) mainModel.playback.beginPipTransition()
    }
    override fun onResume() {
        super.onResume()
        if (!isInPictureInPictureMode && mainModel.playback.pipTransitioning) mainModel.playback.cancelPipTransition()
    }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        mainModel.playback.pictureInPictureChanged(isInPictureInPictureMode)
        if (isInPictureInPictureMode) pipHandoff = false
        syncPlayerWindow()
    }
    override fun onStop() {
        stopped = true
        mainModel.playback.save()
        if (!isInPictureInPictureMode) {
            if (pipHandoff || mayPip()) window.decorView.postDelayed({
                if (stopped && !isDestroyed && !isFinishing && !isInPictureInPictureMode) {
                    mainModel.playback.player.pause(); mainModel.playback.cancelPipTransition()
                }
                pipHandoff = false
            }, 500) else mainModel.playback.player.pause()
        }
        super.onStop()
    }
    override fun dump(prefix: String, fd: java.io.FileDescriptor?, writer: java.io.PrintWriter, args: Array<out String>?) {
        super.dump(prefix, fd, writer, args)
        val engine = mainModel.playback
        writer.println("${prefix}BiliFlowPlayback visible=$videoVisible pipEnabled=${mainModel.pipEnabled} playWhenReady=${engine.player.playWhenReady} pip=${engine.inPip} handoff=$pipHandoff videoSize=${engine.player.videoSize.width}x${engine.player.videoSize.height} pixelRatio=${engine.player.videoSize.pixelWidthHeightRatio} auto=${if (Build.VERSION.SDK_INT >= 33) pipParams.isAutoEnterEnabled else false}")
        writer.println("${prefix}BiliFlowLiveDanmaku connected=${engine.liveDanmaku.connected} received=${engine.liveDanmaku.received}")
        writer.println("${prefix}BiliFlowRefresh requested=${window.attributes.preferredRefreshRate} actual=${window.decorView.display?.refreshRate ?: 0f}")
        writer.println("${prefix}BiliFlowPip transitioning=${engine.pipTransitioning} enters=${engine.pipTransitions}")
        writer.println("${prefix}BiliFlowMini minimized=${engine.minimized} position=${engine.currentPosition()}")
    }
    override fun onDestroy() { mainModel.playback.systemStateChanged = {}; super.onDestroy() }
}

class PipActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val engine = PlaybackRegistry.current ?: return
        when (intent.action) { "toggle" -> engine.toggle(); "back" -> engine.skip(-10_000); "forward" -> engine.skip(10_000) }
    }
}
