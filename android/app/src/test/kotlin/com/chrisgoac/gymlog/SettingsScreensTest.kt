package com.chrisgoac.gymlog

import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import com.chrisgoac.gymlog.designsystem.theme.GymLogTheme
import com.chrisgoac.gymlog.domain.exercise.Equipment
import com.chrisgoac.gymlog.domain.exercise.MuscleGroup
import com.chrisgoac.gymlog.domain.math.WeightUnit
import com.chrisgoac.gymlog.domain.routine.Routine
import com.chrisgoac.gymlog.domain.routine.RoutineInput
import com.chrisgoac.gymlog.domain.routine.RoutineRepository
import com.chrisgoac.gymlog.domain.routine.RoutineSummary
import com.chrisgoac.gymlog.domain.settings.DeviceSettings
import com.chrisgoac.gymlog.domain.settings.DeviceSettingsRepository
import com.chrisgoac.gymlog.domain.settings.GymItem
import com.chrisgoac.gymlog.domain.settings.GymRepository
import com.chrisgoac.gymlog.domain.settings.NotificationChoice
import com.chrisgoac.gymlog.domain.settings.SettingsRepository
import com.chrisgoac.gymlog.domain.settings.ThemeMode
import com.chrisgoac.gymlog.domain.settings.TrainingSettings
import com.chrisgoac.gymlog.domain.workout.ProgressionMode
import com.chrisgoac.gymlog.settings.GymsScreen
import com.chrisgoac.gymlog.settings.GymsViewModel
import com.chrisgoac.gymlog.settings.TrainingSettingsScreen
import com.chrisgoac.gymlog.settings.TrainingSettingsViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

private val SCROLL = SemanticsMatcher("vertical scroll container") { SemanticsProperties.VerticalScrollAxisRange in it.config }

private class ScreenGyms(vararg items: GymItem) : GymRepository {
    val state = MutableStateFlow(items.toList())
    override fun observeGyms(): Flow<List<GymItem>> = state
    override suspend fun create(name: String) = GymItem("new", name, false, 0).also { state.value += it }
    override suspend fun rename(id: String, name: String) = Unit
    override suspend fun setArchived(id: String, archived: Boolean) = Unit
    override suspend fun reorder(idsInOrder: List<String>) = Unit
    override suspend fun delete(id: String) { state.value = state.value.filterNot { it.id == id } }
}

private class ScreenDevice : DeviceSettingsRepository {
    val state = MutableStateFlow(DeviceSettings())
    override fun observe() = state
    override suspend fun setTheme(theme: ThemeMode) { state.value = state.value.copy(theme = theme) }
    override suspend fun setWeightUnit(unit: WeightUnit) { state.value = state.value.copy(weightUnit = unit) }
    override suspend fun setNotificationChoice(choice: NotificationChoice) = Unit
    override suspend fun setDefaultGym(gymId: String?) { state.value = state.value.copy(defaultGymId = gymId) }
    override suspend fun setOnboardingDone(done: Boolean) = Unit
    override suspend fun setFilter(key: String, value: String?) = Unit
}

private class ScreenSettings : SettingsRepository {
    val state = MutableStateFlow(TrainingSettings())
    override fun observeTraining() = state
    override suspend fun setWeeklyTarget(sessionsPerWeek: Int) {
        require(sessionsPerWeek in SettingsRepository.WEEKLY_TARGET_RANGE)
        state.value = state.value.copy(weeklyTarget = sessionsPerWeek)
    }
    override suspend fun setProgressionMode(mode: ProgressionMode) = Unit
    override suspend fun setIncrement(equipment: Equipment, kg: Double) = Unit
    override suspend fun setVolumeGoal(group: MuscleGroup, volume: Double?) = Unit
}

private class ScreenRoutines : RoutineRepository {
    override fun observeRoutines(): Flow<List<RoutineSummary>> = MutableStateFlow(listOf(RoutineSummary("r1", "Push", null, false, 0, emptyList())))
    override fun observeRoutine(id: String): Flow<Routine?> = error("unused")
    override fun observeNextRoutineId(): Flow<String?> = MutableStateFlow(null)
    override suspend fun create(input: RoutineInput): Routine = error("unused")
    override suspend fun update(id: String, input: RoutineInput): Routine = error("unused")
    override suspend fun reorder(idsInOrder: List<String>) = Unit
    override suspend fun setArchived(id: String, archived: Boolean) = Unit
    override suspend fun delete(id: String) = Unit
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xhdpi")
class SettingsScreensTest {
    @get:Rule val rule = createComposeRule()

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        rule.waitForIdle()
    }

    private fun scrollTo(text: String) {
        rule.onNode(SCROLL).performScrollToNode(hasText(text, substring = true))
    }

    private fun content(fontScale: Float, body: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { GymLogTheme { body() } }
        }
        idle()
    }

    @Test fun `deleting a gym in use asks first and says the history is kept (200 percent text)`() {
        val gyms = ScreenGyms(GymItem("g1", "Box", false, 3), GymItem("g2", "Casa", true, 0))
        val viewModel = GymsViewModel(gyms, ScreenDevice())
        content(2f) { GymsScreen(viewModel, onBack = {}) }

        rule.onNodeWithText("Box").assertIsDisplayed()
        scrollTo("Casa")
        rule.onNodeWithText("Casa").assertIsDisplayed()
        scrollTo("Box")
        rule.onNodeWithContentDescription("Eliminar Box").performClick()
        idle()
        scrollTo("Hay 3 entrenamientos")
        rule.onNode(hasText("Hay 3 entrenamientos", substring = true)).assertIsDisplayed()
        assertEquals(2, gyms.state.value.size)

        rule.onNodeWithText("Eliminar gimnasio").performClick()
        idle()
        assertEquals(listOf("g2"), gyms.state.value.map { it.id })
    }

    @Test fun `training settings show the stored goal and the routine order (200 percent text)`() {
        val settings = ScreenSettings().apply { state.value = TrainingSettings(weeklyTarget = 4) }
        val viewModel = TrainingSettingsViewModel(settings, ScreenDevice(), ScreenRoutines())
        content(2f) { TrainingSettingsScreen(viewModel, onBack = {}) }

        scrollTo("SESIONES OBJETIVO")
        rule.onNode(hasText("4")).assertIsDisplayed()
        scrollTo("Push")
        rule.onNodeWithText("Push").assertIsDisplayed()
    }
}
