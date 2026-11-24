package app.biliflow

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

class MiniPointerSharingTest {
    private fun change(x: Float, y: Float, pressed: Boolean, px: Float, py: Float, previousPressed: Boolean, consumed: Boolean = false) =
        PointerInputChange(PointerId(1), 32L, Offset(x, y), pressed, 16L, Offset(px, py), previousPressed, consumed)
    private fun send(node: MiniPointerSharingNode, value: PointerInputChange) {
        node.onPointerEvent(PointerEvent(listOf(value)), PointerEventPass.Initial, IntSize(300, 200))
    }
    @Test fun clickBelongsToMiniAndConsumesDownAndUp() {
        var clicks = 0
        val node = MiniPointerSharingNode(true, 10f) { _, _ -> clicks++ }
        val down = change(100f, 100f, true, 100f, 100f, false); send(node, down)
        assertTrue(down.isConsumed)
        val up = change(104f, 102f, false, 100f, 100f, true); send(node, up)
        assertTrue(up.isConsumed); assertEquals(1, clicks)
    }
    @Test fun dragLeavesMovementAndReleaseForUnderlyingScrollable() {
        var clicks = 0
        val node = MiniPointerSharingNode(true, 10f) { _, _ -> clicks++ }
        send(node, change(100f, 100f, true, 100f, 100f, false))
        val move = change(100f, 60f, true, 100f, 100f, true); send(node, move)
        val up = change(100f, 60f, false, 100f, 60f, true); send(node, up)
        assertFalse(move.isConsumed); assertFalse(up.isConsumed); assertEquals(0, clicks)
    }
    @Test fun cancellingOrDisablingNeverTriggersMiniClick() {
        var clicks = 0
        val node = MiniPointerSharingNode(true, 10f) { _, _ -> clicks++ }
        send(node, change(100f, 100f, true, 100f, 100f, false)); node.onCancelPointerInput()
        send(node, change(100f, 100f, false, 100f, 100f, true)); assertEquals(0, clicks)
        node.enabled = false
        val down = change(100f, 100f, true, 100f, 100f, false); send(node, down)
        assertFalse(down.isConsumed); assertFalse(node.sharePointerInputWithSiblings())
    }
    @Test fun returningFingerToStartAfterDragDoesNotBecomeClick() {
        var clicks = 0
        val node = MiniPointerSharingNode(true, 10f) { _, _ -> clicks++ }
        send(node, change(100f, 100f, true, 100f, 100f, false))
        send(node, change(140f, 100f, true, 100f, 100f, true))
        send(node, change(100f, 100f, false, 140f, 100f, true))
        assertEquals(0, clicks)
    }
}
