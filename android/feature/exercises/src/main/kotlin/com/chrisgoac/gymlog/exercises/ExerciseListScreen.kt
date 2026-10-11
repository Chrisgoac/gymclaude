package com.chrisgoac.gymlog.exercises

import com.chrisgoac.gymlog.feature.exercises.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroEmptyState
import com.chrisgoac.gymlog.designsystem.component.HierroErrorState
import com.chrisgoac.gymlog.designsystem.component.HierroHeaderAction
import com.chrisgoac.gymlog.designsystem.component.HierroLoadingState
import com.chrisgoac.gymlog.designsystem.component.HierroScreenHeader
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.component.HierroTextField
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.domain.exercise.Equipment
import com.chrisgoac.gymlog.domain.exercise.Exercise
import com.chrisgoac.gymlog.domain.exercise.MuscleGroup

/** Catalog + custom exercises with accent-insensitive search and muscle/equipment filters. */
@Composable
fun ExerciseListScreen(
    viewModel: ExerciseListViewModel,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    ExerciseListContent(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onMuscleGroup = viewModel::onMuscleGroupSelected,
        onEquipment = viewModel::onEquipmentSelected,
        onClearFilters = viewModel::clearFilters,
        onRetry = viewModel::retry,
        onOpen = onOpen,
        onCreate = onCreate,
        onSettings = onSettings,
        modifier = modifier,
    )
}

@Composable
internal fun ExerciseListContent(
    state: ExerciseListUiState,
    onQueryChange: (String) -> Unit,
    onMuscleGroup: (MuscleGroup) -> Unit,
    onEquipment: (Equipment) -> Unit,
    onClearFilters: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val newDescription = stringResource(R.string.exercises_new_description)
    Column(modifier.fillMaxSize()) {
        HierroScreenHeader(stringResource(R.string.exercises_title)) {
            HierroHeaderAction(stringResource(R.string.exercises_new), onCreate, Modifier.semantics { contentDescription = newDescription })
            if (onSettings != null) HierroHeaderAction(stringResource(R.string.exercises_settings), onSettings)
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "search") {
                HierroTextField(state.filter.query, onQueryChange, stringResource(R.string.exercises_search_label))
            }
            item(key = "muscle") {
                ChoiceRow(
                    stringResource(R.string.exercises_filter_muscle), MuscleGroup.entries, state.filter.muscleGroup,
                    labelOf = { it.label }, onSelect = onMuscleGroup,
                )
            }
            item(key = "equipment") {
                ChoiceRow(
                    stringResource(R.string.exercises_filter_equipment), Equipment.entries, state.filter.equipment,
                    labelOf = { it.label }, onSelect = onEquipment,
                )
            }
            when (state.status) {
                LoadStatus.Loading -> item(key = "loading") { HierroLoadingState(stringResource(R.string.exercises_loading)) }
                LoadStatus.Failed -> item(key = "failed") {
                    HierroErrorState(
                        message = stringResource(R.string.exercises_error_message),
                        retryLabel = stringResource(R.string.exercises_retry),
                        onRetry = onRetry,
                        title = stringResource(R.string.exercises_error_title),
                    )
                }
                LoadStatus.Ready -> if (state.exercises.isEmpty()) {
                    item(key = "empty") {
                        if (state.filter.isActive) {
                            HierroEmptyState(
                                stringResource(R.string.exercises_no_results_title),
                                stringResource(R.string.exercises_no_results_message),
                                actionLabel = stringResource(R.string.exercises_clear_filters),
                                onAction = onClearFilters,
                            )
                        } else {
                            HierroEmptyState(
                                stringResource(R.string.exercises_empty_title),
                                stringResource(R.string.exercises_empty_message),
                                actionLabel = stringResource(R.string.exercises_empty_action),
                                onAction = onCreate,
                            )
                        }
                    }
                } else {
                    item(key = "count") {
                        HierroText(
                            pluralStringResource(R.plurals.exercises_count, state.exercises.size, state.exercises.size),
                            style = HierroTheme.typography.label,
                            color = HierroTheme.colors.textMuted,
                        )
                    }
                    items(state.exercises, key = { it.id }) { exercise -> ExerciseRow(exercise, onClick = { onOpen(exercise.id) }) }
                }
            }
        }
    }
}

@Composable
private fun ExerciseRow(exercise: Exercise, onClick: () -> Unit) {
    val description = stringResource(
        if (exercise.isCustom) R.string.exercises_exercise_row_description_custom else R.string.exercises_exercise_row_description,
        exercise.name, exercise.muscleGroup.label, exercise.equipment.label, exercise.type.label,
    )
    HierroCard(Modifier.semantics(mergeDescendants = true) { contentDescription = description }, onClick = onClick) {
        HierroText(exercise.name, style = HierroTheme.typography.title)
        HierroText(
            "${exercise.muscleGroup.label} · ${exercise.equipment.label} · ${exercise.type.label}",
            color = HierroTheme.colors.textMuted,
        )
        if (exercise.isCustom) {
            HierroText(stringResource(R.string.exercises_exercise_custom_badge).uppercase(), style = HierroTheme.typography.label, color = HierroTheme.colors.accent)
        }
    }
}
