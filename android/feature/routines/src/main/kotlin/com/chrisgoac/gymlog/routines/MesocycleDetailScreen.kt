package com.chrisgoac.gymlog.routines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.component.HierroButton
import com.chrisgoac.gymlog.designsystem.component.HierroButtonVariant
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroHeaderAction
import com.chrisgoac.gymlog.designsystem.component.HierroScreenHeader
import com.chrisgoac.gymlog.designsystem.component.HierroSmallAction
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.component.HierroTextField
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.domain.ai.MesocycleDraft
import com.chrisgoac.gymlog.domain.ai.MesocycleDraftDay
import com.chrisgoac.gymlog.domain.ai.MesocycleDraftExercise
import com.chrisgoac.gymlog.feature.routines.R

/**
 * Review of a generated mesocycle before anything is stored: name, weekly progression, and every day with its
 * exercises, which can be removed or have their sets/reps adjusted. "Guardar" stores it all at once; "Generar
 * otra" asks for a new draft; "Descartar" goes back to the parameters.
 */
@Composable
internal fun MesocycleDetailContent(
    state: MesocycleGeneratorUiState,
    draft: MesocycleDraft,
    actions: MesocycleActions,
    modifier: Modifier = Modifier,
) {
    val saving = state.phase != MesocyclePhase.REVIEW
    Column(modifier.fillMaxSize()) {
        HierroScreenHeader(stringResource(R.string.routines_meso_review_title)) {
            HierroHeaderAction(stringResource(R.string.routines_meso_discard), actions.onDiscard)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "hint") { HierroText(stringResource(R.string.routines_meso_review_hint), color = HierroTheme.colors.textMuted) }
            item(key = "name") {
                HierroTextField(
                    value = draft.name,
                    onValueChange = actions.onRename,
                    label = stringResource(R.string.routines_meso_name),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving,
                    errorMessage = if (draft.name.isBlank()) stringResource(R.string.routines_meso_name_required) else null,
                )
            }
            item(key = "summary") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    HierroText(stringResource(R.string.routines_meso_summary, draft.goal, draft.weeks, draft.daysPerWeek), style = HierroTheme.typography.label)
                    draft.notes?.let { HierroText(it, color = HierroTheme.colors.textMuted) }
                }
            }
            item(key = "progression") {
                HierroCard {
                    HierroText(stringResource(R.string.routines_meso_progression), Modifier.semantics { heading() }, HierroTheme.typography.label)
                    draft.progression.forEach { week ->
                        val text = if (week.deload) R.string.routines_meso_week_deload else R.string.routines_meso_week
                        HierroText(stringResource(text, week.week, week.adjustment))
                    }
                }
            }
            if (draft.omitted.isNotEmpty()) {
                item(key = "omitted") {
                    HierroText(stringResource(R.string.routines_meso_omitted, draft.omitted.joinToString(", ")), color = HierroTheme.colors.textMuted)
                }
            }
            items(draft.days, key = { "day-${it.key}" }) { day -> DayCard(draft.days.indexOf(day) + 1, day, enabled = !saving, actions) }
            if (state.saveFailed) {
                item(key = "save-failed") {
                    HierroText(
                        stringResource(R.string.routines_meso_save_failed),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = HierroTheme.colors.error,
                    )
                }
            }
            item(key = "actions") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HierroButton(
                        text = stringResource(R.string.routines_meso_save),
                        onClick = actions.onSave,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.canSave,
                        loading = saving,
                        loadingLabel = stringResource(R.string.routines_meso_saving),
                    )
                    HierroButton(
                        stringResource(R.string.routines_meso_regenerate),
                        actions.onRegenerate,
                        Modifier.fillMaxWidth(),
                        variant = HierroButtonVariant.Secondary,
                        enabled = !saving && state.online,
                    )
                }
            }
            item(key = "disclaimer") {
                HierroText(stringResource(R.string.routines_meso_disclaimer), style = HierroTheme.typography.label, color = HierroTheme.colors.textMuted)
            }
        }
    }
}

@Composable
private fun DayCard(number: Int, day: MesocycleDraftDay, enabled: Boolean, actions: MesocycleActions) {
    HierroCard {
        HierroText(stringResource(R.string.routines_meso_day, number, day.name), Modifier.semantics { heading() }, HierroTheme.typography.title)
        if (day.exercises.isEmpty()) {
            HierroText(stringResource(R.string.routines_meso_day_empty), color = HierroTheme.colors.error)
        }
        day.exercises.forEach { exercise -> ExerciseRow(day.key, exercise, enabled, actions) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExerciseRow(dayKey: String, exercise: MesocycleDraftExercise, enabled: Boolean, actions: MesocycleActions) {
    val spoken = stringResource(R.string.routines_meso_exercise, exercise.name, exercise.sets, exercise.reps, exercise.restSeconds)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HierroText(exercise.name, Modifier.clearAndSetSemantics { contentDescription = spoken })
        HierroText(
            "${exercise.sets} × ${exercise.reps} · ${exercise.restSeconds} s",
            Modifier.clearAndSetSemantics {},
            HierroTheme.typography.label,
            HierroTheme.colors.textMuted,
        )
        if (enabled) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val sets = stringResource(R.string.routines_meso_sets_short)
                val reps = stringResource(R.string.routines_meso_reps_short)
                HierroSmallAction("$sets −", stringResource(R.string.routines_meso_sets_less, exercise.name)) { actions.onSets(dayKey, exercise.key, -1) }
                HierroSmallAction("$sets +", stringResource(R.string.routines_meso_sets_more, exercise.name)) { actions.onSets(dayKey, exercise.key, 1) }
                HierroSmallAction("$reps −", stringResource(R.string.routines_meso_reps_less, exercise.name)) { actions.onReps(dayKey, exercise.key, -1) }
                HierroSmallAction("$reps +", stringResource(R.string.routines_meso_reps_more, exercise.name)) { actions.onReps(dayKey, exercise.key, 1) }
                HierroSmallAction(stringResource(R.string.routines_meso_remove_short), stringResource(R.string.routines_meso_remove, exercise.name)) {
                    actions.onRemove(dayKey, exercise.key)
                }
            }
        }
    }
}
