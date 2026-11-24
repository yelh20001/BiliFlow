package app.biliflow

import org.junit.Assert.*
import org.junit.Test

class AdaptiveLayoutTest {
    @Test fun phoneRespectsUserPreferenceWhileTabletExpandsToAvailableWidth() {
        assertEquals(1, feedColumns(411, false))
        assertEquals(2, feedColumns(411, true))
        assertEquals(2, feedColumns(600, false))
        assertEquals(3, feedColumns(900, false))
        assertEquals(4, feedColumns(1280, true))
        assertEquals(6, feedColumns(2400, false))
    }
    @Test fun detailUsesTwoPanesOnlyWhenBothWidthAndOrientationAllowIt() {
        assertFalse(useSideBySidePlayer(411, 891))
        assertFalse(useSideBySidePlayer(800, 1280))
        assertFalse(useSideBySidePlayer(800, 600))
        assertTrue(useSideBySidePlayer(1280, 800))
        assertFalse(useSideBySidePlayer(1280, 1400))
    }
}
