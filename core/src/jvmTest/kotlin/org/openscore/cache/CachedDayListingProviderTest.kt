package org.openscore.cache

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.openscore.model.Game
import org.openscore.model.GameState
import org.openscore.model.League
import org.openscore.model.Score
import org.openscore.model.Sport
import org.openscore.model.TeamRef
import org.openscore.provider.BaseLeagueProvider
import org.openscore.provider.Capability
import org.openscore.provider.ProviderException
import org.openscore.provider.UnsupportedCapabilityException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class CachedDayListingProviderTest {
    private val day = LocalDate(2026, 9, 18)
    private val clock = MutableClock(Instant.parse("2026-09-18T10:00:00Z"))
    private val store = MemoryDayListingStore()
    private val upstream = ScriptedProvider()
    private val provider = CachedDayListingProvider(upstream, store, clock)

    @Test
    fun aSettledDayIsReadOnceAndServedForAWeek() = runTest {
        upstream.games = listOf(game("a", GameState.FINAL, clock.now - 20.hours), game("b", GameState.POSTPONED, clock.now - 19.hours))
        assertEquals(2, provider.gamesOn(day).size)
        clock.now += 6.days
        assertEquals(2, provider.gamesOn(day).size)
        assertEquals(1, upstream.reads, "a day of finals cannot change")
        clock.now += 2.days
        provider.gamesOn(day)
        assertEquals(2, upstream.reads, "after a week a correction upstream gets its chance")
    }

    @Test
    fun anUpcomingDayIsServedForAnHourAndReadAgainAfter() = runTest {
        upstream.games = listOf(game("a", GameState.SCHEDULED, clock.now + 8.hours))
        provider.gamesOn(day)
        clock.now += 59.minutes
        provider.gamesOn(day)
        assertEquals(1, upstream.reads)
        clock.now += 2.minutes
        provider.gamesOn(day)
        assertEquals(2, upstream.reads, "a moved kick-off shows within the hour")
    }

    @Test
    fun aDayWithAGameDueOrRunningAlwaysReadsTheNetwork() = runTest {
        upstream.games = listOf(game("a", GameState.SCHEDULED, clock.now + 4.minutes))
        provider.gamesOn(day)
        provider.gamesOn(day)
        assertEquals(2, upstream.reads, "five minutes before kick-off the listing is what goes live")

        upstream.games = listOf(game("a", GameState.LIVE, clock.now - 30.minutes, Score(1, 0)), game("b", GameState.FINAL, clock.now - 5.hours))
        provider.gamesOn(day)
        assertEquals(GameState.LIVE, provider.gamesOn(day).single { it.id == "a" }.state)
        assertEquals(4, upstream.reads)

        upstream.games = listOf(game("a", GameState.SUSPENDED, clock.now - 30.minutes, Score(1, 0)))
        provider.gamesOn(day)
        provider.gamesOn(day)
        assertEquals(6, upstream.reads, "a suspended game can resume")
    }

    @Test
    fun anEmptyDayIsServedForAnHour() = runTest {
        upstream.games = emptyList()
        assertTrue(provider.gamesOn(day).isEmpty())
        clock.now += 30.minutes
        assertTrue(provider.gamesOn(day).isEmpty())
        assertEquals(1, upstream.reads)
        clock.now += 31.minutes
        provider.gamesOn(day)
        assertEquals(2, upstream.reads)
    }

    @Test
    fun aStoredDayStandsInWhenTheNetworkFailsUnlessSomethingIsDue() = runTest {
        upstream.games = listOf(game("a", GameState.SCHEDULED, clock.now + 8.hours))
        provider.gamesOn(day)
        upstream.down = true
        clock.now += 3.hours
        assertEquals("a", provider.gamesOn(day).single().id, "yesterday's read of tomorrow's fixtures beats an error")
        clock.now += 5.hours
        assertFailsWith<ProviderException>("a due game must not be shown as a frozen fixture") { provider.gamesOn(day) }

        upstream.down = false
        upstream.games = listOf(game("a", GameState.FINAL, clock.now - 2.hours, Score(2, 2)))
        provider.gamesOn(day)
        upstream.down = true
        clock.now += 30.days
        assertEquals(Score(2, 2), provider.gamesOn(day).single().score, "a result is a result however old")
    }

    @Test
    fun aReadersRefreshReachesTheNetworkAndShowsItsFailure() = runTest {
        upstream.games = listOf(game("a", GameState.FINAL, clock.now - 20.hours, Score(1, 0)))
        provider.gamesOn(day)
        upstream.games = listOf(game("a", GameState.FINAL, clock.now - 20.hours, Score(1, 1)))
        assertEquals(Score(1, 0), provider.gamesOn(day).single().score, "a settled day is served as stored")
        assertEquals(Score(1, 1), provider.gamesOn(day, fresh = true).single().score, "unless the reader asks")
        assertEquals(Score(1, 1), provider.gamesOn(day).single().score, "and the store learnt the correction")
        upstream.down = true
        assertFailsWith<ProviderException>("an offline pull must say so, not show the old day") { provider.gamesOn(day, fresh = true) }
        assertEquals(Score(1, 1), provider.gamesOn(day).single().score)
    }

    @Test
    fun anUnknownDayWithADeadNetworkFails() = runTest {
        upstream.down = true
        assertFailsWith<ProviderException> { provider.gamesOn(day) }
        assertTrue(store.listings.isEmpty(), "a failure is never stored")
    }

    @Test
    fun everythingElseIsTheDelegates() = runTest {
        assertEquals(upstream.league, provider.league)
        assertTrue(provider.supports(Capability.GAMES_BY_DATE))
        assertFailsWith<UnsupportedCapabilityException> { provider.game("a") }
    }

    private fun game(id: String, state: GameState, start: Instant, score: Score? = null) = Game(
        leagueId = "test", id = id, startTime = start, state = state, score = score,
        home = TeamRef("test", "h", "Home", "HOM"), away = TeamRef("test", "a", "Away", "AWA"),
    )

    private class MutableClock(var now: Instant) : Clock {
        override fun now(): Instant = now
    }

    private class ScriptedProvider : BaseLeagueProvider() {
        override val league = League("test", Sport.HOCKEY, "Test", "SE", TimeZone.of("Europe/Stockholm"))
        override val capabilities = setOf(Capability.GAMES_BY_DATE)
        var games: List<Game> = emptyList()
        var down = false
        var reads = 0

        override suspend fun gamesOn(date: LocalDate): List<Game> {
            reads++
            if (down) throw ProviderException("down", leagueId = league.id)
            return games
        }
    }

    private class MemoryDayListingStore : DayListingStore {
        val listings = HashMap<Pair<String, LocalDate>, DayListing>()
        override suspend fun load(leagueId: String, date: LocalDate): DayListing? = listings[leagueId to date]
        override suspend fun save(listing: DayListing) { listings[listing.leagueId to listing.date] = listing }
    }
}
