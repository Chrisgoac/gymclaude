package com.chrisgoac.gymlog.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.domain.workout.SetValidation
import com.chrisgoac.gymlog.domain.workout.WorkoutExercise
import com.chrisgoac.gymlog.feature.history.R
import java.text.DateFormat
import java.util.Date

/** One finished workout: ordered exercises and sets, gym (changeable), notes and deletion with confirmation. */
@Composable
fun HistoryDetailScreen(viewModel: HistoryDetailViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    val deleted by viewModel.deleted.collectAsState()
    LaunchedEffect(deleted) { if (deleted) onBack() }
    HistoryDetailContent(
        state = state,
        onBack = onBack,
        onRetry = viewModel::retry,
        onOpenGymPicker = viewModel::openGymPicker,
        onCloseGymPicker = viewModel::closeGymPicker,
        onChangeGym = viewModel::changeGym,
        onRequestDelete = viewModel::requestDelete,
        onCancelDelete = viewModel::cancelDelete,
        onConfirmDelete = viewModel::confirmDelete,
        modifier = modifier,
    )
}

@Composable
internal fun HistoryDetailContent(
    state: HistoryDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenGymPicker: () -> Unit,
    onCloseGymPicker: () -> Unit,
    onChangeGym: (String?) -> Unit,
    onRequestDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        HierroScreenHeader(stringResource(R.string.history_detail_title)) { HierroHeaderAction(stringResource(R.string.history_back), onBack) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state) {
                HistoryDetailUiState.Loading -> item(key = "loading") { HierroLoadingState(stringResource(R.string.history_detail_loading)) }
                HistoryDetailUiState.NotFound -> item(key = "missing") {
                    HierroEmptyState(stringResource(R.string.history_detail_not_found_title), stringResource(R.string.history_detail_not_found_message))
                }
                HistoryDetailUiState.Failed -> item(key = "failed") {
                    HierroErrorState(
                        message = stringResource(R.string.history_error_message),
                        retryLabel = stringResource(R.string.history_retry),
                        onRetry = onRetry,
                        title = stringResource(R.string.history_detail_error_title),
                    )
                }
                is HistoryDetailUiState.Found -> foundItems(state, onOpenGymPicker, onCloseGymPicker, onChangeGym, onRequestDelete, onCancelDelete, onConfirmDelete)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.foundItems(
    state: HistoryDetailUiState.Found,
    onOpenGymPicker: () -> Unit,
    onCloseGymPicker: () -> Unit,
    onChangeGym: (String?) -> Unit,
    onRequestDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    val detail = state.detail
    val session = detail.workout.session
    item(key = "summary") {
        HierroCard {
            HierroText(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(session.startedAt)),
                Modifier.semantics { heading() },
                HierroTheme.typography.title,
            )
            HierroText(detail.routineName ?: stringResource(R.string.history_free_workout))
            HierroText(
                durationText(detail.summary.durationSeconds) + " · " + exercisesText(detail.summary.exerciseCount) + " · " + setsText(detail.summary.setCount),
                color = HierroTheme.colors.textMuted,
            )
            HierroText(
                stringResource(R.string.history_volume, SetValidation.formatWeight(Math.round(detail.summary.volume * 10) / 10.0)),
                color = HierroTheme.colors.textMuted,
            )
        }
    }
    item(key = "gym") {
        HierroCard {
            HierroText(stringResource(R.string.history_detail_gym, detail.gymName ?: stringResource(R.string.history_gym_none)))
            if (state.gymFailed) HierroText(stringResource(R.string.history_detail_gym_failed), color = HierroTheme.colors.error)
            if (state.choosingGym) {
                val gymIds = listOf<String?>(null) + state.gyms.map { it.id }
                HistoryChoiceRow(
                    caption = stringResource(R.string.history_filter_gym),
                    options = gymIds,
                    selected = session.gymId,
                    labelOf = { id -> if (id == null) stringResource(R.string.history_gym_none) else state.gyms.firstOrNull { it.id == id }?.name ?: detail.gymName.orEmpty() },
                    onSelect = onChangeGym,
                )
                HierroButton(stringResource(R.string.history_detail_gym_cancel), onCloseGymPicker, variant = HierroButtonVariant.Secondary)
            } else {
                HierroButton(stringResource(R.string.history_detail_gym_change), onOpenGymPicker, variant = HierroButtonVariant.Secondary)
            }
        }
    }
    session.notes?.let { notes ->
        item(key = "notes") {
            HierroCard {
                HierroText(stringResource(R.string.history_detail_notes).uppercase(), style = HierroTheme.typography.label, color = HierroTheme.colors.textMuted)
                HierroText(notes)
            }
        }
    }
    if (detail.workout.exercises.isEmpty()) {
        item(key = "no-exercises") { HierroText(stringResource(R.string.history_detail_no_exercises), color = HierroTheme.colors.textMuted) }
    }
    items(detail.workout.exercises, key = { it.id }) { exercise -> ExerciseDetail(exercise) }
    item(key = "delete") { DeleteCard(state, onRequestDelete, onCancelDelete, onConfirmDelete) }
}

@Composable
private fun ExerciseDetail(exercise: WorkoutExercise) {
    HierroCard {
        HierroText(exercise.exerciseName ?: stringResource(R.string.history_detail_unknown_exercise), style = HierroTheme.typography.title)
        exercise.sets.forEachIndexed { index, set ->
            val weight = SetValidation.formatWeight(set.weight)
            HierroText(
                if (set.warmup) stringResource(R.string.history_detail_set_warmup, index + 1, weight, set.reps)
                else stringResource(R.string.history_detail_set_line, index + 1, weight, set.reps),
                color = HierroTheme.colors.textMuted,
            )
        }
    }
}

@Composable
private fun DeleteCard(state: HistoryDetailUiState.Found, onRequest: () -> Unit, onCancel: () -> Unit, onConfirm: () -> Unit) {
    HierroCard {
        if (state.deleteFailed) HierroText(stringResource(R.string.history_detail_delete_failed), color = HierroTheme.colors.error)
        if (state.confirmingDelete) {
            HierroText(stringResource(R.string.history_detail_delete_confirm_title).uppercase(), Modifier.semantics { heading() }, HierroTheme.typography.title, HierroTheme.colors.error)
            HierroText(stringResource(R.string.history_detail_delete_confirm_message))
            HierroButton(stringResource(R.string.history_detail_delete_confirm), onConfirm, Modifier.fillMaxWidth(), loading = state.deleting, variant = HierroButtonVariant.Secondary)
            HierroButton(stringResource(R.string.history_detail_delete_cancel), onCancel, Modifier.fillMaxWidth(), enabled = !state.deleting)
        } else {
            HierroButton(stringResource(R.string.history_detail_delete), onRequest, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary)
        }
    }
}
