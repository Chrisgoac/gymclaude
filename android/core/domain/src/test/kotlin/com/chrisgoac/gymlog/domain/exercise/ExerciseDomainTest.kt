package com.chrisgoac.gymlog.domain.exercise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseDomainTest {
    private fun ex(name: String, group: MuscleGroup = MuscleGroup.PECHO, equipment: Equipment = Equipment.BARRA) =
        Exercise("id-$name", name, group, equipment, ExerciseType.COMPUESTO, isCustom = false)

    @Test fun `search ignores accents and case in both directions`() {
        assertTrue(ExerciseFilter(query = "BULGARA").matches(ex("Sentadilla Búlgara")))
        assertTrue(ExerciseFilter(query = "búlgara").matches(ex("sentadilla bulgara")))
        assertTrue(ExerciseFilter(query = "  triceps ").matches(ex("Extensión de Tríceps")))
        assertFalse(ExerciseFilter(query = "remo").matches(ex("Press banca")))
    }

    @Test fun `filters combine with text`() {
        val chest = ex("Press banca")
        val back = ex("Remo", MuscleGroup.ESPALDA, Equipment.POLEA)
        val filter = ExerciseFilter(muscleGroup = MuscleGroup.ESPALDA, equipment = Equipment.POLEA)
        assertFalse(filter.matches(chest))
        assertTrue(filter.matches(back))
        assertFalse(filter.copy(query = "press").matches(back))
        assertFalse(ExerciseFilter().isActive)
        assertTrue(filter.isActive)
    }

    @Test fun `unknown wire values degrade instead of crashing`() {
        assertEquals(MuscleGroup.OTRO, MuscleGroup.fromWire("nuevo"))
        assertEquals(Equipment.OTRO, Equipment.fromWire("kettlebell"))
        assertEquals(ExerciseType.COMPUESTO, ExerciseType.fromWire("?"))
        assertEquals(MuscleGroup.BICEPS, MuscleGroup.fromWire("biceps"))
    }

    private fun invalid(input: ExerciseInput) = (ExerciseValidator.validate(input) as ExerciseValidation.Invalid).errors

    @Test fun `name is required and capped`() {
        assertEquals(ExerciseFieldError.NAME_REQUIRED, invalid(ExerciseInput(name = "   "))[ExerciseField.NAME])
        assertEquals(ExerciseFieldError.NAME_TOO_LONG, invalid(ExerciseInput(name = "a".repeat(121)))[ExerciseField.NAME])
        assertTrue(ExerciseValidator.validate(ExerciseInput(name = "a".repeat(120))) is ExerciseValidation.Valid)
    }

    @Test fun `increment must be a positive number, comma decimals accepted`() {
        assertEquals(ExerciseFieldError.INCREMENT_NOT_POSITIVE, invalid(ExerciseInput(name = "x", incrementKg = "0"))[ExerciseField.INCREMENT_KG])
        assertEquals(ExerciseFieldError.INCREMENT_NOT_POSITIVE, invalid(ExerciseInput(name = "x", incrementKg = "-2.5"))[ExerciseField.INCREMENT_KG])
        assertEquals(ExerciseFieldError.INCREMENT_NOT_A_NUMBER, invalid(ExerciseInput(name = "x", incrementKg = "abc"))[ExerciseField.INCREMENT_KG])
        assertEquals(ExerciseFieldError.INCREMENT_NOT_A_NUMBER, invalid(ExerciseInput(name = "x", incrementKg = "NaN"))[ExerciseField.INCREMENT_KG])
        val ok = ExerciseValidator.validate(ExerciseInput(name = "x", incrementKg = "2,5")) as ExerciseValidation.Valid
        assertEquals(2.5, ok.value.incrementKg!!, 0.0)
        assertEquals(null, (ExerciseValidator.validate(ExerciseInput(name = "x")) as ExerciseValidation.Valid).value.incrementKg)
    }

    @Test fun `video url must be http or https when present`() {
        assertEquals(ExerciseFieldError.VIDEO_URL_INVALID, invalid(ExerciseInput(name = "x", videoUrl = "ftp://a.com/v"))[ExerciseField.VIDEO_URL])
        assertEquals(ExerciseFieldError.VIDEO_URL_INVALID, invalid(ExerciseInput(name = "x", videoUrl = "no es url"))[ExerciseField.VIDEO_URL])
        assertEquals(ExerciseFieldError.VIDEO_URL_INVALID, invalid(ExerciseInput(name = "x", videoUrl = "https://"))[ExerciseField.VIDEO_URL])
        assertTrue(ExerciseValidator.validate(ExerciseInput(name = "x", videoUrl = " https://youtu.be/abc ")) is ExerciseValidation.Valid)
        assertTrue(ExerciseValidator.validate(ExerciseInput(name = "x", videoUrl = "")) is ExerciseValidation.Valid)
    }

    @Test fun `long texts are rejected and blanks become null`() {
        val errors = invalid(
            ExerciseInput(name = "x", notes = "n".repeat(2001), description = "d".repeat(4001), execution = "e".repeat(4001)),
        )
        assertEquals(setOf(ExerciseField.NOTES, ExerciseField.DESCRIPTION, ExerciseField.EXECUTION), errors.keys)
        val ok = ExerciseValidator.validate(ExerciseInput(name = " Dominadas ", notes = "  ", description = "")) as ExerciseValidation.Valid
        assertEquals("Dominadas", ok.value.name)
        assertEquals(null, ok.value.notes)
        assertEquals(null, ok.value.description)
    }
}
