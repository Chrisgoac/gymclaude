package com.chrisgoac.gymlog.timer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether an active-workout screen is on screen right now (its navigation entry is at least
 * STARTED, which also requires the activity to be started). Only that screen tells the user a rest
 * is over in-app (haptic + announcement in the timer bar); everywhere else (another tab, Settings,
 * or the app in the background) the end of a rest raises the local notification instead.
 */
object WorkoutScreenVisibility {
    private val started = AtomicInteger(0)

    val isVisible: Boolean get() = started.get() > 0

    /**
     * Counts [owner] as visible while it is STARTED, until it is destroyed or the returned function
     * is called (whichever comes first).
     */
    fun track(owner: LifecycleOwner): () -> Unit {
        var counted = false
        fun sync(visible: Boolean) {
            if (visible && !counted) started.incrementAndGet()
            if (!visible && counted) started.updateAndGet { (it - 1).coerceAtLeast(0) }
            counted = visible
        }
        val observer = LifecycleEventObserver { source, _ -> sync(source.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
        owner.lifecycle.addObserver(observer)
        return {
            owner.lifecycle.removeObserver(observer)
            sync(false)
        }
    }
}

/** Tracks the workout route's back-stack entry for [WorkoutScreenVisibility] while composed. */
@Composable
fun TrackWorkoutScreenVisibility(owner: LifecycleOwner) {
    DisposableEffect(owner) {
        val untrack = WorkoutScreenVisibility.track(owner)
        onDispose { untrack() }
    }
}
