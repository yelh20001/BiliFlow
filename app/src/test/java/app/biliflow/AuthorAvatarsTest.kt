package app.biliflow

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AuthorAvatarsTest {
    private fun video(id: Int, author: Long = id.toLong()) = Video("BV$id", "video", "", "UP", authorId = author)
    @Test fun differentVideosBySameAuthorShareOnePendingFetch() = runBlocking {
        val ready = CompletableDeferred<Unit>(); var calls = 0
        val store = AuthorAvatars(this) { calls++; ready.await(); AuthorIdentity(it.authorId, "face") }
        store.load(video(1, 42)); store.load(video(2, 42)); yield()
        assertEquals(1, calls); ready.complete(Unit); yield()
        assertEquals("face", store.face(video(2, 42)))
    }
    @Test fun requestsForManyVisibleAuthorsHaveBoundedConcurrency() = runBlocking {
        val ready = CompletableDeferred<Unit>(); var calls = 0; var running = 0; var peak = 0
        val store = AuthorAvatars(this) { calls++; running++; peak = maxOf(peak, running); ready.await(); running--; AuthorIdentity(it.authorId, "face") }
        repeat(12) { store.load(video(it + 1)) }; yield(); assertEquals(3, calls)
        ready.complete(Unit); repeat(16) { yield() }
        assertEquals(12, calls); assertEquals(3, peak)
    }
    @Test fun failedAvatarDoesNotRetryOnEveryRecompositionOrScroll() = runBlocking {
        var calls = 0; val store = AuthorAvatars(this) { calls++; error("offline") }
        repeat(4) { store.load(video(1)); yield() }
        assertEquals(1, calls); assertEquals("", store.face(video(1)))
    }
    @Test fun oldAvatarEntriesAreEvictedInsteadOfGrowingForever() = runBlocking {
        val store = AuthorAvatars(this) { AuthorIdentity(it.authorId, "face-${it.authorId}") }
        repeat(270) { store.load(video(it + 1)); yield() }
        assertEquals("", store.face(video(1))); assertEquals("face-270", store.face(video(270)))
    }
}
