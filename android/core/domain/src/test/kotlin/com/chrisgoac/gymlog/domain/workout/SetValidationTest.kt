package com.chrisgoac.gymlog.domain.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetValidationTest {
    private fun weight(text: String) = SetValidation.parseWeight(text)
    private fun reps(text: String) = SetValidation.parseReps(text)

    @Test fun `weight accepts comma or dot decimals and surrounding spaces`() {
        assertEquals(FieldParse.Valid(62.5), weight("62,5"))
        assertEquals(FieldParse.Valid(62.5), weight("62.5"))
        assertEquals(FieldParse.Valid(62.5), weight(" 62.5 "))
        assertEquals(FieldParse.Valid(0.5), weight(",5"))
        // While typing: a trailing separator is the number so far.
        assertEquals(FieldParse.Valid(62.0), weight("62,"))
        assertEquals(FieldParse.Valid(0.0), weight("0"))
        assertEquals(FieldParse.Valid(1000.0), weight("1000"))
    }

    @Test fun `weight rejects empty, garbage, negatives, overflow and non finite`() {
        assertEquals(FieldParse.Invalid(SetFieldError.REQUIRED), weight(""))
        assertEquals(FieldParse.Invalid(SetFieldError.REQUIRED), weight("  "))
        assertEquals(FieldParse.Invalid(SetFieldError.NOT_A_NUMBER), weight("abc"))
        assertEquals(FieldParse.Invalid(SetFieldError.NOT_A_NUMBER), weight("6,2,5"))
        assertEquals(FieldParse.Invalid(SetFieldError.NOT_A_NUMBER), weight("1e3"))
        assertEquals(FieldParse.Invalid(SetFieldError.NOT_A_NUMBER), weight("NaN"))
        assertEquals(FieldParse.Invalid(SetFieldError.NOT_A_NUMBER), weight("Infinity"))
        assertEquals(FieldParse.Invalid(SetFieldError.NOT_A_NUMBER), weight("."))
        assertEquals(FieldParse.Invalid(SetFieldError.NEGATIVE), weight("-5"))
        assertEquals(FieldParse.Invalid(SetFieldError.TOO_LARGE), weight("1000.5"))
        assertEquals(FieldParse.Invalid(SetFieldError.TOO_LARGE), weight("9".repeat(400)))
    }

    @Test fun `reps are integers from 0 to 1000`() {
        assertEquals(FieldParse.Valid(8), reps("8"))
        assertEquals(FieldParse.Valid(0), reps("0"))
        assertEquals(FieldParse.Valid(1000), reps(" 1000 "))
        assertEquals(FieldParse.Invalid(SetFieldError.REQUIRED), reps(""))
        assertEquals(FieldParse.Invalid(SetFieldError.NOT_INTEGER), reps("8,5"))
        assertEquals(FieldParse.Invalid(SetFieldError.NOT_INTEGER), reps("8.0"))
        assertEquals(FieldParse.Invalid(SetFieldError.NOT_A_NUMBER), reps("x"))
        assertEquals(FieldParse.Invalid(SetFieldError.NEGATIVE), reps("-1"))
        assertEquals(FieldParse.Invalid(SetFieldError.TOO_LARGE), reps("1001"))
        // Would overflow Int: still a clean "too large", never a crash.
        assertEquals(FieldParse.Invalid(SetFieldError.TOO_LARGE), reps("99999999999999999999"))
    }

    @Test fun `stored values are checked the same way before any write`() {
        assertTrue(SetValidation.isStorable(0.0, 0))
        assertTrue(SetValidation.isStorable(1000.0, 1000))
        assertFalse(SetValidation.isStorable(-0.5, 5))
        assertFalse(SetValidation.isStorable(Double.NaN, 5))
        assertFalse(SetValidation.isStorable(Double.POSITIVE_INFINITY, 5))
        assertFalse(SetValidation.isStorable(1000.01, 5))
        assertFalse(SetValidation.isStorable(50.0, -1))
        assertFalse(SetValidation.isStorable(50.0, 1001))
    }

    @Test fun `a set needs at least one repetition to be completed`() {
        assertEquals(SetFieldError.ZERO_REPS, SetValidation.completionError(0))
        assertEquals(null, SetValidation.completionError(1))
    }

    @Test fun `weights render without useless decimals`() {
        assertEquals("60", SetValidation.formatWeight(60.0))
        assertEquals("62.5", SetValidation.formatWeight(62.5))
        assertEquals("11.25", SetValidation.formatWeight(11.25))
        assertEquals("0", SetValidation.formatWeight(0.0))
    }
}
