package com.chrisgoac.gymlog.domain.stats

import com.chrisgoac.gymlog.domain.history.DraftValidationSummary
import com.chrisgoac.gymlog.domain.history.InvalidationScope
import com.chrisgoac.gymlog.domain.history.SaveResult
import com.chrisgoac.gymlog.domain.history.SaveWorkoutDraftUseCase
import com.chrisgoac.gymlog.domain.history.WorkoutDraft
import com.chrisgoac.gymlog.domain.workout.WorkoutAggregate
import com.chrisgoac.gymlog.domain.workout.WorkoutSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
}
