package com.chrisgoac.gymlog.routines

import com.chrisgoac.gymlog.feature.routines.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
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
import com.chrisgoac.gymlog.domain.exercise.Exercise
import com.chrisgoac.gymlog.domain.routine.RoutineError
import com.chrisgoac.gymlog.domain.routine.RoutineExerciseInput
import com.chrisgoac.gymlog.domain.routine.RoutineField

private val NumberKeyboard = KeyboardOptions(keyboardType = KeyboardType.Number)

/** Create or edit a routine. [onDone] fires once after the local commit (saved, archived/restored or deleted). */
@Composable
fun RoutineEditorScreen(
    viewModel: RoutineEditorViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val picker by viewModel.pickerResults.collectAsState()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    val title = stringResource(if (state.routineId == null) R.string.editor_title_new else R.string.editor_title_edit)
    Column(modifier.fillMaxSize()) {
        HierroScreenHeader(title) { HierroHeaderAction(stringResource(R.string.routines_back), onBack) }
        when {
            state.loading -> HierroLoadingState(stringResource(R.string.routines_loading))
            state.unavailable -> Column(Modifier.padding(16.dp)) {
                HierroEmptyState(stringResource(R.string.editor_unavailable_title), stringResource(R.string.editor_unavailable_message))
            }
            else -> EditorForm(state, picker, viewModel)
        }
    }
}

@Composable
private fun EditorForm(state: RoutineEditorUiState, pickerResults: List<Exercise>, vm: RoutineEditorViewModel) {
    val input = state.input
    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "name") {
            HierroTextField(input.name, vm::onName, stringResource(R.string.editor_name), errorMessage = state.message(null, RoutineField.NAME))
        }
        item(key = "description") {
            HierroTextField(
                input.description, vm::onDescription, stringResource(R.string.editor_description),
                singleLine = false, errorMessage = state.message(null, RoutineField.DESCRIPTION),
            )
        }
        item(key = "exercises-title") {
            HierroText(
                stringResource(R.string.editor_exercises_title).uppercase(),
                Modifier.semantics { heading() }, HierroTheme.typography.label, HierroTheme.colors.textMuted,
            )
        }
        if (input.exercises.isEmpty()) {
            item(key = "exercises-empty") { HierroText(stringResource(R.string.editor_exercises_empty), color = HierroTheme.colors.textMuted) }
        }
        items(input.exercises, key = { it.key }) { exercise ->
            val index = input.exercises.indexOf(exercise)
            ExerciseCard(
                exercise = exercise,
                state = state,
                canMoveUp = index > 0,
                canMoveDown = index < input.exercises.lastIndex,
                vm = vm,
            )
        }
        item(key = "picker") {
            if (state.pickerOpen) {
                ExercisePicker(state.pickerQuery, pickerResults, vm::onPickerQuery, vm::addExercise, vm::closePicker)
            } else {
                HierroButton(stringResource(R.string.editor_add_exercise), vm::openPicker, variant = HierroButtonVariant.Secondary)
            }
        }
        item(key = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.saveFailed) {
                    HierroText(
                        stringResource(R.string.editor_save_failed),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = HierroTheme.colors.error,
                    )
                }
                HierroButton(stringResource(R.string.editor_save), vm::save, loading = state.saving, loadingLabel = stringResource(R.string.editor_saving))
                if (state.routineId != null) ManageCard(state, vm)
            }
        }
    }
}

@Composable
private fun ExerciseCard(
    exercise: RoutineExerciseInput,
    state: RoutineEditorUiState,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    vm: RoutineEditorViewModel,
) {
    val name = exercise.exerciseName ?: stringResource(R.string.editor_missing_exercise)
    val up = stringResource(R.string.editor_exercise_up_description, name)
    val down = stringResource(R.string.editor_exercise_down_description, name)
    val remove = stringResource(R.string.editor_exercise_remove_description, name)
    val key = exercise.key
    HierroCard {
        HierroText(name, style = HierroTheme.typography.title)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HierroButton(
                stringResource(R.string.editor_exercise_up), { vm.moveUp(key) }, contentDescription = up, variant = HierroButtonVariant.Secondary, enabled = canMoveUp,
            )
            HierroButton(
                stringResource(R.string.editor_exercise_down), { vm.moveDown(key) }, contentDescription = down, variant = HierroButtonVariant.Secondary, enabled = canMoveDown,
            )
            HierroButton(
                stringResource(R.string.editor_exercise_remove), { vm.remove(key) }, contentDescription = remove, variant = HierroButtonVariant.Secondary,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HierroTextField(
                exercise.sets, { vm.onSets(key, it) }, stringResource(R.string.editor_sets), Modifier.weight(1f),
                errorMessage = state.message(key, RoutineField.SETS), keyboardOptions = NumberKeyboard,
            )
            HierroTextField(
                exercise.restSeconds, { vm.onRest(key, it) }, stringResource(R.string.editor_rest), Modifier.weight(1f),
                errorMessage = state.message(key, RoutineField.REST), keyboardOptions = NumberKeyboard,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HierroTextField(
                exercise.repsMin, { vm.onRepsMin(key, it) }, stringResource(R.string.editor_reps_min), Modifier.weight(1f),
                errorMessage = state.message(key, RoutineField.REPS_MIN), keyboardOptions = NumberKeyboard,
            )
            HierroTextField(
                exercise.repsMax, { vm.onRepsMax(key, it) }, stringResource(R.string.editor_reps_max), Modifier.weight(1f),
                errorMessage = state.message(key, RoutineField.REPS_MAX), keyboardOptions = NumberKeyboard,
            )
        }
        HierroTextField(
            exercise.notes, { vm.onNotes(key, it) }, stringResource(R.string.editor_notes),
            singleLine = false, errorMessage = state.message(key, RoutineField.NOTES),
        )
    }
}

@Composable
private fun ExercisePicker(
    query: String,
    results: List<Exercise>,
    onQuery: (String) -> Unit,
    onAdd: (Exercise) -> Unit,
    onClose: () -> Unit,
) {
    HierroCard {
        HierroText(stringResource(R.string.picker_title).uppercase(), Modifier.semantics { heading() }, HierroTheme.typography.label, HierroTheme.colors.textMuted)
        HierroTextField(query, onQuery, stringResource(R.string.picker_search))
        if (results.isEmpty()) HierroText(stringResource(R.string.picker_empty), color = HierroTheme.colors.textMuted)
        results.take(PICKER_LIMIT).forEach { exercise ->
            val description = stringResource(R.string.picker_add_description, exercise.name)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    HierroText(exercise.name)
                    HierroText("${exercise.muscleGroup.label} · ${exercise.equipment.label}", color = HierroTheme.colors.textMuted)
                }
                HierroButton(stringResource(R.string.picker_add), { onAdd(exercise) }, contentDescription = description, variant = HierroButtonVariant.Secondary)
            }
        }
        HierroButton(stringResource(R.string.picker_done), onClose)
    }
}

private const val PICKER_LIMIT = 30

@Composable
private fun ManageCard(state: RoutineEditorUiState, vm: RoutineEditorViewModel) {
    HierroCard {
        if (state.confirmingDelete) {
            HierroText(stringResource(R.string.editor_delete_confirm_title).uppercase(), Modifier.semantics { heading() }, HierroTheme.typography.title, HierroTheme.colors.error)
            HierroText(stringResource(R.string.editor_delete_confirm_message))
            HierroButton(stringResource(R.string.editor_delete_confirm), vm::confirmDelete, loading = state.saving, variant = HierroButtonVariant.Secondary)
            HierroButton(stringResource(R.string.editor_delete_cancel), vm::cancelDelete, enabled = !state.saving)
        } else {
            HierroButton(
                stringResource(if (state.archived) R.string.editor_restore else R.string.editor_archive), vm::archiveToggle,
                variant = HierroButtonVariant.Secondary, enabled = !state.saving,
            )
            HierroButton(stringResource(R.string.editor_delete), vm::askDelete, variant = HierroButtonVariant.Secondary, enabled = !state.saving)
        }
    }
}

@Composable
private fun RoutineEditorUiState.message(exerciseKey: String?, field: RoutineField): String? =
    issue(exerciseKey, field)?.let { error ->
        stringResource(
            when (error) {
                RoutineError.REQUIRED -> R.string.error_name_required
                RoutineError.TOO_LONG -> if (field == RoutineField.NAME) R.string.error_name_too_long else R.string.error_text_too_long
                RoutineError.NOT_A_NUMBER -> R.string.error_number
                RoutineError.NOT_POSITIVE -> R.string.error_positive
                RoutineError.TOO_LARGE -> when (field) {
                    RoutineField.SETS -> R.string.error_sets_large
                    RoutineField.REST -> R.string.error_rest_large
                    else -> R.string.error_reps_large
                }
                RoutineError.RANGE_INVERTED -> R.string.error_range
                RoutineError.DUPLICATE_KEY -> R.string.error_duplicate
            },
        )
    }
