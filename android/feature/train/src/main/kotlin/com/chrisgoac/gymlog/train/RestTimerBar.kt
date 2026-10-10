package com.chrisgoac.gymlog.train

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.component.HierroButton
import com.chrisgoac.gymlog.designsystem.component.HierroButtonVariant
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.domain.timer.RestTimer
import com.chrisgoac.gymlog.domain.timer.RestTimerState
import com.chrisgoac.gymlog.feature.train.R
import kotlinx.coroutines.delay

/** Completions older than this (an alarm that fired while away) get no haptic: it would buzz out of the blue. */
private const val HAPTIC_MAX_LATE_MILLIS = 3_000L
private const val TICK_MILLIS = 250L

/** `m:ss`, rounding up so the bar never shows 0:00 before the end. */
internal fun formatRest(millis: Long): String {
    val seconds = ((millis + 999) / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/**
 * Sticky rest countdown of the active workout: ticks locally from the wall clock (the source of
 * truth is the persisted end time), buzzes once on completion while on screen.
 */
@Composable
fun RestTimerHost(viewModel: WorkoutViewModel, modifier: Modifier = Modifier) {
    val timer by viewModel.timer.collectAsState()
    var now by remember { mutableLongStateOf(viewModel.nowMillis()) }
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(timer) {
        while (true) {
            now = viewModel.nowMillis()
            val current = timer ?: break
            if (RestTimer.completionDue(current, now)) {
                viewModel.onTimerTick()
                break
            }
            if (RestTimer.isPaused(current) || current.completionDelivered) break
            delay(TICK_MILLIS)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.timerCompletions.collect { if (it.lateMillis <= HAPTIC_MAX_LATE_MILLIS) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    }

    RestTimerBar(
        state = timer,
        nowMillis = now,
        onExtend = viewModel::extendRest,
        onPause = viewModel::pauseRest,
        onResume = viewModel::resumeRest,
        onSkip = viewModel::resetRest,
        modifier = modifier,
    )
}

@Composable
internal fun RestTimerBar(
    state: RestTimerState?,
    nowMillis: Long,
    onExtend: (seconds: Int) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state == null) return
    val paused = RestTimer.isPaused(state)
    val finished = RestTimer.isFinished(state, nowMillis)
    val time = formatRest(RestTimer.remaining(state, nowMillis))
    val label = when {
        finished -> stringResource(R.string.rest_finished)
        paused -> stringResource(R.string.rest_paused, time)
        else -> time
    }
    // Only the end is announced (polite live region): a countdown read out every second would be noise.
    val announce = if (finished) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier
    val spoken = stringResource(if (paused) R.string.rest_description_paused else R.string.rest_description, time)
    HierroCard(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HierroText(stringResource(R.string.rest_title), color = HierroTheme.colors.textMuted)
            HierroText(
                label,
                modifier = announce.semantics { if (!finished) contentDescription = spoken },
                color = HierroTheme.colors.ink,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HierroButton(stringResource(R.string.rest_plus_15), { onExtend(15) }, variant = HierroButtonVariant.Secondary)
            HierroButton(stringResource(R.string.rest_plus_30), { onExtend(30) }, variant = HierroButtonVariant.Secondary)
            if (!finished) {
                if (paused) {
                    HierroButton(stringResource(R.string.rest_resume), onResume, variant = HierroButtonVariant.Secondary)
                } else {
                    HierroButton(stringResource(R.string.rest_pause), onPause, variant = HierroButtonVariant.Secondary)
                }
            }
            HierroButton(stringResource(if (finished) R.string.rest_dismiss else R.string.rest_skip), onSkip, variant = HierroButtonVariant.Secondary)
        }
    }
}
