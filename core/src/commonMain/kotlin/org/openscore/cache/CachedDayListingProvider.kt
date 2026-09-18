package org.openscore.cache

import kotlinx.datetime.LocalDate
import org.openscore.model.Game
import org.openscore.provider.LeagueProvider
import org.openscore.provider.runCatchingUnlessCancelled
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Keeps a league's day listings in a [DayListingStore] and answers [gamesOn] from it whenever
 * the stored day cannot have changed — so a day that has been read once is free from then on,
 * across processes, and the last read stands in when the network does not.
 *
 * What a stored day is worth is read off its games, with no idea of "today" needed (leagues
 * file days in their own zones):
 *
 * - **settled** — every game over, cancelled or postponed: served for [SETTLED_MAX_AGE]; only
 *   a correction upstream could change it, and a reader's refresh ([gamesOn] with `fresh`)
 *   reaches the network anyway;
 * - **due** — a game not over whose kick-off is within [KICKOFF_LEAD] or past: always read
 *   from the network, at the provider's own live cadence, since this is what turns a fixture
 *   into a live score — and never served stale, so a dead network shows an error rather than
 *   a frozen clock;
 * - **upcoming** (fixtures not yet due) and **empty** days: served for [UPCOMING_MAX_AGE], long
 *   enough that the morning's feed costs nothing, short enough that a moved kick-off shows.
 *
 * When the network fails, a stored day with nothing due is served whatever its age: that is
 * the offline app. Every other call is the delegate's, untouched.
 */
public class CachedDayListingProvider(
    private val delegate: LeagueProvider,
    private val store: DayListingStore,
    private val clock: Clock = Clock.System,
) : LeagueProvider by delegate {

    override suspend fun gamesOn(date: LocalDate): List<Game> = gamesOn(date, fresh = false)

    /**
     * [gamesOn], or with [fresh] the reader's own refresh: the store is neither consulted nor
     * fallen back on, so the answer is the network's or an error the screen can show.
     */
    public suspend fun gamesOn(date: LocalDate, fresh: Boolean): List<Game> {
        val now = clock.now()
        val stored = if (fresh) null else store.load(league.id, date)
        if (stored != null && stored.isFresh(now)) return stored.games
        val fetched = runCatchingUnlessCancelled { delegate.gamesOn(date) }
        fetched.getOrNull()?.let { games ->
            store.save(DayListing(league.id, date, now, games))
            return games
        }
        if (stored != null && stored.games.none { it.isDue(now) }) return stored.games
        throw fetched.exceptionOrNull()!!
    }

    private fun DayListing.isFresh(now: Instant): Boolean {
        val maxAge: Duration = when {
            games.isEmpty() -> UPCOMING_MAX_AGE
            games.all { it.state.isTerminal } -> SETTLED_MAX_AGE
            games.any { it.isDue(now) } -> return false
            else -> UPCOMING_MAX_AGE
        }
        return now - fetchedAt < maxAge
    }

    /** Not over, and kick-off is at hand or behind us: the listing is what says what happened next. */
    private fun Game.isDue(now: Instant): Boolean = !state.isTerminal && startTime <= now + KICKOFF_LEAD

    public companion object {
        /** A settled day changes only by an upstream correction; the reader's refresh is the shortcut. */
        public val SETTLED_MAX_AGE: Duration = 7.days
        /** Fixtures and empty days: a moved kick-off or an added game shows within the hour. */
        public val UPCOMING_MAX_AGE: Duration = 1.hours
        /** The same lead the app's own poll uses, so the first minute is read from the network. */
        public val KICKOFF_LEAD: Duration = 5.minutes
    }
}
