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
import androidx.compose.ui.res.pluralStringResource
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
import com.chrisgoac.gymlog.domain.workout.SetValidation
import com.chrisgoac.gymlog.domain.workout.WorkoutSummary
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
    val finish by viewModel.finishState.collectAsState()
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
        restTimer = { RestTimerHost(viewModel) },
        finish = finish,
        canFinish = viewModel.canFinish,
        onFinish = viewModel::finish,
        onConfirmFinishEmpty = viewModel::confirmFinishEmpty,
        onCancelFinish = viewModel::cancelFinish,
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
    restTimer: @Composable () -> Unit = {},
    finish: FinishUiState = FinishUiState.Idle,
    canFinish: Boolean = false,
    onFinish: () -> Unit = {},
    onConfirmFinishEmpty: () -> Unit = {},
    onCancelFinish: () -> Unit = {},
) {
    if (finish is FinishUiState.Finished) {
        FinishSummaryContent(finish.summary, onDone = onBack, modifier = modifier)
        return
    }
    Column(modifier.fillMaxSize().imePadding()) {
        HierroScreenHeader(stringResource(R.string.train_workout_title)) {
            HierroHeaderAction(stringResource(R.string.train_workout_back), onBack)
        }
        restTimer()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state.status) {
                WorkoutLogStatus.Loading -> item(key = "loading") { HierroLoadingState(stringResource(R.string.train_workout_loading)) }
                WorkoutLogStatus.Missing -> item(key = "missing") {
                    HierroEmptyState(stringResource(R.string.train_workout_missing_title), stringResource(R.string.train_workout_missing_message))
                }
                WorkoutLogStatus.Failed -> item(key = "failed") {
                    HierroErrorState(
                        message = stringResource(R.string.train_error_message),
                        retryLabel = stringResource(R.string.train_workout_retry),
                        onRetry = onRetry,
                        title = stringResource(R.string.train_workout_error_title),
                    )
                }
                WorkoutLogStatus.Ready -> {
                    item(key = "started") {
                        HierroText(
                            stringResource(R.string.train_workout_started, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(state.startedAt ?: 0L))),
                            color = HierroTheme.colors.textMuted,
                        )
                    }
                    if (state.writeFailed) {
                        item(key = "write-failed") {
                            HierroCard {
                                HierroText(stringResource(R.string.train_workout_write_failed), color = HierroTheme.colors.error)
                                HierroButton(stringResource(R.string.train_workout_dismiss), onDismissError, variant = HierroButtonVariant.Secondary)
                            }
                        }
                    }
                    if (state.exercises.isEmpty()) {
                        item(key = "empty") {
                            HierroEmptyState(stringResource(R.string.train_workout_empty_title), stringResource(R.string.train_workout_empty_message))
                        }
                    }
                    // Keyed by the stable logged-exercise id: focus and typed text survive reorders and re-emissions.
                    items(state.exercises, key = { it.id }) { exercise -> ExerciseLogCard(exercise, actions) }
                    item(key = "picker") {
                        if (state.pickerOpen) {
                            ExerciseSearch(state.pickerQuery, pickerResults, onPickerQuery, onPick, onClosePicker)
                        } else {
                            HierroButton(stringResource(R.string.train_workout_add_exercise), onOpenPicker, Modifier.fillMaxWidth())
                        }
                    }
                    item(key = "notes") {
                        HierroTextField(
                            value = state.notes,
                            onValueChange = onNotes,
                            label = stringResource(R.string.train_workout_notes),
                            singleLine = false,
                            errorMessage = if (state.notesTooLong) stringResource(R.string.train_workout_notes_too_long) else null,
                        )
                    }
                    if (canFinish) item(key = "finish") { FinishCard(finish, state.notesTooLong, onFinish, onConfirmFinishEmpty, onCancelFinish) }
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
            stringResource(R.string.train_workout_picker_title).uppercase(),
            Modifier.semantics { heading() },
            HierroTheme.typography.label,
            HierroTheme.colors.textMuted,
        )
        HierroTextField(query, onQuery, stringResource(R.string.train_workout_picker_search))
        if (results.isEmpty()) HierroText(stringResource(R.string.train_workout_picker_empty), color = HierroTheme.colors.textMuted)
        results.take(MAX_RESULTS).forEach { exercise ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HierroText(exercise.name, Modifier.weight(1f))
                HierroButton(
                    stringResource(R.string.train_workout_picker_add),
                    { onPick(exercise) },
                    variant = HierroButtonVariant.Secondary,
                    contentDescription = stringResource(R.string.train_workout_picker_add_description, exercise.name),
                )
            }
        }
        HierroButton(stringResource(R.string.train_workout_picker_close), onClose, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary)
    }
}

/** Enough to find an exercise by typing; the full catalog list would be hundreds of rows. */
private const val MAX_RESULTS = 30

@Composable
private fun FinishCard(
    finish: FinishUiState,
    notesTooLong: Boolean,
    onFinish: () -> Unit,
    onConfirmEmpty: () -> Unit,
    onCancel: () -> Unit,
) {
    HierroCard {
        when (finish) {
            FinishUiState.ConfirmEmpty -> {
                HierroText(stringResource(R.string.train_finish_empty_title).uppercase(), Modifier.semantics { heading() }, HierroTheme.typography.title)
                HierroText(stringResource(R.string.train_finish_empty_message))
                HierroButton(stringResource(R.string.train_finish_empty_confirm), onConfirmEmpty, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary)
                HierroButton(stringResource(R.string.train_finish_empty_cancel), onCancel, Modifier.fillMaxWidth())
            }
            else -> {
                if (finish == FinishUiState.Failed) {
                    HierroText(stringResource(R.string.train_finish_failed), color = HierroTheme.colors.error)
                }
                HierroButton(
                    stringResource(R.string.train_finish_button),
                    onFinish,
                    Modifier.fillMaxWidth(),
                    enabled = !notesTooLong,
                    loading = finish == FinishUiState.Saving,
                )
            }
        }
    }
}

/** Closing screen: what the workout amounted to. */
@Composable
internal fun FinishSummaryContent(summary: WorkoutSummary, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        HierroScreenHeader(stringResource(R.string.train_finish_summary_title))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "stats") {
                HierroCard {
                    HierroText(stringResource(R.string.train_finish_summary_done), Modifier.semantics { heading() }, HierroTheme.typography.title)
                    val minutes = summary.durationSeconds / 60
                    HierroText(
                        if (minutes < 60) stringResource(R.string.train_finish_summary_duration_minutes, minutes)
                        else stringResource(R.string.train_finish_summary_duration_hours, minutes / 60, minutes % 60),
                    )
                    HierroText(pluralStringResource(R.plurals.train_finish_summary_exercises, summary.exerciseCount, summary.exerciseCount))
                    HierroText(pluralStringResource(R.plurals.train_finish_summary_sets, summary.setCount, summary.setCount))
                    HierroText(stringResource(R.string.train_finish_summary_volume, SetValidation.formatWeight(Math.round(summary.volume * 10) / 10.0)))
                }
            }
            item(key = "done") { HierroButton(stringResource(R.string.train_finish_summary_close), onDone, Modifier.fillMaxWidth()) }
        }
    }
}
