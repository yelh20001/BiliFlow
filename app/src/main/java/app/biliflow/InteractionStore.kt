package app.biliflow

import androidx.compose.runtime.*
import kotlinx.coroutines.*

/** Acknowledged mutations supersede reads that were already in flight. */
class InteractionStore(private val scope: CoroutineScope, private val read: suspend (String) -> VideoInteraction,
    private val failed: (Exception) -> Unit) {
    var value by mutableStateOf(VideoInteraction()); private set
    var reading by mutableStateOf(false); private set
    var writing by mutableStateOf(false); private set
    private var identity: String? = null
    private var revision = 0
    private var readJob: Job? = null
    fun reset(id: String?) {
        readJob?.cancel(); identity = id; revision++; value = VideoInteraction(); reading = false; writing = false
    }
    fun refresh() {
        val id = identity ?: return
        if (reading || writing) return
        val version = revision
        reading = true
        readJob = scope.launch {
            try { val result = read(id); if (identity == id && version == revision) value = result }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (identity == id && version == revision) failed(e) }
            finally { if (identity == id && version == revision) reading = false }
        }
    }
    fun mutate(transform: (VideoInteraction) -> VideoInteraction, write: suspend () -> Unit) {
        val id = identity ?: return
        if (writing) return
        val before = value
        val version = ++revision
        readJob?.cancel(); reading = false; writing = true
        value = transform(before)
        scope.launch {
            try { write() }
            catch (e: CancellationException) { if (identity == id && revision == version) value = before; throw e }
            catch (e: Exception) { if (identity == id && revision == version) { value = before; failed(e) } }
            finally { if (identity == id && revision == version) writing = false }
        }
    }
}
