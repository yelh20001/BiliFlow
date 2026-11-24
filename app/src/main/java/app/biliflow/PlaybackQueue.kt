package app.biliflow

/** Immutable queue snapshot. Reaching the last item stops; it never wraps silently. */
data class PlaybackQueue(val items: List<Video> = emptyList(), val index: Int = -1) {
    val current get() = items.getOrNull(index)
    fun move(offset: Int): PlaybackQueue = if (index + offset in items.indices) copy(index = index + offset) else this
    fun select(id: String): PlaybackQueue = items.indexOfFirst { it.id == id }.let { if (it >= 0) copy(index = it) else this }
    fun retain(ids: Set<String>): PlaybackQueue {
        // Removing the playing item detaches the queue without interrupting playback.
        val id = current?.id ?: return PlaybackQueue()
        if (id !in ids) return PlaybackQueue()
        return start(items.filter { it.id in ids }, id)
    }
    companion object {
        fun start(items: List<Video>, id: String) = items.distinctBy { it.id }.let { list ->
            val index = list.indexOfFirst { it.id == id }
            if (index < 0) PlaybackQueue() else PlaybackQueue(list.toList(), index)
        }
    }
}
