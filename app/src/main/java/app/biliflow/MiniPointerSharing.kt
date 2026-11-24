package app.biliflow

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize

/** Let the real page receive a drag beginning over a fixed mini player.
 * Buttons/taps still consume their events; lists own scrolling and fling naturally. */
fun Modifier.shareMiniPointerInput(enabled: Boolean, touchSlop: Float, onTap: (Offset, IntSize) -> Unit): Modifier =
    then(MiniPointerSharingElement(enabled, touchSlop = touchSlop, onTap = onTap))

private data class MiniPointerSharingElement(val enabled: Boolean, val touchSlop: Float, val onTap: (Offset, IntSize) -> Unit) : ModifierNodeElement<MiniPointerSharingNode>() {
    override fun create() = MiniPointerSharingNode(enabled, touchSlop, onTap)
    override fun update(node: MiniPointerSharingNode) { node.enabled = enabled; node.touchSlop = touchSlop; node.onTap = onTap }
    override fun InspectorInfo.inspectableProperties() { name = "shareMiniPointerInput"; properties["enabled"] = enabled }
}
internal class MiniPointerSharingNode(var enabled: Boolean, var touchSlop: Float, var onTap: (Offset, IntSize) -> Unit) : Modifier.Node(), PointerInputModifierNode {
    private var pointer: PointerId? = null
    private var start = Offset.Zero
    private var dragged = false
    override fun sharePointerInputWithSiblings() = enabled
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (!enabled) { onCancelPointerInput(); return }
        if (pass != PointerEventPass.Initial) return
        val down = pointerEvent.changes.firstOrNull { it.pressed && !it.previousPressed }
        if (pointer == null && down != null) {
            pointer = down.id; start = down.position; dragged = false
            // Prevent child/underlay click recognizers from starting. Scrollables
            // deliberately accept a consumed down, just as they do over buttons.
            down.consume()
        }
        val change = pointerEvent.changes.firstOrNull { it.id == pointer } ?: return
        if (pointerEvent.changes.any { it.pressed && it.id != pointer }) dragged = true
        if ((change.position - start).getDistance() > touchSlop) dragged = true
        if (!change.pressed && change.previousPressed) {
            val click = !dragged && !change.isConsumed
            pointer = null
            if (click) { change.consume(); onTap(start, bounds) }
        }
    }
    override fun onCancelPointerInput() { pointer = null; dragged = false }
}
