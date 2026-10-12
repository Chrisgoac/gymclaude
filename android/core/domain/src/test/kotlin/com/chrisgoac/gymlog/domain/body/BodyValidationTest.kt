package com.chrisgoac.gymlog.domain.body

import com.chrisgoac.gymlog.domain.math.WeightUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BodyValidationTest {
    private val weight = BodyMetrics.resolve("peso", emptyList())
    private val waist = BodyMetrics.resolve("cintura", emptyList())

    private fun parse(text: String, metric: BodyMetricDef = weight, unit: WeightUnit = WeightUnit.KG) = BodyValidation.parseValue(text, metric, unit)
    private fun error(text: String, metric: BodyMetricDef = weight) = (parse(text, metric) as? BodyParse.Invalid)?.error

    @Test fun `accepts dot and comma decimals and trims`() {
        assertEquals(BodyParse.Valid(80.5), parse(" 80,5 "))
        assertEquals(BodyParse.Valid(80.5), parse("80.5"))
        assertEquals(BodyParse.Valid(90.0), parse("90", waist))
    }

    @Test fun `rejects what the permissive PWA parser would accept`() {
        for (text in listOf("Infinity", "-Infinity", "0x50", "0b101", "1e2", "NaN", "80kg", "8 0", "--5", "+80", ".")) {
            assertEquals(text, BodyValueError.NOT_A_NUMBER, error(text))
        }
        assertEquals(BodyValueError.REQUIRED, error("   "))
        assertEquals(BodyValueError.NEGATIVE, error("-80"))
        assertEquals(BodyValueError.TOO_LARGE, error("1" + "0".repeat(20)))
    }

    @Test fun `bounds per metric`() {
        assertEquals(BodyParse.Valid(20.0), parse("20"))
        assertEquals(BodyParse.Valid(400.0), parse("400"))
        assertEquals(BodyValueError.TOO_SMALL, error("19.99"))
        assertEquals(BodyValueError.TOO_LARGE, error("400.01"))
        assertEquals(BodyValueError.TOO_SMALL, error("0"))
        assertEquals(BodyValueError.TOO_SMALL, error("9.99", waist))
        assertEquals(BodyValueError.TOO_LARGE, error("300.01", waist))
        val custom = BodyMetrics.custom(CustomBodyMetric("grasa", "Grasa", "%"))
        assertEquals(BodyValueError.TOO_SMALL, error("0", custom))
        assertEquals(BodyParse.Valid(18.5), parse("18,5", custom))
    }

    @Test fun `weight typed in pounds is stored in kilograms and checked there`() {
        assertEquals(BodyParse.Valid(69.85), parse("154,0", weight, WeightUnit.LB))
        // 44 lb is 19.96 kg: below the 20 kg floor even though 44 > 20.
        assertEquals(BodyValueError.TOO_SMALL, (parse("44", weight, WeightUnit.LB) as BodyParse.Invalid).error)
        // Lengths ignore the weight unit.
        assertEquals(BodyParse.Valid(90.0), parse("90", waist, WeightUnit.LB))
        // The bound reported to the user is in their unit.
        assertEquals(881.8, (parse("900", weight, WeightUnit.LB) as BodyParse.Invalid).max)
    }

    @Test fun `dates tolerate five minutes of clock skew and reject typos`() {
        val now = 1_800_000_000_000L
        assertNull(BodyValidation.checkDate(now + 5 * 60 * 1000, now))
        assertEquals(BodyDateError.IN_FUTURE, BodyValidation.checkDate(now + 5 * 60 * 1000 + 1, now))
        assertEquals(BodyDateError.TOO_OLD, BodyValidation.checkDate(BodyValidation.MIN_DATE_MILLIS - 1, now))
        assertNull(BodyValidation.checkDate(BodyValidation.MIN_DATE_MILLIS, now))
    }

    @Test fun `slugify matches the PWA`() {
        assertEquals("grasa-corporal", BodyMetrics.slugify("  Grasa  corporal! "))
        assertEquals("muneca", BodyMetrics.slugify("Muñeca"))
        assertEquals("", BodyMetrics.slugify("¡¡"))
        assertEquals("a-b", BodyMetrics.slugify("--A__b--"))
    }

    @Test fun `custom metric checks`() {
        val existing = listOf(CustomBodyMetric("muneca", "Muñeca", "cm"))
        assertEquals(CustomMetricCheck.Existing(existing[0]), BodyMetrics.checkCustom("MUÑECA", "mm", existing))
        assertEquals(CustomMetricCheck.Invalid(CustomMetricProblem.RESERVED), BodyMetrics.checkCustom("Pecho", "cm", existing))
        assertEquals(CustomMetricCheck.Invalid(CustomMetricProblem.LIMIT), BodyMetrics.checkCustom("Otra", "", List(30) { CustomBodyMetric("k$it", "k$it", "") }))
        assertEquals(CustomMetricCheck.New(CustomBodyMetric("tobillo", "Tobillo", "")), BodyMetrics.checkCustom(" Tobillo ", " ", existing))
    }

    @Test fun `unknown keys resolve to a defensive definition`() {
        val unknown = BodyMetrics.resolve("grasa", emptyList())
        assertEquals("grasa", unknown.label)
        assertEquals("", unknown.unit)
    }
}
