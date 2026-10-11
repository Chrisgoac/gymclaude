package com.chrisgoac.gymlog.domain.stats

import com.chrisgoac.gymlog.domain.exercise.Equipment
import com.chrisgoac.gymlog.domain.exercise.MuscleGroup
import com.chrisgoac.gymlog.domain.history.DraftValidationSummary
import com.chrisgoac.gymlog.domain.history.InvalidationScope
import com.chrisgoac.gymlog.domain.history.SaveResult
import com.chrisgoac.gymlog.domain.history.SaveWorkoutDraftUseCase
import com.chrisgoac.gymlog.domain.history.WorkoutDraft
import com.chrisgoac.gymlog.domain.settings.SettingsRepository
import com.chrisgoac.gymlog.domain.settings.TrainingSettings
import com.chrisgoac.gymlog.domain.workout.ProgressionMode
import com.chrisgoac.gymlog.domain.workout.WorkoutAggregate
import com.chrisgoac.gymlog.domain.workout.WorkoutSession
import com.chrisgoac.gymlog.domain.OutboxDiagnostic
import com.chrisgoac.gymlog.domain.SyncRepository
import com.chrisgoac.gymlog.domain.SyncResult
import com.chrisgoac.gymlog.domain.SyncStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class StatsInvalidationTest {
    @Test fun `changes wakes only on events touching its scopes, empty sets are dropped`() = runTest(UnconfinedTestDispatcher()) {
        val invalidation = StatsInvalidation()
        var stalls = 0
        var streaks = 0
        backgroundScope.launch { invalidation.changes(setOf(InvalidationScope.STALLS)).collect { stalls++ } }
        backgroundScope.launch { invalidation.changes(setOf(InvalidationScope.STREAKS, InvalidationScope.WEEKLY_SUMMARY)).collect { streaks++ } }
        invalidation.publish(setOf(InvalidationScope.STALLS, InvalidationScope.COACH_SNAPSHOT))
        invalidation.publish(setOf(InvalidationScope.WEEKLY_SUMMARY))
        invalidation.publish(emptySet())
        assertEquals(1, stalls)
        assertEquals(1, streaks)
    }

    @Test fun `the save wrapper publishes only successful saves`() = runTest(UnconfinedTestDispatcher()) {
        val invalidation = StatsInvalidation()
        val published = mutableListOf<Set<InvalidationScope>>()
        backgroundScope.launch { invalidation.scopes.collect { published += it } }
        var next: SaveResult = SaveResult.Saved(setOf(InvalidationScope.VOLUME))
        val save = InvalidatingSaveWorkoutDraftUseCase(object : SaveWorkoutDraftUseCase {
            override suspend fun invoke(original: WorkoutAggregate, draft: WorkoutDraft) = next
        }, invalidation)
        val original = WorkoutAggregate(WorkoutSession("s", null, null, 0, 60, null), emptyList())
        val draft = WorkoutDraft("s", Instant.EPOCH, null, "", emptyList())
        save(original, draft)
        next = SaveResult.Conflict
        save(original, draft)
        next = SaveResult.Invalid(DraftValidationSummary(emptyList()))
        save(original, draft)
        assertEquals(listOf(setOf(InvalidationScope.VOLUME)), published)
    }

    @Test fun `a weekly target change publishes ACHIEVEMENTS as a non-local event, other settings nothing`() = runTest(UnconfinedTestDispatcher()) {
        val invalidation = StatsInvalidation()
        var achievements = 0
        var localEdits = 0
        backgroundScope.launch { invalidation.changes(setOf(InvalidationScope.ACHIEVEMENTS)).collect { achievements++ } }
        backgroundScope.launch { invalidation.localEditChanges(InvalidationScope.entries.toSet()).collect { localEdits++ } }
        val writes = mutableListOf<String>()
        val settings = InvalidatingSettingsRepository(object : SettingsRepository {
            override fun observeTraining(): Flow<TrainingSettings> = emptyFlow()
            override suspend fun setWeeklyTarget(sessionsPerWeek: Int) { writes += "target=$sessionsPerWeek" }
            override suspend fun setProgressionMode(mode: ProgressionMode) { writes += "mode" }
            override suspend fun setIncrement(equipment: Equipment, kg: Double) { writes += "increment" }
            override suspend fun setVolumeGoal(group: MuscleGroup, volume: Double?) { writes += "goal" }
        }, invalidation)

        settings.setWeeklyTarget(4)
        assertEquals(1, achievements)
        settings.setProgressionMode(ProgressionMode.entries.first())
        settings.setIncrement(Equipment.entries.first(), 2.5)
        settings.setVolumeGoal(MuscleGroup.entries.first(), null)
        assertEquals(1, achievements)
        assertEquals(0, localEdits)
        assertEquals(listOf("target=4", "mode", "increment", "goal"), writes)
    }

    @Test fun `a rejected weekly target publishes nothing`() = runTest(UnconfinedTestDispatcher()) {
        val invalidation = StatsInvalidation()
        var achievements = 0
        backgroundScope.launch { invalidation.changes(setOf(InvalidationScope.ACHIEVEMENTS)).collect { achievements++ } }
        val settings = InvalidatingSettingsRepository(object : SettingsRepository {
            override fun observeTraining(): Flow<TrainingSettings> = emptyFlow()
            override suspend fun setWeeklyTarget(sessionsPerWeek: Int) = throw IllegalArgumentException("out of range")
            override suspend fun setProgressionMode(mode: ProgressionMode) = Unit
            override suspend fun setIncrement(equipment: Equipment, kg: Double) = Unit
            override suspend fun setVolumeGoal(group: MuscleGroup, volume: Double?) = Unit
        }, invalidation)
        runCatching { settings.setWeeklyTarget(99) }
        assertEquals(0, achievements)
    }

    private class FakeSync(var result: SyncResult) : SyncRepository {
        override fun observeStatus(): Flow<SyncStatus> = emptyFlow()
        override suspend fun syncOnce(userId: String) = result
        override fun observeDiagnostics(): Flow<List<OutboxDiagnostic>> = emptyFlow()
    }

    @Test fun `a sync that pulled rows publishes every scope, others publish nothing`() = runTest(UnconfinedTestDispatcher()) {
        val invalidation = StatsInvalidation()
        val published = mutableListOf<Set<InvalidationScope>>()
        backgroundScope.launch { invalidation.scopes.collect { published += it } }
        val inner = FakeSync(SyncResult.Success(pushed = 2, pulled = 0, quarantined = 0))
        val sync = InvalidatingSyncRepository(inner, invalidation)

        sync.syncOnce("u")
        inner.result = SyncResult.Retryable(retryAfterMillis = null, quarantined = 0)
        sync.syncOnce("u")
        inner.result = SyncResult.SessionRequired(quarantined = 0)
        sync.syncOnce("u")
        assertEquals(emptyList<Set<InvalidationScope>>(), published)

        inner.result = SyncResult.Success(pushed = 0, pulled = 3, quarantined = 0)
        assertEquals(inner.result, sync.syncOnce("u"))
        assertEquals(listOf(InvalidationScope.entries.toSet()), published)
    }
}
