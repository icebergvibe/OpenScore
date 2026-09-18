package org.openscore.model.football

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FootballFormationTest {
    @Test
    fun `normalizes compact and dashed formations to digits`() {
        assertEquals("433", footballFormation("433"))
        assertEquals("433", footballFormation("4-3-3"))
        assertEquals("4231", footballFormation("4 – 2 – 3 – 1"))
    }

    @Test
    fun `drops tactic descriptions after the formation`() {
        assertEquals("3421", footballFormation("3-4-2-1 Double-10"))
        assertEquals("442", footballFormation("4-4-2 Diamond"))
    }

    @Test
    fun `creates one shared display form`() {
        assertEquals("4-3-3", footballFormationLabel("433"))
        assertEquals("4-2-3-1", footballFormationLabel("4-2-3-1"))
    }

    @Test
    fun `rejects placeholders and values that are not formations`() {
        assertNull(footballFormation(null))
        assertNull(footballFormation(""))
        assertNull(footballFormation("Unknown"))
        assertNull(footballFormation("0"))
    }
}
