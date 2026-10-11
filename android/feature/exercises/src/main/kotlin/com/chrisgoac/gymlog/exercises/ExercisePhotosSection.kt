package com.chrisgoac.gymlog.exercises

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chrisgoac.gymlog.designsystem.component.HierroButton
import com.chrisgoac.gymlog.designsystem.component.HierroButtonVariant
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.component.PhotoTile
import com.chrisgoac.gymlog.designsystem.component.PrivatePhoto
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.domain.media.MediaStatusKind
import com.chrisgoac.gymlog.domain.media.MediaUiState
import com.chrisgoac.gymlog.domain.media.PhotoMessage
import com.chrisgoac.gymlog.domain.media.actions
import com.chrisgoac.gymlog.domain.media.isProblem
import com.chrisgoac.gymlog.domain.media.statusLine
import com.chrisgoac.gymlog.feature.exercises.R

/**
 * Photos of one custom exercise. Pictures come from the system Photo Picker or the camera app (no
 * permission requested) and live in the account's private storage. Like the progress photos, this
 * deliberately does not set FLAG_SECURE: they are the user's own images.
 */
@Composable
fun ExercisePhotosSection(viewModel: ExercisePhotosViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> viewModel.onPhotoPicked(uri?.toString()) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved -> viewModel.onCameraResult(saved) }
    ExercisePhotosContent(
        state = state,
        actions = ExercisePhotoActions(
            onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onCamera = { viewModel.prepareCamera()?.let { camera.launch(android.net.Uri.parse(it)) } },
            onRetryLoad = viewModel::retryLoad,
            onDismissMessage = viewModel::dismissMessage,
            onRequestImage = viewModel::requestImage,
            onRetryImage = viewModel::retryImage,
            onOpen = viewModel::onOpen,
            onClose = viewModel::onClose,
            onAskDelete = viewModel::onAskDelete,
            onCancelDelete = viewModel::onCancelDelete,
            onConfirmDelete = viewModel::onConfirmDelete,
            onRetryUpload = viewModel::onRetryUpload,
        ),
        modifier = modifier,
    )
}

internal class ExercisePhotoActions(
    val onPick: () -> Unit,
    val onCamera: () -> Unit,
    val onRetryLoad: () -> Unit,
    val onDismissMessage: () -> Unit,
    val onRequestImage: (String) -> Unit,
    val onRetryImage: (String) -> Unit,
    val onOpen: (String) -> Unit,
    val onClose: () -> Unit,
    val onAskDelete: (String) -> Unit,
    val onCancelDelete: () -> Unit,
    val onConfirmDelete: () -> Unit,
    val onRetryUpload: (String) -> Unit,
)

@Composable
internal fun ExercisePhotosContent(state: ExercisePhotosUiState, actions: ExercisePhotoActions, modifier: Modifier = Modifier) {
    HierroCard(modifier) {
        HierroText(stringResource(R.string.exercises_photos_title).uppercase(), Modifier.semantics { heading() }, HierroTheme.typography.label, HierroTheme.colors.textMuted)
        state.message?.let { MessageNote(it, actions.onDismissMessage) }
        when {
            state.failed -> {
                HierroText(stringResource(R.string.exercises_photos_error), color = HierroTheme.colors.error)
                HierroButton(stringResource(R.string.exercises_photos_retry_load), actions.onRetryLoad, variant = HierroButtonVariant.Secondary)
            }
            !state.loaded -> HierroText(stringResource(R.string.exercises_photos_loading), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = HierroTheme.colors.textMuted)
            state.photos.isEmpty() -> HierroText(stringResource(R.string.exercises_photos_empty), color = HierroTheme.colors.textMuted)
            else -> state.photos.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { photo -> Thumbnail(photo, state.photos.indexOf(photo) + 1, state.photos.size, actions, Modifier.weight(1f)) }
                    if (row.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                }
            }
        }
        if (state.replaces) HierroText(stringResource(R.string.exercises_photos_replace_note), color = HierroTheme.colors.textMuted)
        HierroButton(
            stringResource(if (state.replaces) R.string.exercises_photos_replace_pick else R.string.exercises_photos_pick), actions.onPick, Modifier.fillMaxWidth(),
            loading = state.saving, loadingLabel = stringResource(R.string.exercises_photos_saving),
        )
        HierroButton(
            stringResource(if (state.replaces) R.string.exercises_photos_replace_camera else R.string.exercises_photos_camera), actions.onCamera, Modifier.fillMaxWidth(),
            variant = HierroButtonVariant.Secondary, enabled = state.canAdd,
        )
    }
    state.viewing?.let { photo ->
        PhotoDialog(photo, confirming = state.pendingDelete?.id == photo.id, actions)
    }
}

@Composable
private fun Thumbnail(photo: ExercisePhotoUi, number: Int, total: Int, actions: ExercisePhotoActions, modifier: Modifier) {
    LaunchedEffect(photo.id, photo.state::class) { actions.onRequestImage(photo.id) }
    val status = stateText(photo.state)
    Column(modifier) {
        PhotoTile(
            imagePath = photo.state.localThumbnail,
            description = stringResource(R.string.exercises_photos_tile_description, number, total, status).trim(),
            statusText = status,
            placeholderText = stringResource(R.string.exercises_photos_placeholder),
            statusIsProblem = photo.state.isProblem(),
            onClick = { actions.onOpen(photo.id) },
        )
    }
}

@Composable
private fun PhotoDialog(photo: ExercisePhotoUi, confirming: Boolean, actions: ExercisePhotoActions) {
    val title = stringResource(R.string.exercises_photos_viewer_title)
    val state = photo.state
    val rejected = state is MediaUiState.Rejected
    val status = stateText(state)
    Dialog(onDismissRequest = actions.onClose) {
        HierroCard(Modifier.padding(16.dp).semantics { paneTitle = title }) {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HierroText(title, Modifier.semantics { heading() }, HierroTheme.typography.title)
                val path = state.localThumbnail
                var undecodable by remember(path) { mutableStateOf(false) }
                if (path != null && !undecodable) {
                    PrivatePhoto(path, stringResource(R.string.exercises_photos_viewer_image, title.lowercase()), Modifier.fillMaxWidth(), 2048, ContentScale.FillWidth, onUndecodable = { undecodable = true })
                } else if (undecodable) {
                    HierroText(stringResource(R.string.exercises_photos_viewer_undecodable), color = HierroTheme.colors.textMuted)
                }
                if (status.isNotEmpty()) HierroText(status, color = if (state.isProblem()) HierroTheme.colors.error else HierroTheme.colors.textMuted)
                if (confirming) {
                    HierroText(
                        stringResource(if (rejected) R.string.exercises_photos_discard_title else R.string.exercises_photos_delete_title),
                        Modifier.semantics { heading() }, HierroTheme.typography.title, HierroTheme.colors.error,
                    )
                    HierroText(stringResource(if (rejected) R.string.exercises_photos_discard_message else R.string.exercises_photos_delete_message))
                    HierroButton(stringResource(if (rejected) R.string.exercises_photos_discard_confirm else R.string.exercises_photos_delete_confirm), actions.onConfirmDelete, Modifier.fillMaxWidth())
                    HierroButton(stringResource(R.string.exercises_photos_delete_cancel), actions.onCancelDelete, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary)
                } else {
                    val available = state.actions()
                    if (available.retryImage || undecodable) {
                        HierroButton(stringResource(R.string.exercises_photos_viewer_retry_image), { actions.onRetryImage(photo.id) }, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary)
                    }
                    if (available.retryUpload) HierroButton(stringResource(R.string.exercises_photos_viewer_retry_upload), { actions.onRetryUpload(photo.id) }, Modifier.fillMaxWidth())
                    HierroButton(
                        stringResource(if (available.discard) R.string.exercises_photos_viewer_discard else R.string.exercises_photos_viewer_delete),
                        { actions.onAskDelete(photo.id) }, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary,
                    )
                    HierroButton(stringResource(R.string.exercises_photos_viewer_close), actions.onClose, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary)
                }
            }
        }
    }
}

@Composable
private fun MessageNote(message: PhotoMessage, onDismiss: () -> Unit) {
    val text = stringResource(
        when (message) {
            PhotoMessage.Corrupt -> R.string.exercises_photos_message_corrupt
            PhotoMessage.Unsupported -> R.string.exercises_photos_message_unsupported
            PhotoMessage.TooLarge -> R.string.exercises_photos_message_too_large
            PhotoMessage.Unreadable -> R.string.exercises_photos_message_unreadable
            PhotoMessage.ActionFailed -> R.string.exercises_photos_message_action
            PhotoMessage.CameraUnavailable -> R.string.exercises_photos_message_camera
        },
    )
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HierroText(text, color = HierroTheme.colors.error)
        HierroButton(stringResource(R.string.exercises_photos_message_dismiss), onDismiss, variant = HierroButtonVariant.Secondary)
    }
}

@Composable
private fun stateText(state: MediaUiState): String {
    val line = state.statusLine()
    return when (line.kind) {
        MediaStatusKind.NONE -> ""
        MediaStatusKind.PENDING -> stringResource(R.string.exercises_photos_state_pending)
        MediaStatusKind.UPLOADING -> stringResource(R.string.exercises_photos_state_uploading)
        MediaStatusKind.UPLOADING_PERCENT -> stringResource(R.string.exercises_photos_state_uploading_percent, line.percent ?: 0)
        MediaStatusKind.LOADING -> stringResource(R.string.exercises_photos_state_loading)
        MediaStatusKind.OFFLINE -> stringResource(R.string.exercises_photos_state_offline)
        MediaStatusKind.FAILURE_UPLOAD -> stringResource(R.string.exercises_photos_state_failure_upload)
        MediaStatusKind.FAILURE_LOAD -> stringResource(R.string.exercises_photos_state_failure_load)
        MediaStatusKind.REJECTED_LARGE -> stringResource(R.string.exercises_photos_state_rejected_large)
        MediaStatusKind.REJECTED_TYPE -> stringResource(R.string.exercises_photos_state_rejected_type)
        MediaStatusKind.REJECTED_OTHER -> stringResource(R.string.exercises_photos_state_rejected_other)
    }
}
