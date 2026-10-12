package com.chrisgoac.gymlog.train

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.component.HierroButton
import com.chrisgoac.gymlog.designsystem.component.HierroButtonVariant
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroSetRow
import com.chrisgoac.gymlog.designsystem.component.HierroSmallAction
import com.chrisgoac.gymlog.designsystem.component.SetRowLabels
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.domain.workout.SetFieldError
import com.chrisgoac.gymlog.domain.workout.SetValidation
import com.chrisgoac.gymlog.feature.train.R
import java.text.DateFormat
import java.util.Date

/** Everything a card can ask for; ids are the stable logged-exercise / set ids. */
internal interface ExerciseLogActions {
    fun onWeight(setId: String, text: String)
    fun onReps(setId: String, text: String)
    fun onComplete(setId: String)
    fun onReopen(setId: String)
    fun onWarmup(setId: String, warmup: Boolean)
    fun onMoveSet(setId: String, delta: Int)
    fun onRemoveSet(setId: String)
    fun onAddSet(exerciseId: String)
    fun onMoveExercise(exerciseId: String, delta: Int)
    fun onRemoveExercise(exerciseId: String)
}

/** One exercise of the active workout: context lines (target, last time, suggestion) and its set rows. */
@Composable
internal fun ExerciseLogCard(exercise: ExerciseLogUi, actions: ExerciseLogActions, modifier: Modifier = Modifier) {
    val name = exercise.name ?: stringResource(R.string.train_workout_unknown_exercise)
    HierroCard(modifier) {
        HierroText(name, Modifier.semantics { heading() }, HierroTheme.typography.title)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!exercise.isFirst) {
                SmallAction(stringResource(R.string.train_workout_exercise_up), stringResource(R.string.train_workout_exercise_up_description, name)) {
                    actions.onMoveExercise(exercise.id, -1)
                }
            }
            if (!exercise.isLast) {
                SmallAction(stringResource(R.string.train_workout_exercise_down), stringResource(R.string.train_workout_exercise_down_description, name)) {
                    actions.onMoveExercise(exercise.id, +1)
                }
            }
            SmallAction(stringResource(R.string.train_workout_exercise_remove), stringResource(R.string.train_workout_exercise_remove_description, name)) {
                actions.onRemoveExercise(exercise.id)
            }
        }
        ContextLines(exercise)
        exercise.sets.forEachIndexed { index, set ->
            SetRow(set, isFirst = index == 0, isLast = index == exercise.sets.lastIndex, actions = actions)
        }
        HierroButton(
            stringResource(R.string.train_workout_add_set),
            { actions.onAddSet(exercise.id) },
            Modifier.fillMaxWidth(),
            variant = HierroButtonVariant.Secondary,
            contentDescription = stringResource(R.string.train_workout_add_set_description, name),
        )
    }
}

@Composable
private fun ContextLines(exercise: ExerciseLogUi) {
    val muted = HierroTheme.colors.textMuted
    exercise.target?.let { target ->
        val parts = buildList {
            if (target.sets != null || target.repsMax != null) {
                val reps = when {
                    target.repsMin != null && target.repsMax != null && target.repsMin != target.repsMax -> "${target.repsMin}–${target.repsMax}"
                    else -> (target.repsMax ?: target.repsMin)?.toString() ?: "—"
                }
                add(stringResource(R.string.train_workout_target_sets_reps, target.sets?.toString() ?: "—", reps))
            }
            target.restSeconds?.takeIf { it > 0 }?.let { add(stringResource(R.string.train_workout_target_rest, it)) }
        }
        if (parts.isNotEmpty()) HierroText(stringResource(R.string.train_workout_target, parts.joinToString(" · ")), style = HierroTheme.typography.label, color = muted)
    }
    val previous = exercise.previous
    val date = exercise.previousDate
    if (previous != null && date != null) {
        HierroText(
            stringResource(R.string.train_workout_previous, SetValidation.formatWeight(previous.weight), previous.reps, DateFormat.getDateInstance(DateFormat.SHORT).format(Date(date))),
            style = HierroTheme.typography.label,
            color = muted,
        )
    }
    val badge = exercise.suggestionBadge
    val suggestion = exercise.suggestion
    if (badge != null && suggestion != null) {
        HierroText(
            stringResource(R.string.train_workout_suggestion, badge, SetValidation.formatWeight(suggestion.weight), suggestion.reps),
            style = HierroTheme.typography.label,
            color = HierroTheme.colors.accent,
        )
    }
}

@Composable
private fun SetRow(set: SetRowUi, isFirst: Boolean, isLast: Boolean, actions: ExerciseLogActions) {
    HierroSetRow(
        badge = if (set.warmup) stringResource(R.string.train_workout_set_warmup_badge) else set.number.toString(),
        weightText = set.weightText,
        repsText = set.repsText,
        onWeight = { actions.onWeight(set.id, it) },
        onReps = { actions.onReps(set.id, it) },
        labels = SetRowLabels(
            weight = stringResource(R.string.train_workout_set_weight),
            reps = stringResource(R.string.train_workout_set_reps),
            weightDescription = stringResource(R.string.train_workout_set_weight_description, set.number),
            repsDescription = stringResource(R.string.train_workout_set_reps_description, set.number),
        ),
        weightError = set.weightError?.let { weightErrorText(it) },
        repsError = set.repsError?.let { repsErrorText(it) },
        highlighted = set.completed,
        trailing = {
            if (set.completed) {
                HierroButton(
                    stringResource(R.string.train_workout_set_completed),
                    { actions.onReopen(set.id) },
                    contentDescription = stringResource(R.string.train_workout_set_reopen_description, set.number),
                )
            } else {
                HierroButton(
                    stringResource(R.string.train_workout_set_complete),
                    { actions.onComplete(set.id) },
                    variant = HierroButtonVariant.Secondary,
                    contentDescription = stringResource(R.string.train_workout_set_complete_description, set.number),
                )
            }
        },
    ) {
        set.previous?.let { previous ->
            HierroText(
                stringResource(R.string.train_workout_set_previous, SetValidation.formatWeight(previous.weight), previous.reps),
                Modifier.padding(end = 4.dp),
                HierroTheme.typography.label,
                HierroTheme.colors.textMuted,
            )
        }
        SmallAction(
            stringResource(if (set.warmup) R.string.train_workout_set_warmup_on else R.string.train_workout_set_warmup),
            stringResource(R.string.train_workout_set_warmup_description, set.number),
            selected = set.warmup,
        ) { actions.onWarmup(set.id, !set.warmup) }
        if (!isFirst) SmallAction("↑", stringResource(R.string.train_workout_set_up_description, set.number)) { actions.onMoveSet(set.id, -1) }
        if (!isLast) SmallAction("↓", stringResource(R.string.train_workout_set_down_description, set.number)) { actions.onMoveSet(set.id, +1) }
        SmallAction("✕", stringResource(R.string.train_workout_set_remove_description, set.number)) { actions.onRemoveSet(set.id) }
    }
}

@Composable
private fun SmallAction(text: String, description: String, selected: Boolean = false, onClick: () -> Unit) =
    HierroSmallAction(text, description, selected, onClick)

@Composable
private fun weightErrorText(error: SetFieldError): String = stringResource(
    when (error) {
        SetFieldError.TOO_LARGE -> R.string.train_workout_field_too_large_weight
        else -> error.messageRes()
    },
)

@Composable
private fun repsErrorText(error: SetFieldError): String = stringResource(
    when (error) {
        SetFieldError.TOO_LARGE -> R.string.train_workout_field_too_large_reps
        else -> error.messageRes()
    },
)

private fun SetFieldError.messageRes(): Int = when (this) {
    SetFieldError.REQUIRED -> R.string.train_workout_field_required
    SetFieldError.NOT_A_NUMBER -> R.string.train_workout_field_not_a_number
    SetFieldError.NOT_INTEGER -> R.string.train_workout_field_not_integer
    SetFieldError.NEGATIVE -> R.string.train_workout_field_negative
    SetFieldError.TOO_LARGE -> R.string.train_workout_field_too_large_reps
    SetFieldError.ZERO_REPS -> R.string.train_workout_field_zero_reps
}
