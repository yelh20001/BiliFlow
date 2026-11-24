package app.biliflow

import android.content.Context
import android.util.Base64
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.geometry.Rect
import androidx.media3.common.*
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackController(context: Context, private val api: BiliApi, private val scope: CoroutineScope,
    private val history: PlaybackHistory, private val preferred: () -> Int, private val qualityChangeNotice: () -> Boolean) {
    private val hardwareVideo = HardwareVideo()
    private val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true).setMediaCodecSelector { mime, secure, tunneled ->
        MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneled).sortedBy { if (it.hardwareAccelerated) 0 else 1 }
    }
    var horizontalSeek by mutableStateOf(true)
    private var lastReportTime = 0L
    val player = ExoPlayer.Builder(context, renderers).setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build().apply {
        setAudioAttributes(AudioAttributes.DEFAULT, true)
        setHandleAudioBecomingNoisy(true)
        setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
    }
    private val mediaSession = MediaSession.Builder(context, player).build()
    private val http = DefaultHttpDataSource.Factory().setUserAgent(BiliApi.UA).setDefaultRequestProperties(mapOf("Referer" to "https://www.bilibili.com/"))
    private val dataSource = DefaultDataSource.Factory(context, http)
    var video by mutableStateOf<Video?>(null); private set
    var queue by mutableStateOf(PlaybackQueue()); private set
    fun startQueue(videos: List<Video>, id: String) { queue = PlaybackQueue.start(videos, id) }
    fun clearQueue() { queue = PlaybackQueue() }
    fun retainQueue(ids: Set<String>) { queue = queue.retain(ids) }
    fun moveQueue(offset: Int) { changeQueue(queue.move(offset)) }
    fun selectQueue(id: String) { changeQueue(queue.select(id)) }
    private fun changeQueue(next: PlaybackQueue) {
        if (next == queue) return
        queue = next
        // Playback continues even while the activity composition is suspended.
        next.current?.let(::open)
    }
    var detail by mutableStateOf<VideoDetail?>(null); private set
    var liveDetail by mutableStateOf<LiveDetail?>(null); private set
    var liveRoomId by mutableStateOf<Long?>(null); private set
    val isLive get() = liveRoomId != null
    val title get() = liveDetail?.title ?: video?.title.orEmpty()
    var part by mutableIntStateOf(0); private set
    var qualities by mutableStateOf<List<Quality>>(emptyList()); private set
    var quality by mutableIntStateOf(0); private set
    var actualQuality by mutableIntStateOf(0); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var position by mutableLongStateOf(0); private set
    var duration by mutableLongStateOf(0); private set
    var playing by mutableStateOf(false); private set
    var buffering by mutableStateOf(false); private set
    var firstFrameReady by mutableStateOf(false); private set
    var hdrSurface by mutableStateOf(false); private set
    var hdrPlaying by mutableStateOf(false); private set
    private var outputDisplay = context.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)
    private val hdrTypes get() = runCatching {
        val display = outputDisplay ?: return@runCatching intArrayOf()
        val current = if (android.os.Build.VERSION.SDK_INT >= 34) display.mode.supportedHdrTypes else intArrayOf()
        current.takeIf { it.isNotEmpty() } ?: display.hdrCapabilities.supportedHdrTypes
    }.getOrDefault(intArrayOf())
    val hdrSupported get() = hdrTypes.isNotEmpty()
    val dolbyVisionSupported get() = android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in hdrTypes
    fun updateOutputDisplay(display: android.view.Display?) { if (display != null) outputDisplay = display }
    var fullScreen by mutableStateOf(false); private set
    var minimized by mutableStateOf(false); private set
    var miniDragProgress by mutableFloatStateOf(0f); private set
    var miniTarget by mutableStateOf(Rect.Zero)
    var videoAspectRatio by mutableFloatStateOf(16f / 9f); private set
    var miniPositionX by mutableFloatStateOf(1f); private set
    var miniPositionY by mutableFloatStateOf(1f); private set
    var miniMovementBounds by mutableStateOf(Rect.Zero)
    var miniCanDrag by mutableStateOf(false)
    var miniDraggingEnabled by mutableStateOf(false)
    var miniRestoring by mutableStateOf(false)
    fun moveMiniBy(dx: Float, dy: Float) {
        if (!minimized || pipPresentation || !miniCanDrag || !miniDraggingEnabled) return
        val rangeX = miniMovementBounds.width
        val rangeY = miniMovementBounds.height
        if (rangeX > 0f) miniPositionX = (miniPositionX + dx / rangeX).coerceIn(0f, 1f)
        if (rangeY > 0f) miniPositionY = (miniPositionY + dy / rangeY).coerceIn(0f, 1f)
    }
    var miniHandoff by mutableStateOf(false); private set
    private var miniDragJob: Job? = null
    var inPip by mutableStateOf(false)
    var pipTransitioning by mutableStateOf(false); private set
    val pipPresentation get() = inPip || pipTransitioning
    var pipTransitions = 0; private set
    var danmakuEnabled by mutableStateOf(true)
    var danmakuStyle by mutableStateOf(DanmakuStyle())
    var danmaku by mutableStateOf<List<Danmaku>>(emptyList()); private set
    var danmakuError by mutableStateOf<String?>(null); private set
    var fans by mutableStateOf<Long?>(null); private set
    var notice by mutableStateOf<String?>(null)
    val liveDanmaku = LiveDanmaku(api, scope)
    val visibleDanmaku get() = if (isLive) liveDanmaku.bullets else danmaku
    fun danmakuPosition(): Long = if (isLive) android.os.SystemClock.elapsedRealtime() else currentPosition()
    private val interactions = InteractionStore(scope, api::interaction) { notice = networkMessage(it) }
    val relation get() = interactions.value
    val relationLoading get() = interactions.reading
    val actionBusy get() = interactions.writing
    var systemStateChanged: () -> Unit = {}
    private var loadJob: Job? = null
    private var extras = mutableListOf<Job>()
    private var epoch = 0
    private var segmentLengths = emptyList<Long>()
    private var saveTime = 0L
    private val dmCache = linkedMapOf<Int, List<Danmaku>>()
    private val dmJobs = mutableMapOf<Int, Job>()
    private var sessionDuration = 0L
    private var liveRecoveries = 0
    private var lastLiveRecovery = 0L
    private var compatibilityCodec = false
    private var usingEfficientCodec = false
    init {
        PlaybackRegistry.current = this
        // DASH manifests may omit ColorInfo. Read the decoded sample format as well.
        player.setVideoFrameMetadataListener { _, _, format, _ ->
            val hdr = format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 || format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG
            if (hdr != hdrPlaying) {
                val generation = epoch
                scope.launch { if (generation == epoch) { hdrPlaying = hdr; systemStateChanged() } }
            }
        }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { playing = value; systemStateChanged(); if (!value) save() }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { updateLiveDanmaku() }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) {
                    save(reportNow = true)
                    if (queue.current?.id == video?.id) {
                        val generation = epoch
                        scope.launch {
                            if (generation == epoch && player.playbackState == Player.STATE_ENDED) {
                                if (part + 1 < (detail?.parts?.size ?: 0)) selectPart(part + 1) else moveQueue(1)
                            }
                        }
                    }
                }
            }
            override fun onPlayerError(failure: PlaybackException) {
                if (isLive && failure.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    val now = System.currentTimeMillis()
                    if (now - lastLiveRecovery > 30_000) liveRecoveries = 0
                    lastLiveRecovery = now
                    if (++liveRecoveries <= 3) { error = null; player.seekToDefaultPosition(); player.prepare(); return }
                }
                if (!isLive && video != null && usingEfficientCodec && !compatibilityCodec &&
                    isVideoDecoderFailure(failure.errorCode)) {
                    compatibilityCodec = true
                    val at = currentPosition(); val resume = player.playWhenReady
                    launchLoad { loadPart(at, resume) }
                    return
                }
                error = "播放失败，请重试或降低清晰度（${failure.errorCodeName}）"
            }
            override fun onTracksChanged(tracks: Tracks) {
                hdrPlaying = false
                tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.forEach { group ->
                    for (index in 0 until group.length) if (group.isTrackSelected(index)) {
                        val format = group.getTrackFormat(index)
                        format.id?.split('-')?.getOrNull(1)?.toIntOrNull()?.let { actualQuality = it }
                        hdrPlaying = format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 || format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG
                    }
                }
                systemStateChanged()
            }
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) {
                    val ratio = size.width * size.pixelWidthHeightRatio / size.height
                    if (ratio.isFinite() && ratio > 0f) videoAspectRatio = ratio
                }
                systemStateChanged()
            }
            override fun onRenderedFirstFrame() { firstFrameReady = true }
        })
        scope.launch {
            snapshotFlow { video?.id to liveRoomId }.collectLatest { (id, live) ->
                if (id != null || live != null) while (isActive) {
                if (player.mediaItemCount > 0) {
                    position = currentPosition()
                    duration = if (sessionDuration > 0) sessionDuration else player.duration.coerceAtLeast(0)
                    if (playing && System.currentTimeMillis() - saveTime >= 5000) save()
                    if (danmakuEnabled && !isLive && !minimized && !pipPresentation) updateDanmaku()
                }
                delay(if (playing) 250 else 1000)
                }
            }
        }
    }
    fun openLive(roomId: Long) {
        if (liveRoomId == roomId && player.mediaItemCount > 0) return
        close(); liveRoomId = roomId; quality = 10000; error = null
        launchLoad {
            val info = api.liveDetail(roomId); liveDetail = info
            require(info.live) { "主播暂未开播" }
            loadLiveStream()
        }
    }
    private suspend fun loadLiveStream() {
        val id = liveDetail?.id ?: liveRoomId ?: return
        val stream = api.liveStream(id, quality)
        qualities = stream.qualities; quality = stream.quality; actualQuality = stream.quality
        val source = HlsMediaSource.Factory(dataSource).createMediaSource(MediaItem.Builder().setUri(stream.url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
        val resume = if (player.mediaItemCount == 0) true else player.playWhenReady
        player.setMediaSource(source); player.prepare(); player.playWhenReady = resume; systemStateChanged()
    }
    fun open(value: Video) {
        if (video?.id == value.id && detail != null) return
        val queued = queue.current?.id == value.id
        val mini = minimized; val full = fullScreen; val pip = inPip; val pipPending = pipTransitioning
        close(clearQueue = !queued, notifySystem = !queued)
        if (queued) { minimized = mini; fullScreen = full; inPip = pip; pipTransitioning = pipPending }
        video = value; quality = preferred(); error = null; fans = null; interactions.reset(value.id)
        launchLoad {
            val info = api.detail(value.id); detail = info
            part = history.latest(value.id)?.part?.coerceIn(0, (info.parts.size - 1).coerceAtLeast(0)) ?: 0
            loadPart(history.get(value.id, info.parts.getOrNull(part)?.cid ?: 0)?.resumePosition() ?: 0)
            extras += scope.launch {
                try { fans = api.followerCount(info.authorId) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
            refreshRelation()
        }
    }
    private fun launchLoad(block: suspend () -> Unit) {
        val generation = ++epoch; loadJob?.cancel(); loading = true; error = null
        loadJob = scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == epoch) error = networkMessage(e) }
            finally { if (generation == epoch) loading = false }
        }
    }
    private suspend fun loadPart(at: Long, resume: Boolean = true) {
        val info = detail ?: return
        val cid = info.parts.getOrNull(part)?.cid ?: error("没有可播放的分集")
        var streams = api.streams(info.video.id, cid, quality)
        fun supported(rep: org.json.JSONObject) = canPlayHdrRepresentation(rep, hdrSupported,
            hardwareVideo::supportsNative, hardwareVideo::supports, compatibilityCodec, dolbyVisionSupported)
        var streamData = filterHdrStreams(streams.data, ::supported)
        fun videos(data: org.json.JSONObject) = data.optJSONObject("dash")?.optJSONArray("video")?.objects().orEmpty()
        val requested = quality
        val requestedBlocked = requested in setOf(125, 126) && videos(streams.data).any { it.optInt("id") == requested } &&
            videos(streamData).none { it.optInt("id") == requested }
        var decoderFallback = false
        if (requestedBlocked || (videos(streams.data).isNotEmpty() && videos(streamData).isEmpty())) {
            // Fetch another source, not another manifest for the same DV-only response.
            quality = if (requested == 126 && hdrSupported && !compatibilityCodec) 125 else 80
            streams = api.streams(info.video.id, cid, quality)
            streamData = filterHdrStreams(streams.data, ::supported)
            if (videos(streams.data).isNotEmpty() && videos(streamData).isEmpty() && quality != 80) {
                quality = 80; streams = api.streams(info.video.id, cid, quality); streamData = filterHdrStreams(streams.data, ::supported)
            }
            decoderFallback = true
        } else if (requested == 126 && !dolbyVisionSupported && videos(streamData).any { it.optInt("id") == 126 && !hardwareVideo.supportsNative(it) }) {
            notice = "杜比视界片源使用基础层兼容播放"
        }
        qualities = streams.qualities.map { incoming -> incoming.copy(available =
            (incoming.available || qualities.any { it.id == incoming.id && it.available }) &&
                when (incoming.id) {
                    125, 126 -> videos(streamData).any { it.optInt("id") == incoming.id } || videos(streams.data).none { it.optInt("id") == incoming.id }
                    else -> true
                }) }
        actualQuality = streams.actualQuality
        if (quality != 0 && streams.qualities.none { it.id == quality && it.available }) {
            quality = streams.actualQuality
            if (qualityChangeNotice()) notice = "已切换至当前账号可用的 ${qualityLabel(quality)}"
        }
        if (decoderFallback) notice = if (compatibilityCodec) "视频解码失败，已切换兼容画质 ${qualityLabel(quality)}"
            else "当前杜比/HDR 片源无可用解码方式，已切换 ${qualityLabel(quality)}"
        sessionDuration = streams.data.optLong("timelength")
        val selectedQuality = quality
        val compatible = compatibilityCodec
        val manifest = withContext(Dispatchers.Default) {
            StreamManifest.build(streamData, selectedQuality, if (compatible) ({ rep -> rep.optInt("codecid") == 7 }) else hardwareVideo::supports)
        }
        hdrSurface = manifest?.let { it.contains("id=\"video-125-") || it.contains("id=\"video-126-") } == true
        usingEfficientCodec = manifest?.let { Regex("codecs=\"(?:hev|hvc|av01|dvhe|dvh1|dvav|dva1|dav1)").containsMatchIn(it) } == true
        if (manifest != null) {
            segmentLengths = emptyList()
            val uri = "data:application/dash+xml;base64," + Base64.encodeToString(manifest.toByteArray(), Base64.NO_WRAP)
            val source = DashMediaSource.Factory(dataSource).createMediaSource(MediaItem.Builder().setUri(uri).setMimeType(MimeTypes.APPLICATION_MPD).build())
            player.setMediaSource(source, at)
        } else {
            val urls = streams.data.optJSONArray("durl")?.objects().orEmpty()
            require(urls.isNotEmpty()) { "暂时无法获取播放地址" }
            segmentLengths = urls.map { it.optLong("length") }
            val sources = urls.map { ProgressiveMediaSource.Factory(dataSource).createMediaSource(MediaItem.fromUri(secureUrl(it.getString("url")))) }
            val (index, local) = segmentPosition(at)
            player.setMediaSources(sources, index, local)
        }
        player.prepare(); player.playWhenReady = resume; position = at; duration = sessionDuration
        systemStateChanged()
    }
    fun selectPart(index: Int) {
        val info = detail ?: return
        if (index !in info.parts.indices || part == index) return
        save(reportNow = true); part = index; firstFrameReady = false; clearDanmaku()
        launchLoad { loadPart(history.get(info.video.id, info.parts[index].cid)?.resumePosition() ?: 0) }
    }
    fun selectQuality(value: Int) {
        if (value == quality) return
        compatibilityCodec = false
        if (isLive) { quality = value; launchLoad { loadLiveStream() }; return }
        val at = currentPosition(); val resume = player.playWhenReady; val previous = quality; save(); quality = value
        launchLoad { try { loadPart(at, resume) } catch (e: Exception) { quality = previous; throw e } }
    }
    fun retry() {
        if (isLive) {
            val id = liveRoomId ?: return
            if (liveDetail == null || liveDetail?.live == false) { liveRoomId = null; openLive(id) } else launchLoad { loadLiveStream() }
            return
        }
        val value = video ?: return
        if (detail == null) { video = null; open(value) } else launchLoad { loadPart(currentPosition()) }
    }
    fun updateFullScreen(value: Boolean) { fullScreen = value; systemStateChanged() }
    fun dragToMini(progress: Float) { miniDragJob?.cancel(); miniDragProgress = progress.coerceIn(0f, .95f) }
    fun finishMiniDrag(commit: Boolean, animationScope: CoroutineScope) {
        miniDragJob?.cancel()
        val start = miniDragProgress
        miniDragJob = animationScope.launch {
            animate(start, if (commit) 1f else 0f, animationSpec = tween(((if (commit) 1f - start else start) * 280).toInt().coerceAtLeast(80), easing = FastOutSlowInEasing)) { value, _ -> miniDragProgress = value }
            if (commit && video != null && !fullScreen && !pipPresentation) {
                // Present the final transformed frame before switching the enclosing layout.
                // Apply the handoff as one snapshot so no frame sees a reset transform
                // while the full-size layout is still active.
                withFrameNanos { }
                if (video != null && !fullScreen && !pipPresentation) {
                    Snapshot.withMutableSnapshot { miniHandoff = true; minimized = true; miniDragProgress = 0f }
                    systemStateChanged()
                } else miniDragProgress = 0f
            }
        }
    }
    fun minimize() { if (video != null && !fullScreen && !pipPresentation) { minimized = true; systemStateChanged() } }
    fun restoreFromMini() { if (minimized) { miniRestoring = true; miniHandoff = false; minimized = false; systemStateChanged() } }
    fun toggle() {
        if (player.isPlaying) player.pause() else {
            if (isLive) { player.seekToDefaultPosition(); if (player.playerError != null) { error = null; player.prepare() } }
            else if (player.playbackState == Player.STATE_ENDED) seek(0)
            player.play()
        }
    }
    fun skip(delta: Long) { if (!isLive) seek(currentPosition() + delta) }
    fun seek(at: Long) {
        val target = at.coerceIn(0, duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        if (segmentLengths.isEmpty()) player.seekTo(target) else { val (index, local) = segmentPosition(target); player.seekTo(index, local) }
        position = target; save(); if (danmakuEnabled) updateDanmaku()
    }
    private fun segmentPosition(at: Long): Pair<Int, Long> {
        var remaining = at
        for (index in segmentLengths.indices) {
            if (remaining < segmentLengths[index] || index == segmentLengths.lastIndex) return index to remaining
            remaining -= segmentLengths[index]
        }
        return 0 to at
    }
    fun currentPosition(): Long = player.currentPosition.coerceAtLeast(0) + segmentLengths.take(player.currentMediaItemIndex.coerceAtLeast(0)).sum()
    fun save(reportNow: Boolean = false) {
        val id = video?.id ?: return
        val cid = detail?.parts?.getOrNull(part)?.cid ?: return
        if (player.mediaItemCount == 0 || loading) return
        val at = currentPosition()
        history.save(PlaybackMark(id, cid, part, at, duration, System.currentTimeMillis()))
        val info = detail
        if (info != null && ((reportNow && at > 0) || (playing && System.currentTimeMillis() - lastReportTime >= 15_000))) {
            lastReportTime = System.currentTimeMillis()
            scope.launch { try { api.reportHistory(info, cid, at) } catch (e: CancellationException) { throw e } catch (_: Exception) { } }
        }
        saveTime = System.currentTimeMillis()
    }
    fun refreshRelation() {
        interactions.refresh()
    }
    fun action(transform: (VideoInteraction) -> VideoInteraction = { it }, block: suspend () -> Unit) {
        interactions.mutate(transform, block)
    }
    private fun updateDanmaku() {
        val cid = detail?.parts?.getOrNull(part)?.cid ?: return
        val current = (position / 360_000).toInt() + 1
        listOf(current, current + 1).forEach { segment ->
            if (dmCache.containsKey(segment) || dmJobs.containsKey(segment)) return@forEach
            dmJobs[segment] = scope.launch {
                try {
                    val values = api.danmaku(cid, segment)
                    if (detail?.parts?.getOrNull(part)?.cid == cid) {
                        dmCache[segment] = values
                        dmCache.keys.filter { it < current - 1 || it > current + 2 }.toList().forEach(dmCache::remove)
                        danmaku = dmCache.values.flatten().sortedBy { it.progress }; danmakuError = null
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { dmCache[segment] = emptyList(); danmakuError = "弹幕暂时未加载，点击弹幕开关可重试" }
                finally { dmJobs.remove(segment) }
            }
        }
    }
    fun updateLiveDanmaku() {
        val room = liveDetail?.id ?: return
        if (isLive && danmakuEnabled && !pipPresentation && player.playWhenReady) liveDanmaku.start(room)
        else liveDanmaku.stop(if (!danmakuEnabled) "弹幕已关闭" else if (inPip) "小窗不显示弹幕" else "播放后连接实时弹幕")
    }
    fun beginPipTransition() { if (!pipPresentation) { pipTransitioning = true; pipTransitions++; updateLiveDanmaku(); systemStateChanged() } }
    fun cancelPipTransition() { pipTransitioning = false; updateLiveDanmaku(); systemStateChanged() }
    fun pictureInPictureChanged(value: Boolean) { inPip = value; pipTransitioning = false; updateLiveDanmaku() }
    fun toggleDanmaku() { danmakuEnabled = !danmakuEnabled; if (isLive) updateLiveDanmaku() else if (danmakuEnabled && danmakuError != null) clearDanmaku(); systemStateChanged() }
    private fun clearDanmaku() { dmJobs.values.forEach { it.cancel() }; dmJobs.clear(); dmCache.clear(); danmaku = emptyList(); danmakuError = null }
    fun close(clearQueue: Boolean = true, notifySystem: Boolean = true) {
        if (clearQueue) clearQueue()
        miniDragJob?.cancel(); miniDragProgress = 0f; miniHandoff = false
        notice = null
        liveDanmaku.stop()
        save(reportNow = true); ++epoch; loadJob?.cancel(); extras.forEach { it.cancel() }; extras.clear(); clearDanmaku()
        player.pause(); player.clearMediaItems(); video = null; detail = null; liveDetail = null; liveRoomId = null; position = 0; duration = 0; sessionDuration = 0
        qualities = emptyList(); hdrSurface = false; hdrPlaying = false; fullScreen = false; minimized = false; loading = false; inPip = false; pipTransitioning = false; firstFrameReady = false; liveRecoveries = 0; compatibilityCodec = false; usingEfficientCodec = false; interactions.reset(null)
        if (notifySystem) systemStateChanged()
    }
    fun release() { liveDanmaku.stop(); save(); loadJob?.cancel(); clearDanmaku(); mediaSession.release(); player.release(); PlaybackRegistry.current = null }
}

object PlaybackRegistry { var current: PlaybackController? = null }
