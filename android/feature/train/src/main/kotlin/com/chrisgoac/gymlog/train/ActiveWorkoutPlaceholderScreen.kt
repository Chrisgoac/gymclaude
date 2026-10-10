package com.chrisgoac.gymlog.train

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroEmptyState
import com.chrisgoac.gymlog.designsystem.component.HierroHeaderAction
import com.chrisgoac.gymlog.designsystem.component.HierroLoadingState
import com.chrisgoac.gymlog.designsystem.component.HierroScreenHeader
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.feature.train.R
import java.text.DateFormat
import java.util.Date

/**
 * Placeholder route for the active workout: shows the session date and its exercise names so the
 * start flows can land somewhere real. Set logging, timers and finishing are a later task; nothing
 * here writes data.
 */
@Composable
fun ActiveWorkoutPlaceholderScreen(
    viewModel: ActiveWorkoutPlaceholderViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    ActiveWorkoutPlaceholderContent(state, onBack, modifier)
}

@Composable
internal fun ActiveWorkoutPlaceholderContent(state: ActiveWorkoutUiState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        HierroScreenHeader(stringResource(R.string.workout_title)) {
            HierroHeaderAction(stringResource(R.string.workout_back), onBack)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val workout = state.workout
            if (!state.loaded) {
                item(key = "loading") { HierroLoadingState(stringResource(R.string.workout_loading)) }
            } else if (workout == null) {
                item(key = "missing") {
                    HierroEmptyState(stringResource(R.string.workout_missing_title), stringResource(R.string.workout_missing_message))
                }
            } else {
                item(key = "date") {
                    HierroText(
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(workout.session.startedAt)),
                        style = HierroTheme.typography.title,
                    )
                }
                if (workout.exercises.isEmpty()) {
                    item(key = "empty") {
                        HierroEmptyState(stringResource(R.string.workout_empty_title), stringResource(R.string.workout_empty_message))
                    }
                }
                items(workout.exercises, key = { it.id }) { exercise ->
                    HierroCard {
                        HierroText(exercise.exerciseName ?: stringResource(R.string.workout_unknown_exercise), style = HierroTheme.typography.title)
                    }
                }
            }
        }
    }
}
