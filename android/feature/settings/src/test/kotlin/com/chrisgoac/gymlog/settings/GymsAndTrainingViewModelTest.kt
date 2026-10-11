package com.chrisgoac.gymlog.settings

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
import com.chrisgoac.gymlog.domain.settings.GymNameProblem
import com.chrisgoac.gymlog.domain.settings.GymNotFoundException
import com.chrisgoac.gymlog.domain.settings.GymRepository
import com.chrisgoac.gymlog.domain.settings.InvalidGymNameException
import com.chrisgoac.gymlog.domain.settings.InvalidSettingException
import com.chrisgoac.gymlog.domain.settings.NotificationChoice
import com.chrisgoac.gymlog.domain.settings.SettingProblem
import com.chrisgoac.gymlog.domain.settings.SettingsRepository
import com.chrisgoac.gymlog.domain.settings.ThemeMode
import com.chrisgoac.gymlog.domain.settings.TrainingSettings
import com.chrisgoac.gymlog.domain.workout.ProgressionMode
import com.chrisgoac.gymlog.domain.workout.ProgressionSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeGyms : GymRepository {
    val items = MutableStateFlow<List<GymItem>>(emptyList())
    val calls = mutableListOf<String>()
    private var next = 0
    override fun observeGyms(): Flow<List<GymItem>> = items
    override suspend fun create(name: String): GymItem {
        val clean = name.trim()
        if (clean.isEmpty()) throw InvalidGymNameException(GymNameProblem.BLANK)
        return GymItem("g${++next}", clean, false, 0).also { items.value += it; calls += "create:$clean" }
    }
    override suspend fun rename(id: String, name: String) {
        if (name.isBlank()) throw InvalidGymNameException(GymNameProblem.BLANK)
        if (items.value.none { it.id == id }) throw GymNotFoundException(id)
        items.value = items.value.map { if (it.id == id) it.copy(name = name.trim()) else it }
        calls += "rename:$id"
    }
    override suspend fun setArchived(id: String, archived: Boolean) {
        items.value = items.value.map { if (it.id == id) it.copy(archived = archived) else it }
        calls += "archive:$id:$archived"
    }
    override suspend fun reorder(idsInOrder: List<String>) {
        items.value = idsInOrder.mapNotNull { id -> items.value.firstOrNull { it.id == id } }
        calls += "reorder:${idsInOrder.joinToString(",")}"
    }
    override suspend fun delete(id: String) {
        items.value = items.value.filterNot { it.id == id }
        calls += "delete:$id"
    }
}

private class FakeDevice : DeviceSettingsRepository {
    val state = MutableStateFlow(DeviceSettings())
    override fun observe() = state
    override suspend fun setTheme(theme: ThemeMode) { state.value = state.value.copy(theme = theme) }
    override suspend fun setWeightUnit(unit: WeightUnit) { state.value = state.value.copy(weightUnit = unit) }
    override suspend fun setNotificationChoice(choice: NotificationChoice) { state.value = state.value.copy(notifications = choice) }
    override suspend fun setDefaultGym(gymId: String?) { state.value = state.value.copy(defaultGymId = gymId) }
    override suspend fun setOnboardingDone(done: Boolean) { state.value = state.value.copy(onboardingDone = done) }
    override suspend fun setFilter(key: String, value: String?) {
        state.value = state.value.copy(filters = if (value == null) state.value.filters - key else state.value.filters + (key to value))
    }
}

private class FakeSettings : SettingsRepository {
    val state = MutableStateFlow(TrainingSettings())
    override fun observeTraining() = state
    override suspend fun setWeeklyTarget(sessionsPerWeek: Int) {
        if (sessionsPerWeek !in SettingsRepository.WEEKLY_TARGET_RANGE) throw InvalidSettingException(SettingProblem.WEEKLY_TARGET_OUT_OF_RANGE)
        state.value = state.value.copy(weeklyTarget = sessionsPerWeek)
    }
    override suspend fun setProgressionMode(mode: ProgressionMode) {
        state.value = state.value.copy(progression = state.value.progression.copy(mode = mode))
    }
    override suspend fun setIncrement(equipment: Equipment, kg: Double) {
        if (kg < 0) throw InvalidSettingException(SettingProblem.INCREMENT_OUT_OF_RANGE)
        state.value = state.value.copy(progression = ProgressionSettings(state.value.progression.mode, state.value.progression.increments + (equipment to kg)))
    }
    override suspend fun setVolumeGoal(group: MuscleGroup, volume: Double?) {
        state.value = state.value.copy(volumeGoals = if (volume == null) state.value.volumeGoals - group else state.value.volumeGoals + (group to volume))
    }
}

private class FakeRoutines(names: List<String>) : RoutineRepository {
    val summaries = MutableStateFlow(names.map { RoutineSummary(it, it.uppercase(), null, false, 0, emptyList()) })
    override fun observeRoutines() = summaries
    override fun observeRoutine(id: String): Flow<Routine?> = error("unused")
    override fun observeNextRoutineId(): Flow<String?> = summaries.map { it.firstOrNull()?.id }
    override suspend fun create(input: RoutineInput): Routine = error("unused")
    override suspend fun update(id: String, input: RoutineInput): Routine = error("unused")
    override suspend fun reorder(idsInOrder: List<String>) {
        summaries.value = idsInOrder.mapNotNull { id -> summaries.value.firstOrNull { it.id == id } }
    }
    override suspend fun setArchived(id: String, archived: Boolean) = Unit
    override suspend fun delete(id: String) = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class GymsAndTrainingViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val gyms = FakeGyms()
    private val device = FakeDevice()

    private fun gymsVm(scope: kotlinx.coroutines.test.TestScope): GymsViewModel =
        GymsViewModel(gyms, device).also { vm -> scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { vm.state.collect {} } }

    @Test fun `creating a gym validates the name and keeps the editor open on error`() = runTest {
        val vm = gymsVm(this)
        vm.startCreate()
        vm.onName("   ")
        vm.save()
        assertEquals(GymNameProblem.BLANK, vm.state.value.editor!!.error)
        assertTrue(gyms.calls.isEmpty())

        vm.onName(" Box ")
        vm.save()
        assertNull(vm.state.value.editor)
        assertEquals(listOf("Box"), vm.state.value.gyms.map { it.name })
        assertEquals(GymsLoadStatus.Ready, vm.state.value.status)
    }

    @Test fun `rename starts from the current name`() = runTest {
        gyms.items.value = listOf(GymItem("g1", "Box", false, 0))
        val vm = gymsVm(this)
        vm.startRename("g1")
        assertEquals("Box", vm.state.value.editor!!.name)
        vm.onName("Box 2")
        vm.save()
        assertEquals("Box 2", vm.state.value.gyms.single().name)
    }

    @Test fun `deleting a gym in use asks first, reports how many workouts keep it, and clears the default`() = runTest {
        gyms.items.value = listOf(GymItem("g1", "Box", false, 3), GymItem("g2", "Casa", false, 0))
        device.state.value = DeviceSettings(defaultGymId = "g1")
        val vm = gymsVm(this)
        assertEquals("g1", vm.state.value.defaultGymId)

        vm.requestDelete("g1")
        assertEquals(PendingGymDelete("g1", "Box", 3), vm.state.value.pendingDelete)
        assertTrue("nothing deleted before the confirmation", gyms.calls.none { it.startsWith("delete") })
        vm.cancelDelete()
        assertNull(vm.state.value.pendingDelete)
        assertEquals(2, vm.state.value.gyms.size)

        vm.requestDelete("g1")
        vm.confirmDelete()
        assertEquals(listOf("delete:g1"), gyms.calls)
        assertEquals(listOf("g2"), vm.state.value.gyms.map { it.id })
        assertNull(device.state.value.defaultGymId)
    }

    @Test fun `default gym is set, shown only while the gym is usable, and archiving it clears the default`() = runTest {
        gyms.items.value = listOf(GymItem("g1", "Box", false, 0), GymItem("g2", "Casa", false, 0))
        val vm = gymsVm(this)
        vm.setDefault("g2")
        assertEquals("g2", vm.state.value.defaultGymId)

        vm.setArchived("g2", true)
        assertNull(device.state.value.defaultGymId)
        assertNull(vm.state.value.defaultGymId)

        // A default naming a gym that no longer exists (deleted on another device) is not offered.
        device.state.value = DeviceSettings(defaultGymId = "gone")
        assertNull(vm.state.value.defaultGymId)
    }

    @Test fun `moving a gym reorders by id and ignores the edges`() = runTest {
        gyms.items.value = listOf(GymItem("a", "A", false, 0), GymItem("b", "B", false, 0), GymItem("c", "C", false, 0))
        val vm = gymsVm(this)
        vm.move("a", -1)
        vm.move("c", 1)
        assertTrue(gyms.calls.isEmpty())
        vm.move("c", -1)
        assertEquals(listOf("reorder:a,c,b"), gyms.calls)
        assertEquals(listOf("a", "c", "b"), vm.state.value.gyms.map { it.id })
    }

    // ---- Training settings ----

    private fun trainingVm(settings: FakeSettings = FakeSettings(), routines: FakeRoutines = FakeRoutines(listOf("r1", "r2", "r3")), scope: kotlinx.coroutines.test.TestScope) =
        TrainingSettingsViewModel(settings, device, routines).also { vm -> scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { vm.state.collect {} } }

    @Test fun `weekly target accepts whole numbers in range and flags the field otherwise without storing`() = runTest {
        val settings = FakeSettings()
        val vm = trainingVm(settings, scope = this)
        vm.commitWeeklyTarget("5")
        assertEquals(5, vm.state.value.training.weeklyTarget)
        assertNull(vm.state.value.invalid)

        for (bad in listOf("0", "99", "abc", "2.5", "")) {
            vm.commitWeeklyTarget(bad)
            assertEquals("input '$bad'", SettingField.WeeklyTarget, vm.state.value.invalid)
            assertEquals(5, settings.state.value.weeklyTarget)
        }
        vm.commitWeeklyTarget("4")
        assertNull(vm.state.value.invalid)
    }

    @Test fun `increments take comma decimals and volume goals clear on blank text`() = runTest {
        val settings = FakeSettings()
        val vm = trainingVm(settings, scope = this)
        vm.commitIncrement(Equipment.BARRA, "2,5")
        assertEquals(2.5, settings.state.value.progression.increments.getValue(Equipment.BARRA), 0.0)
        vm.commitIncrement(Equipment.BARRA, "-1")
        assertEquals(SettingField.Increment(Equipment.BARRA), vm.state.value.invalid)

        vm.commitVolumeGoal(MuscleGroup.PECHO, "12000")
        assertEquals(12000.0, settings.state.value.volumeGoals.getValue(MuscleGroup.PECHO), 0.0)
        vm.commitVolumeGoal(MuscleGroup.PECHO, "  ")
        assertTrue(settings.state.value.volumeGoals.isEmpty())
    }

    @Test fun `theme and unit go to the device store, never to synced settings`() = runTest {
        val settings = FakeSettings()
        val vm = trainingVm(settings, scope = this)
        vm.setTheme(ThemeMode.DARK)
        vm.setWeightUnit(WeightUnit.LB)
        assertEquals(ThemeMode.DARK, vm.state.value.device.theme)
        assertEquals(WeightUnit.LB, vm.state.value.device.weightUnit)
        assertEquals(TrainingSettings(), settings.state.value)
    }

    @Test fun `routine order moves by one and persists through the repository`() = runTest {
        val routines = FakeRoutines(listOf("r1", "r2", "r3"))
        val vm = trainingVm(routines = routines, scope = this)
        vm.moveRoutine("r1", -1)
        vm.moveRoutine("r3", 1)
        assertEquals(listOf("r1", "r2", "r3"), routines.summaries.value.map { it.id })
        vm.moveRoutine("r3", -1)
        assertEquals(listOf("r1", "r3", "r2"), routines.summaries.value.map { it.id })
        assertEquals(listOf("r1", "r3", "r2"), vm.state.value.routines.map { it.id })
        assertFalse(vm.state.value.writeFailed)
        assertEquals(true, vm.state.first { it.loaded }.loaded)
    }
}
