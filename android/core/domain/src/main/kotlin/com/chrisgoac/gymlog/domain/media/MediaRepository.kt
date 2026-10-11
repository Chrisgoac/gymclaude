package com.chrisgoac.gymlog.domain.media

import com.chrisgoac.gymlog.model.media.EnqueueResult
import com.chrisgoac.gymlog.model.media.MediaImage
import com.chrisgoac.gymlog.model.media.MediaItem
import com.chrisgoac.gymlog.model.media.MediaKind
import com.chrisgoac.gymlog.model.media.MediaOwner
import kotlinx.coroutines.flow.Flow

/**
 * Private photos of the open account. Uploads and deletes go through the v1 media endpoints (never the
 * sync outbox: the server journals the row itself) via a durable per-photo background queue, so every
 * call here works offline.
 */
interface MediaRepository {
    /**
     * Copies the image at [sourceUri] (Photo Picker or camera-capture content URI) into the account's
     * private pending directory, compresses it (EXIF orientation applied, metadata stripped), records it
     * as pending and schedules its upload. Everything is local before this returns.
     */
    suspend fun enqueue(sourceUri: String, owner: MediaOwner): EnqueueResult

    /** Synced and pending photos of [kind], merged by id; photos with a queued delete are already gone. */
    fun observe(kind: MediaKind): Flow<List<MediaItem>>

    /** Deletes a photo: optimistic locally, confirmed by the server in the background. */
    suspend fun delete(mediaId: String)

    /** Puts a refused upload, or one that keeps failing, back in the queue with a fresh attempt count. */
    suspend fun retry(mediaId: String)

    /** Drops a rejected upload and its local file (it never reached the server). */
    suspend fun discard(mediaId: String)

    /** A local file to display: pending original, cached copy, or downloaded through a fresh read grant. */
    suspend fun image(mediaId: String): MediaImage
}
