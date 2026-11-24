package app.biliflow

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Stream metadata without signed URLs or credentials; optional local-history seed for UI tests. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StreamProbeActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = TextView(this).apply { text = "正在检查播放格式" }; setContentView(result)
        lifecycleScope.launch {
            try {
                val id = intent.getStringExtra("bvid") ?: "BV1aQn96fEan"
                val detail = model.api.detail(id)
                if (intent.getBooleanExtra("seedHistory", false)) model.record(detail.video)
                val streams = model.api.streams(id, detail.parts.first().cid, 126)
                val hardware = HardwareVideo()
                val metadata = streams.data.optJSONObject("dash")?.optJSONArray("video")?.objects().orEmpty().map { rep ->
                    JSONObject().apply {
                        listOf("id", "codecid", "codecs", "width", "height", "frameRate", "bandwidth").forEach { key -> if (rep.has(key)) put(key, rep.get(key)) }
                        put("nativeDecoder", hardware.supportsNative(rep)); put("compatibleDecoder", hardware.supports(rep))
                    }
                }
                val report = JSONObject().put("video", id).put("actualQuality", streams.actualQuality).put("representations", JSONArray(metadata))
                    .put("qualities", JSONArray(streams.qualities.map { JSONObject().put("id", it.id).put("available", it.available) }))
                report.put("media3BaseLayers", JSONObject().apply {
                    listOf("dvhe.08.07", "dvhe.05.07").forEach { codec ->
                        val format = androidx.media3.common.Format.Builder().setSampleMimeType("video/dolby-vision").setCodecs(codec).build()
                        put(codec, androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getAlternativeCodecMimeType(format) ?: "none")
                    }
                })
                if (intent.getBooleanExtra("captureConfig", false)) {
                    val configs = JSONArray()
                    streams.data.optJSONObject("dash")?.optJSONArray("video")?.objects().orEmpty()
                        .filter { it.optInt("codecid") == 12 && it.optInt("id") in setOf(126, 80) }.forEach { rep ->
                            val config = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { readHevcConfig(rep) }
                            java.io.File(filesDir, "hevc-${rep.optInt("id")}.bin").writeBytes(config)
                            val parsed = androidx.media3.extractor.HevcConfig.parse(androidx.media3.common.util.ParsableByteArray(config))
                            configs.put(JSONObject().put("quality", rep.optInt("id")).put("width", parsed.width).put("height", parsed.height)
                                .put("bitDepth", parsed.bitdepthLuma).put("transfer", parsed.colorTransfer).put("stereoMode", parsed.stereoMode))
                        }
                    report.put("parsedHevcConfigurations", configs)
                }
                java.io.File(filesDir, "stream-probe.json").writeText(report.toString(2))
                result.text = "播放格式检查完成"
            } catch (e: Exception) { result.text = "播放格式检查失败：${e.javaClass.simpleName}" }
        }
    }
    private fun readHevcConfig(rep: JSONObject): ByteArray {
        val url = rep.optString("baseUrl").ifBlank { rep.optString("base_url") }
        val request = okhttp3.Request.Builder().url(url).header("User-Agent", BiliApi.UA)
            .header("Referer", "https://www.bilibili.com/").header("Range", "bytes=0-65535").build()
        val data = okhttp3.OkHttpClient().newCall(request).execute().use { response ->
            check(response.isSuccessful)
            response.body!!.byteStream().use { input ->
                val bytes = ByteArray(65536); var count = 0
                while (count < bytes.size) { val read = input.read(bytes, count, bytes.size - count); if (read < 0) break; count += read }
                bytes.copyOf(count)
            }
        }
        for (i in 4 until data.size - 4) {
            if (String(data, i, 4, Charsets.US_ASCII) == "hvcC") {
                val size = java.nio.ByteBuffer.wrap(data, i - 4, 4).int
                if (size >= 8 && i - 4 + size <= data.size) return data.copyOfRange(i + 4, i - 4 + size)
            }
        }
        error("Missing HEVC config")
    }
}
