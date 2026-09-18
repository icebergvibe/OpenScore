package org.openscore.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class DatesTest {
    @Test
    fun instantAcceptsCompactOffsetsAndZulu() {
        assertEquals(Instant.parse("2026-09-12T19:00:00Z"), Dates.instant("2026-09-12T21:00:00+0200"))
        assertEquals(Instant.parse("2026-09-12T19:00:00Z"), Dates.instant(" 2026-09-12T19:00:00Z "))
        assertNull(Dates.instantOrNull("not a date"))
        assertNull(Dates.instantOrNull(null))
    }

    @Test
    fun localDateTakesTheDatePartOfWhateverAFeedWrites() {
        val expected = LocalDate(2001, 5, 14)
        assertEquals(expected, Dates.localDateOrNull("2001-05-14"))
        assertEquals(expected, Dates.localDateOrNull("2001-05-14T00:00:00Z"))
        assertEquals(expected, Dates.localDateOrNull(" 2001-05-14 "))
        assertNull(Dates.localDateOrNull("14/05/2001"))
        assertNull(Dates.localDateOrNull("2001-05"))
        assertNull(Dates.localDateOrNull(""))
        assertNull(Dates.localDateOrNull(null))
    }

    @Test
    fun runCatchingUnlessCancelledKeepsFailuresAndLetsCancellationThrough() = runTest {
        assertEquals(1, runCatchingUnlessCancelled { 1 }.getOrNull())
        assertTrue(runCatchingUnlessCancelled<Int> { error("boom") }.isFailure)
        assertFailsWith<CancellationException> { runCatchingUnlessCancelled<Int> { throw CancellationException("gone") } }
    }
}
