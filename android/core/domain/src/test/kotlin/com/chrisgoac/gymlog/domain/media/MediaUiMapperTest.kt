package com.chrisgoac.gymlog.domain.media

import com.chrisgoac.gymlog.model.media.MediaImage
import com.chrisgoac.gymlog.model.media.MediaItem
import com.chrisgoac.gymlog.model.media.MediaOwner
import com.chrisgoac.gymlog.model.media.MediaStatus
import org.junit.Test
import org.junit.Assert.assertEquals

class MediaUiMapperTest {
    private fun item(status: MediaStatus) = MediaItem("m1", MediaOwner.Exercise("e1"), status, null)
    private val available = MediaImage.Available("/private/m1.jpg")

    @Test fun `a queued upload is local and pending, with the original as thumbnail`() {
        assertEquals(MediaUiState.LocalPending("/private/m1.jpg"), MediaUiMapper.map(item(MediaStatus.PendingUpload(0, null)), available))
        assertEquals(MediaUiState.LocalPending(null), MediaUiMapper.map(item(MediaStatus.PendingUpload(0, null)), null))
    }

    @Test fun `a retry the user asked for shows as uploading until it fails again`() {
        val pending = item(MediaStatus.PendingUpload(1, null))
        assertEquals(MediaUiState.Uploading(null, "/private/m1.jpg"), MediaUiMapper.map(pending, available, retrying = true))
        val failed = item(MediaStatus.PendingUpload(2, "timeout"))
        assertEquals(MediaUiState.RetryableFailure("/private/m1.jpg", "timeout"), MediaUiMapper.map(failed, available, retrying = true))
    }

    @Test fun `a synced photo is available once cached and loading before`() {
        val synced = item(MediaStatus.Synced)
        assertEquals(MediaUiState.Available(null), MediaUiMapper.map(synced, null))
        assertEquals(MediaUiState.Available("/private/m1.jpg"), MediaUiMapper.map(synced, available))
    }

    @Test fun `a synced photo that is not cached while offline or signed out is unavailable offline`() {
        val synced = item(MediaStatus.Synced)
        assertEquals(MediaUiState.RemoteUnavailableOffline, MediaUiMapper.map(synced, MediaImage.Unavailable(MediaImage.Unavailable.Reason.OFFLINE)))
        assertEquals(MediaUiState.RemoteUnavailableOffline, MediaUiMapper.map(synced, MediaImage.Unavailable(MediaImage.Unavailable.Reason.SESSION_REQUIRED)))
    }

    @Test fun `a remote 404 is a retryable failure`() {
        assertEquals(
            MediaUiState.RetryableFailure(),
            MediaUiMapper.map(item(MediaStatus.Synced), MediaImage.Unavailable(MediaImage.Unavailable.Reason.NOT_FOUND)),
        )
    }

    @Test fun `a rejected upload explains the reason and keeps the local file`() {
        assertEquals(MediaUiState.Rejected(MediaRejection.TOO_LARGE, "/private/m1.jpg"), MediaUiMapper.map(item(MediaStatus.Rejected("MEDIA_TOO_LARGE")), available))
        assertEquals(MediaRejection.UNSUPPORTED, MediaRejection.fromCode("UNSUPPORTED_MEDIA_TYPE"))
        assertEquals(MediaRejection.OTHER, MediaRejection.fromCode("RECORD_ID_CONFLICT"))
    }
}
