package org.openscore.cache

import kotlinx.datetime.LocalDate
import org.openscore.model.Game
import kotlin.time.Instant

/** One league's games on one of its calendar dates, as last read from the network. */
public data class DayListing(
    val leagueId: String,
    val date: LocalDate,
    val fetchedAt: Instant,
    val games: List<Game>,
)

/**
 * Durable storage for normalized day listings, keyed by league and the league's own date.
 *
 * Providers never put upstream response bodies here; what is stored is the [Game] list a
 * provider produced, so a build with a fixed mapper must be able to disown what an older one
 * wrote (the Android store stamps rows with the installed build). Freshness is decided by
 * [CachedDayListingProvider] from the listing's content, not by the store.
 */
public interface DayListingStore {
    public suspend fun load(leagueId: String, date: LocalDate): DayListing?
    public suspend fun save(listing: DayListing)
}

public object NoopDayListingStore : DayListingStore {
    override suspend fun load(leagueId: String, date: LocalDate): DayListing? = null
    override suspend fun save(listing: DayListing): Unit = Unit
}
