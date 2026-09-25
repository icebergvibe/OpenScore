package org.openscore

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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

@OptIn(ExperimentalCoroutinesApi::class)
class OpenScoreTest {

    private class Stub(id: String, private val games: List<Game>) : BaseLeagueProvider() {
        override val league = League(id, Sport.FOOTBALL, id, "SE", TimeZone.of("Europe/Stockholm"))
        override val capabilities = setOf(Capability.GAMES_BY_DATE)
        override suspend fun gamesOn(date: LocalDate): List<Game> = games
    }

    private fun game(league: String, id: String, competition: String? = null) = Game(
        leagueId = league, id = id, competition = competition, startTime = Instant.parse("2026-09-13T12:00:00Z"),
        home = TeamRef(league, "h", "Home"), away = TeamRef(league, "a", "Away"), state = GameState.SCHEDULED,
    )

    private val allsvenskan = Stub("allsvenskan", listOf(game("allsvenskan", "6529991")))
    private val fogis = Stub("fogis", listOf(game("fogis", "6529991", "Allsvenskan 2026"), game("fogis", "6547937", "Div 2 Norra Svealand, herr 2026")))
    private val day = LocalDate.parse("2026-09-13")

    @Test
    fun umbrellaCopyIsDroppedWhenBothLeaguesAreAsked() = runTest {
        val result = OpenScore(listOf(allsvenskan, fogis)).gamesOn(day)
        assertEquals(listOf("allsvenskan/6529991", "fogis/6547937"), result.games.map { "${it.leagueId}/${it.id}" })
    }

    @Test
    fun umbrellaKeepsEverythingWhenAskedAlone() = runTest {
        val result = OpenScore(listOf(allsvenskan, fogis)).gamesOn(day, listOf("fogis"))
        assertEquals(listOf("6529991", "6547937"), result.games.map { it.id })
    }

    @Test
    fun noDedupeWithoutAnUmbrellaRule() = runTest {
        val result = OpenScore(listOf(allsvenskan, fogis), umbrellas = emptyMap()).gamesOn(day)
        assertEquals(3, result.games.size)
    }

    @Test
    fun repeatedLeagueIdsAreFetchedOnce() = runTest {
        val result = OpenScore(listOf(allsvenskan), umbrellas = emptyMap())
            .gamesOn(day, listOf("allsvenskan", "ALLSVENSKAN"))

        assertEquals(listOf("allsvenskan"), result.leagues)
        assertEquals(1, result.games.size)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OpenScoreProgressiveTest {

    /** Answers after [gate] completes, so the test decides who is slow. */
    private class Gated(id: String, private val games: List<Game>, private val gate: CompletableDeferred<Unit>?) : BaseLeagueProvider() {
        override val league = League(id, Sport.FOOTBALL, id, "SE", TimeZone.of("Europe/Stockholm"))
        override val capabilities = setOf(Capability.GAMES_BY_DATE)
        override suspend fun gamesOn(date: LocalDate): List<Game> { gate?.await(); return games }
    }

    private fun game(league: String, id: String, competition: String? = null) = Game(
        leagueId = league, id = id, competition = competition, startTime = Instant.parse("2026-09-13T12:00:00Z"),
        home = TeamRef(league, "h", "Home"), away = TeamRef(league, "a", "Away"), state = GameState.SCHEDULED,
    )

    private val day = LocalDate.parse("2026-09-13")

    @Test
    fun quickLeaguesAreEmittedBeforeTheSlowOneAnswers() = runTest {
        val slow = CompletableDeferred<Unit>()
        val core = OpenScore(listOf(Gated("nhl", listOf(game("nhl", "1")), null), Gated("shl", listOf(game("shl", "2")), slow)), umbrellas = emptyMap())
        val seen = mutableListOf<GamesOnDate>()
        val job = launch { core.gamesOnProgressively(day).collect { seen += it } }
        runCurrent()
        assertEquals(listOf("nhl"), seen.single().games.map { it.leagueId })
        assertEquals(listOf("shl"), seen.single().pending)
        slow.complete(Unit)
        job.join()
        assertEquals(2, seen.size)
        assertEquals(listOf("nhl", "shl"), seen.last().games.map { it.leagueId })
        assertTrue(seen.last().pending.isEmpty())
        assertEquals(seen.last(), core.gamesOn(day))
    }

    @Test
    fun umbrellaWaitsForItsDedicatedLeagues() = runTest {
        val allsvenskan = CompletableDeferred<Unit>()
        val core = OpenScore(
            listOf(
                Gated("allsvenskan", listOf(game("allsvenskan", "6529991")), allsvenskan),
                Gated("fogis", listOf(game("fogis", "6529991", "Allsvenskan 2026"), game("fogis", "6547937", "Div 2")), null),
                Gated("shl", listOf(game("shl", "9")), null),
            ),
            umbrellas = mapOf("fogis" to setOf("allsvenskan")),
        )
        val seen = mutableListOf<GamesOnDate>()
        val job = launch { core.gamesOnProgressively(day).collect { seen += it } }
        runCurrent()
        // Fogis has answered, but it is not shown until Allsvenskan has: its copy of 6529991 would otherwise flash.
        assertEquals(listOf("shl"), seen.last().games.map { it.leagueId })
        assertEquals(listOf("allsvenskan", "fogis"), seen.last().pending)
        allsvenskan.complete(Unit)
        job.join()
        assertEquals(listOf("allsvenskan/6529991", "fogis/6547937", "shl/9"), seen.last().games.map { "${it.leagueId}/${it.id}" })
        assertTrue(seen.last().pending.isEmpty())
    }

    @Test
    fun errorsArriveWithTheLeagueThatFailed() = runTest {
        val failing = object : BaseLeagueProvider() {
            override val league = League("khl", Sport.HOCKEY, "khl", "RU", TimeZone.of("Europe/Moscow"))
            override val capabilities = setOf(Capability.GAMES_BY_DATE)
            override suspend fun gamesOn(date: LocalDate): List<Game> = throw IllegalStateException("down")
        }
        val result = OpenScore(listOf(failing, Gated("nhl", listOf(game("nhl", "1")), null)), umbrellas = emptyMap()).gamesOn(day)
        assertEquals(listOf("khl"), result.errors.map { it.leagueId })
        assertEquals(listOf("nhl"), result.games.map { it.leagueId })
    }
}
