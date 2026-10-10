package com.chrisgoac.gymlog.domain.routine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineValidatorTest {
    private fun ex(
        key: String = "k1",
        sets: String = "",
        repsMin: String = "",
        repsMax: String = "",
        rest: String = "",
        notes: String = "",
    ) = RoutineExerciseInput(key, "seed-press", "Press", sets, repsMin, repsMax, rest, notes)

    private fun valid(input: RoutineInput) = (RoutineValidator.validate(input) as RoutineValidation.Valid).value
    private fun issues(input: RoutineInput) = (RoutineValidator.validate(input) as RoutineValidation.Invalid).issues

    @Test
    fun trimsNameAndMapsBlankOptionalsToNull() {
        val v = valid(RoutineInput("  Push  ", "  ", listOf(ex(notes = "  "))))
        assertEquals("Push", v.name)
        assertEquals(null, v.description)
        assertEquals(null, v.exercises.single().sets)
        assertEquals(null, v.exercises.single().notes)
    }

    @Test
    fun nameIsRequiredAndBounded() {
        assertEquals(listOf(RoutineIssue(null, RoutineField.NAME, RoutineError.REQUIRED)), issues(RoutineInput("  ")))
        assertEquals(RoutineError.TOO_LONG, issues(RoutineInput("x".repeat(121))).single().error)
    }

    @Test
    fun parsesTargets() {
        val e = valid(RoutineInput("Push", exercises = listOf(ex(sets = "4", repsMin = "8", repsMax = "12", rest = "90", notes = "lento")))).exercises.single()
        assertEquals(4, e.sets)
        assertEquals(8, e.repsMin)
        assertEquals(12, e.repsMax)
        assertEquals(90, e.restSeconds)
        assertEquals("lento", e.notes)
    }

    @Test
    fun zeroRestIsAllowedButZeroSetsAndRepsAreNot() {
        assertEquals(0, valid(RoutineInput("A", exercises = listOf(ex(rest = "0")))).exercises.single().restSeconds)
        val bad = issues(RoutineInput("A", exercises = listOf(ex(sets = "0", repsMin = "0", repsMax = "0"))))
        assertEquals(
            setOf(RoutineField.SETS, RoutineField.REPS_MIN, RoutineField.REPS_MAX),
            bad.filter { it.error == RoutineError.NOT_POSITIVE }.map { it.field }.toSet(),
        )
    }

    @Test
    fun rejectsNonIntegersAndNegatives() {
        val bad = issues(RoutineInput("A", exercises = listOf(ex(sets = "x", repsMax = "1.5", rest = "-5"))))
        assertEquals(RoutineError.NOT_A_NUMBER, bad.first { it.field == RoutineField.SETS }.error)
        assertEquals(RoutineError.NOT_A_NUMBER, bad.first { it.field == RoutineField.REPS_MAX }.error)
        assertEquals(RoutineError.NOT_A_NUMBER, bad.first { it.field == RoutineField.REST }.error)
    }

    @Test
    fun rangeMinMustNotExceedMax() {
        val bad = issues(RoutineInput("A", exercises = listOf(ex(key = "k9", repsMin = "12", repsMax = "8"))))
        assertEquals(listOf(RoutineIssue("k9", RoutineField.REPS_MIN, RoutineError.RANGE_INVERTED)), bad)
        // equal bounds and a single bound are fine
        valid(RoutineInput("A", exercises = listOf(ex(repsMin = "8", repsMax = "8"))))
        valid(RoutineInput("A", exercises = listOf(ex(repsMin = "8"))))
        valid(RoutineInput("A", exercises = listOf(ex(repsMax = "8"))))
    }

    @Test
    fun upperBounds() {
        val bad = issues(RoutineInput("A", exercises = listOf(ex(sets = "100", repsMax = "1000", rest = "3601"))))
        assertEquals(3, bad.count { it.error == RoutineError.TOO_LARGE })
    }

    @Test
    fun duplicateKeysAreRejected() {
        assertTrue(issues(RoutineInput("A", exercises = listOf(ex(key = "k"), ex(key = "k")))).any { it.error == RoutineError.DUPLICATE_KEY })
    }

    @Test
    fun orderFollowsListPosition() {
        val v = valid(RoutineInput("A", exercises = listOf(ex(key = "a"), ex(key = "b"), ex(key = "c"))))
        assertEquals(listOf("a", "b", "c"), v.exercises.map { it.key })
    }
}
