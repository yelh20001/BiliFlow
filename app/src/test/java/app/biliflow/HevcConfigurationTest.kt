package app.biliflow

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.HevcConfig
import org.junit.Assert.*
import org.junit.Test

/** Real hvcC metadata only; fixtures contain no media samples or signed URLs. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class HevcConfigurationTest {
    private fun parse(quality: Int): HevcConfig {
        val bytes = javaClass.getResourceAsStream("/hevc-bv1aQn96fEan-$quality.bin")!!.use { it.readBytes() }
        return HevcConfig.parse(ParsableByteArray(bytes))
    }
    @Test fun dolbySourcePreservesMain10HlgColorInformation() {
        val config = parse(126)
        assertEquals(1080, config.width); assertEquals(1920, config.height)
        assertEquals(10, config.bitdepthLuma); assertEquals(10, config.bitdepthChroma)
        assertEquals(C.COLOR_TRANSFER_HLG, config.colorTransfer)
        assertFalse(config.initializationData.isEmpty())
    }
    @Test fun ordinaryHevcWith3dSeiAndNoVpsLayerListDoesNotCrash() {
        val config = parse(80)
        assertEquals(1080, config.width); assertEquals(1920, config.height)
        assertEquals(8, config.bitdepthLuma)
        assertEquals(Format.NO_VALUE, config.stereoMode)
        assertFalse(config.initializationData.isEmpty())
    }
}
