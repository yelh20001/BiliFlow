package app.biliflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniDanmakuTest {
    @Test fun fadesBeforeCompactHandoffAndRestoresOnCancellation() {
        assertEquals(1f, miniDanmakuAlpha(0f), 0f)
        assertEquals(1f, miniDanmakuAlpha(.2f), 0f)
        assertTrue(miniDanmakuAlpha(.5f) in 0f..1f)
        assertEquals(0f, miniDanmakuAlpha(.75f), .00001f)
        assertEquals(0f, miniDanmakuAlpha(1f), 0f)
        val steps = (0..100).map { miniDanmakuAlpha(it / 100f) }
        assertTrue(steps.zipWithNext().all { (a, b) -> a >= b })
    }
}
