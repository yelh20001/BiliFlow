package app.biliflow

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WatchLaterTest {
    private fun video(id: String) = Video(id, id, "", "UP")
    private fun entry(id: String) = WatchLaterEntry(id.hashCode().toLong(), video(id), 0, true)
    @Test fun acknowledgedMutationWaitsForListReplicaInsteadOfReportingStaleSuccess() = runBlocking {
        var reads = 0
        awaitWatchLaterUpdate({ if (++reads < 3) emptyList() else listOf(entry("A")) }, { it.any { entry -> entry.video.id == "A" } }, {})
        assertEquals(3, reads)
    }
    @Test fun delayedReplicaExhaustionReportsRefreshRequired() = runBlocking {
        var reads = 0
        val failure = runCatching { awaitWatchLaterUpdate({ reads++; emptyList() }, { false }, {}) }.exceptionOrNull()
        assertNotNull(failure); assertEquals(8, reads)
    }
    @Test fun queueDeduplicatesAndStartsAtSelectedItemWithoutWrapping() {
        val q = PlaybackQueue.start(listOf(video("A"), video("B"), video("A"), video("C")), "B")
        assertEquals(listOf("A", "B", "C"), q.items.map { it.id })
        assertEquals("B", q.current?.id)
        assertEquals("A", q.move(-1).current?.id)
        assertEquals("C", q.move(1).move(1).current?.id)
        assertNull(PlaybackQueue.start(q.items, "missing").current)
        assertEquals("C", q.select("C").current?.id)
    }
    @Test fun deletingOtherEntriesKeepsPlayingItemAndDeletingCurrentDetachesQueue() {
        val q = PlaybackQueue.start(listOf(video("A"), video("B"), video("C")), "B")
        assertEquals(0, q.retain(setOf("B", "C")).index)
        assertEquals("C", q.retain(setOf("B", "C")).move(1).current?.id)
        assertNull(q.retain(setOf("A", "C")).current)
        assertNull(q.retain(emptySet()).current)
    }
    @Test fun serverListRetainsPlayedAndUnavailableEntriesForDeletion() {
        val entries = parseWatchLater(JSONObject("""{"list":[{"aid":1,"bvid":"BV1","title":"one","progress":-1,"state":0,"duration":60,"owner":{"name":"UP","mid":4}},{"aid":2,"bvid":"BV2","progress":42,"state":-100},{"aid":2,"bvid":"BV2"},{"aid":3}]}"""))
        assertEquals(2, entries.size); assertEquals(-1, entries[0].progress)
        assertTrue(entries[0].playable); assertFalse(entries[1].playable)
        assertEquals("UP", entries[0].video.author)
        assertTrue(parseWatchLater(JSONObject()).isEmpty())
    }
    @Test fun mutationsAreSerializedAndRefreshFromServerAfterEveryWrite() = runBlocking {
        val ack = CompletableDeferred<Unit>(); val remote = mutableListOf<WatchLaterEntry>(); val operations = mutableListOf<String>()
        val store = WatchLaterStore(this, { 1L }, {}, {}, { operations += "load"; remote.toList() },
            { operations += "add"; ack.await(); remote += entry(it.id) }, { operations += "remove"; remote.clear() }, { operations += "clear:$it"; remote.clear() })
        store.add(video("A")); yield(); assertTrue(store.busy); assertTrue(store.entries.isEmpty())
        store.clear(true); yield(); assertEquals(listOf("add"), operations)
        ack.complete(Unit); yield(); yield()
        assertEquals(listOf("add", "load", "clear:true", "load"), operations)
        assertTrue(store.entries.isEmpty()); assertFalse(store.busy)
    }
    @Test fun accountSwitchDiscardsLateResponseEvenWhenSwitchingBackToSameId() = runBlocking {
        var account: Long? = 1L; val stale = CompletableDeferred<List<WatchLaterEntry>>()
        var loads = 0
        val store = WatchLaterStore(this, { account }, {}, {}, { if (++loads == 1) withContext(NonCancellable) { stale.await() } else listOf(entry("new")) }, {}, {}, {})
        store.refresh(); yield(); account = 2; store.reset(account); account = 1; store.reset(account)
        store.refresh(); stale.complete(listOf(entry("old"))); yield(); yield()
        assertEquals(listOf("new"), store.entries.map { it.video.id })
    }
    @Test fun failedWriteKeepsServerSnapshotAndAllowsRetry() = runBlocking {
        var fail = true; val messages = mutableListOf<String>()
        val store = WatchLaterStore(this, { 1L }, messages::add, {}, { listOf(entry("A")) }, { if (fail) error("offline") }, {}, {})
        store.refresh(); yield(); store.add(video("B")); yield()
        assertEquals(listOf("A"), store.entries.map { it.video.id }); assertNotNull(store.error); assertFalse(store.busy)
        fail = false; store.add(video("B")); yield(); assertNull(store.error)
    }
}
