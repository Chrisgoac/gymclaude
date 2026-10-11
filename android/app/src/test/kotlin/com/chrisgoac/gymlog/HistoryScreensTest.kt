package com.chrisgoac.gymlog

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.chrisgoac.gymlog.designsystem.theme.GymLogTheme
import com.chrisgoac.gymlog.domain.SyncUiState
import com.chrisgoac.gymlog.domain.exercise.searchKey
import com.chrisgoac.gymlog.domain.workout.Gym
import com.chrisgoac.gymlog.domain.workout.HistoryDetail
import com.chrisgoac.gymlog.domain.workout.HistoryFilter
import com.chrisgoac.gymlog.domain.workout.HistoryItem
import com.chrisgoac.gymlog.domain.workout.HistoryPage
import com.chrisgoac.gymlog.domain.workout.HistoryRepository
import com.chrisgoac.gymlog.domain.workout.StartedWorkout
import com.chrisgoac.gymlog.domain.workout.TrainingSummary
import com.chrisgoac.gymlog.domain.workout.WorkoutAggregate
import com.chrisgoac.gymlog.domain.workout.WorkoutExercise
import com.chrisgoac.gymlog.domain.workout.WorkoutRepository
import com.chrisgoac.gymlog.domain.workout.WorkoutSession
import com.chrisgoac.gymlog.domain.workout.WorkoutSet
import com.chrisgoac.gymlog.domain.workout.summary
import com.chrisgoac.gymlog.history.HistoryDetailScreen
import com.chrisgoac.gymlog.history.HistoryDetailViewModel
import com.chrisgoac.gymlog.history.HistoryListScreen
import com.chrisgoac.gymlog.history.HistoryListViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val NOW = 1_800_000_000_000L
private const val DAY = 24L * 60 * 60 * 1000

private class ScreenHistory : HistoryRepository {
    val items = MutableStateFlow(
        listOf(
            HistoryItem("s1", NOW - DAY, 2700, "g1", "Casa", "Empuje", null, 2, 6, 4800.0),
            HistoryItem("s2", NOW - 3 * DAY, 3600, "g2", "Fitness", "Tirón", "Hombro molesto", 3, 9, 6000.0),
            HistoryItem("s3", NOW - 60 * DAY, 3000, null, null, null, null, 1, 3, 900.0),
        ),
    )
    val detail = MutableStateFlow<HistoryDetail?>(null)
    val calls = mutableListOf<String>()

    override fun observeHistory(filter: HistoryFilter, limit: Int): Flow<HistoryPage> = items.map { all ->
        val since = filter.period.sinceMillis(NOW)
        val key = filter.query.searchKey()
        val rows = all.filter { it.startedAt >= since }
            .filter { filter.gymId == null || it.gymId == filter.gymId }
            .filter { key.isEmpty() || listOfNotNull(it.routineName, it.notes).any { text -> text.searchKey().contains(key) } }
        HistoryPage(rows.take(limit), rows.size > limit)
    }

    override fun observeDetail(sessionId: String): Flow<HistoryDetail?> = detail

    override suspend fun changeGym(sessionId: String, gymId: String?) {
        calls += "gym:$gymId"
    }

    override suspend fun deleteSession(sessionId: String) {
        calls += "delete:$sessionId"
        detail.value = null
    }
}

private class Gyms : WorkoutRepository {
    override fun observeActiveWorkout(): Flow<WorkoutAggregate?> = flowOf(null)
    override fun observeWorkout(sessionId: String): Flow<WorkoutAggregate?> = flowOf(null)
    override fun observeGyms(): Flow<List<Gym>> = flowOf(listOf(Gym("g1", "Casa"), Gym("g2", "Fitness")))
    override fun observeTrainingSummary(): Flow<TrainingSummary> = flowOf(TrainingSummary(0, 3, 0))
    override suspend fun startFreeWorkout(gymId: String?) = StartedWorkout("x", false)
    override suspend fun startRoutineWorkout(routineId: String, gymId: String?) = StartedWorkout("x", false)
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xhdpi")
class HistoryScreensTest {
    @get:Rule val rule = createComposeRule()
    private val repo = ScreenHistory()

    private fun showList(sync: SyncUiState = SyncUiState.Synced, opened: MutableList<String> = mutableListOf()) {
        val viewModel = HistoryListViewModel(repo, Gyms(), flowOf(sync))
        rule.setContent { GymLogTheme { HistoryListScreen(viewModel, onOpen = { opened += it }) } }
        rule.waitForIdle()
    }

    private fun aggregate(gymId: String? = "g1") = WorkoutAggregate(
        WorkoutSession("s1", "r1", gymId, NOW - DAY, 2700, "Buena sesión"),
        listOf(
            WorkoutExercise("le1", "seed-remo", "Remo con barra", 0, listOf(WorkoutSet("a", 0, 60.0, 8, false))),
            WorkoutExercise("le2", "seed-press", "Press de banca", 1, listOf(WorkoutSet("b", 0, 40.0, 10, true), WorkoutSet("c", 1, 100.0, 5, false))),
        ),
    )

    private fun showDetail(onBack: () -> Unit = {}) {
        val agg = aggregate()
        repo.detail.value = HistoryDetail(agg, "Casa", "Empuje", agg.summary(2700))
        val viewModel = HistoryDetailViewModel(repo, Gyms(), "s1")
        rule.setContent { GymLogTheme { HistoryDetailScreen(viewModel, onBack = onBack) } }
        rule.waitForIdle()
    }

    @Test fun listShowsRowsAndTheSyncIndicator(): Unit = with(rule) {
        showList(SyncUiState.Pending(2))
        onNodeWithText("2 cambios pendientes de enviar").assertExists()
        onNodeWithText("Empuje").assertExists()
        onNodeWithText("Tirón").assertExists()
        onNodeWithText("2 ejercicios · 6 series · 45 min").assertExists()
    }

    @Test fun textFilterIsAccentInsensitiveAndNoResultsOffersClearing(): Unit = with(rule) {
        showList()
        onNode(hasSetTextAction()).performTextInput("TIRON")
        waitForIdle()
        onNodeWithText("Tirón").assertExists()
        onAllNodesWithText("Empuje").assertCountEquals(0)

        onNode(hasSetTextAction()).performTextInput("zzz")
        waitForIdle()
        onNodeWithText("SIN RESULTADOS").assertExists()
        onNodeWithText("Quitar filtros").performClick()
        waitForIdle()
        onNodeWithText("Empuje").assertExists()
        onNodeWithText("Tirón").assertExists()
    }

    @Test fun periodAndGymChipsNarrowTheList(): Unit = with(rule) {
        showList()
        // s3 is 60 days old: "Todo" shows it, "4 sem" does not.
        onAllNodesWithText("Entrenamiento libre").assertCountEquals(1)
        onNodeWithContentDescription("4 sem").performClick()
        waitForIdle()
        onAllNodesWithText("Entrenamiento libre").assertCountEquals(0)
        onNodeWithContentDescription("4 sem, seleccionado").assertExists()

        onNodeWithContentDescription("Fitness").performClick()
        waitForIdle()
        onNodeWithText("Tirón").assertExists()
        onAllNodesWithText("Empuje").assertCountEquals(0)
    }

    @Test fun tappingARowOpensTheDetail(): Unit = with(rule) {
        val opened = mutableListOf<String>()
        showList(opened = opened)
        onNodeWithText("Tirón").performClick()
        assertEquals(listOf("s2"), opened)
    }

    @Test fun detailShowsOrderedExercisesSetsGymAndNotes(): Unit = with(rule) {
        showDetail()
        onNodeWithText("Remo con barra").assertExists()
        onNodeWithText("Press de banca").assertExists()
        onNodeWithText("1. 60 kg × 8").assertExists()
        onNodeWithText("1. 40 kg × 10 (calentamiento)").assertExists()
        onNodeWithText("2. 100 kg × 5").assertExists()
        onNodeWithText("Gimnasio: Casa").assertExists()
        onNodeWithText("Buena sesión").assertExists()
    }

    @Test fun changeGymShortcutUpdatesTheSession(): Unit = with(rule) {
        showDetail()
        onNodeWithText("Cambiar gimnasio").performScrollTo().performClick()
        onNodeWithContentDescription("Fitness").performScrollTo().performClick()
        waitForIdle()
        assertEquals(listOf("gym:g2"), repo.calls)
        onNodeWithText("Cambiar gimnasio").assertExists()
    }

    @Test fun deleteAsksForConfirmationAndCancelKeepsTheWorkout(): Unit = with(rule) {
        var left = 0
        showDetail(onBack = { left++ })
        // The delete card is the last lazy item: scroll the list to it before looking for its nodes.
        fun scrollTo(text: String) = onNode(hasScrollAction()).performScrollToNode(hasText(text))
        scrollTo("Borrar entreno")
        onNodeWithText("Borrar entreno").performClick()
        onNodeWithText("¿BORRAR ESTE ENTRENO?").assertExists()
        onNodeWithText("Cancelar").performScrollTo().performClick()
        waitForIdle()
        assertTrue(repo.calls.isEmpty())
        scrollTo("Borrar entreno")
        onNodeWithText("Borrar entreno").assertExists()

        onNodeWithText("Borrar entreno").performClick()
        onNodeWithText("Borrar").performScrollTo().performClick()
        waitForIdle()
        assertEquals(listOf("delete:s1"), repo.calls)
        assertEquals(1, left)
    }
}
