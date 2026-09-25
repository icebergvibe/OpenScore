package org.openscore

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.openscore.model.Game
import org.openscore.model.GameState
import org.openscore.model.League
import org.openscore.model.Sport
import org.openscore.model.TeamRef
import org.openscore.provider.BaseLeagueProvider
import org.openscore.provider.Capability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The aggregator on a real multi-threaded dispatcher, which is how the app and the feed server
 * run it. [OpenScoreProgressiveTest] pins the ordering rules on a single-threaded test
 * dispatcher, where the leagues cannot actually interleave; these run them in parallel.
 *
 * Repetition is the point. The fault these cover - a snapshot built under the lock and emitted
 * after releasing it, so a thinner snapshot could overtake a fuller one - showed in about one
 * run in ninety, which no single pass would catch.
 */
class OpenScoreConcurrencyTest {

    private class Stub(id: String) : BaseLeagueProvider() {
        override val league = League(id, Sport.FOOTBALL, id, "SE", TimeZone.of("Europe/Stockholm"))
        override val capabilities = setOf(Capability.GAMES_BY_DATE)
        override suspend fun gamesOn(date: LocalDate): List<Game> = listOf(
            Game(
                leagueId = league.id,
                id = "g",
                startTime = Instant.parse("2026-09-13T12:00:00Z"),
                home = TeamRef(league.id, "h", "Home"),
                away = TeamRef(league.id, "a", "Away"),
                state = GameState.SCHEDULED,
            ),
        )
    }

    private val day = LocalDate.parse("2026-09-13")
    private val core = OpenScore((1..LEAGUES).map { Stub("l$it") }, umbrellas = emptyMap())

    @Test
    fun gamesOnAlwaysAnswersWithEveryLeague() = runBlocking(Dispatchers.Default) {
        repeat(RUNS) { run ->
            val result = core.gamesOn(day)
            assertTrue(result.pending.isEmpty(), "run $run answered with ${result.pending.size} league(s) still pending")
            assertEquals(LEAGUES, result.games.size, "run $run answered with ${result.games.size} of $LEAGUES games")
        }
    }

    @Test
    fun progressiveEmissionsOnlyEverGrow() = runBlocking(Dispatchers.Default) {
        repeat(RUNS) { run ->
            val seen = core.gamesOnProgressively(day).toList()
            assertEquals(LEAGUES, seen.size, "run $run emitted ${seen.size} time(s) for $LEAGUES leagues")
            // Each league answers once, so every emission must carry more games and fewer
            // outstanding leagues than the one before it.
            seen.zipWithNext { earlier, later ->
                assertTrue(
                    later.games.size > earlier.games.size,
                    "run $run went from ${earlier.games.size} games back to ${later.games.size}",
                )
                assertTrue(
                    later.pending.size < earlier.pending.size,
                    "run $run went from ${earlier.pending.size} pending back to ${later.pending.size}",
                )
            }
            assertTrue(seen.last().pending.isEmpty(), "run $run ended with ${seen.last().pending.size} league(s) pending")
        }
    }

    private companion object {
        /** Enough leagues to keep several threads busy; the app asks for about this many. */
        const val LEAGUES = 24
        /** Chosen so a one-in-ninety fault is missed about once in a hundred thousand test runs. */
        const val RUNS = 1000
    }
}
