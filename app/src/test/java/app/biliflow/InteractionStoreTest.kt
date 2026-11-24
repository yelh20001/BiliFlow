package app.biliflow

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InteractionStoreTest {
    @Test fun numericAndBooleanAccountStatesAreBothSupported() {
        assertEquals(VideoInteraction(true, 2, true, true), parseInteraction(JSONObject("""{"like":1,"coin":2,"favorite":true,"attention":"1"}""")))
        assertFalse(parseInteraction(JSONObject("""{"like":0,"favorite":false}""")).liked)
    }
    @Test fun pendingWriteUpdatesImmediatelyAndRejectsDuplicateTaps() = runBlocking {
        val ack = CompletableDeferred<Unit>(); var requests = 0
        val store = InteractionStore(this, { VideoInteraction() }, { throw it })
        store.reset("a")
        store.mutate({ it.copy(liked = true) }) { requests++; ack.await() }
        assertTrue(store.value.liked); assertTrue(store.writing)
        store.mutate({ it.copy(liked = false) }) { requests++ }
        yield(); assertEquals(1, requests)
        ack.complete(Unit); yield()
        assertTrue(store.value.liked); assertFalse(store.writing)
    }
    @Test fun failedMutationRollsBackAndAllowsRetry() = runBlocking {
        var failures = 0
        val store = InteractionStore(this, { VideoInteraction() }, { failures++ })
        store.reset("a"); store.mutate({ it.copy(favorite = true) }) { error("offline") }
        yield(); assertFalse(store.value.favorite); assertFalse(store.writing); assertEquals(1, failures)
        store.mutate({ it.copy(favorite = true) }) { }; yield(); assertTrue(store.value.favorite)
    }
    @Test fun staleReadCannotEraseAcknowledgedLike() = runBlocking {
        val stale = CompletableDeferred<VideoInteraction>()
        val store = InteractionStore(this, { withContext(NonCancellable) { stale.await() } }, { throw it })
        store.reset("a"); store.refresh(); yield()
        store.mutate({ it.copy(liked = true) }) { }; yield()
        stale.complete(VideoInteraction()); yield()
        assertTrue(store.value.liked); assertFalse(store.reading)
    }
    @Test fun oldVideoWriteFailureCannotChangeNewVideoState() = runBlocking {
        val finish = CompletableDeferred<Unit>()
        val store = InteractionStore(this, { VideoInteraction() }, { throw it })
        store.reset("a"); store.mutate({ it.copy(liked = true) }) { finish.await(); error("old failure") }
        yield(); store.reset("b"); store.mutate({ it.copy(favorite = true) }) { }; yield()
        finish.complete(Unit); yield()
        assertTrue(store.value.favorite); assertFalse(store.value.liked)
    }
}
