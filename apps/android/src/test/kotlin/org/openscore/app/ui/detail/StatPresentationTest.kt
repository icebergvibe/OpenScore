package org.openscore.app.ui.detail

import kotlin.test.Test
import kotlin.test.assertEquals

class StatPresentationTest {
    @Test
    fun possessionValuesShowTheirPercentageUnitOnce() {
        assertEquals("57%", statValue("possession", "57"))
        assertEquals("43%", statValue("possession", "43%"))
    }

    @Test
    fun passAccuracyIsAPercentageToo() {
        assertEquals("88%", statValue("passAccuracy", "88"))
    }

    @Test
    fun otherStatsRemainUnchanged() {
        assertEquals("12", statValue("shots", "12"))
        assertEquals("1.23", statValue("xg", "1.23"))
    }

    @Test
    fun longDecimalsAreRoundedToOnePlace() {
        assertEquals("104.4", statValue("distanceKm", "104.431"))
    }

    @Test
    fun labelsNameTheirUnit() {
        assertEquals("Distance (km)", statLabel("distanceKm"))
        assertEquals("Shots on target", statLabel("shotsOnTarget"))
    }
}
