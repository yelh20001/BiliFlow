package app.biliflow

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HdrPlaybackTest {
    private fun rep(id: Int, codec: String) = JSONObject().put("id", id).put("codecs", codec)
    @Test fun reportedVideoBv1aQn96fEanUsesHevcMain10AndCanPlayOnAnHdrScreen() {
        // Sanitized live API response: quality 126, HEVC Main10, 1080x1920@30.
        val actual = rep(126, "hvc1.2.4.L120.90").put("codecid", 12).put("width", 1080).put("height", 1920)
        assertTrue(canPlayHdrRepresentation(actual, true, { false }, { it.optString("codecs").startsWith("hvc1.2.") }))
        val data = JSONObject().put("quality", 126).put("dash", JSONObject().put("video", org.json.JSONArray().put(actual)))
        val manifest = StreamManifest.build(filterHdrStreams(data) { canPlayHdrRepresentation(it, true, { false }, { true }) }, 126)!!
        assertTrue(manifest.contains("hvc1.2.4.L120.90"))
        assertTrue(manifest.contains("video-126-12"))
    }
    @Test fun dolbyCompatibleBaseLayerIsNotBlockedByMissingDolbyScreenBranding() {
        assertTrue(canPlayHdrRepresentation(rep(126, "dvhe.08.07"), true, { false }, { true }))
    }
    @Test fun nativeDecoderCanTryDolbyWhenDisplayFlagIsMissing() {
        assertTrue(canPlayHdrRepresentation(rep(126, "dvhe.05.07"), false, { true }, { false }))
    }
    @Test fun nativeDolbyScreenCanLetRendererInspectTheInBandConfiguration() {
        assertTrue(canPlayHdrRepresentation(rep(126, "hvc1.2.4.L120.90"), true, { false }, { false }, dolbyDisplay = true))
        assertFalse(canPlayHdrRepresentation(rep(126, "hvc1.2.4.L120.90"), true, { true }, { true }, sdrOnly = true, dolbyDisplay = true))
    }
    @Test fun incompatibleDolbyProfileIsNotRewrittenToHevc() {
        assertFalse(canPlayHdrRepresentation(rep(126, "dvhe.05.07"), true, { false }, { false }))
        assertFalse(canPlayHdrRepresentation(rep(126, "dvhe.08.07"), false, { false }, { true }))
    }
    @Test fun decoderFailureBlocksHdrButOrdinarySdrRemainsAvailable() {
        assertFalse(canPlayHdrRepresentation(rep(126, "dvhe.08.07"), true, { true }, { true }, true))
        assertTrue(canPlayHdrRepresentation(rep(80, "avc1.640028"), false, { false }, { false }, true))
    }
    @Test fun filteringRetainsOriginalDolbyMetadataAndDoesNotModifyApiResponse() {
        val data = JSONObject("""{"quality":126,"dash":{"video":[{"id":126,"codecs":"dvhe.08.07"},{"id":125,"codecs":"hev1.2.4.L153.B0"},{"id":80,"codecs":"avc1.640028"}]}}""")
        val retained = filterHdrStreams(data) { canPlayHdrRepresentation(it, true, { false }, { true }) }
        val manifest = StreamManifest.build(retained, 126)!!
        assertTrue(manifest.contains("dvhe.08.07"))
        assertTrue(manifest.contains("video-126-"))
        val filtered = filterHdrStreams(data) { it.optInt("id") != 126 }
        assertEquals(2, filtered.getJSONObject("dash").getJSONArray("video").length())
        assertEquals(3, data.getJSONObject("dash").getJSONArray("video").length())
    }
    @Test fun networkAndParsingFailuresDoNotDisableDolbyForTheRestOfTheSession() {
        assertFalse(isVideoDecoderFailure(2000))
        assertFalse(isVideoDecoderFailure(2004))
        assertFalse(isVideoDecoderFailure(3001))
        assertTrue(isVideoDecoderFailure(4001))
        assertTrue(isVideoDecoderFailure(4005))
    }
}
