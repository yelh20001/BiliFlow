package app.biliflow

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.json.JSONObject

/** Opt-in emulator acceptance. Removes only the fixture added by this run. Never clears the account. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class WatchLaterProbeActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = TextView(this).apply { text = "正在验证稍后再看" }; setContentView(label)
        lifecycleScope.launch {
            val report = JSONObject()
            var addedAid: Long? = null
            try {
                val before = model.api.watchLater()
                report.put("accountListRead", true).put("initialCount", before.size).put("stage", "read")
                if (intent.getBooleanExtra("roundtrip", false)) {
                    val detail = model.api.detail("BV1aQn96fEan")
                    val candidates = listOf(detail.video) + model.api.relatedVideos(detail.video.id)
                    report.put("candidateCount", candidates.size).put("stage", "choose")
                    val fixture = candidates.first { candidate -> before.none { it.video.id == candidate.id } }
                    val fixtureAid = model.api.detail(fixture.id).aid
                    check(before.none { it.aid == fixtureAid })
                    addedAid = fixtureAid
                    report.put("stage", "add").put("fixtureBvid", fixture.id).put("fixtureAid", fixtureAid)
                    model.api.addWatchLater(fixture)
                    report.put("stage", "readBack")
                    val readBack = model.api.watchLater()
                    report.put("addedIds", org.json.JSONArray(readBack.filter { item -> before.none { it.aid == item.aid } }.map { it.video.id }))
                    readBack.first { it.aid == fixtureAid }
                    report.put("addReadBack", true)
                    java.io.File(filesDir, "watch-later-probe.json").writeText(report.toString(2))
                    label.text = "验证连播与小窗状态"
                    val videos = (listOf(fixture) + candidates).distinctBy { it.id }.take(2)
                    val engine = model.playback
                    engine.startQueue(videos, videos[0].id); engine.open(videos[0])
                    suspend fun ready(id: String) = withTimeout(90_000) {
                        while (engine.video?.id != id || engine.duration <= 0 || engine.player.playbackState != androidx.media3.common.Player.STATE_READY) {
                            engine.error?.let { error(it) }; delay(250)
                        }
                    }
                    ready(videos[0].id); engine.minimize()
                    // Test the actual player ENDED callback, rather than calling moveQueue.
                    engine.seek(engine.duration - 500)
                    ready(videos[1].id)
                    report.put("endedAdvancesQueue", engine.queue.index == 1)
                    report.put("automaticNextKeepsMini", engine.minimized)
                    withTimeout(30_000) {
                        while (model.api.watchLater().first { it.aid == fixtureAid }.progress == 0) delay(500)
                    }
                    report.put("playedProgressSync", true)
                    engine.seek(engine.duration - 500); delay(2500)
                    report.put("lastItemDoesNotWrap", engine.queue.index == 1 && engine.video?.id == videos[1].id)
                    engine.restoreFromMini(); engine.updateFullScreen(true); engine.moveQueue(-1)
                    ready(videos[0].id)
                    report.put("manualPreviousKeepsFullscreen", engine.fullScreen)
                    engine.close()
                    model.api.removeWatchLater(fixtureAid)
                    val after = model.api.watchLater()
                    report.put("removeReadBack", after.none { it.aid == fixtureAid })
                    report.put("originalEntriesPreserved", before.map { it.aid }.toSet() == after.map { it.aid }.toSet())
                    addedAid = null
                }
                label.text = "稍后再看验证完成"
            } catch (e: Exception) {
                report.put("failure", e.javaClass.simpleName).put("trace", e.stackTrace.firstOrNull { it.className.contains("WatchLater") }?.lineNumber); label.text = "稍后再看验证失败"
            } finally {
                addedAid?.let { withContext(NonCancellable) { model.api.removeWatchLater(it) } }
                java.io.File(filesDir, "watch-later-probe.json").writeText(report.toString(2))
            }
        }
    }
}
