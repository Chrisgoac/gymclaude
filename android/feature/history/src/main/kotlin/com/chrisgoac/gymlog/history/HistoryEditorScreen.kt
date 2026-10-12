package com.chrisgoac.gymlog.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.component.HierroButton
import com.chrisgoac.gymlog.designsystem.component.HierroButtonVariant
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroEmptyState
import com.chrisgoac.gymlog.designsystem.component.HierroErrorState
import com.chrisgoac.gymlog.designsystem.component.HierroHeaderAction
import com.chrisgoac.gymlog.designsystem.component.HierroLoadingState
import com.chrisgoac.gymlog.designsystem.component.HierroScreenHeader
import com.chrisgoac.gymlog.designsystem.component.HierroSetRow
import com.chrisgoac.gymlog.designsystem.component.HierroSmallAction
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.component.HierroTextField
import com.chrisgoac.gymlog.designsystem.component.SetRowLabels
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.domain.exercise.Exercise
import com.chrisgoac.gymlog.domain.workout.SetFieldError
import com.chrisgoac.gymlog.feature.history.R

/**
 * Full editor of a past workout: date and time, gym, notes, exercises and sets, with the same set
 * rows as the active workout. Nothing is stored until "Guardar cambios"; Cancel / back asks before
 * discarding changes. [onExit] runs once the editor should close (saved or discarded).
 */
@Composable
fun HistoryEditorScreen(viewModel: HistoryEditorViewModel, onExit: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    val picker by viewModel.pickerResults.collectAsState()
    val exit by viewModel.exit.collectAsState()
    LaunchedEffect(exit) { if (exit) onExit() }
    val actions = remember(viewModel) { EditorActions(viewModel) }
    HistoryEditorContent(state, picker, actions, modifier)
}

/** Everything the editor can ask for (ids are the stable draft ids). */
internal interface HistoryEditorActions {
    fun requestExit()
    fun keepEditing()
    fun discard()
    fun retry()
    fun reload()
    fun save()
    fun dismissSaveError()
    fun onDate(text: String)
    fun onTime(text: String)
    fun onGym(gymId: String?)
    fun onNotes(text: String)
    fun openPicker()
    fun closePicker()
    fun onPickerQuery(query: String)
    fun addExercise(exercise: Exercise)
    fun removeExercise(id: String)
    fun moveExercise(id: String, delta: Int)
    fun addSet(exerciseId: String)
    fun onWeight(setId: String, text: String)
    fun onReps(setId: String, text: String)
    fun setWarmup(setId: String, warmup: Boolean)
    fun removeSet(setId: String)
    fun moveSet(setId: String, delta: Int)
}

private class EditorActions(private val vm: HistoryEditorViewModel) : HistoryEditorActions {
    override fun requestExit() = vm.requestExit()
    override fun keepEditing() = vm.keepEditing()
    override fun discard() = vm.discard()
    override fun retry() = vm.retry()
    override fun reload() = vm.reload()
    override fun save() = vm.save()
    override fun dismissSaveError() = vm.dismissSaveError()
    override fun onDate(text: String) = vm.onDate(text)
    override fun onTime(text: String) = vm.onTime(text)
    override fun onGym(gymId: String?) = vm.onGym(gymId)
    override fun onNotes(text: String) = vm.onNotes(text)
    override fun openPicker() = vm.openPicker()
    override fun closePicker() = vm.closePicker()
    override fun onPickerQuery(query: String) = vm.onPickerQuery(query)
    override fun addExercise(exercise: Exercise) = vm.addExercise(exercise)
    override fun removeExercise(id: String) = vm.removeExercise(id)
    override fun moveExercise(id: String, delta: Int) = vm.moveExercise(id, delta)
    override fun addSet(exerciseId: String) = vm.addSet(exerciseId)
    override fun onWeight(setId: String, text: String) = vm.onWeight(setId, text)
    override fun onReps(setId: String, text: String) = vm.onReps(setId, text)
    override fun setWarmup(setId: String, warmup: Boolean) = vm.setWarmup(setId, warmup)
    override fun removeSet(setId: String) = vm.removeSet(setId)
    override fun moveSet(setId: String, delta: Int) = vm.moveSet(setId, delta)
}

@Composable
internal fun HistoryEditorContent(
    state: HistoryEditorUiState,
    pickerResults: List<Exercise>,
    actions: HistoryEditorActions,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            HierroScreenHeader(stringResource(R.string.history_editor_title)) {
                HierroHeaderAction(stringResource(R.string.history_editor_cancel), actions::requestExit)
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state.status) {
                    EditorStatus.Loading -> item(key = "loading") { HierroLoadingState(stringResource(R.string.history_detail_loading)) }
                    EditorStatus.NotFound -> item(key = "missing") {
                        HierroEmptyState(stringResource(R.string.history_detail_not_found_title), stringResource(R.string.history_detail_not_found_message))
                    }
                    EditorStatus.Unfinished -> item(key = "unfinished") {
                        HierroEmptyState(stringResource(R.string.history_editor_unfinished_title), stringResource(R.string.history_editor_unfinished_message))
                    }
                    EditorStatus.Failed -> item(key = "failed") {
                        HierroErrorState(
                            message = stringResource(R.string.history_error_message),
                            retryLabel = stringResource(R.string.history_retry),
                            onRetry = actions::retry,
                            title = stringResource(R.string.history_detail_error_title),
                        )
                    }
                    EditorStatus.Ready -> readyItems(state, pickerResults, actions)
                }
            }
        }
        if (state.confirmingExit) DiscardConfirmation(actions)
    }
}

private fun LazyListScope.readyItems(state: HistoryEditorUiState, pickerResults: List<Exercise>, actions: HistoryEditorActions) {
    if (state.conflict || state.gone) {
        item(key = "conflict") {
            HierroCard {
                HierroText(
                    stringResource(if (state.gone) R.string.history_editor_gone else R.string.history_editor_conflict),
                    Modifier.semantics { heading() },
                    color = HierroTheme.colors.error,
                )
                if (state.conflict) HierroButton(stringResource(R.string.history_editor_reload), actions::reload, Modifier.fillMaxWidth())
                HierroButton(stringResource(R.string.history_editor_close), actions::discard, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary)
            }
        }
    }
    if (state.showIssues) item(key = "issues") { IssuesCard(state.issues) }
    if (state.saveFailed) {
        item(key = "save-failed") {
            HierroCard {
                HierroText(stringResource(R.string.history_editor_save_failed), color = HierroTheme.colors.error)
                HierroButton(stringResource(R.string.history_editor_dismiss), actions::dismissSaveError, variant = HierroButtonVariant.Secondary)
            }
        }
    }
    item(key = "when") {
        HierroCard {
            HierroText(stringResource(R.string.history_editor_when).uppercase(), Modifier.semantics { heading() }, HierroTheme.typography.label, HierroTheme.colors.textMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HierroTextField(
                    value = state.dateText,
                    onValueChange = actions::onDate,
                    label = stringResource(R.string.history_editor_date),
                    modifier = Modifier.weight(1.4f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    errorMessage = if (state.dateUnreadable) stringResource(R.string.history_editor_date_unreadable) else null,
                )
                HierroTextField(
                    value = state.timeText,
                    onValueChange = actions::onTime,
                    label = stringResource(R.string.history_editor_time),
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        }
    }
    item(key = "gym") {
        HierroCard {
            val options = listOf<String?>(null) + state.gyms.map { it.id } +
                listOfNotNull(state.gymId?.takeIf { id -> state.gyms.none { it.id == id } })
            HistoryChoiceRow(
                caption = stringResource(R.string.history_filter_gym),
                options = options,
                selected = state.gymId,
                labelOf = { id ->
                    when {
                        id == null -> stringResource(R.string.history_gym_none)
                        else -> state.gyms.firstOrNull { it.id == id }?.name ?: state.currentGymName ?: stringResource(R.string.history_editor_gym_unknown)
                    }
                },
                onSelect = actions::onGym,
            )
        }
    }
    item(key = "notes") {
        HierroTextField(
            value = state.notes,
            onValueChange = actions::onNotes,
            label = stringResource(R.string.history_detail_notes),
            singleLine = false,
            errorMessage = if (state.notesTooLong) stringResource(R.string.history_editor_notes_too_long) else null,
        )
    }
    if (state.exercises.isEmpty()) {
        item(key = "no-exercises") { HierroText(stringResource(R.string.history_detail_no_exercises), color = HierroTheme.colors.textMuted) }
    }
    // Keyed by stable draft id: typed text and focus survive reorders.
    items(state.exercises, key = { it.id }) { exercise -> EditorExerciseCard(exercise, actions) }
    item(key = "picker") {
        if (state.pickerOpen) {
            ExercisePicker(state.pickerQuery, pickerResults, actions)
        } else {
            HierroButton(stringResource(R.string.history_editor_add_exercise), actions::openPicker, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary)
        }
    }
    item(key = "save") {
        HierroButton(
            stringResource(R.string.history_editor_save),
            actions::save,
            Modifier.fillMaxWidth(),
            enabled = !state.conflict && !state.gone,
            loading = state.saving,
        )
    }
}

@Composable
private fun IssuesCard(issues: List<EditorIssue>) {
    HierroCard {
        HierroText(stringResource(R.string.history_editor_issues_title).uppercase(), Modifier.semantics { heading() }, HierroTheme.typography.title, HierroTheme.colors.error)
        issues.forEach { issue -> HierroText("• " + issueText(issue), color = HierroTheme.colors.error) }
    }
}

@Composable
private fun issueText(issue: EditorIssue): String = when (issue) {
    EditorIssue.DateUnreadable -> stringResource(R.string.history_editor_date_unreadable)
    EditorIssue.DateInFuture -> stringResource(R.string.history_editor_issue_future)
    EditorIssue.DateOutOfRange -> stringResource(R.string.history_editor_issue_out_of_range)
    EditorIssue.GymUnavailable -> stringResource(R.string.history_editor_issue_gym)
    EditorIssue.NotesTooLong -> stringResource(R.string.history_editor_notes_too_long)
    is EditorIssue.UnknownExercise -> stringResource(R.string.history_editor_issue_exercise, issue.exerciseNumber)
    is EditorIssue.InvalidWeight -> stringResource(
        R.string.history_editor_issue_weight,
        issue.exerciseName ?: stringResource(R.string.history_detail_unknown_exercise),
        issue.setNumber,
        weightErrorText(issue.error),
    )
    is EditorIssue.InvalidReps -> stringResource(
        R.string.history_editor_issue_reps,
        issue.exerciseName ?: stringResource(R.string.history_detail_unknown_exercise),
        issue.setNumber,
        repsErrorText(issue.error),
    )
}

@Composable
private fun EditorExerciseCard(exercise: EditorExerciseUi, actions: HistoryEditorActions) {
    val name = exercise.name ?: stringResource(R.string.history_detail_unknown_exercise)
    HierroCard {
        HierroText(name, Modifier.semantics { heading() }, HierroTheme.typography.title)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!exercise.isFirst) {
                HierroSmallAction(stringResource(R.string.history_editor_up), stringResource(R.string.history_editor_exercise_up_description, name)) {
                    actions.moveExercise(exercise.id, -1)
                }
            }
            if (!exercise.isLast) {
                HierroSmallAction(stringResource(R.string.history_editor_down), stringResource(R.string.history_editor_exercise_down_description, name)) {
                    actions.moveExercise(exercise.id, +1)
                }
            }
            HierroSmallAction(stringResource(R.string.history_editor_remove), stringResource(R.string.history_editor_exercise_remove_description, name)) {
                actions.removeExercise(exercise.id)
            }
        }
        exercise.sets.forEachIndexed { index, set ->
            EditorSetRow(set, name, isFirst = index == 0, isLast = index == exercise.sets.lastIndex, actions = actions)
        }
        HierroButton(
            stringResource(R.string.history_editor_add_set),
            { actions.addSet(exercise.id) },
            Modifier.fillMaxWidth(),
            variant = HierroButtonVariant.Secondary,
            contentDescription = stringResource(R.string.history_editor_add_set_description, name),
        )
    }
}

@Composable
private fun EditorSetRow(set: EditorSetUi, exerciseName: String, isFirst: Boolean, isLast: Boolean, actions: HistoryEditorActions) {
    HierroSetRow(
        badge = if (set.warmup) stringResource(R.string.history_editor_warmup_badge) else set.number.toString(),
        weightText = set.weightText,
        repsText = set.repsText,
        onWeight = { actions.onWeight(set.id, it) },
        onReps = { actions.onReps(set.id, it) },
        labels = SetRowLabels(
            weight = stringResource(R.string.history_editor_weight),
            reps = stringResource(R.string.history_editor_reps),
            weightDescription = stringResource(R.string.history_editor_weight_description, set.number, exerciseName),
            repsDescription = stringResource(R.string.history_editor_reps_description, set.number, exerciseName),
        ),
        weightError = set.weightError?.let { weightErrorText(it) },
        repsError = set.repsError?.let { repsErrorText(it) },
    ) {
        HierroSmallAction(
            stringResource(if (set.warmup) R.string.history_editor_warmup_on else R.string.history_editor_warmup),
            stringResource(R.string.history_editor_warmup_description, set.number, exerciseName),
            selected = set.warmup,
        ) { actions.setWarmup(set.id, !set.warmup) }
        if (!isFirst) HierroSmallAction("↑", stringResource(R.string.history_editor_set_up_description, set.number, exerciseName)) { actions.moveSet(set.id, -1) }
        if (!isLast) HierroSmallAction("↓", stringResource(R.string.history_editor_set_down_description, set.number, exerciseName)) { actions.moveSet(set.id, +1) }
        HierroSmallAction("✕", stringResource(R.string.history_editor_set_remove_description, set.number, exerciseName)) { actions.removeSet(set.id) }
    }
}

@Composable
private fun ExercisePicker(query: String, results: List<Exercise>, actions: HistoryEditorActions) {
    HierroCard {
        HierroText(stringResource(R.string.history_editor_picker_title).uppercase(), Modifier.semantics { heading() }, HierroTheme.typography.label, HierroTheme.colors.textMuted)
        HierroTextField(query, actions::onPickerQuery, stringResource(R.string.history_editor_picker_search))
        if (results.isEmpty()) HierroText(stringResource(R.string.history_editor_picker_empty), color = HierroTheme.colors.textMuted)
        results.forEach { exercise ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HierroText(exercise.name, Modifier.weight(1f))
                HierroButton(
                    stringResource(R.string.history_editor_picker_add),
                    { actions.addExercise(exercise) },
                    variant = HierroButtonVariant.Secondary,
                    contentDescription = stringResource(R.string.history_editor_picker_add_description, exercise.name),
                )
            }
        }
        HierroButton(stringResource(R.string.history_editor_picker_close), actions::closePicker, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary)
    }
}

/** Blocking "discard changes?" prompt over the editor (the scrim swallows touches). */
@Composable
private fun DiscardConfirmation(actions: HistoryEditorActions) {
    val title = stringResource(R.string.history_editor_discard_title)
    Box(
        Modifier
            .fillMaxSize()
            .background(HierroTheme.colors.ink.copy(alpha = 0.5f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
            .padding(16.dp)
            .semantics { paneTitle = title },
        contentAlignment = Alignment.Center,
    ) {
        HierroCard {
            HierroText(title.uppercase(), Modifier.semantics { heading() }, HierroTheme.typography.title)
            HierroText(stringResource(R.string.history_editor_discard_message))
            HierroButton(stringResource(R.string.history_editor_discard), actions::discard, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary)
            HierroButton(stringResource(R.string.history_editor_keep_editing), actions::keepEditing, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun weightErrorText(error: SetFieldError): String = stringResource(
    if (error == SetFieldError.TOO_LARGE) R.string.history_editor_error_too_large_weight else error.messageRes(),
)

@Composable
private fun repsErrorText(error: SetFieldError): String = stringResource(error.messageRes())

private fun SetFieldError.messageRes(): Int = when (this) {
    SetFieldError.REQUIRED -> R.string.history_editor_error_required
    SetFieldError.NOT_A_NUMBER -> R.string.history_editor_error_not_a_number
    SetFieldError.NOT_INTEGER -> R.string.history_editor_error_not_integer
    SetFieldError.NEGATIVE -> R.string.history_editor_error_negative
    SetFieldError.TOO_LARGE -> R.string.history_editor_error_too_large_reps
    SetFieldError.ZERO_REPS -> R.string.history_editor_error_zero_reps
}
