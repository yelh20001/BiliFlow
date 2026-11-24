package app.biliflow

import org.junit.Assert.*
import org.junit.Test

class DynamicFeedStateTest {
    private fun author(id: Long, updated: Boolean = false) = FollowedAuthor(id, "UP $id", "", updated)
    @Test fun authorReadUpdatesAndNewPagesDoNotMoveExistingSwipeDestinations() {
        val order = mergeDynamicAuthorOrder(listOf(author(1), author(2)), listOf(author(2, true), author(3), author(1)), author(2))
        assertEquals(listOf(1L, 2L, 3L), order.map { it.id })
        assertTrue(order[1].updated)
    }
    @Test fun selectedAuthorRemainsAvailableWhenTheFollowingListIsOnlyPartiallyLoaded() {
        val order = mergeDynamicAuthorOrder(emptyList(), listOf(author(1), author(1)), author(9))
        assertEquals(listOf(1L, 9L), order.map { it.id })
    }
    @Test fun allFeedAndIndividualFeedsKeepTheirOwnPaginationAndEmptyLoadedState() {
        val cache = DynamicFeedCache()
        cache.put(null, DynamicFeedSnapshot(offset = "all-next", loaded = true))
        cache.put(1, DynamicFeedSnapshot(offset = "author-next", more = false, loaded = true, loading = true))
        assertEquals("all-next", cache.get(null).offset)
        assertEquals("author-next", cache.get(1).offset)
        assertTrue(cache.get(1).loaded); assertFalse(cache.get(1).more); assertFalse(cache.get(1).loading)
        assertFalse(cache.get(2).loaded)
        cache.clear(); assertFalse(cache.get(null).loaded); assertFalse(cache.get(1).loaded)
    }
}
