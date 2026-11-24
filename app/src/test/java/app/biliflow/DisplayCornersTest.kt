package app.biliflow

import org.junit.Assert.*
import org.junit.Test

class DisplayCornersTest {
    @Test fun scaledCardKeepsEachPhysicalCornerRadius() {
        val physical = DisplayCorners(120f, 110f, 100f, 90f)
        val local = physical.compensated(.91f)
        assertEquals(120f, local.topLeft * .91f, .001f)
        assertEquals(110f, local.topRight * .91f, .001f)
        assertEquals(100f, local.bottomRight * .91f, .001f)
        assertEquals(90f, local.bottomLeft * .91f, .001f)
    }
    @Test fun squareDisplayIsNotGivenAnInventedRoundness() { assertEquals(DisplayCorners(), DisplayCorners().compensated(.91f)) }
}
