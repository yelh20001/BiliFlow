package app.biliflow

import org.junit.Assert.*
import org.junit.Test

class DanmakuFrameClockTest {
    @Test fun interpolatesBetweenCoarsePlayerPositionsAndTracksSeek() {
        val clock = DanmakuFrameClock()
        val frames = (0..12).map { i ->
            val elapsed = i * 8_333_333L
            val sampled = (elapsed / 20_000_000L) * 20L
            clock.sample(sampled, 1_000_000_000L + elapsed, 1f)
        }
        assertTrue(frames.zipWithNext().all { (a, b) -> b > a })
        assertTrue(frames.last() in 85..105)
        assertEquals(5_000L, clock.sample(5_000, 1_110_000_000L, 1f))
        clock.reset()
        assertEquals(100L, clock.sample(100, 2_000_000_000L, 1f))
    }

    @Test fun respectsPlaybackSpeed() {
        val clock = DanmakuFrameClock()
        clock.sample(1000, 1_000_000_000L, 2f)
        assertEquals(1020L, clock.sample(1020, 1_010_000_000L, 2f))
    }
}
