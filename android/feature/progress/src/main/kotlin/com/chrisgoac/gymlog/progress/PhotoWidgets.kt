package com.chrisgoac.gymlog.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.component.HierroButton
import com.chrisgoac.gymlog.designsystem.component.HierroButtonVariant
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.theme.HierroShape
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.domain.media.MediaRejection
import com.chrisgoac.gymlog.domain.media.MediaUiState
import com.chrisgoac.gymlog.feature.progress.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Spanish dates (the app is Spanish-only), independent of the device locale. */
internal object PhotoFormat {
    private val ES: Locale = Locale.forLanguageTag("es-ES")
    private val LONG: DateTimeFormatter = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", ES)
    private val SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", ES)

    /** "12 de agosto de 2026": what TalkBack reads and headings show. */
    fun longDate(date: LocalDate): String = LONG.format(date)

    /** "12 ago 2026" for tight spots. */
    fun shortDate(date: LocalDate): String = SHORT.format(date)
}

@Composable
internal fun angleLabel(angle: String): String = stringResource(
    when (angle) {
        "lado" -> R.string.progress_photos_angle_lado
        "espalda" -> R.string.progress_photos_angle_espalda
        else -> R.string.progress_photos_angle_frente
    },
)

/** What the status line of a photo says, for every [MediaUiState]. Empty when a loaded synced photo needs no remark. */
@Composable
internal fun stateText(state: MediaUiState): String = when (state) {
    is MediaUiState.LocalPending -> stringResource(R.string.progress_photos_state_pending)
    is MediaUiState.Uploading -> state.progress?.let { stringResource(R.string.progress_photos_state_uploading_percent, (it * 100).toInt()) }
        ?: stringResource(R.string.progress_photos_state_uploading)
    is MediaUiState.Available -> if (state.localThumbnail == null) stringResource(R.string.progress_photos_state_loading) else ""
    MediaUiState.RemoteUnavailableOffline -> stringResource(R.string.progress_photos_state_offline)
    is MediaUiState.RetryableFailure ->
        stringResource(if (state.detail != null) R.string.progress_photos_state_failure_upload else R.string.progress_photos_state_failure_load)
    is MediaUiState.Rejected -> stringResource(
        when (state.reason) {
            MediaRejection.TOO_LARGE -> R.string.progress_photos_state_rejected_large
            MediaRejection.UNSUPPORTED -> R.string.progress_photos_state_rejected_type
            MediaRejection.OTHER -> R.string.progress_photos_state_rejected_other
        },
    )
}

internal fun MediaUiState.isProblem(): Boolean =
    this is MediaUiState.Rejected || this is MediaUiState.RetryableFailure || this == MediaUiState.RemoteUnavailableOffline

/** A single-choice row (angle, comparison photo): one 48 dp target that speaks as a radio button. */
@Composable
internal fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    val colors = HierroTheme.colors
    val selectedLabel = stringResource(R.string.progress_photos_compare_selected)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = HierroShape.minTouchTarget)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .semantics { if (selected) stateDescription = selectedLabel }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HierroText(if (selected) "●" else "○", Modifier.clearAndSetSemantics {}, color = if (selected) colors.accent else colors.textMuted)
        HierroText(label, Modifier.weight(1f))
    }
}

@Composable
internal fun ChoiceGroup(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        HierroText(title.uppercase(), Modifier.semantics { heading() }, HierroTheme.typography.label, HierroTheme.colors.textMuted)
        content()
    }
}

@Composable
internal fun PhotoMessageCard(message: PhotoMessage, onDismiss: () -> Unit) {
    val text = stringResource(
        when (message) {
            PhotoMessage.Corrupt -> R.string.progress_photos_message_corrupt
            PhotoMessage.Unsupported -> R.string.progress_photos_message_unsupported
            PhotoMessage.TooLarge -> R.string.progress_photos_message_too_large
            PhotoMessage.Unreadable -> R.string.progress_photos_message_unreadable
            PhotoMessage.ActionFailed -> R.string.progress_photos_message_action
            PhotoMessage.CameraUnavailable -> R.string.progress_photos_message_camera
        },
    )
    HierroCard(Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = text }) {
        HierroText(text, color = HierroTheme.colors.error)
        HierroButton(stringResource(R.string.progress_photos_message_dismiss), onDismiss, variant = HierroButtonVariant.Secondary)
    }
}
