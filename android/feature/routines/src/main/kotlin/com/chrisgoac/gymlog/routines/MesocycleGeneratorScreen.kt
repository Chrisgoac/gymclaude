package com.chrisgoac.gymlog.routines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.component.HierroButton
import com.chrisgoac.gymlog.designsystem.component.HierroButtonVariant
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroHeaderAction
import com.chrisgoac.gymlog.designsystem.component.HierroLoadingState
import com.chrisgoac.gymlog.designsystem.component.HierroScreenHeader
import com.chrisgoac.gymlog.designsystem.component.HierroSmallAction
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.domain.ai.MesocycleGoal
import com.chrisgoac.gymlog.domain.ai.MesocycleParams
import com.chrisgoac.gymlog.domain.coach.CoachFailure
import com.chrisgoac.gymlog.feature.routines.R

/** Everything the mesocycle screens can do (the ViewModel's methods, or recorders in tests). */
data class MesocycleActions(
    val onBack: () -> Unit,
    val onGoal: (MesocycleGoal) -> Unit,
    val onDays: (Int) -> Unit,
    val onWeeks: (Int) -> Unit,
    val onMinutes: (Int) -> Unit,
    val onGenerate: () -> Unit,
    val onDismissNotice: () -> Unit,
    val onRename: (String) -> Unit,
    val onSets: (dayKey: String, exerciseKey: String, delta: Int) -> Unit,
    val onReps: (dayKey: String, exerciseKey: String, delta: Int) -> Unit,
    val onRemove: (dayKey: String, exerciseKey: String) -> Unit,
    val onSave: () -> Unit,
    val onRegenerate: () -> Unit,
    val onDiscard: () -> Unit,
) {
    companion object {
        fun of(viewModel: MesocycleGeneratorViewModel, onBack: () -> Unit) = MesocycleActions(
            onBack = onBack,
            onGoal = viewModel::onGoal,
            onDays = viewModel::onDays,
            onWeeks = viewModel::onWeeks,
            onMinutes = viewModel::onMinutes,
            onGenerate = viewModel::generate,
            onDismissNotice = viewModel::dismissNotice,
            onRename = viewModel::onRename,
            onSets = viewModel::onSets,
            onReps = viewModel::onReps,
            onRemove = viewModel::onRemoveExercise,
            onSave = viewModel::save,
            onRegenerate = viewModel::regenerate,
            onDiscard = viewModel::discard,
        )
    }
}

/**
 * AI mesocycle: parameters → generated draft → review/edit ([MesocycleDetailContent]) → one local save.
 * [onSaved] receives the new mesocycle id once it is stored (the screen then leaves).
 */
@Composable
fun MesocycleGeneratorScreen(
    viewModel: MesocycleGeneratorViewModel,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.savedMesocycleId) { state.savedMesocycleId?.let(onSaved) }
    MesocycleContent(state, MesocycleActions.of(viewModel, onBack), modifier)
}

@Composable
internal fun MesocycleContent(state: MesocycleGeneratorUiState, actions: MesocycleActions, modifier: Modifier = Modifier) {
    val draft = state.draft
    if (draft != null && (state.phase == MesocyclePhase.REVIEW || state.phase == MesocyclePhase.SAVING || state.phase == MesocyclePhase.SAVED)) {
        MesocycleDetailContent(state, draft, actions, modifier)
    } else {
        MesocycleFormContent(state, actions, modifier)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MesocycleFormContent(state: MesocycleGeneratorUiState, actions: MesocycleActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        HierroScreenHeader(stringResource(R.string.routines_meso_title)) {
            HierroHeaderAction(stringResource(R.string.routines_meso_back), actions.onBack)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state.access) {
                MesocycleAccess.CHECKING -> item(key = "checking") { HierroLoadingState(stringResource(R.string.routines_meso_checking)) }
                MesocycleAccess.LOCKED -> item(key = "locked") {
                    HierroCard {
                        HierroText(stringResource(R.string.routines_meso_locked_title), Modifier.semantics { heading() }, HierroTheme.typography.label)
                        HierroText(stringResource(R.string.routines_meso_locked_message))
                    }
                }
                MesocycleAccess.AVAILABLE -> {
                    item(key = "intro") { HierroText(stringResource(R.string.routines_meso_intro), color = HierroTheme.colors.textMuted) }
                    item(key = "goal") {
                        HierroCard {
                            HierroText(stringResource(R.string.routines_meso_goal), Modifier.semantics { heading() }, HierroTheme.typography.label)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                MesocycleGoal.entries.forEach { goal ->
                                    HierroSmallAction(goal.label, goal.label, selected = goal == state.params.goal) { actions.onGoal(goal) }
                                }
                            }
                        }
                    }
                    item(key = "days") { Stepper(stringResource(R.string.routines_meso_days), state.params.daysPerWeek, MesocycleParams.DAYS, actions.onDays) }
                    item(key = "weeks") { Stepper(stringResource(R.string.routines_meso_weeks), state.params.weeks, MesocycleParams.WEEKS, actions.onWeeks) }
                    item(key = "minutes") {
                        Stepper(stringResource(R.string.routines_meso_minutes), state.params.minutesPerSession, MesocycleParams.MINUTES, actions.onMinutes)
                    }
                    item(key = "notice") { Notice(state, actions) }
                    item(key = "generate") {
                        HierroButton(
                            text = stringResource(R.string.routines_meso_generate),
                            onClick = actions.onGenerate,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.canGenerate,
                            loading = state.phase == MesocyclePhase.GENERATING,
                            loadingLabel = stringResource(R.string.routines_meso_generating),
                        )
                    }
                    item(key = "disclaimer") {
                        HierroText(stringResource(R.string.routines_meso_disclaimer), style = HierroTheme.typography.label, color = HierroTheme.colors.textMuted)
                    }
                }
            }
        }
    }
}

/** A labelled value with −/+ (48 dp targets); the buttons say what they change. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Stepper(label: String, value: Int, range: IntRange, onDelta: (Int) -> Unit) {
    HierroCard {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            HierroText(stringResource(R.string.routines_meso_value, label, value), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            HierroSmallAction("−", stringResource(R.string.routines_meso_less, label)) { if (value > range.first) onDelta(-1) }
            HierroSmallAction("+", stringResource(R.string.routines_meso_more, label)) { if (value < range.last) onDelta(1) }
        }
    }
}

@Composable
private fun Notice(state: MesocycleGeneratorUiState, actions: MesocycleActions) {
    val message = when {
        state.offlineNotice -> stringResource(R.string.routines_meso_offline)
        state.failure != null -> stringResource(failureText(state.failure))
        else -> return
    }
    HierroCard(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        HierroText(message, color = HierroTheme.colors.error)
        val retry = state.failure?.retryable == true && !state.offlineNotice
        if (retry) HierroButton(stringResource(R.string.routines_meso_retry), actions.onGenerate, enabled = state.canGenerate)
        HierroButton(stringResource(R.string.routines_meso_dismiss), actions.onDismissNotice, variant = HierroButtonVariant.Secondary)
    }
}

private fun failureText(failure: CoachFailure): Int = when (failure) {
    CoachFailure.BUSY -> R.string.routines_meso_failed_busy
    CoachFailure.REJECTED, CoachFailure.NO_ACCOUNT, CoachFailure.PRO_REQUIRED -> R.string.routines_meso_failed_rejected
    CoachFailure.SESSION -> R.string.routines_meso_failed_session
    CoachFailure.INTERRUPTED, CoachFailure.UNAVAILABLE -> R.string.routines_meso_failed_retry
}
