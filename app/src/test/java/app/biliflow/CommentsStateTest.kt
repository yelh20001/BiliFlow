package app.biliflow

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CommentsStateTest {
    private fun comment(id: Long) = Comment(id, "name", "", "$id", 0, 0, 0)
    @Test fun paginationDeduplicatesPinnedCommentsAndRefreshReplacesPages() = runBlocking {
        var generation = 0
        val state = CommentsState(this) { page ->
            if (generation == 1) CommentPage(listOf(comment(9)), false, 1)
            else CommentPage(if (page == 1) listOf(comment(1), comment(2)) else listOf(comment(1), comment(3)), page == 1, 3)
        }
        state.load(); yield(); state.load(); yield()
        assertEquals(listOf(1L, 2L, 3L), state.items.map { it.id }); assertEquals(2, state.page)
        generation = 1; state.load(true); yield()
        assertEquals(listOf(9L), state.items.map { it.id }); assertEquals(1, state.page); assertFalse(state.more)
    }
    @Test fun refreshingDuringPageLoadRejectsOldResponse() = runBlocking {
        val old = CompletableDeferred<CommentPage>(); var calls = 0
        val state = CommentsState(this) { calls++; if (calls == 1) withContext(NonCancellable) { old.await() } else CommentPage(listOf(comment(9)), false, 1) }
        state.load(); yield(); state.load(true); yield()
        old.complete(CommentPage(listOf(comment(1)), true, 2)); yield()
        assertEquals(listOf(9L), state.items.map { it.id }); assertFalse(state.loading)
    }
}
