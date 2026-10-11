package com.chrisgoac.gymlog.routines

import com.chrisgoac.gymlog.feature.routines.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
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
import com.chrisgoac.gymlog.domain.routine.RoutineSummary

/**
 * The user's routines in manual order with the rotation's suggested next one marked.
 * [onStartRoutine] hands the routine id to whoever starts workouts; this feature never creates one.
 */
@Composable
fun RoutineListScreen(
    viewModel: RoutineListViewModel,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onStartRoutine: (String) -> Unit,
    onSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onGenerateMesocycle: (() -> Unit)? = null,
    mesocycleIsPro: Boolean = false,
) {
    val state by viewModel.state.collectAsState()
    RoutineListContent(
        state = state,
        onOpen = onOpen,
        onCreate = onCreate,
        onStartRoutine = onStartRoutine,
        onMoveUp = viewModel::moveUp,
        onMoveDown = viewModel::moveDown,
        onRestore = viewModel::restore,
        onRetry = viewModel::retry,
        onDismissError = viewModel::dismissError,
        onSettings = onSettings,
        modifier = modifier,
        onGenerateMesocycle = onGenerateMesocycle,
        mesocycleIsPro = mesocycleIsPro,
    )
}

@Composable
internal fun RoutineListContent(
    state: RoutineListUiState,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onStartRoutine: (String) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onRestore: (String) -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onGenerateMesocycle: (() -> Unit)? = null,
    mesocycleIsPro: Boolean = false,
) {
    val newDescription = stringResource(R.string.routines_new_description)
    Column(modifier.fillMaxSize()) {
        HierroScreenHeader(stringResource(R.string.routines_title)) {
            HierroHeaderAction(stringResource(R.string.routines_new), onCreate, Modifier.semantics { contentDescription = newDescription })
            if (onSettings != null) HierroHeaderAction(stringResource(R.string.routines_settings), onSettings)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state.status) {
                LoadStatus.Loading -> item(key = "loading") { HierroLoadingState(stringResource(R.string.routines_loading)) }
                LoadStatus.Failed -> item(key = "failed") {
                    HierroErrorState(
                        message = stringResource(R.string.routines_error_message),
                        retryLabel = stringResource(R.string.routines_retry),
                        onRetry = onRetry,
                        title = stringResource(R.string.routines_error_title),
                    )
                }
                LoadStatus.Ready -> {
                    if (onGenerateMesocycle != null) {
                        item(key = "mesocycle") { MesocycleAction(mesocycleIsPro, onGenerateMesocycle) }
                    }
                    if (state.reorderFailed) {
                        item(key = "action-failed") {
                            HierroCard {
                                HierroText(stringResource(R.string.routines_action_failed), color = HierroTheme.colors.error)
                                HierroButton(stringResource(R.string.routines_dismiss), onDismissError, variant = HierroButtonVariant.Secondary)
                            }
                        }
                    }
                    if (state.active.isEmpty() && state.archived.isEmpty()) {
                        item(key = "empty") {
                            HierroEmptyState(
                                stringResource(R.string.routines_empty_title),
                                stringResource(R.string.routines_empty_message),
                                actionLabel = stringResource(R.string.routines_empty_action),
                                onAction = onCreate,
                            )
                        }
                    }
                    items(state.active, key = { it.id }) { routine ->
                        val index = state.active.indexOf(routine)
                        ActiveRoutineCard(
                            routine = routine,
                            isNext = routine.id == state.nextRoutineId,
                            canMoveUp = index > 0,
                            canMoveDown = index < state.active.lastIndex,
                            onStart = { onStartRoutine(routine.id) },
                            onEdit = { onOpen(routine.id) },
                            onMoveUp = { onMoveUp(routine.id) },
                            onMoveDown = { onMoveDown(routine.id) },
                        )
                    }
                    if (state.archived.isNotEmpty()) {
                        item(key = "archived-title") {
                            HierroText(
                                stringResource(R.string.routines_archived_title).uppercase(),
                                Modifier.semantics { heading() },
                                HierroTheme.typography.label,
                                HierroTheme.colors.textMuted,
                            )
                        }
                        items(state.archived, key = { it.id }) { routine ->
                            ArchivedRoutineCard(routine, onEdit = { onOpen(routine.id) }, onRestore = { onRestore(routine.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutineSummaryText(routine: RoutineSummary) {
    HierroText(routine.name, style = HierroTheme.typography.title)
    routine.description?.let { HierroText(it, color = HierroTheme.colors.textMuted) }
    HierroText(
        pluralStringResource(R.plurals.routines_exercise_count, routine.exerciseCount, routine.exerciseCount) +
            routine.exercisePreview.takeIf { it.isNotEmpty() }?.joinToString(separator = ", ", prefix = " · ").orEmpty(),
        color = HierroTheme.colors.textMuted,
    )
}

@Composable
private fun ActiveRoutineCard(
    routine: RoutineSummary,
    isNext: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onStart: () -> Unit,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val startDescription = stringResource(R.string.routines_start_description, routine.name)
    val editDescription = stringResource(R.string.routines_edit_description, routine.name)
    val upDescription = stringResource(R.string.routines_move_up_description, routine.name)
    val downDescription = stringResource(R.string.routines_move_down_description, routine.name)
    HierroCard {
        if (isNext) {
            HierroText(stringResource(R.string.routines_next_badge).uppercase(), style = HierroTheme.typography.label, color = HierroTheme.colors.accent)
        }
        RoutineSummaryText(routine)
        HierroButton(stringResource(R.string.routines_start), onStart, contentDescription = startDescription)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HierroButton(
                stringResource(R.string.routines_edit), onEdit, contentDescription = editDescription, variant = HierroButtonVariant.Secondary,
            )
            HierroButton(
                stringResource(R.string.routines_move_up), onMoveUp, contentDescription = upDescription, variant = HierroButtonVariant.Secondary, enabled = canMoveUp,
            )
            HierroButton(
                stringResource(R.string.routines_move_down), onMoveDown, contentDescription = downDescription, variant = HierroButtonVariant.Secondary, enabled = canMoveDown,
            )
        }
    }
}

@Composable
private fun ArchivedRoutineCard(routine: RoutineSummary, onEdit: () -> Unit, onRestore: () -> Unit) {
    val editDescription = stringResource(R.string.routines_edit_description, routine.name)
    val restoreDescription = stringResource(R.string.routines_restore_description, routine.name)
    HierroCard {
        RoutineSummaryText(routine)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HierroButton(stringResource(R.string.routines_restore), onRestore, contentDescription = restoreDescription)
            HierroButton(
                stringResource(R.string.routines_edit), onEdit, contentDescription = editDescription, variant = HierroButtonVariant.Secondary,
            )
        }
    }
}

/** AI mesocycle entry: always visible, enabled only with Pro (presentation only: the server decides). */
@Composable
internal fun MesocycleAction(isPro: Boolean, onGenerate: () -> Unit) {
    HierroButton(
        text = stringResource(if (isPro) R.string.routines_meso_action else R.string.routines_meso_action_locked),
        onClick = onGenerate,
        modifier = Modifier.fillMaxWidth(),
        variant = HierroButtonVariant.Secondary,
        enabled = isPro,
    )
}
