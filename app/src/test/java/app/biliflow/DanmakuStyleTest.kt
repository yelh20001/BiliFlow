package app.biliflow
import org.junit.Assert.*
import org.junit.Test
class DanmakuStyleTest {
    @Test fun speedAdjustsLifetimeIncludingSlowerThanDefault() {
        assertEquals(16000L, DanmakuStyle(speed = .5f).scrollingLifetime)
        assertEquals(4000L, DanmakuStyle(speed = 2f).scrollingLifetime)
        assertEquals(2000L, DanmakuStyle(speed = 2f).fixedLifetime)
    }
    @Test fun areaAndFontSizeControlLaneCapacity() {
        val regular = DanmakuStyle(area = .5f)
        assertTrue(regular.copy(area = 1f).lanes(400f, 20f) > regular.lanes(400f, 20f))
        assertTrue(regular.lanes(400f, 40f) < regular.lanes(400f, 20f))
        assertEquals(0, regular.lanes(1f, 20f))
    }
    @Test fun invalidSavedValuesAreClampedAndBottomFilterIsIndependent() {
        val style = DanmakuStyle(Float.NaN, -2f, 100f, 0f, 3f, false).normalized()
        assertEquals(1f, style.opacity); assertEquals(0f, style.outlineDp)
        assertEquals(2f, style.fontScale); assertEquals(.5f, style.speed); assertEquals(1f, style.area)
        assertFalse(style.bottomEnabled); assertTrue(DanmakuStyle().bottomEnabled)
        assertFalse(style.allows(4)); assertTrue(style.allows(5)); assertTrue(style.allows(1)); assertTrue(style.allows(6))
        val bottomOnly = DanmakuStyle(scrollingEnabled = false, topEnabled = false)
        assertTrue(bottomOnly.allows(4)); assertFalse(bottomOnly.allows(1)); assertFalse(bottomOnly.allows(5))
    }
}
