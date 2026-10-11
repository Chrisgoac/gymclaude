package com.chrisgoac.gymlog.train

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.component.HierroButton
import com.chrisgoac.gymlog.designsystem.component.HierroButtonVariant
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroErrorState
import com.chrisgoac.gymlog.designsystem.component.HierroHeaderAction
import com.chrisgoac.gymlog.designsystem.component.HierroLoadingState
import com.chrisgoac.gymlog.designsystem.component.HierroScreenHeader
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.feature.train.R
import java.text.DateFormat
import java.util.Date

/**
 * Train home: resume the active workout, or start the next routine / a free workout, with the
 * gym selection, the weekly summary and the (not yet available) Coach entry.
 * Navigation is by callback; this screen never creates a workout by itself.
 */
@Composable
fun TrainHomeScreen(
    viewModel: TrainHomeViewModel,
    onOpenWorkout: (String) -> Unit,
    onOpenBody: () -> Unit,
    onOpenAchievements: () -> Unit,
    onSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    TrainHomeContent(
        state = state,
        onContinue = { state.activeWorkout?.let { onOpenWorkout(it.session.id) } },
        onStartNext = { state.nextRoutine?.let { viewModel.startRoutine(it.id) } },
        onStartFree = viewModel::startFree,
        onSelectGym = viewModel::selectGym,
        onDismissError = viewModel::dismissError,
        onRetry = viewModel::retry,
        onOpenBody = onOpenBody,
        onOpenAchievements = onOpenAchievements,
        onSettings = onSettings,
        modifier = modifier,
    )
}

@Composable
internal fun TrainHomeContent(
    state: TrainHomeUiState,
    onContinue: () -> Unit,
    onStartNext: () -> Unit,
    onStartFree: () -> Unit,
    onSelectGym: (String?) -> Unit,
    onDismissError: () -> Unit,
    onRetry: () -> Unit,
    onOpenBody: () -> Unit,
    onOpenAchievements: () -> Unit,
    onSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        HierroScreenHeader(stringResource(R.string.train_title)) {
            if (onSettings != null) HierroHeaderAction(stringResource(R.string.train_settings), onSettings)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state.status) {
                TrainLoadStatus.Loading -> item(key = "loading") { HierroLoadingState(stringResource(R.string.train_loading)) }
                TrainLoadStatus.Failed -> item(key = "failed") {
                    HierroErrorState(
                        message = stringResource(R.string.train_error_message),
                        retryLabel = stringResource(R.string.train_retry),
                        onRetry = onRetry,
                        title = stringResource(R.string.train_error_title),
                    )
                }
                TrainLoadStatus.Ready -> {
                    state.startError?.let { error ->
                        item(key = "start-error") {
                            HierroCard {
                                HierroText(stringResource(error.message()), color = HierroTheme.colors.error)
                                HierroButton(stringResource(R.string.train_dismiss), onDismissError, variant = HierroButtonVariant.Secondary)
                            }
                        }
                    }
                    val active = state.activeWorkout
                    if (active != null) {
                        item(key = "continue") {
                            HierroCard {
                                HierroText(stringResource(R.string.train_continue_title), style = HierroTheme.typography.title)
                                HierroText(
                                    stringResource(R.string.train_continue_started, formatStart(active.session.startedAt)),
                                    color = HierroTheme.colors.textMuted,
                                )
                                HierroButton(stringResource(R.string.train_continue_button), onContinue)
                            }
                        }
                    } else {
                        item(key = "next") { NextRoutineCard(state, onStartNext) }
                        item(key = "free") {
                            val description = stringResource(R.string.train_free_start_description)
                            HierroButton(
                                stringResource(R.string.train_free_start),
                                onStartFree,
                                variant = HierroButtonVariant.Secondary,
                                enabled = !state.starting,
                                contentDescription = description,
                            )
                        }
                        item(key = "gym") { GymSelector(state, onSelectGym) }
                    }
                    state.summary?.let { summary ->
                        item(key = "summary") {
                            // The streak card opens Achievements; one spoken line states what it shows and where it goes.
                            val sessionsText = pluralStringResource(R.plurals.train_week_sessions, summary.weekSessions, summary.weekSessions, summary.weeklyGoal)
                            val streakText = if (summary.streakWeeks > 0) {
                                pluralStringResource(R.plurals.train_streak, summary.streakWeeks, summary.streakWeeks)
                            } else {
                                stringResource(R.string.train_streak_none)
                            }
                            val open = stringResource(R.string.train_streak_open)
                            HierroCard(onClick = onOpenAchievements, contentDescription = "$sessionsText. $streakText. $open") {
                                HierroText(
                                    stringResource(R.string.train_week_title).uppercase(),
                                    Modifier.semantics { heading() },
                                    HierroTheme.typography.label,
                                    HierroTheme.colors.textMuted,
                                )
                                HierroText(sessionsText, style = HierroTheme.typography.title)
                                HierroText(
                                    streakText,
                                    color = HierroTheme.colors.textMuted,
                                )
                            }
                        }
                    }
                    item(key = "coach") { CoachEntry() }
                    item(key = "body") {
                        HierroCard(onClick = onOpenBody) { HierroText(stringResource(R.string.train_body), style = HierroTheme.typography.title) }
                    }
                    item(key = "achievements") {
                        HierroCard(onClick = onOpenAchievements) {
                            HierroText(stringResource(R.string.train_achievements), style = HierroTheme.typography.title)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NextRoutineCard(state: TrainHomeUiState, onStart: () -> Unit) {
    val routine = state.nextRoutine
    HierroCard {
        if (routine == null) {
            HierroText(stringResource(R.string.train_no_routine_title), style = HierroTheme.typography.title)
            HierroText(stringResource(R.string.train_no_routine_message), color = HierroTheme.colors.textMuted)
        } else {
            val description = stringResource(R.string.train_next_start_description, routine.name)
            HierroText(stringResource(R.string.train_next_badge).uppercase(), style = HierroTheme.typography.label, color = HierroTheme.colors.accent)
            HierroText(routine.name, style = HierroTheme.typography.title)
            if (routine.exercisePreview.isNotEmpty()) {
                HierroText(routine.exercisePreview.joinToString(", "), color = HierroTheme.colors.textMuted)
            }
            HierroButton(
                stringResource(R.string.train_next_start, routine.name),
                onStart,
                enabled = !state.starting,
                contentDescription = description,
            )
        }
    }
}

@Composable
private fun GymSelector(state: TrainHomeUiState, onSelect: (String?) -> Unit) {
    HierroCard {
        HierroText(
            stringResource(R.string.train_gym_title).uppercase(),
            Modifier.semantics { heading() },
            HierroTheme.typography.label,
            HierroTheme.colors.textMuted,
        )
        val selectedLabel = stringResource(R.string.train_gym_selected)
        GymOption(stringResource(R.string.train_gym_none), state.selectedGymId == null, selectedLabel) { onSelect(null) }
        state.gyms.forEach { gym ->
            GymOption(gym.name, state.selectedGymId == gym.id, selectedLabel) { onSelect(gym.id) }
        }
    }
}

@Composable
private fun GymOption(name: String, selected: Boolean, selectedLabel: String, onClick: () -> Unit) {
    HierroButton(
        text = name,
        onClick = onClick,
        variant = if (selected) HierroButtonVariant.Primary else HierroButtonVariant.Secondary,
        modifier = Modifier.semantics { this.selected = selected },
        contentDescription = if (selected) "$name, $selectedLabel" else name,
    )
}

/** Visibly Pro and inert: the Coach arrives in a later plan, so this routes nowhere. */
@Composable
private fun CoachEntry() {
    val title = stringResource(R.string.train_coach_title)
    val pro = stringResource(R.string.train_coach_pro)
    HierroCard(Modifier.semantics { disabled(); contentDescription = "$title, $pro" }) {
        HierroText(title, style = HierroTheme.typography.title, color = HierroTheme.colors.textMuted)
        HierroText(pro, style = HierroTheme.typography.label, color = HierroTheme.colors.accent)
    }
}

private fun StartError.message(): Int = when (this) {
    StartError.GymUnavailable -> R.string.train_error_gym
    StartError.RoutineUnavailable -> R.string.train_error_routine_gone
    StartError.RoutineArchived -> R.string.train_error_routine_archived
    StartError.Failed -> R.string.train_error_failed
}

private fun formatStart(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
