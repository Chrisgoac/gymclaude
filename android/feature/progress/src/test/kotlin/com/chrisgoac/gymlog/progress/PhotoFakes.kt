package com.chrisgoac.gymlog.progress

import com.chrisgoac.gymlog.domain.media.CaptureSlot
import com.chrisgoac.gymlog.domain.media.MediaRepository
import com.chrisgoac.gymlog.domain.media.PhotoCaptureTargets
import com.chrisgoac.gymlog.model.media.EnqueueResult
import com.chrisgoac.gymlog.model.media.MediaImage
import com.chrisgoac.gymlog.model.media.MediaItem
import com.chrisgoac.gymlog.model.media.MediaKind
import com.chrisgoac.gymlog.model.media.MediaOwner
import com.chrisgoac.gymlog.model.media.MediaStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId

internal val MADRID: ZoneId = ZoneId.of("Europe/Madrid")

internal fun dayMillis(year: Int, month: Int, day: Int): Long =
    LocalDate.of(year, month, day).atStartOfDay(MADRID).toInstant().toEpochMilli()

internal fun progressItem(
    id: String,
    fecha: Long,
    angle: String = "frente",
    note: String? = null,
    status: MediaStatus = MediaStatus.Synced,
) = MediaItem(id, MediaOwner.Progress(fecha, angle, note), status, if (status == MediaStatus.Synced) "users/h/progress/$id" else null)

/** In-memory [MediaRepository] whose photos and image answers are settable. */
internal class FakeMedia : MediaRepository {
    val items = MutableStateFlow<List<MediaItem>>(emptyList())
    val images = mutableMapOf<String, MediaImage>()
    var enqueueResult: EnqueueResult = EnqueueResult.Queued("new")
    var failNext: Exception? = null
    val enqueued = mutableListOf<Pair<String, MediaOwner>>()
    val deleted = mutableListOf<String>()
    val discarded = mutableListOf<String>()
    val retried = mutableListOf<String>()
    val imageCalls = mutableListOf<String>()
    var observeError: Throwable? = null

    override suspend fun enqueue(sourceUri: String, owner: MediaOwner): EnqueueResult {
        failNext?.let { failNext = null; throw it }
        enqueued += sourceUri to owner
        return enqueueResult
    }

    override fun observe(kind: MediaKind): Flow<List<MediaItem>> {
        observeError?.let { error -> return kotlinx.coroutines.flow.flow { throw error } }
        return items.map { list -> list.filter { it.owner.kind == kind } }
    }

    override suspend fun delete(mediaId: String) {
        failNext?.let { failNext = null; throw it }
        deleted += mediaId
        items.value = items.value.filterNot { it.id == mediaId }
    }

    override suspend fun retry(mediaId: String) {
        failNext?.let { failNext = null; throw it }
        retried += mediaId
        items.value = items.value.map { if (it.id == mediaId) it.copy(status = MediaStatus.PendingUpload(0, null)) else it }
    }

    override suspend fun discard(mediaId: String) {
        discarded += mediaId
        items.value = items.value.filterNot { it.id == mediaId }
    }

    override suspend fun image(mediaId: String): MediaImage {
        imageCalls += mediaId
        return images[mediaId] ?: MediaImage.Unavailable(MediaImage.Unavailable.Reason.NOT_FOUND)
    }
}

internal class FakeCaptureTargets : PhotoCaptureTargets {
    var available = true
    var created = 0
    val released = mutableListOf<CaptureSlot>()

    override fun create(): CaptureSlot? = if (available) CaptureSlot("content://gymlog.media/capture/${++created}.jpg", "t$created") else null

    override fun release(slot: CaptureSlot) {
        released += slot
    }
}
