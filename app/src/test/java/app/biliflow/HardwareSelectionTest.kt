package app.biliflow

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HardwareSelectionTest {
    private fun rep(codec: Int, bandwidth: Long) = JSONObject().put("codecid", codec).put("bandwidth", bandwidth)
    @Test fun selectsLowerBitrateAmongFormatsSupportedByActualHardware() {
        val group = listOf(rep(7, 9000), rep(12, 5000), rep(13, 3000))
        assertEquals(13, StreamManifest.chooseVideo(group) { it.optInt("codecid") in setOf(7, 12, 13) }.optInt("codecid"))
        assertEquals(12, StreamManifest.chooseVideo(group) { it.optInt("codecid") in setOf(7, 12) }.optInt("codecid"))
    }
    @Test fun unsupportedEfficientCodecDoesNotForceSoftwareAv1() {
        val group = listOf(rep(13, 2000), rep(7, 9000))
        assertEquals(7, StreamManifest.chooseVideo(group) { false }.optInt("codecid"))
    }
    @Test fun missingBitrateIsNotTreatedAsTheCheapestHardwareStream() {
        val group = listOf(rep(12, 0), rep(7, 9000))
        assertEquals(7, StreamManifest.chooseVideo(group) { true }.optInt("codecid"))
    }
}
