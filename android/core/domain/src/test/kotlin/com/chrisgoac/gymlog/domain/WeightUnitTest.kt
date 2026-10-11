package com.chrisgoac.gymlog.domain

import com.chrisgoac.gymlog.domain.math.WeightUnit
import org.junit.Assert.assertEquals
import org.junit.Test

/** The PWA stores kg and has no lb display; these pin the Android conversion (storage is always kg). */
class WeightUnitTest {
    @Test fun kilogramsAreUntouched() {
        assertEquals(62.5, WeightUnit.KG.fromKg(62.5), 0.0)
        assertEquals(62.5, WeightUnit.KG.toKg(62.5), 0.0)
    }

    @Test fun poundsDisplayRoundedToATenth() {
        assertEquals(220.5, WeightUnit.LB.display(100.0), 0.0)
        assertEquals(0.0, WeightUnit.LB.display(0.0), 0.0)
        assertEquals(137.8, WeightUnit.LB.display(62.5), 0.0)
    }

    @Test fun enteredPoundsConvertBackToKg() {
        assertEquals(45.359237, WeightUnit.LB.toKg(100.0), 1e-9)
    }

    @Test fun displayDoesNotChangeTheStoredValue() {
        val stored = 82.5
        WeightUnit.LB.display(stored)
        assertEquals(82.5, stored, 0.0)
        assertEquals(82.5, WeightUnit.LB.toKg(WeightUnit.LB.fromKg(stored)), 1e-12)
    }

    @Test fun unknownWireFallsBackToKg() {
        assertEquals(WeightUnit.KG, WeightUnit.fromWire(null))
        assertEquals(WeightUnit.LB, WeightUnit.fromWire("lb"))
    }
}
