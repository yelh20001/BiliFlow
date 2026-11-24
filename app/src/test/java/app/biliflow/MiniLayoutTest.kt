package app.biliflow

import org.junit.Assert.*
import org.junit.Test

class MiniLayoutTest {
    @Test fun portraitSquareAndLandscapeShareTheSameArea() {
        listOf(411f to 891f, 1280f to 800f, 891f to 411f).forEach { (width, height) ->
            val landscape = miniLayout(width, height, 92f, 24f, 16f / 9f)
            listOf(9f / 16f, 1f, 2.4f).forEach { aspect ->
                val mini = miniLayout(width, height, 92f, 24f, aspect)
                assertEquals(landscape.height * landscape.width, mini.width * mini.height, .02f)
                assertEquals(aspect, mini.width / mini.height, .001f)
            }
            assertTrue(landscape.height <= 180f)
        }
    }
    @Test fun ultraWideVideoShrinksOnlyToFitTheAvailableWidth() {
        val mini = miniLayout(320f, 640f, 92f, 24f, 4f)
        assertEquals(4f, mini.width / mini.height, .001f)
        assertTrue(mini.left + mini.width <= 304f)
    }
    @Test fun portraitLandscapeAndSquareKeepVideoAspectAndStayOnScreen() {
        listOf(9f/16f, 16f/9f, 1f, 2.4f, .3f).forEach { aspect ->
            val mini = miniLayout(411f, 891f, 92f, 24f, aspect)
            assertEquals(aspect, mini.width / mini.height, .001f)
            assertTrue(mini.left >= 16f && mini.left + mini.width <= 395f)
            assertTrue(mini.top >= 24f && mini.top + mini.height <= 787f)
            assertTrue(mini.height <= 891f * .45f + .01f)
        }
    }
    @Test fun dragClampsAllEdgesAndNormalizedPositionSurvivesRotation() {
        val topLeft = miniLayout(411f, 891f, 92f, 24f, 9f/16f, -5f, -5f)
        assertEquals(topLeft.minLeft, topLeft.left); assertEquals(topLeft.minTop, topLeft.top)
        val bottomRight = miniLayout(891f, 411f, 92f, 24f, 9f/16f, 5f, 5f)
        assertEquals(bottomRight.maxLeft, bottomRight.left); assertEquals(bottomRight.maxTop, bottomRight.top)
        assertTrue(bottomRight.top + bottomRight.height <= 307f)
        assertTrue(miniLayout(411f, 891f, 92f, 24f, Float.NaN).width.isFinite())
    }
}
