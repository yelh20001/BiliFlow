package app.biliflow

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class VideoNavigationTest {
    private fun video(id: String) = Video(id, id, "https://example.com/$id.jpg", "UP")
    @Test fun relatedNavigationReturnsToPreviousVideoAndSerializesItsRouteKey() {
        val stack = VideoBackStack(); stack.open(video("A")); val key = stack.current!!.key
        stack.push(video("B")); stack.push(video("C"))
        val restored = VideoBackStack.decode(stack.encode()); restored.pop(); assertEquals("B", restored.current!!.video.id)
        restored.pop(); assertEquals("A", restored.current!!.video.id); assertEquals(key, restored.current!!.key)
        restored.clear(); assertNull(restored.current)
    }
    @Test fun repeatedTapDoesNotDuplicateCurrentVideoButRevisitingEarlierVideoIsValid() {
        val stack = VideoBackStack(); stack.open(video("A")); assertFalse(stack.push(video("A")))
        stack.push(video("B")); stack.push(video("A")); assertEquals(3, stack.entries.size)
        assertNotEquals(stack.entries.first().key, stack.entries.last().key)
    }
    @Test fun coverFlightAcceptsOnlyMatchingDestinationAndOldAnimationCannotClearNewFlight() {
        val motion = CoverTransitionState(); val a = video("A"); val b = video("B")
        motion.capture(a, Rect(20f, 400f, 700f, 780f)); motion.start(a); val key = motion.flight!!.key
        motion.reportPlayer("B", Rect(0f, 192f, 720f, 597f)); assertNull(motion.destination)
        motion.reportPlayer("A", Rect(0f, 192f, 720f, 597f)); assertNotNull(motion.destination)
        motion.capture(b, Rect(20f, 900f, 700f, 1280f)); motion.start(b); motion.finish(key)
        assertEquals("B", motion.flight!!.video.id)
        motion.cancel(); assertNull(motion.flight)
    }
    @Test fun navigationAndMetadataCachesStayBounded() {
        val stack = VideoBackStack(); repeat(100) { stack.push(video("$it")) }; assertEquals(32, stack.entries.size)
        val cache = VideoSceneCache(); repeat(20) { cache.put("$it", VideoScene(fans = it.toLong())) }
        assertNull(cache.get("0").fans); assertEquals(19L, cache.get("19").fans)
    }
}
