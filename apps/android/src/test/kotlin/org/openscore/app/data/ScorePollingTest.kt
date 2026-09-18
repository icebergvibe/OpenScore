package org.openscore.app.data

import org.openscore.app.Fixtures
import org.openscore.model.GameState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ScorePollingTest {
    private val now = Instant.parse("2026-09-18T17:05:00Z")

    private fun at(offset: kotlin.time.Duration, state: GameState = GameState.SCHEDULED) =
        Fixtures.game(state = state, startTime = now + offset)

    @Test
    fun aGameInPlayIsAlwaysPolled() {
        assertEquals(true, at(-5.hours, GameState.LIVE).wantsScorePoll(now))
        assertEquals(true, at(-5.hours, GameState.INTERMISSION).wantsScorePoll(now))
    }

    @Test
    fun aScheduledGameIsPolledFromJustBeforeKickOffUntilItIsClearlyNotStarting() {
        assertEquals(false, at(6.minutes).wantsScorePoll(now), "not yet due")
        assertEquals(true, at(4.minutes).wantsScorePoll(now), "on the doorstep")
        assertEquals(true, at(-5.minutes).wantsScorePoll(now), "kicked off, listing still says scheduled")
        assertEquals(true, at(-5.minutes, GameState.PRE_GAME).wantsScorePoll(now))
        assertEquals(true, at(-(2.hours + 59.minutes)).wantsScorePoll(now))
        assertEquals(false, at(-(3.hours + 1.minutes)).wantsScorePoll(now), "a fixture that never went live is left alone")
        assertEquals(false, at(1.hours, GameState.PRE_GAME).wantsScorePoll(now), "warm-ups an hour out are not kick-off")
    }

    @Test
    fun anUnfixedKickOffAndAnythingOverAreNeverPolled() {
        assertEquals(false, at(-1.hours).copy(startTimeTbd = true).wantsScorePoll(now))
        assertEquals(false, at(-1.hours, GameState.FINAL).wantsScorePoll(now))
        assertEquals(false, at(-1.hours, GameState.POSTPONED).wantsScorePoll(now))
        assertEquals(false, at(-1.hours, GameState.CANCELLED).wantsScorePoll(now))
        assertEquals(false, at(-1.hours, GameState.SUSPENDED).wantsScorePoll(now))
    }
}
