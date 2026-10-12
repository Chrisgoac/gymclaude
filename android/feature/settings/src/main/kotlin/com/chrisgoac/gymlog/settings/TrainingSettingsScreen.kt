package com.chrisgoac.gymlog.settings

import com.chrisgoac.gymlog.feature.settings.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.component.HierroButton
import com.chrisgoac.gymlog.designsystem.component.HierroButtonVariant
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroHeaderAction
import com.chrisgoac.gymlog.designsystem.component.HierroLoadingState
import com.chrisgoac.gymlog.designsystem.component.HierroScreenHeader
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.domain.exercise.Equipment
import com.chrisgoac.gymlog.domain.exercise.MuscleGroup
import com.chrisgoac.gymlog.domain.settings.ThemeMode
import com.chrisgoac.gymlog.domain.workout.ProgressionMode
import com.chrisgoac.gymlog.domain.workout.SetValidation

/** Training preferences (goal, progression, volume goals, routine order) and the device-only look (theme, units). */
@Composable
fun TrainingSettingsScreen(viewModel: TrainingSettingsViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    Column(modifier.fillMaxSize()) {
        HierroScreenHeader(stringResource(R.string.settings_training_title)) { HierroHeaderAction(stringResource(R.string.settings_back), onBack) }
        if (!state.loaded) {
            HierroLoadingState(stringResource(R.string.settings_training_loading))
        } else {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.writeFailed) {
                    HierroCard {
                        HierroText(stringResource(R.string.settings_training_write_failed), color = HierroTheme.colors.error)
                        HierroButton(stringResource(R.string.settings_gyms_dismiss), viewModel::dismissWriteFailed, variant = HierroButtonVariant.Secondary)
                    }
                }
                AppearanceCard(state, viewModel)
                GoalCard(state, viewModel)
                ProgressionCard(state, viewModel)
                VolumeGoalsCard(state, viewModel)
                RoutineOrderCard(state, viewModel)
            }
        }
    }
}

@Composable
private fun AppearanceCard(state: TrainingSettingsUiState, vm: TrainingSettingsViewModel) {
    HierroCard {
        HierroText(stringResource(R.string.settings_training_appearance).uppercase(), style = HierroTheme.typography.label, color = HierroTheme.colors.textMuted)
        ChoiceRow(
            stringResource(R.string.settings_training_theme), ThemeMode.entries, state.device.theme,
            labelOf = { stringResource(when (it) { ThemeMode.SYSTEM -> R.string.settings_training_theme_system; ThemeMode.LIGHT -> R.string.settings_training_theme_light; ThemeMode.DARK -> R.string.settings_training_theme_dark }) },
            onSelect = vm::setTheme,
        )
        HierroText(stringResource(R.string.settings_training_device_only), color = HierroTheme.colors.textMuted)
    }
}

@Composable
private fun GoalCard(state: TrainingSettingsUiState, vm: TrainingSettingsViewModel) {
    HierroCard {
        HierroText(stringResource(R.string.settings_training_goals).uppercase(), style = HierroTheme.typography.label, color = HierroTheme.colors.textMuted)
        CommitField(
            label = stringResource(R.string.settings_training_weekly_target),
            stored = state.training.weeklyTarget.toString(),
            error = if (state.invalid == SettingField.WeeklyTarget) stringResource(R.string.settings_training_weekly_target_error) else null,
            onCommit = vm::commitWeeklyTarget,
        )
    }
}

@Composable
private fun ProgressionCard(state: TrainingSettingsUiState, vm: TrainingSettingsViewModel) {
    HierroCard {
        HierroText(stringResource(R.string.settings_training_progression).uppercase(), style = HierroTheme.typography.label, color = HierroTheme.colors.textMuted)
        ChoiceRow(
            stringResource(R.string.settings_training_progression_mode), ProgressionMode.entries, state.training.progression.mode,
            labelOf = {
                stringResource(when (it) {
                    ProgressionMode.DOBLE -> R.string.settings_training_mode_doble
                    ProgressionMode.OBJETIVO -> R.string.settings_training_mode_objetivo
                    ProgressionMode.REPITE -> R.string.settings_training_mode_repite
                    ProgressionMode.OFF -> R.string.settings_training_mode_off
                })
            },
            onSelect = vm::setProgressionMode,
        )
        HierroText(stringResource(R.string.settings_training_increments_hint), color = HierroTheme.colors.textMuted)
        Equipment.entries.filter { it != Equipment.PESO_CORPORAL && it != Equipment.OTRO }.forEach { equipment ->
            CommitField(
                label = stringResource(R.string.settings_training_increment, equipment.label),
                stored = SetValidation.formatWeight(state.training.progression.increments.getValue(equipment)),
                error = if (state.invalid == SettingField.Increment(equipment)) stringResource(R.string.settings_training_increment_error) else null,
                onCommit = { vm.commitIncrement(equipment, it) },
            )
        }
    }
}

@Composable
private fun VolumeGoalsCard(state: TrainingSettingsUiState, vm: TrainingSettingsViewModel) {
    HierroCard {
        HierroText(stringResource(R.string.settings_training_volume_goals).uppercase(), style = HierroTheme.typography.label, color = HierroTheme.colors.textMuted)
        HierroText(stringResource(R.string.settings_training_volume_hint), color = HierroTheme.colors.textMuted)
        MuscleGroup.entries.filter { it != MuscleGroup.OTRO }.forEach { group ->
            CommitField(
                label = stringResource(R.string.settings_training_volume_goal, group.label),
                stored = state.training.volumeGoals[group]?.let(SetValidation::formatWeight).orEmpty(),
                error = if (state.invalid == SettingField.VolumeGoal(group)) stringResource(R.string.settings_training_volume_error) else null,
                onCommit = { vm.commitVolumeGoal(group, it) },
            )
        }
    }
}

@Composable
private fun RoutineOrderCard(state: TrainingSettingsUiState, vm: TrainingSettingsViewModel) {
    HierroCard {
        HierroText(stringResource(R.string.settings_training_routine_order).uppercase(), style = HierroTheme.typography.label, color = HierroTheme.colors.textMuted)
        HierroText(stringResource(R.string.settings_training_routine_order_hint), color = HierroTheme.colors.textMuted)
        if (state.routines.isEmpty()) HierroText(stringResource(R.string.settings_training_routine_order_empty))
        state.routines.forEachIndexed { index, routine ->
            HierroText(routine.name, style = HierroTheme.typography.title)
            MoveActions(
                routine.name, index > 0, index < state.routines.lastIndex,
                stringResource(R.string.settings_gyms_move_up), stringResource(R.string.settings_gyms_move_down),
                { vm.moveRoutine(routine.id, -1) }, { vm.moveRoutine(routine.id, 1) },
            )
        }
    }
}
