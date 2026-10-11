package com.chrisgoac.gymlog.navigation

import androidx.annotation.StringRes
import com.chrisgoac.gymlog.R
import kotlinx.serialization.Serializable

/** Typed routes. Bottom tabs each own a nested graph ([GymLogGraph]) so back stacks stay independent. */
sealed interface GymLogDestination {
    @get:StringRes val title: Int

    @Serializable data object Train : GymLogDestination { override val title = R.string.nav_train }
    @Serializable data object Routines : GymLogDestination { override val title = R.string.nav_routines }
    @Serializable data object Exercises : GymLogDestination { override val title = R.string.nav_exercises }
    @Serializable data object Progress : GymLogDestination { override val title = R.string.nav_progress }
    @Serializable data object History : GymLogDestination { override val title = R.string.nav_history }

    // Secondary routes: reached from headers or cards, not from the bottom bar.
    @Serializable data object Coach : GymLogDestination { override val title = R.string.nav_coach }
    @Serializable data object Body : GymLogDestination { override val title = R.string.nav_body }
    @Serializable data object Achievements : GymLogDestination { override val title = R.string.nav_achievements }
    @Serializable data object Settings : GymLogDestination { override val title = R.string.nav_settings }
    // Exercises graph: list (the tab) -> detail -> editor.
    @Serializable data class ExerciseDetail(val id: String) : GymLogDestination {
        override val title: Int get() = R.string.nav_exercise_detail
    }
    /** [id] == null creates a custom exercise. */
    @Serializable data class ExerciseEditor(val id: String? = null) : GymLogDestination {
        override val title: Int get() = R.string.nav_exercise_editor
    }
    /** [id] == null creates a routine. */
    @Serializable data class RoutineEditor(val id: String? = null) : GymLogDestination {
        override val title: Int get() = R.string.nav_routine_editor
    }
    /** The workout [sessionId] in progress (placeholder until set logging exists). */
    @Serializable data class ActiveWorkout(val sessionId: String) : GymLogDestination {
        override val title: Int get() = R.string.nav_active_workout
    }
    /** A finished workout in the History graph. */
    @Serializable data class HistoryDetail(val sessionId: String) : GymLogDestination {
        override val title: Int get() = R.string.nav_history_detail
    }
    /** Full editor of a finished workout, above its detail in the History graph. */
    @Serializable data class HistoryEditor(val sessionId: String) : GymLogDestination {
        override val title: Int get() = R.string.nav_history_editor
    }
    @Serializable data object SyncDiagnostics : GymLogDestination { override val title = R.string.nav_sync_diagnostics }
}

/** Per-tab nested graph routes (spec 6.2: each main destination has its own graph). */
sealed interface GymLogGraph {
    @Serializable data object Train : GymLogGraph
    @Serializable data object Routines : GymLogGraph
    @Serializable data object Exercises : GymLogGraph
    @Serializable data object Progress : GymLogGraph
    @Serializable data object History : GymLogGraph
}

data class BottomDestination(val graph: GymLogGraph, val start: GymLogDestination)

/** Spec 6.2 order: Entrenar, Rutinas, Ejercicios, Progreso, Historial. */
val BottomDestinations: List<BottomDestination> = listOf(
    BottomDestination(GymLogGraph.Train, GymLogDestination.Train),
    BottomDestination(GymLogGraph.Routines, GymLogDestination.Routines),
    BottomDestination(GymLogGraph.Exercises, GymLogDestination.Exercises),
    BottomDestination(GymLogGraph.Progress, GymLogDestination.Progress),
    BottomDestination(GymLogGraph.History, GymLogDestination.History),
)
