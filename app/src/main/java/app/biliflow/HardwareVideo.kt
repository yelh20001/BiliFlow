package app.biliflow

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import org.json.JSONObject

/** Use the player's profile checks and safe Dolby base-layer fallback rules. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class HardwareVideo {
    fun supports(rep: JSONObject): Boolean = supports(rep, nativeOnly = false)
    fun supportsNative(rep: JSONObject): Boolean = supports(rep, nativeOnly = true)
    private fun supports(rep: JSONObject, nativeOnly: Boolean): Boolean = runCatching {
        val codec = rep.optString("codecs")
        val mime = MimeTypes.getMediaMimeType(codec) ?: when (rep.optInt("codecid")) {
            7 -> MimeTypes.VIDEO_H264; 12 -> MimeTypes.VIDEO_H265; 13 -> MimeTypes.VIDEO_AV1; else -> return false
        }
        if (nativeOnly && rep.optInt("id") == 126 && mime != MimeTypes.VIDEO_DOLBY_VISION) return false
        val width = rep.optInt("width"); val height = rep.optInt("height")
        if (width <= 0 || height <= 0) return false
        val rate = rep.optString("frameRate").ifBlank { rep.optString("frame_rate", "30") }.split('/')
        val fps = ((rate[0].toDoubleOrNull() ?: 30.0) / (rate.getOrNull(1)?.toDoubleOrNull() ?: 1.0)).takeIf { it.isFinite() && it > 0 } ?: 30.0
        val format = Format.Builder().setSampleMimeType(mime).setCodecs(codec).setWidth(width).setHeight(height).setFrameRate(fps.toFloat()).build()
        val decoders = if (nativeOnly) MediaCodecUtil.getDecoderInfos(mime, false, false)
            else MediaCodecUtil.getDecoderInfosSoftMatch(MediaCodecSelector.DEFAULT, format, false, false)
        decoders.any { it.hardwareAccelerated && it.isFormatSupported(format) }
    }.getOrDefault(false)
}
