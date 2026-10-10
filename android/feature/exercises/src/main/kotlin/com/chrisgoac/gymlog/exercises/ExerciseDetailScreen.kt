package com.chrisgoac.gymlog.exercises

import com.chrisgoac.gymlog.feature.exercises.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.component.HierroButton
import com.chrisgoac.gymlog.designsystem.component.HierroButtonVariant
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroEmptyState
import com.chrisgoac.gymlog.designsystem.component.HierroErrorState
import com.chrisgoac.gymlog.designsystem.component.HierroHeaderAction
import com.chrisgoac.gymlog.designsystem.component.HierroLoadingState
import com.chrisgoac.gymlog.designsystem.component.HierroScreenHeader
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.domain.exercise.Exercise

/** One exercise: facts, description/execution, and edit/delete when it is the user's own. */
@Composable
fun ExerciseDetailScreen(
    viewModel: ExerciseDetailViewModel,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val deleted by viewModel.deleted.collectAsState()
    LaunchedEffect(deleted) { if (deleted) onBack() }
    Column(modifier.fillMaxSize()) {
        HierroScreenHeader(stringResource(R.string.detail_title)) {
            HierroHeaderAction(stringResource(R.string.exercises_back), onBack)
        }
        when (val current = state) {
            ExerciseDetailUiState.Loading -> HierroLoadingState(stringResource(R.string.exercises_loading))
            ExerciseDetailUiState.Failed -> Column(Modifier.padding(16.dp)) {
                HierroErrorState(
                    stringResource(R.string.exercises_error_message), stringResource(R.string.exercises_retry), viewModel::retry,
                    title = stringResource(R.string.exercises_error_title),
                )
            }
            ExerciseDetailUiState.NotFound -> Column(Modifier.padding(16.dp)) {
                HierroEmptyState(stringResource(R.string.detail_not_found_title), stringResource(R.string.detail_not_found_message))
            }
            is ExerciseDetailUiState.Found -> ExerciseDetailContent(current, onEdit, viewModel::delete)
        }
    }
}

@Composable
internal fun ExerciseDetailContent(state: ExerciseDetailUiState.Found, onEdit: (String) -> Unit, onDelete: () -> Unit) {
    val exercise = state.exercise
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HierroCard {
            HierroText(exercise.name, Modifier.semantics { heading() }, HierroTheme.typography.title)
            Fact(stringResource(R.string.detail_muscle), exercise.muscleGroup.label)
            Fact(stringResource(R.string.detail_equipment), exercise.equipment.label)
            Fact(stringResource(R.string.detail_type), exercise.type.label)
            exercise.incrementKg?.let {
                Fact(stringResource(R.string.detail_increment), stringResource(R.string.detail_increment_value, formatKg(it)))
            }
            if (!exercise.isCustom) HierroText(stringResource(R.string.detail_catalog_note), color = HierroTheme.colors.textMuted)
        }
        TextSection(R.string.detail_description, exercise.description)
        TextSection(R.string.detail_execution, exercise.execution)
        TextSection(R.string.detail_notes, exercise.notes)
        exercise.videoUrl?.let { url -> VideoButton(url) }
        PhotosCard()
        if (exercise.isCustom) ManageCard(state, confirmingDelete, { confirmingDelete = it }, onEdit, onDelete)
    }
}

@Composable
private fun Fact(label: String, value: String) {
    HierroText(label.uppercase(), style = HierroTheme.typography.label, color = HierroTheme.colors.textMuted)
    HierroText(value)
}

@Composable
private fun TextSection(titleRes: Int, text: String?) {
    if (text.isNullOrBlank()) return
    HierroCard {
        HierroText(stringResource(titleRes).uppercase(), Modifier.semantics { heading() }, HierroTheme.typography.label, HierroTheme.colors.textMuted)
        HierroText(text)
    }
}

@Composable
private fun VideoButton(url: String) {
    val uriHandler = LocalUriHandler.current
    HierroButton(stringResource(R.string.detail_video), onClick = { runCatching { uriHandler.openUri(url) } }, variant = HierroButtonVariant.Secondary)
}

/** Photos arrive with cloud storage (a later plan): shown disabled with the reason, never a fake upload. */
@Composable
private fun PhotosCard() {
    HierroCard {
        HierroText(stringResource(R.string.photos_title).uppercase(), Modifier.semantics { heading() }, HierroTheme.typography.label, HierroTheme.colors.textMuted)
        HierroButton(stringResource(R.string.photos_add), onClick = {}, enabled = false, variant = HierroButtonVariant.Secondary)
        HierroText(stringResource(R.string.photos_unavailable), color = HierroTheme.colors.textMuted)
    }
}

@Composable
private fun ManageCard(
    state: ExerciseDetailUiState.Found,
    confirming: Boolean,
    setConfirming: (Boolean) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: () -> Unit,
) {
    HierroCard {
        if (state.deleteFailed) HierroText(stringResource(R.string.detail_delete_failed), color = HierroTheme.colors.error)
        if (confirming) {
            HierroText(stringResource(R.string.detail_delete_confirm_title).uppercase(), Modifier.semantics { heading() }, HierroTheme.typography.title, HierroTheme.colors.error)
            HierroText(stringResource(R.string.detail_delete_confirm_message))
            HierroButton(stringResource(R.string.detail_delete_confirm), onDelete, loading = state.deleting, variant = HierroButtonVariant.Secondary)
            HierroButton(stringResource(R.string.detail_delete_cancel), { setConfirming(false) }, enabled = !state.deleting)
        } else {
            HierroButton(stringResource(R.string.detail_edit), { onEdit(state.exercise.id) })
            HierroButton(stringResource(R.string.detail_delete), { setConfirming(true) }, variant = HierroButtonVariant.Secondary)
        }
    }
}

private fun formatKg(value: Double): String =
    (if (value == Math.floor(value)) value.toLong().toString() else value.toString()).replace('.', ',')

