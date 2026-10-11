package com.chrisgoac.gymlog.exercises

import com.chrisgoac.gymlog.feature.exercises.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.component.HierroButton
import com.chrisgoac.gymlog.designsystem.component.HierroButtonVariant
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroEmptyState
import com.chrisgoac.gymlog.designsystem.component.HierroHeaderAction
import com.chrisgoac.gymlog.designsystem.component.HierroLoadingState
import com.chrisgoac.gymlog.designsystem.component.HierroScreenHeader
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.component.HierroTextField
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.domain.exercise.Equipment
import com.chrisgoac.gymlog.domain.exercise.ExerciseField
import com.chrisgoac.gymlog.domain.exercise.ExerciseFieldError
import com.chrisgoac.gymlog.domain.exercise.ExerciseType
import com.chrisgoac.gymlog.domain.exercise.MuscleGroup

/** Create (id == null) or edit a custom exercise. [onSaved] receives the saved id once, after the local commit. */
@Composable
fun ExerciseEditorScreen(
    viewModel: ExerciseEditorViewModel,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.savedId) { state.savedId?.let(onSaved) }
    val title = stringResource(if (state.exerciseId == null) R.string.exercises_editor_title_new else R.string.exercises_editor_title_edit)
    Column(modifier.fillMaxSize()) {
        HierroScreenHeader(title) { HierroHeaderAction(stringResource(R.string.exercises_back), onBack) }
        when {
            state.loading -> HierroLoadingState(stringResource(R.string.exercises_loading))
            state.unavailable -> Column(Modifier.padding(16.dp)) {
                HierroEmptyState(stringResource(R.string.exercises_editor_unavailable_title), stringResource(R.string.exercises_editor_unavailable_message))
            }
            else -> EditorForm(state, viewModel)
        }
    }
}

@Composable
private fun EditorForm(state: ExerciseEditorUiState, vm: ExerciseEditorViewModel) {
    val input = state.input
    Column(
        Modifier.verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HierroTextField(input.name, vm::onName, stringResource(R.string.exercises_editor_name), errorMessage = state.error(ExerciseField.NAME))
        ChoiceRow(stringResource(R.string.exercises_editor_muscle), MuscleGroup.entries, input.muscleGroup, { it.label }, vm::onMuscleGroup)
        ChoiceRow(stringResource(R.string.exercises_editor_equipment), Equipment.entries, input.equipment, { it.label }, vm::onEquipment)
        ChoiceRow(stringResource(R.string.exercises_editor_type), ExerciseType.entries, input.type, { it.label }, vm::onType)
        HierroTextField(
            input.incrementKg, vm::onIncrementKg, stringResource(R.string.exercises_editor_increment),
            errorMessage = state.error(ExerciseField.INCREMENT_KG),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        HierroTextField(
            input.videoUrl, vm::onVideoUrl, stringResource(R.string.exercises_editor_video),
            errorMessage = state.error(ExerciseField.VIDEO_URL),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        HierroTextField(input.description, vm::onDescription, stringResource(R.string.exercises_editor_description), singleLine = false, errorMessage = state.error(ExerciseField.DESCRIPTION))
        HierroTextField(input.execution, vm::onExecution, stringResource(R.string.exercises_editor_execution), singleLine = false, errorMessage = state.error(ExerciseField.EXECUTION))
        HierroTextField(input.notes, vm::onNotes, stringResource(R.string.exercises_editor_notes), singleLine = false, errorMessage = state.error(ExerciseField.NOTES))
        HierroCard {
            HierroText(stringResource(R.string.exercises_photos_title).uppercase(), Modifier.semantics { heading() }, HierroTheme.typography.label, HierroTheme.colors.textMuted)
            HierroButton(stringResource(R.string.exercises_photos_add), onClick = {}, enabled = false, variant = HierroButtonVariant.Secondary)
            HierroText(stringResource(R.string.exercises_photos_unavailable), color = HierroTheme.colors.textMuted)
        }
        if (state.saveFailed) {
            HierroText(stringResource(R.string.exercises_editor_save_failed), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = HierroTheme.colors.error)
        }
        HierroButton(
            stringResource(R.string.exercises_editor_save), vm::save,
            loading = state.saving, loadingLabel = stringResource(R.string.exercises_editor_saving),
        )
    }
}

@Composable
private fun ExerciseEditorUiState.error(field: ExerciseField): String? = errors[field]?.let { error ->
    stringResource(
        when (error) {
            ExerciseFieldError.NAME_REQUIRED -> R.string.exercises_error_name_required
            ExerciseFieldError.NAME_TOO_LONG -> R.string.exercises_error_name_too_long
            ExerciseFieldError.VIDEO_URL_INVALID -> R.string.exercises_error_video_invalid
            ExerciseFieldError.VIDEO_URL_TOO_LONG -> R.string.exercises_error_video_too_long
            ExerciseFieldError.TEXT_TOO_LONG -> R.string.exercises_error_text_too_long
            ExerciseFieldError.INCREMENT_NOT_A_NUMBER -> R.string.exercises_error_increment_number
            ExerciseFieldError.INCREMENT_NOT_POSITIVE -> R.string.exercises_error_increment_positive
        },
    )
}
