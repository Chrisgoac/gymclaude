package com.chrisgoac.gymlog.train

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.component.HierroButton
import com.chrisgoac.gymlog.designsystem.component.HierroButtonVariant
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.theme.HierroShape
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
    val name = exercise.name ?: stringResource(R.string.workout_unknown_exercise)
    HierroCard(modifier) {
        HierroText(name, Modifier.semantics { heading() }, HierroTheme.typography.title)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!exercise.isFirst) {
                SmallAction(stringResource(R.string.workout_exercise_up), stringResource(R.string.workout_exercise_up_description, name)) {
                    actions.onMoveExercise(exercise.id, -1)
                }
            }
            if (!exercise.isLast) {
                SmallAction(stringResource(R.string.workout_exercise_down), stringResource(R.string.workout_exercise_down_description, name)) {
                    actions.onMoveExercise(exercise.id, +1)
                }
            }
            SmallAction(stringResource(R.string.workout_exercise_remove), stringResource(R.string.workout_exercise_remove_description, name)) {
                actions.onRemoveExercise(exercise.id)
            }
        }
        ContextLines(exercise)
        exercise.sets.forEachIndexed { index, set ->
            SetRow(set, isFirst = index == 0, isLast = index == exercise.sets.lastIndex, actions = actions)
        }
        HierroButton(
            stringResource(R.string.workout_add_set),
            { actions.onAddSet(exercise.id) },
            Modifier.fillMaxWidth(),
            variant = HierroButtonVariant.Secondary,
            contentDescription = stringResource(R.string.workout_add_set_description, name),
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
                add(stringResource(R.string.workout_target_sets_reps, target.sets?.toString() ?: "—", reps))
            }
            target.restSeconds?.takeIf { it > 0 }?.let { add(stringResource(R.string.workout_target_rest, it)) }
        }
        if (parts.isNotEmpty()) HierroText(stringResource(R.string.workout_target, parts.joinToString(" · ")), style = HierroTheme.typography.label, color = muted)
    }
    val previous = exercise.previous
    val date = exercise.previousDate
    if (previous != null && date != null) {
        HierroText(
            stringResource(R.string.workout_previous, SetValidation.formatWeight(previous.weight), previous.reps, DateFormat.getDateInstance(DateFormat.SHORT).format(Date(date))),
            style = HierroTheme.typography.label,
            color = muted,
        )
    }
    val badge = exercise.suggestionBadge
    val suggestion = exercise.suggestion
    if (badge != null && suggestion != null) {
        HierroText(
            stringResource(R.string.workout_suggestion, badge, SetValidation.formatWeight(suggestion.weight), suggestion.reps),
            style = HierroTheme.typography.label,
            color = HierroTheme.colors.accent,
        )
    }
}

@Composable
private fun SetRow(set: SetRowUi, isFirst: Boolean, isLast: Boolean, actions: ExerciseLogActions) {
    val colors = HierroTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (set.completed) colors.surfaceMuted else colors.surface)
            .border(HierroShape.border, if (set.completed) colors.accent else colors.border)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            Box(
                Modifier.widthIn(min = 32.dp).heightIn(min = 56.dp).border(HierroShape.border, colors.border).background(colors.surfaceMuted),
                contentAlignment = Alignment.Center,
            ) {
                HierroText(if (set.warmup) stringResource(R.string.workout_set_warmup_badge) else set.number.toString(), style = HierroTheme.typography.title)
            }
            NumberField(
                value = set.weightText,
                onValueChange = { actions.onWeight(set.id, it) },
                label = stringResource(R.string.workout_set_weight),
                description = stringResource(R.string.workout_set_weight_description, set.number),
                error = set.weightError?.let { weightErrorText(it) },
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                modifier = Modifier.weight(1f),
            )
            NumberField(
                value = set.repsText,
                onValueChange = { actions.onReps(set.id, it) },
                label = stringResource(R.string.workout_set_reps),
                description = stringResource(R.string.workout_set_reps_description, set.number),
                error = set.repsError?.let { repsErrorText(it) },
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                modifier = Modifier.weight(1f),
            )
            if (set.completed) {
                HierroButton(
                    stringResource(R.string.workout_set_completed),
                    { actions.onReopen(set.id) },
                    contentDescription = stringResource(R.string.workout_set_reopen_description, set.number),
                )
            } else {
                HierroButton(
                    stringResource(R.string.workout_set_complete),
                    { actions.onComplete(set.id) },
                    variant = HierroButtonVariant.Secondary,
                    contentDescription = stringResource(R.string.workout_set_complete_description, set.number),
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            set.previous?.let { previous ->
                HierroText(
                    stringResource(R.string.workout_set_previous, SetValidation.formatWeight(previous.weight), previous.reps),
                    Modifier.padding(end = 4.dp),
                    HierroTheme.typography.label,
                    colors.textMuted,
                )
            }
            SmallAction(
                stringResource(if (set.warmup) R.string.workout_set_warmup_on else R.string.workout_set_warmup),
                stringResource(R.string.workout_set_warmup_description, set.number),
                selected = set.warmup,
            ) { actions.onWarmup(set.id, !set.warmup) }
            if (!isFirst) SmallAction("↑", stringResource(R.string.workout_set_up_description, set.number)) { actions.onMoveSet(set.id, -1) }
            if (!isLast) SmallAction("↓", stringResource(R.string.workout_set_down_description, set.number)) { actions.onMoveSet(set.id, +1) }
            SmallAction("✕", stringResource(R.string.workout_set_remove_description, set.number)) { actions.onRemoveSet(set.id) }
        }
    }
}

/** Large, centered numeric field: one stop for TalkBack with label, value and error. */
@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    description: String,
    error: String?,
    keyboard: KeyboardOptions,
    modifier: Modifier = Modifier,
) {
    val colors = HierroTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        HierroText(label.uppercase(), Modifier.clearAndSetSemantics {}, HierroTheme.typography.label, colors.textMuted, maxLines = 1)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            keyboardOptions = keyboard,
            interactionSource = interaction,
            textStyle = HierroTheme.typography.title.copy(color = colors.ink, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .background(colors.surface)
                .border(if (focused) 4.dp else HierroShape.border, if (error != null) colors.error else colors.border)
                .padding(horizontal = 6.dp, vertical = 12.dp)
                .semantics {
                    contentDescription = description
                    if (error != null) error(error)
                },
        )
        if (error != null) {
            HierroText(error, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, HierroTheme.typography.label, colors.error)
        }
    }
}

/** Compact bordered action with a full 48dp target; [description] names the set/exercise it acts on. */
@Composable
private fun SmallAction(text: String, description: String, selected: Boolean = false, onClick: () -> Unit) {
    val colors = HierroTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
            .heightIn(min = HierroShape.minTouchTarget)
            .widthIn(min = HierroShape.minTouchTarget)
            .background(if (selected) colors.accent else colors.surface)
            .border(if (focused) 4.dp else HierroShape.border, colors.border)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                this.selected = selected
            }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        HierroText(text, Modifier.clearAndSetSemantics {}, HierroTheme.typography.label, if (selected) colors.onAccent else colors.ink)
    }
}

@Composable
private fun weightErrorText(error: SetFieldError): String = stringResource(
    when (error) {
        SetFieldError.TOO_LARGE -> R.string.workout_field_too_large_weight
        else -> error.messageRes()
    },
)

@Composable
private fun repsErrorText(error: SetFieldError): String = stringResource(
    when (error) {
        SetFieldError.TOO_LARGE -> R.string.workout_field_too_large_reps
        else -> error.messageRes()
    },
)

private fun SetFieldError.messageRes(): Int = when (this) {
    SetFieldError.REQUIRED -> R.string.workout_field_required
    SetFieldError.NOT_A_NUMBER -> R.string.workout_field_not_a_number
    SetFieldError.NOT_INTEGER -> R.string.workout_field_not_integer
    SetFieldError.NEGATIVE -> R.string.workout_field_negative
    SetFieldError.TOO_LARGE -> R.string.workout_field_too_large_reps
    SetFieldError.ZERO_REPS -> R.string.workout_field_zero_reps
}
