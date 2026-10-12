package com.chrisgoac.gymlog.progress

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chrisgoac.gymlog.designsystem.component.HierroButton
import com.chrisgoac.gymlog.designsystem.component.HierroButtonVariant
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroEmptyState
import com.chrisgoac.gymlog.designsystem.component.HierroErrorState
import com.chrisgoac.gymlog.designsystem.component.HierroHeaderAction
import com.chrisgoac.gymlog.designsystem.component.HierroLoadingState
import com.chrisgoac.gymlog.designsystem.component.HierroScreenHeader
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.component.HierroTextField
import com.chrisgoac.gymlog.designsystem.component.PhotoTile
import com.chrisgoac.gymlog.designsystem.component.PrivatePhoto
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.domain.media.MediaUiState
import com.chrisgoac.gymlog.domain.media.PhotoMessage
import com.chrisgoac.gymlog.domain.media.actions
import com.chrisgoac.gymlog.domain.media.isProblem
import com.chrisgoac.gymlog.feature.progress.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** What the gallery and its sub-views (capture form, viewer, comparison) can ask of the view model. */
internal class PhotoActions(
    val onBack: () -> Unit,
    val onAdd: () -> Unit,
    val onRetryLoad: () -> Unit,
    val onDismissMessage: () -> Unit,
    val onRequestImage: (String) -> Unit,
    val onRetryImage: (String) -> Unit,
    val onOpen: (String) -> Unit,
    val onReveal: (String) -> Unit,
    val onCloseViewer: () -> Unit,
    val onAskDelete: (String) -> Unit,
    val onCancelDelete: () -> Unit,
    val onConfirmDelete: () -> Unit,
    val onRetryUpload: (String) -> Unit,
    val onCompare: () -> Unit,
    val onCloseCompare: () -> Unit,
    val onCompareAngle: (String) -> Unit,
    val onCompareA: (String) -> Unit,
    val onCompareB: (String) -> Unit,
    val onSwap: () -> Unit,
    val onDraftAngle: (String) -> Unit,
    val onDraftDate: (LocalDate) -> Unit,
    val onDraftNote: (String) -> Unit,
    val onCancelDraft: () -> Unit,
    val onPickPhoto: () -> Unit,
    val onTakePhoto: () -> Unit,
)

/**
 * Private progress photos. Pictures come from the system Photo Picker or the camera app (no storage or
 * camera permission is requested) and are shown only inside the app. This screen deliberately does NOT
 * set FLAG_SECURE: the photos are the user's own, and taking a screenshot of one's own progress is a
 * normal choice; blocking it would also break accessibility tooling and screen recording the user starts.
 */
@Composable
fun ProgressGalleryScreen(viewModel: PhotoGalleryViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> viewModel.onPhotoPicked(uri?.toString()) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved -> viewModel.onCameraResult(saved) }
    val actions = PhotoActions(
        onBack = onBack,
        onAdd = viewModel::onAddPhoto,
        onRetryLoad = viewModel::retryLoad,
        onDismissMessage = viewModel::dismissMessage,
        onRequestImage = viewModel::requestImage,
        onRetryImage = viewModel::retryImage,
        onOpen = viewModel::onOpen,
        onReveal = viewModel::onReveal,
        onCloseViewer = viewModel::onCloseViewer,
        onAskDelete = viewModel::onAskDelete,
        onCancelDelete = viewModel::onCancelDelete,
        onConfirmDelete = viewModel::onConfirmDelete,
        onRetryUpload = viewModel::onRetryUpload,
        onCompare = viewModel::onCompare,
        onCloseCompare = viewModel::onCloseCompare,
        onCompareAngle = viewModel::onCompareAngle,
        onCompareA = viewModel::onCompareA,
        onCompareB = viewModel::onCompareB,
        onSwap = viewModel::onSwap,
        onDraftAngle = viewModel::onDraftAngle,
        onDraftDate = viewModel::onDraftDate,
        onDraftNote = viewModel::onDraftNote,
        onCancelDraft = viewModel::onCancelDraft,
        onPickPhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onTakePhoto = { viewModel.prepareCamera()?.let { camera.launch(android.net.Uri.parse(it)) } },
    )
    PhotoGalleryContent(state, actions, modifier)
}

@Composable
internal fun PhotoGalleryContent(state: PhotoGalleryUiState, actions: PhotoActions, modifier: Modifier = Modifier) {
    val viewing = state.viewing
    Column(modifier.fillMaxSize()) {
        when {
            viewing != null -> PhotoViewer(viewing, state.message, actions)
            state.comparing -> PhotoComparisonScreen(state.comparison, state.message, actions)
            state.draft != null -> CaptureForm(state.draft, state.clock, state.message, actions)
            else -> {
                HierroScreenHeader(stringResource(R.string.progress_photos_title)) {
                    HierroHeaderAction(stringResource(R.string.progress_photos_back), actions.onBack)
                }
                GalleryBody(state, actions)
            }
        }
    }
    state.pendingDelete?.let { DeleteDialog(it, actions.onConfirmDelete, actions.onCancelDelete) }
}

@Composable
private fun GalleryBody(state: PhotoGalleryUiState, actions: PhotoActions) {
    when (state.status) {
        PhotoGalleryStatus.Loading -> HierroLoadingState(stringResource(R.string.progress_photos_loading))
        PhotoGalleryStatus.Failed -> Column(Modifier.padding(16.dp)) {
            HierroErrorState(
                stringResource(R.string.progress_photos_error_message), stringResource(R.string.progress_photos_retry), actions.onRetryLoad,
                title = stringResource(R.string.progress_photos_error_title),
            )
        }
        PhotoGalleryStatus.Ready -> LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "actions", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.message?.let { PhotoMessageCard(it, actions.onDismissMessage) }
                    HierroButton(stringResource(R.string.progress_photos_add), actions.onAdd, Modifier.fillMaxWidth())
                    if (state.canCompare) {
                        HierroButton(stringResource(R.string.progress_photos_compare), actions.onCompare, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary)
                    }
                    HierroText(stringResource(R.string.progress_photos_private_note), color = HierroTheme.colors.textMuted)
                }
            }
            if (state.days.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    HierroEmptyState(stringResource(R.string.progress_photos_empty_title), stringResource(R.string.progress_photos_empty_message))
                }
            }
            state.days.forEach { day ->
                item(key = "day-${day.date}", span = { GridItemSpan(maxLineSpan) }) {
                    HierroText(
                        PhotoFormat.longDate(day.date).uppercase(),
                        Modifier.semantics { heading() },
                        HierroTheme.typography.label,
                        HierroTheme.colors.textMuted,
                    )
                }
                items(day.photos, key = { it.id }) { photo -> GalleryTile(photo, actions) }
            }
        }
    }
}

@Composable
private fun GalleryTile(photo: ProgressPhotoUi, actions: PhotoActions) {
    LaunchedEffect(photo.id, photo.state::class) { actions.onRequestImage(photo.id) }
    val status = stateText(photo.state)
    val angle = angleLabel(photo.angle)
    val date = PhotoFormat.longDate(photo.date)
    val description = when {
        photo.concealed -> stringResource(R.string.progress_photos_tile_description_concealed, angle, date)
        photo.note != null -> stringResource(R.string.progress_photos_tile_description_note, angle, date, photo.note, status)
        else -> stringResource(R.string.progress_photos_tile_description, angle, date, status)
    }
    PhotoTile(
        imagePath = photo.state.localThumbnail,
        description = description.trim(),
        statusText = listOf(angle, status).filter { it.isNotEmpty() }.joinToString(" · "),
        placeholderText = stringResource(R.string.progress_photos_placeholder),
        statusIsProblem = photo.state.isProblem(),
        concealed = photo.concealed,
        concealedText = stringResource(R.string.progress_photos_concealed_cover),
        // First tap lifts the cover, the next one opens the viewer.
        onClick = { if (photo.concealed) actions.onReveal(photo.id) else actions.onOpen(photo.id) },
    )
}

// ---- Capture ----

@Composable
private fun CaptureForm(draft: CaptureDraft, clock: PhotoClock?, message: PhotoMessage?, actions: PhotoActions) {
    BackHandler(enabled = !draft.busy, onBack = actions.onCancelDraft)
    var picking by rememberSaveable { mutableStateOf(false) }
    val date = PhotoFormat.longDate(draft.date)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HierroText(stringResource(R.string.progress_photos_capture_title).uppercase(), Modifier.semantics { heading() }, HierroTheme.typography.title)
        HierroText(stringResource(R.string.progress_photos_capture_hint), color = HierroTheme.colors.textMuted)
        message?.let { PhotoMessageCard(it, actions.onDismissMessage) }
        ChoiceGroup(stringResource(R.string.progress_photos_capture_angle)) {
            PHOTO_ANGLES.forEach { angle -> ChoiceRow(angleLabel(angle), draft.angle == angle, { if (!draft.busy) actions.onDraftAngle(angle) }) }
        }
        HierroText(stringResource(R.string.progress_photos_capture_date).uppercase(), style = HierroTheme.typography.label, color = HierroTheme.colors.textMuted)
        HierroButton(
            date, { picking = true }, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary, enabled = !draft.busy,
            contentDescription = stringResource(R.string.progress_photos_capture_date_change, date),
        )
        HierroTextField(
            value = draft.note,
            onValueChange = actions.onDraftNote,
            label = stringResource(R.string.progress_photos_capture_note),
            enabled = !draft.busy,
        )
        HierroButton(
            stringResource(R.string.progress_photos_capture_pick), actions.onPickPhoto, Modifier.fillMaxWidth(),
            loading = draft.busy, loadingLabel = stringResource(R.string.progress_photos_capture_saving),
        )
        HierroButton(stringResource(R.string.progress_photos_capture_camera), actions.onTakePhoto, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary, enabled = !draft.busy)
        HierroButton(stringResource(R.string.progress_photos_capture_cancel), actions.onCancelDraft, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary, enabled = !draft.busy)
    }
    if (picking && clock != null) {
        PhotoDatePicker(draft.date, clock.today, onPick = { actions.onDraftDate(it); picking = false }, onDismiss = { picking = false })
    }
}

/** Material 3 picker works in UTC midnights; days after [today] are not selectable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoDatePicker(initial: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val todayUtc = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val colors = HierroTheme.colors
    val scheme = (if (colors.isDark) darkColorScheme() else lightColorScheme()).copy(
        primary = colors.accent, onPrimary = colors.onAccent,
        surface = colors.surface, onSurface = colors.ink, surfaceVariant = colors.surfaceMuted, onSurfaceVariant = colors.textMuted,
        background = colors.background, onBackground = colors.ink, surfaceContainerHigh = colors.surface, error = colors.error,
        outline = colors.border, secondaryContainer = colors.accent, onSecondaryContainer = colors.onAccent,
    )
    MaterialTheme(colorScheme = scheme) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayUtc
                override fun isSelectableYear(year: Int) = year <= today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    onClick = { pickerState.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss() },
                ) { Text(stringResource(R.string.progress_photos_date_ok)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.progress_photos_date_cancel)) } },
        ) {
            DatePicker(state = pickerState, title = { Text(stringResource(R.string.progress_photos_date_title), Modifier.padding(start = 24.dp, top = 16.dp)) })
        }
    }
}

// ---- Viewer ----

/** Full-screen private viewer: the photo, its facts and the actions that fit its state. */
@Composable
private fun PhotoViewer(photo: ProgressPhotoUi, message: PhotoMessage?, actions: PhotoActions) {
    BackHandler(onBack = actions.onCloseViewer)
    LaunchedEffect(photo.id, photo.state::class) { actions.onRequestImage(photo.id) }
    val angle = angleLabel(photo.angle)
    val date = PhotoFormat.longDate(photo.date)
    val status = stateText(photo.state)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        HierroScreenHeader(stringResource(R.string.progress_photos_viewer_title)) {
            HierroHeaderAction(stringResource(R.string.progress_photos_viewer_close), actions.onCloseViewer)
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            message?.let { PhotoMessageCard(it, actions.onDismissMessage) }
            val path = photo.state.localThumbnail
            var undecodable by remember(path) { mutableStateOf(false) }
            if (path != null && !undecodable) {
                PrivatePhoto(
                    path, stringResource(R.string.progress_photos_viewer_image, angle, date),
                    Modifier.fillMaxWidth(), maxSidePx = 2048, contentScale = ContentScale.FillWidth,
                    onUndecodable = { undecodable = true },
                )
            } else {
                HierroCard {
                    HierroText(
                        if (undecodable) stringResource(R.string.progress_photos_viewer_undecodable) else status.ifEmpty { stringResource(R.string.progress_photos_placeholder) },
                        color = HierroTheme.colors.textMuted,
                    )
                    if (undecodable) HierroButton(stringResource(R.string.progress_photos_viewer_retry_image), { actions.onRetryImage(photo.id) }, variant = HierroButtonVariant.Secondary)
                }
            }
            HierroText("$angle · $date", Modifier.semantics { heading() }, HierroTheme.typography.title)
            photo.note?.let { HierroText(stringResource(R.string.progress_photos_viewer_note, it)) }
            if (path != null && status.isNotEmpty()) HierroText(status, color = if (photo.state.isProblem()) HierroTheme.colors.error else HierroTheme.colors.textMuted)
            ViewerActions(photo, actions)
        }
    }
}

@Composable
private fun ViewerActions(photo: ProgressPhotoUi, actions: PhotoActions) {
    val available = photo.state.actions()
    if (available.retryImage) {
        HierroButton(stringResource(R.string.progress_photos_viewer_retry_image), { actions.onRetryImage(photo.id) }, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary)
    }
    if (available.retryUpload) {
        HierroButton(stringResource(R.string.progress_photos_viewer_retry_upload), { actions.onRetryUpload(photo.id) }, Modifier.fillMaxWidth())
    }
    if (available.discard) {
        HierroButton(stringResource(R.string.progress_photos_viewer_discard), { actions.onAskDelete(photo.id) }, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary)
    }
    if (available.delete) {
        HierroButton(stringResource(R.string.progress_photos_viewer_delete), { actions.onAskDelete(photo.id) }, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary)
    }
}

@Composable
private fun DeleteDialog(photo: ProgressPhotoUi, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val discard = photo.state is MediaUiState.Rejected
    val title = stringResource(if (discard) R.string.progress_photos_discard_title else R.string.progress_photos_delete_title)
    val angle = angleLabel(photo.angle)
    val date = PhotoFormat.longDate(photo.date)
    Dialog(onDismissRequest = onCancel) {
        HierroCard(Modifier.padding(16.dp).semantics { paneTitle = title }) {
            HierroText(title, Modifier.semantics { heading() }, HierroTheme.typography.title)
            HierroText(stringResource(if (discard) R.string.progress_photos_discard_message else R.string.progress_photos_delete_message, angle, date))
            HierroButton(stringResource(if (discard) R.string.progress_photos_discard_confirm else R.string.progress_photos_delete_confirm), onConfirm, Modifier.fillMaxWidth())
            HierroButton(stringResource(R.string.progress_photos_delete_cancel), onCancel, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary)
        }
    }
}
