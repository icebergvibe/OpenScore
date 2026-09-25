package org.openscore.provider

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.openscore.model.Game
import org.openscore.model.GameState
import org.openscore.model.League
import org.openscore.model.Score
import org.openscore.model.Sport
import org.openscore.model.TeamRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PollGameTest {

    /** Answers each scripted step in turn: a [Game] to return, or a [Throwable] to throw. */
    private class Scripted(private val steps: List<Any>) : BaseLeagueProvider() {
        override val league = League("test", Sport.HOCKEY, "Test", "SE", TimeZone.of("Europe/Stockholm"))
        override val capabilities = setOf(Capability.GAME, Capability.LIVE_UPDATES)
        var reads = 0
            private set

        override suspend fun game(id: String): Game {
            val step = steps[minOf(reads, steps.lastIndex)]
            reads++
            return step as? Game ?: throw step as Throwable
        }
    }

    private fun game(state: GameState, score: Score? = null) = Game(
        leagueId = "test", id = "1", startTime = Instant.parse("2026-09-13T12:00:00Z"),
        home = TeamRef("test", "h", "Home"), away = TeamRef("test", "a", "Away"),
        state = state, score = score,
    )

    @Test
    fun aFailedReadIsRetriedRatherThanEndingTheFlow() = runTest {
        val provider = Scripted(
            listOf(
                game(GameState.LIVE, Score(0, 0)),
                IllegalStateException("one bad request"),
                game(GameState.LIVE, Score(1, 0)),
                game(GameState.FINAL, Score(2, 0)),
            ),
        )

        val seen = pollGame(provider, "1", 10.seconds).toList()

        // The failure is invisible to the collector: it is not a change, it is a missed look.
        assertEquals(listOf(Score(0, 0), Score(1, 0), Score(2, 0)), seen.map { it.score })
        assertEquals(GameState.FINAL, seen.last().state)
    }

    @Test
    fun aGoodReadForgivesTheFailuresBeforeIt() = runTest {
        // Four failures, then a good read, then four more: never five in a row, so it survives.
        val fail = IllegalStateException("flaky")
        val steps = List(4) { fail } + game(GameState.LIVE) + List(4) { fail } + game(GameState.FINAL)
        val provider = Scripted(steps)

        val seen = pollGame(provider, "1", 10.seconds).toList()

        assertEquals(listOf(GameState.LIVE, GameState.FINAL), seen.map { it.state })
    }

    @Test
    fun failuresInARowAreTakenAsRealAndThrown() = runTest {
        val provider = Scripted(listOf(IllegalStateException("upstream is down")))

        val thrown = assertFailsWith<IllegalStateException> { pollGame(provider, "1", 10.seconds).toList() }

        assertEquals("upstream is down", thrown.message)
        assertEquals(MAX_CONSECUTIVE_FAILURES, provider.reads)
    }

    @Test
    fun aGameThatNeverEndsIsNotPolledForever() = runTest {
        // SUSPENDED is not terminal - a suspended game resumes - so nothing else stops this.
        val provider = Scripted(listOf(game(GameState.SUSPENDED)))

        val seen = pollGame(provider, "1", 10.seconds).toList()

        assertEquals(1, seen.size, "an unchanging game is emitted once")
        assertEquals((LIVE_POLL_CEILING / 10.seconds).toInt(), provider.reads)
        assertTrue(provider.reads in 1..10_000, "the ceiling has to be a bound, not a formality")
    }

    @Test
    fun aFinishedGameEndsTheFlowAtOnce() = runTest {
        val provider = Scripted(listOf(game(GameState.FINAL, Score(3, 1))))

        val seen = pollGame(provider, "1", 10.seconds).toList()

        assertEquals(1, seen.size)
        assertEquals(1, provider.reads)
    }
}
