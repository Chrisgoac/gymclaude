package com.chrisgoac.gymlog.progress

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.component.HierroButton
import com.chrisgoac.gymlog.designsystem.component.HierroButtonVariant
import com.chrisgoac.gymlog.designsystem.component.HierroEmptyState
import com.chrisgoac.gymlog.designsystem.component.HierroHeaderAction
import com.chrisgoac.gymlog.designsystem.component.HierroScreenHeader
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.component.PhotoTile
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.feature.progress.R

/**
 * Two photos of the same angle side by side. Each side is labelled with visible text (side, angle and
 * day) that is also what TalkBack reads, so the comparison never depends on the picture alone. Photos
 * are chosen from the angle's list (oldest first); the sides can be swapped. Vertical flow of choices
 * keeps every target at least 48 dp and legible at 200% font.
 */
@Composable
internal fun PhotoComparisonScreen(comparison: ComparisonUi?, message: PhotoMessage?, actions: PhotoActions) {
    BackHandler(onBack = actions.onCloseCompare)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        HierroScreenHeader(stringResource(R.string.progress_photos_compare_title)) {
            HierroHeaderAction(stringResource(R.string.progress_photos_compare_close), actions.onCloseCompare)
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            message?.let { PhotoMessageCard(it, actions.onDismissMessage) }
            if (comparison == null) {
                HierroEmptyState(stringResource(R.string.progress_photos_compare_title), stringResource(R.string.progress_photos_compare_need_two))
            } else {
                ComparisonContent(comparison, actions)
            }
        }
    }
}

@Composable
private fun ComparisonContent(comparison: ComparisonUi, actions: PhotoActions) {
    LaunchedEffect(comparison.a.id, comparison.a.state::class) { actions.onRequestImage(comparison.a.id) }
    LaunchedEffect(comparison.b.id, comparison.b.state::class) { actions.onRequestImage(comparison.b.id) }
    if (comparison.angles.size > 1) {
        ChoiceGroup(stringResource(R.string.progress_photos_compare_angle)) {
            comparison.angles.forEach { angle -> ChoiceRow(angleLabel(angle), angle == comparison.angle, { actions.onCompareAngle(angle) }) }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Side(stringResource(R.string.progress_photos_compare_side_a), comparison.a, Modifier.weight(1f))
        Side(stringResource(R.string.progress_photos_compare_side_b), comparison.b, Modifier.weight(1f))
    }
    HierroButton(stringResource(R.string.progress_photos_compare_swap), actions.onSwap, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary)
    Chooser(stringResource(R.string.progress_photos_compare_choose_a), comparison, comparison.a.id, actions.onCompareA)
    Chooser(stringResource(R.string.progress_photos_compare_choose_b), comparison, comparison.b.id, actions.onCompareB)
}

@Composable
private fun Side(sideLabel: String, photo: ProgressPhotoUi, modifier: Modifier) {
    val angle = angleLabel(photo.angle)
    val date = PhotoFormat.longDate(photo.date)
    val status = stateText(photo.state)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PhotoTile(
            imagePath = photo.state.localThumbnail,
            description = stringResource(R.string.progress_photos_compare_image, sideLabel, angle, date, status).trim(),
            statusText = status,
            placeholderText = stringResource(R.string.progress_photos_placeholder),
            statusIsProblem = photo.state.isProblem(),
        )
        HierroText(sideLabel.uppercase(), style = HierroTheme.typography.label, color = HierroTheme.colors.textMuted)
        HierroText(date)
    }
}

/** Radio list of the angle's photos (oldest first) for one side. */
@Composable
private fun Chooser(title: String, comparison: ComparisonUi, selectedId: String, onSelect: (String) -> Unit) {
    ChoiceGroup(title) {
        comparison.series.forEach { photo ->
            val label = stringResource(R.string.progress_photos_compare_option, PhotoFormat.longDate(photo.date), angleLabel(photo.angle))
            ChoiceRow(label, photo.id == selectedId, { onSelect(photo.id) })
        }
    }
}
