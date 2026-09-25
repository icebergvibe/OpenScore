package org.openscore.app.data

import org.openscore.OpenScore
import org.openscore.cache.CachedDayListingProvider
import org.openscore.net.FetchResponse
import org.openscore.net.Fetcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration

/**
 * [RoomScoresCache] is the [org.openscore.cache.SeasonScheduleStore] every league without a day
 * route is handed, and it stores nothing for a league it does not know. The provider that asked
 * wraps its write in a `runCatching`, so a league missing from [RoomScoresCache.SNAPSHOT_LEAGUES]
 * would lose its snapshot on every process start and say nothing at all about it - which is what
 * happened to the UFC. Assert the two agree instead.
 */
class SnapshotLeaguesTest {

    /** Providers are built without touching the network; nothing here is ever called. */
    private object NoFetcher : Fetcher {
        override suspend fun get(url: String, headers: Map<String, String>, maxAge: Duration): FetchResponse =
            throw UnsupportedOperationException("the test builds providers, it does not read them")
    }

    @Test
    fun everyLeagueKeepingASeasonSnapshotIsOneTheStoreAccepts() {
        // `OpenScore.default` gives a league either the day-listing cache or, where the feed has
        // no day route, the season store. The ones it leaves unwrapped are exactly the latter.
        val snapshotLeagues = OpenScore.default(NoFetcher).providers
            .filterNot { it is CachedDayListingProvider }
            .map { it.league.id }
            .toSet()

        assertEquals(snapshotLeagues, RoomScoresCache.SNAPSHOT_LEAGUES)
    }
}
