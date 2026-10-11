package com.chrisgoac.gymlog.domain.history

import com.chrisgoac.gymlog.domain.history.InvalidationScope.ACHIEVEMENTS
import com.chrisgoac.gymlog.domain.history.InvalidationScope.CHARTS
import com.chrisgoac.gymlog.domain.history.InvalidationScope.COACH_SNAPSHOT
import com.chrisgoac.gymlog.domain.history.InvalidationScope.MUSCLE_BALANCE
import com.chrisgoac.gymlog.domain.history.InvalidationScope.ONE_REP_MAX
import com.chrisgoac.gymlog.domain.history.InvalidationScope.PERSONAL_RECORDS
import com.chrisgoac.gymlog.domain.history.InvalidationScope.STALLS
import com.chrisgoac.gymlog.domain.history.InvalidationScope.STREAKS
import com.chrisgoac.gymlog.domain.history.InvalidationScope.VOLUME
import com.chrisgoac.gymlog.domain.history.InvalidationScope.WEEKLY_SUMMARY
import com.chrisgoac.gymlog.domain.workout.SetFieldError
import com.chrisgoac.gymlog.domain.workout.WorkoutAggregate
import com.chrisgoac.gymlog.domain.workout.WorkoutExercise
import com.chrisgoac.gymlog.domain.workout.WorkoutLimits
import com.chrisgoac.gymlog.domain.workout.WorkoutSession
import com.chrisgoac.gymlog.domain.workout.WorkoutSet
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

private const val NOW = 1_800_000_000_000L
private const val DAY = 24L * 60 * 60 * 1000

private class FakeEdits(
    val exercises: Set<String> = setOf("seed-press", "seed-row", "seed-squat"),
    val gyms: Set<String> = setOf("g1", "g2"),
    var outcome: EditOutcome = EditOutcome.Applied,
) : WorkoutEditRepository {
    val applied = mutableListOf<WorkoutEdit>()

    override suspend fun missingExercises(exerciseIds: Set<String>): Set<String> = exerciseIds - exercises
    override suspend fun isGymAvailable(gymId: String): Boolean = gymId in gyms
    override suspend fun apply(original: WorkoutAggregate, edit: WorkoutEdit): EditOutcome {
        applied += edit
        return outcome
    }
}

class SaveWorkoutDraftTest {
    private val original = WorkoutAggregate(
        WorkoutSession("s1", "r1", "g1", NOW - 2 * DAY, 3600, "Bien", updatedAt = 10),
        listOf(
            WorkoutExercise(
                "le1", "seed-press", "Press", 0,
                listOf(WorkoutSet("a", 0, 60.0, 8, false, 11), WorkoutSet("b", 1, 62.5, 6, false, 12)),
                updatedAt = 13,
            ),
            WorkoutExercise("le2", "seed-row", "Remo", 1, listOf(WorkoutSet("c", 0, 50.0, 10, true, 14)), updatedAt = 15),
            // A custom exercise deleted after the workout: the row stays editable.
            WorkoutExercise("le3", "custom-gone", null, 2, listOf(WorkoutSet("d", 0, 20.0, 12, false, 16)), updatedAt = 17),
        ),
    )
    private val draft = WorkoutDraft.from(original)

    private suspend fun save(draft: WorkoutDraft, repo: FakeEdits = FakeEdits()): Pair<SaveResult, FakeEdits> =
        DefaultSaveWorkoutDraftUseCase(repo) { NOW }(original, draft) to repo

    @Test fun `an unchanged draft writes nothing`() = runTest {
        val (result, repo) = save(draft)
        assertEquals(SaveResult.Saved(emptySet()), result)
        assertTrue(repo.applied.isEmpty())
    }

    @Test fun `the draft of an aggregate reproduces its order and formatted values`() {
        assertEquals(listOf("le1", "le2", "le3"), draft.exercises.map { it.id })
        assertEquals(listOf("60", "62.5"), draft.exercises[0].sets.map { it.weight })
        assertEquals("Bien", draft.notes)
    }

    @Test fun `date gym and notes edit only the session row`() = runTest {
        val edited = draft.copy(startedAt = Instant.ofEpochMilli(NOW - 3 * DAY), gymId = "g2", notes = "  Mejor \u0000 ")
        val (result, repo) = save(edited)
        val edit = repo.applied.single()
        assertEquals(SessionEdit(NOW - 3 * DAY, "g2", "Mejor"), edit.session)
        assertTrue(edit.upsertExercises.isEmpty() && edit.upsertSets.isEmpty() && edit.removedExerciseIds.isEmpty() && edit.removedSetIds.isEmpty())
        assertEquals(
            SaveResult.Saved(setOf(STREAKS, WEEKLY_SUMMARY, CHARTS, PERSONAL_RECORDS, STALLS, ACHIEVEMENTS, COACH_SNAPSHOT)),
            result,
        )
    }

    @Test fun `blank notes clear them and alone only invalidate the coach snapshot`() = runTest {
        val (result, repo) = save(draft.copy(notes = "   "))
        assertNull(repo.applied.single().session!!.notes)
        assertEquals(SaveResult.Saved(setOf(COACH_SNAPSHOT)), result)
    }

    @Test fun `gym change only invalidates charts and coach`() = runTest {
        val (result, _) = save(draft.copy(gymId = null))
        assertEquals(SaveResult.Saved(setOf(CHARTS, COACH_SNAPSHOT)), result)
    }

    @Test fun `set values add and remove keep stable ids and compact positions`() = runTest {
        val edited = draft
            .withSet("a") { it.copy(weight = "65", reps = "7") }
            .removeSet("b")
            .withExercise("le2") { it.copy(sets = it.sets + LoggedSetDraft("new-set", "52,5", "8", false)) }
        val (result, repo) = save(edited)
        val edit = repo.applied.single()
        assertNull(edit.session)
        assertEquals(listOf(SetRowEdit("a", "le1", 0, 65.0, 7, false), SetRowEdit("new-set", "le2", 1, 52.5, 8, false)), edit.upsertSets)
        assertEquals(listOf("b"), edit.removedSetIds)
        assertTrue(edit.upsertExercises.isEmpty())
        assertEquals(SaveResult.Saved(setOf(PERSONAL_RECORDS, ONE_REP_MAX, VOLUME, CHARTS, STALLS, ACHIEVEMENTS, COACH_SNAPSHOT)), result)
    }

    @Test fun `warm-up flag is a set value`() = runTest {
        val (result, repo) = save(draft.withSet("c") { it.copy(warmup = false) })
        assertEquals(listOf(SetRowEdit("c", "le2", 0, 50.0, 10, false)), repo.applied.single().upsertSets)
        assertTrue((result as SaveResult.Saved).invalidatedScopes.contains(ONE_REP_MAX))
    }

    @Test fun `typing the same value differently writes nothing`() = runTest {
        val (result, repo) = save(draft.withSet("b") { it.copy(weight = "62,50", reps = "06") })
        assertEquals(SaveResult.Saved(emptySet()), result)
        assertTrue(repo.applied.isEmpty())
    }

    @Test fun `reordering writes only the rows whose position changed and invalidates no metric`() = runTest {
        val edited = draft.moveExercise("le3", -2).moveSet("b", -1)
        val (result, repo) = save(edited)
        val edit = repo.applied.single()
        assertEquals(
            listOf(ExerciseRowEdit("le3", "custom-gone", 0), ExerciseRowEdit("le1", "seed-press", 1), ExerciseRowEdit("le2", "seed-row", 2)),
            edit.upsertExercises,
        )
        assertEquals(listOf(SetRowEdit("b", "le1", 0, 62.5, 6, false), SetRowEdit("a", "le1", 1, 60.0, 8, false)), edit.upsertSets)
        assertEquals(SaveResult.Saved(setOf(COACH_SNAPSHOT)), result)
    }

    @Test fun `removing an exercise tombstones it and every set it had`() = runTest {
        val (result, repo) = save(draft.removeExercise("le1"))
        val edit = repo.applied.single()
        assertEquals(listOf("le1"), edit.removedExerciseIds)
        assertEquals(listOf("a", "b"), edit.removedSetIds)
        assertEquals(listOf(ExerciseRowEdit("le2", "seed-row", 0), ExerciseRowEdit("le3", "custom-gone", 1)), edit.upsertExercises)
        assertTrue((result as SaveResult.Saved).invalidatedScopes.containsAll(setOf(MUSCLE_BALANCE, PERSONAL_RECORDS, VOLUME)))
    }

    @Test fun `adding an exercise with sets keeps its id and invalidates balance`() = runTest {
        val added = LoggedExerciseDraft("le-new", "seed-squat", "Sentadilla", listOf(LoggedSetDraft("s-new", "100", "5", false)))
        val (result, repo) = save(draft.copy(exercises = draft.exercises + added))
        val edit = repo.applied.single()
        assertEquals(listOf(ExerciseRowEdit("le-new", "seed-squat", 3)), edit.upsertExercises)
        assertEquals(listOf(SetRowEdit("s-new", "le-new", 0, 100.0, 5, false)), edit.upsertSets)
        assertEquals(
            SaveResult.Saved(setOf(MUSCLE_BALANCE, PERSONAL_RECORDS, ONE_REP_MAX, VOLUME, CHARTS, STALLS, ACHIEVEMENTS, COACH_SNAPSHOT)),
            result,
        )
    }

    @Test fun `every invalid field is reported at once and nothing is written`() = runTest {
        val added = LoggedExerciseDraft("le-new", "nope", null, emptyList())
        val edited = draft.copy(
            startedAt = Instant.ofEpochMilli(NOW + 10 * 60 * 1000),
            gymId = "gone",
            notes = "x".repeat(WorkoutLimits.NOTES_MAX + 1),
            exercises = draft.exercises + added,
        )
            .withSet("a") { it.copy(weight = "1001") }
            .withSet("b") { it.copy(reps = "6.5") }
            .withSet("c") { it.copy(weight = "", reps = "-1") }
        val (result, repo) = save(edited)
        assertTrue(repo.applied.isEmpty())
        val errors = (result as SaveResult.Invalid).summary.errors
        assertEquals(
            setOf(
                DraftError.DateInFuture,
                DraftError.GymUnavailable("gone"),
                DraftError.NotesTooLong,
                DraftError.UnknownExercise("le-new", "nope"),
                DraftError.InvalidWeight("a", SetFieldError.TOO_LARGE),
                DraftError.InvalidReps("b", SetFieldError.NOT_INTEGER),
                DraftError.InvalidWeight("c", SetFieldError.REQUIRED),
                DraftError.InvalidReps("c", SetFieldError.NEGATIVE),
            ),
            errors.toSet(),
        )
        assertEquals(8, errors.size)
    }

    @Test fun `a start within the clock tolerance is accepted, before the epoch is not`() = runTest {
        val (ok, _) = save(draft.copy(startedAt = Instant.ofEpochMilli(NOW + WorkoutDraftValidation.FUTURE_TOLERANCE_MILLIS)))
        assertTrue(ok is SaveResult.Saved)
        val (bad, _) = save(draft.copy(startedAt = Instant.ofEpochMilli(-1)))
        assertEquals(SaveResult.Invalid(DraftValidationSummary(listOf(DraftError.DateOutOfRange))), bad)
    }

    @Test fun `existing references are not re-validated, so a deleted exercise or gym does not block an edit`() = runTest {
        val repo = FakeEdits(gyms = emptySet())
        val (result, _) = save(draft.withSet("d") { it.copy(reps = "15") }, repo)
        assertTrue(result is SaveResult.Saved)
        assertEquals(1, repo.applied.size)
    }

    @Test fun `conflict and not found are reported as they are`() = runTest {
        val edited = draft.withSet("a") { it.copy(reps = "9") }
        assertEquals(SaveResult.Conflict, save(edited, FakeEdits(outcome = EditOutcome.Conflict)).first)
        assertEquals(SaveResult.NotFound, save(edited, FakeEdits(outcome = EditOutcome.NotFound)).first)
    }

    @Test fun `draft and original survive the saved-state codec unchanged`() {
        val edited = draft.copy(gymId = null, notes = "ñ \"comillas\"")
            .withExercise("le3") { it.copy(sets = it.sets + LoggedSetDraft("x", "6,", "", true)) }
        assertEquals(edited, WorkoutDraftCodec.decodeDraft(WorkoutDraftCodec.encode(edited)))
        assertEquals(original, WorkoutDraftCodec.decodeAggregate(WorkoutDraftCodec.encode(original)))
        assertNull(WorkoutDraftCodec.decodeDraft("{broken"))
        assertNull(WorkoutDraftCodec.decodeAggregate(null))
    }

    // ---- Fix round 1: stored order and values as the PWA leaves them ----

    /** PWA data: removals never renumber and adds use the live count, so orden has gaps and duplicates. */
    private val messy = WorkoutAggregate(
        WorkoutSession("s1", null, "g1", NOW - 2 * DAY, 3600, null, updatedAt = 1),
        listOf(
            WorkoutExercise(
                "le1", "seed-press", "Press", 3,
                listOf(WorkoutSet("a", 0, 60.0, 8, false), WorkoutSet("b", 2, 62.123456789012, 1500, false), WorkoutSet("c", 2, 65.0, 5, false)),
            ),
            WorkoutExercise("le2", "seed-row", "Remo", 3, listOf(WorkoutSet("d", 5, 50.0, 10, false))),
        ),
    )

    private suspend fun saveMessy(draft: WorkoutDraft, repo: FakeEdits = FakeEdits()) =
        DefaultSaveWorkoutDraftUseCase(repo) { NOW }(messy, draft) to repo

    @Test fun `a clean draft of non-compact and duplicate orden writes nothing`() = runTest {
        val draft = WorkoutDraft.from(messy)
        assertTrue(WorkoutEdit.between(messy, draft).isEmpty)
        val (result, repo) = saveMessy(draft)
        assertEquals(SaveResult.Saved(emptySet()), result)
        assertTrue(repo.applied.isEmpty())
    }

    @Test fun `editing one weight in a non-compact parent writes exactly that set at its stored position`() = runTest {
        val (_, repo) = saveMessy(WorkoutDraft.from(messy).withSet("c") { it.copy(weight = "67,5") })
        val edit = repo.applied.single()
        assertNull(edit.session)
        assertTrue(edit.upsertExercises.isEmpty())
        assertEquals(listOf(SetRowEdit("c", "le1", 2, 67.5, 5, false)), edit.upsertSets)
    }

    @Test fun `only a reordered parent is renumbered`() = runTest {
        val (_, repo) = saveMessy(WorkoutDraft.from(messy).moveSet("c", -1))
        val edit = repo.applied.single()
        // a(0) c b → 0 1 2: a and b already hold their new positions; le2's d keeps its 5.
        assertEquals(listOf(SetRowEdit("c", "le1", 1, 65.0, 5, false)), edit.upsertSets)
        assertTrue(edit.upsertExercises.isEmpty())
    }

    @Test fun `untouched values that do not round-trip or exceed today's bounds never block a save`() = runTest {
        val draft = WorkoutDraft.from(messy)
        assertEquals("62.123456789012", draft.exercises[0].sets[1].weight) // 15 chars: unparseable as typed text
        assertTrue(WorkoutDraftValidation.validateValues(draft, NOW, messy).isEmpty())
        val (result, repo) = saveMessy(draft.withSet("a") { it.copy(reps = "9") })
        assertTrue(result is SaveResult.Saved)
        assertEquals(listOf(SetRowEdit("a", "le1", 0, 60.0, 9, false)), repo.applied.single().upsertSets)
        // Touching such a value validates it like any other.
        val (invalid, _) = saveMessy(draft.withSet("b") { it.copy(reps = "1501") })
        assertEquals(listOf(DraftError.InvalidReps("b", SetFieldError.TOO_LARGE)), (invalid as SaveResult.Invalid).summary.errors)
    }

    @Test fun `a set moved to another exercise counts as a value change`() {
        val draft = WorkoutDraft.from(original)
        val moved = draft.exercises[0].sets[0]
        val edited = draft.copy(
            exercises = listOf(
                draft.exercises[0].copy(sets = draft.exercises[0].sets.drop(1)),
                draft.exercises[1].copy(sets = draft.exercises[1].sets + moved),
                draft.exercises[2],
            ),
        )
        val edit = WorkoutEdit.between(original, edited)
        assertEquals(SetRowEdit("a", "le2", 1, 60.0, 8, false), edit.upsertSets.first { it.id == "a" })
        assertTrue(edit.invalidatedScopes(original).containsAll(setOf(PERSONAL_RECORDS, VOLUME, ONE_REP_MAX)))
    }

    @Test fun `an unfinished workout is not editable here`() = runTest {
        val active = original.copy(session = original.session.copy(durationSeconds = null))
        val repo = FakeEdits()
        val result = DefaultSaveWorkoutDraftUseCase(repo) { NOW }(active, WorkoutDraft.from(active).copy(notes = "x"))
        assertEquals(SaveResult.NotEditable, result)
        assertTrue(repo.applied.isEmpty())
    }
}
