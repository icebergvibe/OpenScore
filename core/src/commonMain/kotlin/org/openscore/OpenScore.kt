package org.openscore

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import org.openscore.cache.CachedDayListingProvider
import org.openscore.cache.DayListingStore
import org.openscore.cache.NoopDayListingStore
import org.openscore.cache.NoopSeasonScheduleStore
import org.openscore.cache.SeasonScheduleStore
import org.openscore.model.Game
import org.openscore.model.League
import org.openscore.net.Fetcher
import org.openscore.net.KtorFetcher
import org.openscore.provider.LeagueProvider
import org.openscore.provider.ProviderException
import org.openscore.provider.RacingProvider
import org.openscore.provider.runCatchingUnlessCancelled
import org.openscore.providers.bundesliga.BundesligaProvider
import org.openscore.providers.chl.ChlProvider
import org.openscore.providers.espn.EspnRosters
import org.openscore.providers.fogis.FogisProvider
import org.openscore.providers.fogis.SwedishLeagueProvider
import org.openscore.providers.jolpica.JolpicaProvider
import org.openscore.providers.khl.KhlProvider
import org.openscore.providers.laliga.LaLigaProvider
import org.openscore.providers.ligue1.Ligue1Provider
import org.openscore.providers.liiga.LiigaProvider
import org.openscore.providers.malta.MaltaProvider
import org.openscore.providers.mlb.MlbProvider
import org.openscore.providers.mls.MlsProvider
import org.openscore.providers.nhl.NhlProvider
import org.openscore.providers.premierleague.PremierLeagueProvider
import org.openscore.providers.seriea.SerieAProvider
import org.openscore.providers.sportality.HockeyAllsvenskanProvider
import org.openscore.providers.sportality.ShlProvider
import org.openscore.providers.sportomedia.AllsvenskanProvider
import org.openscore.providers.sportomedia.SuperettanProvider
import org.openscore.providers.uefa.ChampionsLeagueProvider
import org.openscore.providers.uefa.ConferenceLeagueProvider
import org.openscore.providers.uefa.EuropaLeagueProvider

/**
 * All leagues behind one door. Look a provider up by league id, or ask across leagues.
 *
 * @param umbrellas leagues whose feed also carries games that a dedicated provider serves under
 *   the same ids (Fogis carries every SvFF tier, including the Allsvenskan, Superettan and
 *   Svenska Cupen games the [SwedishLeagueProvider]s serve). When both sides are asked in one
 *   [gamesOn], the umbrella's copy of a shared game is dropped so a "today" screen does not list
 *   a match twice.
 */
public class OpenScore(
    public val providers: List<LeagueProvider>,
    private val umbrellas: Map<String, Set<String>> = DEFAULT_UMBRELLAS,
    private val racing: List<RacingProvider> = emptyList(),
) {

    private val byId: Map<String, LeagueProvider> = providers.associateBy { it.league.id }

    public val leagues: List<League> get() = providers.map { it.league } + racing.map { it.league }

    public fun provider(leagueId: String): LeagueProvider =
        byId[leagueId.lowercase()] ?: throw UnknownLeagueException(leagueId)

    public fun providerOrNull(leagueId: String): LeagueProvider? = byId[leagueId.lowercase()]

    /** F1 is a field sport, so it has its own racing surface instead of pretending sessions are two-team games. */
    public fun racingProviderOrNull(leagueId: String): RacingProvider? =
        racing.firstOrNull { it.league.id == leagueId.lowercase() }

    /**
     * Games on [date] across [leagueIds] (all leagues by default), fetched concurrently.
     * A failing league does not fail the call; it is reported in [GamesOnDate.errors].
     */
    public suspend fun gamesOn(date: LocalDate, leagueIds: Collection<String>? = null, fresh: Boolean = false): GamesOnDate =
        gamesOnProgressively(date, leagueIds, fresh).last()

    /**
     * [gamesOn] as it comes in: one [GamesOnDate] after each league answers, carrying every
     * game so far and, in [GamesOnDate.pending], the leagues still to come. The last emission
     * has nothing pending and equals what [gamesOn] returns. A screen can draw the quick
     * leagues while the slow host is still thinking.
     *
     * An umbrella league whose dedicated leagues are also selected is held back until they
     * have answered, so its copy of a shared game is never shown and then taken away.
     *
     * @param fresh the reader's own refresh: a league's stored day listing is neither served
     *   nor fallen back on, so what comes back is the network's answer or that league's error.
     */
    public fun gamesOnProgressively(date: LocalDate, leagueIds: Collection<String>? = null, fresh: Boolean = false): Flow<GamesOnDate> = channelFlow {
        // Public callers (including the comma-separated feed query) can repeat ids. Resolve
        // each provider once so duplicate input cannot duplicate games or work.
        val selected = leagueIds?.distinctBy { it.lowercase() }?.map(::provider) ?: providers
        val selectedIds = selected.map { it.league.id }
        val overlap = umbrellas.filterKeys { it in selectedIds }
            .mapValues { (_, dedicated) -> dedicated.filter { it in selectedIds }.toSet() }
            .filterValues { it.isNotEmpty() }
        val answered = LinkedHashMap<String, LeagueResult>()
        val lock = Mutex()

        // An umbrella counts as answered only once the leagues it overlaps have all answered.
        fun ready(): Map<String, LeagueResult> = answered.filterKeys { id -> overlap[id]?.all { it in answered } ?: true }

        fun snapshot(): GamesOnDate {
            val ready = ready()
            return GamesOnDate(
                date = date,
                leagues = selectedIds,
                games = deduplicate(ready.values.flatMap { it.games }, overlap).sortedWith(compareBy({ it.startTime }, { it.leagueId }, { it.id })),
                errors = ready.values.mapNotNull { r -> r.error?.let { LeagueError(r.leagueId, it) } },
                pending = selectedIds.filterNot { it in ready },
            )
        }

        if (selected.isEmpty()) {
            send(snapshot())
            return@channelFlow
        }
        selected.forEach { p ->
            launch {
                val result = runCatchingUnlessCancelled { if (fresh && p is CachedDayListingProvider) p.gamesOn(date, fresh = true) else p.gamesOn(date) }
                    .fold({ LeagueResult(p.league.id, it, null) }, { LeagueResult(p.league.id, emptyList(), it) })
                val snap = lock.withLock {
                    val readyBefore = ready().size
                    answered[p.league.id] = result
                    // Nothing to say while an umbrella waits on its dedicated leagues.
                    if (ready().size > readyBefore) snapshot() else null
                }
                if (snap != null) send(snap)
            }
        }
    }

    /** Drops an umbrella league's copy of every game a selected dedicated league also returned. */
    private fun deduplicate(games: List<Game>, overlap: Map<String, Set<String>>): List<Game> {
        if (overlap.isEmpty()) return games
        val dedicatedIds = games.filter { g -> overlap.values.any { g.leagueId in it } }.map { it.id }.toSet()
        return games.filterNot { it.leagueId in overlap && it.id in dedicatedIds }
    }

    private class LeagueResult(val leagueId: String, val games: List<Game>, val error: Throwable?)

    public companion object {
        /** The three Swedish leagues are cut from the same Fogis feed, so the umbrella's copies are theirs. */
        public val DEFAULT_UMBRELLAS: Map<String, Set<String>> = mapOf(
            FogisProvider.LEAGUE.id to setOf(AllsvenskanProvider.LEAGUE.id, SuperettanProvider.LEAGUE.id, SwedishLeagueProvider.SVENSKA_CUPEN.id),
        )

        /**
         * Every league that has a provider, sharing one [Fetcher].
         *
         * @param seasonScheduleStore durable home of HockeyAllsvenskan's season, the one league
         *   with no day route of its own.
         * @param dayListingStore durable home of every other league's day listings; each provider
         *   is wrapped in a [CachedDayListingProvider] that serves a settled or upcoming day from
         *   it and falls back to it when the network fails.
         */
        public fun default(
            fetcher: Fetcher = KtorFetcher(),
            seasonScheduleStore: SeasonScheduleStore = NoopSeasonScheduleStore,
            dayListingStore: DayListingStore = NoopDayListingStore,
        ): OpenScore {
            val fogis = FogisProvider(fetcher)
            // Squads for the two feeds without a key-less squad of their own (docs/principles.md).
            val espnRosters = EspnRosters(fetcher)
            val providers = listOf(
                NhlProvider(fetcher),
                LiigaProvider(fetcher),
                ShlProvider(fetcher),
                HockeyAllsvenskanProvider(fetcher, scheduleStore = seasonScheduleStore),
                ChlProvider(fetcher),
                KhlProvider(fetcher),
                Ligue1Provider(fetcher),
                BundesligaProvider(fetcher, rosters = espnRosters),
                PremierLeagueProvider(fetcher),
                SerieAProvider(fetcher),
                LaLigaProvider(fetcher),
                MlsProvider(fetcher),
                MaltaProvider(fetcher),
            ) + SwedishLeagueProvider.default(fetcher, fogis) + listOf(
                fogis,
                ChampionsLeagueProvider(fetcher, rosters = espnRosters),
                EuropaLeagueProvider(fetcher, rosters = espnRosters),
                ConferenceLeagueProvider(fetcher, rosters = espnRosters),
                MlbProvider(fetcher),
            )
            return OpenScore(
                // HockeyAllsvenskan keeps its own season snapshot; every other league's days are stored as read.
                providers.map { if (it is HockeyAllsvenskanProvider) it else CachedDayListingProvider(it, dayListingStore) },
                racing = listOf(JolpicaProvider(fetcher)),
            )
        }
    }
}

public data class GamesOnDate(
    val date: LocalDate,
    val leagues: List<String>,
    val games: List<Game>,
    val errors: List<LeagueError>,
    /** Leagues that have not answered yet; empty from [OpenScore.gamesOn], and in the last emission of [OpenScore.gamesOnProgressively]. */
    val pending: List<String> = emptyList(),
)

public data class LeagueError(val leagueId: String, val cause: Throwable)

public class UnknownLeagueException(leagueId: String) : ProviderException("Unknown league '$leagueId'", leagueId = leagueId)
