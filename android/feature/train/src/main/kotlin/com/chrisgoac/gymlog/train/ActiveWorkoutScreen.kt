package com.chrisgoac.gymlog.train

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.chrisgoac.gymlog.designsystem.component.HierroTextField
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.domain.exercise.Exercise
import com.chrisgoac.gymlog.feature.train.R
import java.text.DateFormat
import java.util.Date

/**
 * The active workout: fast set logging with targets, last time and progression suggestion.
 * Leaving the screen (back or navigation) flushes pending edits first.
 */
@Composable
fun ActiveWorkoutScreen(viewModel: WorkoutViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    val picker by viewModel.pickerResults.collectAsState()
    // Any way out of this destination (system back, tab switch) commits what was typed.
    DisposableEffect(viewModel) { onDispose { viewModel.flushPending() } }
    val actions = remember(viewModel) { ViewModelActions(viewModel) }
    ActiveWorkoutContent(
        state = state,
        pickerResults = picker,
        actions = actions,
        onBack = {
            viewModel.flushPending()
            onBack()
        },
        onNotes = viewModel::onNotes,
        onOpenPicker = viewModel::openPicker,
        onClosePicker = viewModel::closePicker,
        onPickerQuery = viewModel::onPickerQuery,
        onPick = viewModel::addExercise,
        onDismissError = viewModel::dismissWriteError,
        onRetry = viewModel::retry,
        modifier = modifier,
    )
}

private class ViewModelActions(private val vm: WorkoutViewModel) : ExerciseLogActions {
    override fun onWeight(setId: String, text: String) = vm.onWeight(setId, text)
    override fun onReps(setId: String, text: String) = vm.onReps(setId, text)
    override fun onComplete(setId: String) = vm.completeSet(setId)
    override fun onReopen(setId: String) = vm.reopenSet(setId)
    override fun onWarmup(setId: String, warmup: Boolean) = vm.setWarmup(setId, warmup)
    override fun onMoveSet(setId: String, delta: Int) = vm.moveSet(setId, delta)
    override fun onRemoveSet(setId: String) = vm.removeSet(setId)
    override fun onAddSet(exerciseId: String) = vm.addSet(exerciseId)
    override fun onMoveExercise(exerciseId: String, delta: Int) = vm.moveExercise(exerciseId, delta)
    override fun onRemoveExercise(exerciseId: String) = vm.removeExercise(exerciseId)
}

@Composable
internal fun ActiveWorkoutContent(
    state: WorkoutLogUiState,
    pickerResults: List<Exercise>,
    actions: ExerciseLogActions,
    onBack: () -> Unit,
    onNotes: (String) -> Unit,
    onOpenPicker: () -> Unit,
    onClosePicker: () -> Unit,
    onPickerQuery: (String) -> Unit,
    onPick: (Exercise) -> Unit,
    onDismissError: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().imePadding()) {
        HierroScreenHeader(stringResource(R.string.workout_title)) {
            HierroHeaderAction(stringResource(R.string.workout_back), onBack)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state.status) {
                WorkoutLogStatus.Loading -> item(key = "loading") { HierroLoadingState(stringResource(R.string.workout_loading)) }
                WorkoutLogStatus.Missing -> item(key = "missing") {
                    HierroEmptyState(stringResource(R.string.workout_missing_title), stringResource(R.string.workout_missing_message))
                }
                WorkoutLogStatus.Failed -> item(key = "failed") {
                    HierroErrorState(
                        message = stringResource(R.string.train_error_message),
                        retryLabel = stringResource(R.string.workout_retry),
                        onRetry = onRetry,
                        title = stringResource(R.string.workout_error_title),
                    )
                }
                WorkoutLogStatus.Ready -> {
                    item(key = "started") {
                        HierroText(
                            stringResource(R.string.workout_started, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(state.startedAt ?: 0L))),
                            color = HierroTheme.colors.textMuted,
                        )
                    }
                    if (state.writeFailed) {
                        item(key = "write-failed") {
                            HierroCard {
                                HierroText(stringResource(R.string.workout_write_failed), color = HierroTheme.colors.error)
                                HierroButton(stringResource(R.string.workout_dismiss), onDismissError, variant = HierroButtonVariant.Secondary)
                            }
                        }
                    }
                    if (state.exercises.isEmpty()) {
                        item(key = "empty") {
                            HierroEmptyState(stringResource(R.string.workout_empty_title), stringResource(R.string.workout_empty_message))
                        }
                    }
                    // Keyed by the stable logged-exercise id: focus and typed text survive reorders and re-emissions.
                    items(state.exercises, key = { it.id }) { exercise -> ExerciseLogCard(exercise, actions) }
                    item(key = "picker") {
                        if (state.pickerOpen) {
                            ExerciseSearch(state.pickerQuery, pickerResults, onPickerQuery, onPick, onClosePicker)
                        } else {
                            HierroButton(stringResource(R.string.workout_add_exercise), onOpenPicker, Modifier.fillMaxWidth())
                        }
                    }
                    item(key = "notes") {
                        HierroTextField(
                            value = state.notes,
                            onValueChange = onNotes,
                            label = stringResource(R.string.workout_notes),
                            singleLine = false,
                            errorMessage = if (state.notesTooLong) stringResource(R.string.workout_notes_too_long) else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExerciseSearch(
    query: String,
    results: List<Exercise>,
    onQuery: (String) -> Unit,
    onPick: (Exercise) -> Unit,
    onClose: () -> Unit,
) {
    HierroCard {
        HierroText(
            stringResource(R.string.workout_picker_title).uppercase(),
            Modifier.semantics { heading() },
            HierroTheme.typography.label,
            HierroTheme.colors.textMuted,
        )
        HierroTextField(query, onQuery, stringResource(R.string.workout_picker_search))
        if (results.isEmpty()) HierroText(stringResource(R.string.workout_picker_empty), color = HierroTheme.colors.textMuted)
        results.take(MAX_RESULTS).forEach { exercise ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HierroText(exercise.name, Modifier.weight(1f))
                HierroButton(
                    stringResource(R.string.workout_picker_add),
                    { onPick(exercise) },
                    variant = HierroButtonVariant.Secondary,
                    contentDescription = stringResource(R.string.workout_picker_add_description, exercise.name),
                )
            }
        }
        HierroButton(stringResource(R.string.workout_picker_close), onClose, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary)
    }
}

/** Enough to find an exercise by typing; the full catalog list would be hundreds of rows. */
private const val MAX_RESULTS = 30
