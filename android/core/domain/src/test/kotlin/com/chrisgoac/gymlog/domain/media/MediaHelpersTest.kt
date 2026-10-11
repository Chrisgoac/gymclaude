package com.chrisgoac.gymlog.domain.media

import com.chrisgoac.gymlog.model.media.EnqueueResult
import com.chrisgoac.gymlog.model.media.InvalidImage
import com.chrisgoac.gymlog.model.media.MediaImage
import com.chrisgoac.gymlog.model.media.MediaItem
import com.chrisgoac.gymlog.model.media.MediaKind
import com.chrisgoac.gymlog.model.media.MediaOwner
import com.chrisgoac.gymlog.model.media.MediaStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun item(id: String, status: MediaStatus = MediaStatus.Synced) = MediaItem(id, MediaOwner.Exercise("e"), status, null)

private class CountingMedia : MediaRepository {
    val calls = mutableListOf<String>()
    var inFlight = 0
    var maxInFlight = 0
    var failure: Exception? = null
    override suspend fun enqueue(sourceUri: String, owner: MediaOwner): EnqueueResult = EnqueueResult.Queued("x")
    override fun observe(kind: MediaKind): Flow<List<MediaItem>> = emptyFlow()
    override suspend fun delete(mediaId: String) = Unit
    override suspend fun retry(mediaId: String) = Unit
    override suspend fun discard(mediaId: String) = Unit
    override suspend fun image(mediaId: String): MediaImage {
        calls += mediaId
        inFlight++; maxInFlight = maxOf(maxInFlight, inFlight)
        kotlinx.coroutines.delay(10)
        inFlight--
        failure?.let { throw it }
        return MediaImage.Available("/p/$mediaId.jpg")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MediaHelpersTest {
    @Test fun `the loader fetches each photo once per state, three at a time, and retries on demand`() = runTest {
        val media = CountingMedia()
        val loader = MediaImageLoader(media, this)
        val items = (1..6).map { item("m$it") }
        items.forEach(loader::request)
        items.forEach(loader::request)
        advanceUntilIdle()
        assertEquals(6, media.calls.size)
        assertEquals(3, media.maxInFlight)
        assertEquals(MediaImage.Available("/p/m1.jpg"), MediaImageLoader.imageFor(loader.loaded.value, items[0]))
        // A state change (queued -> synced) invalidates the loaded image.
        assertNull(MediaImageLoader.imageFor(loader.loaded.value, item("m1", MediaStatus.PendingUpload(0, null))))
        loader.retry(items[0])
        advanceUntilIdle()
        assertEquals(7, media.calls.size)
    }

    @Test fun `a failing fetch is recorded as not found instead of throwing`() = runTest {
        val media = CountingMedia().apply { failure = IllegalStateException("x") }
        val loader = MediaImageLoader(media, this)
        loader.request(item("a"))
        advanceUntilIdle()
        assertEquals(MediaImage.Unavailable(MediaImage.Unavailable.Reason.NOT_FOUND), MediaImageLoader.imageFor(loader.loaded.value, item("a")))
    }

    @Test fun `pruning forgets photos that are gone`() = runTest {
        val loader = MediaImageLoader(CountingMedia(), this)
        loader.request(item("a")); loader.request(item("b"))
        advanceUntilIdle()
        loader.prune(setOf("a"))
        assertEquals(setOf("a"), loader.loaded.value.keys)
    }

    @Test fun `a retry shows for a bounded window and ends when the entry moves on`() = runTest {
        val tracker = RetryTracker(this, windowMillis = 1_000)
        tracker.mark("a"); tracker.mark("b")
        assertEquals(setOf("a", "b"), tracker.ids.value)
        tracker.prune(listOf(item("a", MediaStatus.PendingUpload(0, null)), item("b", MediaStatus.PendingUpload(2, "x"))))
        assertEquals(setOf("a"), tracker.ids.value)
        advanceTimeBy(1_001)
        advanceUntilIdle()
        assertTrue(tracker.ids.value.isEmpty())
    }

    @Test fun `the capture session keeps one slot, replaces and releases it, and reports changes`() {
        val released = mutableListOf<CaptureSlot>()
        var counter = 0
        val targets = object : PhotoCaptureTargets {
            override fun create() = CaptureSlot("content://c/${++counter}", "t$counter")
            override fun release(slot: CaptureSlot) { released += slot }
        }
        val changes = mutableListOf<CaptureSlot?>()
        val session = CaptureSession(targets, onChange = { changes += it })
        assertEquals("content://c/1", session.prepare())
        assertEquals("content://c/2", session.prepare())
        assertEquals(listOf("t1"), released.map { it.token })
        assertEquals("t2", session.take()?.token)
        assertNull(session.take())
        session.prepare()
        session.close()
        assertEquals(listOf("t1", "t3"), released.map { it.token })
        assertNull(changes.last())
        val restored = CaptureSession(targets, restored = CaptureSlot("content://old", "old"))
        assertEquals("old", restored.take()?.token)
    }

    @Test fun `viewer actions follow the state`() {
        assertEquals(MediaActions(false, true, true, false), MediaUiState.Rejected(MediaRejection.OTHER).actions())
        assertEquals(MediaActions(false, true, false, true), MediaUiState.RetryableFailure(null, "timeout").actions())
        assertEquals(MediaActions(true, false, false, true), MediaUiState.RetryableFailure().actions())
        assertEquals(MediaActions(true, false, false, true), MediaUiState.RemoteUnavailableOffline.actions())
        assertEquals(MediaActions(false, false, false, true), MediaUiState.Available("/p").actions())
        assertTrue(MediaUiState.RemoteUnavailableOffline.isProblem())
        assertFalse(MediaUiState.LocalPending().isProblem())
    }

    @Test fun `status lines and messages cover every state and refusal`() {
        assertEquals(MediaStatusKind.UPLOADING_PERCENT, MediaUiState.Uploading(0.4f).statusLine().kind)
        assertEquals(40, MediaUiState.Uploading(0.4f).statusLine().percent)
        assertEquals(MediaStatusKind.LOADING, MediaUiState.Available(null).statusLine().kind)
        assertEquals(MediaStatusKind.NONE, MediaUiState.Available("/p").statusLine().kind)
        assertEquals(PhotoMessage.TooLarge, PhotoMessage.of(InvalidImage.TOO_LARGE))
    }
}
