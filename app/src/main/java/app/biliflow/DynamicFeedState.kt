package app.biliflow

data class DynamicFeedSnapshot(val posts: List<DynamicPost> = emptyList(), val offset: String = "", val more: Boolean = true,
    val loaded: Boolean = false, val loading: Boolean = false, val error: String? = null)

/** Feed metadata only; account changes clear the cache. */
class DynamicFeedCache {
    private val values = LinkedHashMap<Long?, DynamicFeedSnapshot>()
    fun get(author: Long?): DynamicFeedSnapshot = values[author] ?: DynamicFeedSnapshot()
    fun put(author: Long?, snapshot: DynamicFeedSnapshot) {
        values.remove(author); values[author] = snapshot.copy(loading = false)
        while (values.size > 16) values.remove(values.keys.first())
    }
    fun clear() = values.clear()
}

/** Read-status updates must not change the position of pages during a swipe. */
fun mergeDynamicAuthorOrder(previous: List<FollowedAuthor>, current: List<FollowedAuthor>, selected: FollowedAuthor?): List<FollowedAuthor> {
    val latest = (listOfNotNull(selected) + current).associateBy { it.id }
    return (previous.map { latest[it.id] ?: it } + current + listOfNotNull(selected)).filter { it.id > 0 }.distinctBy { it.id }
}
