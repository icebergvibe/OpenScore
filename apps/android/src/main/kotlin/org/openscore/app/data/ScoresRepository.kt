package org.openscore.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.openscore.GamesOnDate
import org.openscore.OpenScore
import org.openscore.app.ui.team.clubSources
import org.openscore.model.Game
import org.openscore.model.GameEvent
import org.openscore.model.League
import org.openscore.model.Lineup
import org.openscore.model.Player
import org.openscore.model.Sport
import org.openscore.model.StandingsTable
import org.openscore.model.Team
import org.openscore.model.TeamRef
import org.openscore.model.TeamSeasonStats
import org.openscore.model.motorsport.RacingClassification
import org.openscore.model.motorsport.RacingSeason
import org.openscore.model.motorsport.RacingSessionKind
import org.openscore.model.motorsport.RacingStandings
import org.openscore.provider.Capability
import org.openscore.provider.LeagueProvider
import org.openscore.provider.runCatchingUnlessCancelled

/**
 * The app's one door to the core. Deliberately thin: caching and politeness live in the
 * core's fetcher, and every screen reads the same model the providers produce.
 *
 * Every call runs on [Dispatchers.Default]: the core decodes bodies, parses JSON/XML and maps
 * games in whichever coroutine asks, and the view models ask from the main thread, where a
 * dozen leagues' worth of parsing would stall the frame the spinner is drawn in.
 */
class ScoresRepository(private val openScore: OpenScore, private val cache: ScoresCache = NoopScoresCache) {

    /**
     * The leagues the app knows. The core's umbrella feeds (Fogis carries every Swedish tier
     * down to the districts) are left out: Allsvenskan, Superettan and Svenska Cupen are cut
     * from that feed as leagues of their own, and the rest would swamp "all football" with
     * dozens of lower-league fixtures — and, polled on their own, hand back copies of the
     * games those three already show.
     */
    val leagues: List<League> = openScore.leagues.filterNot { it.id in OpenScore.DEFAULT_UMBRELLAS }

    fun leagues(sport: Sport): List<League> = leagues.filter { it.sport == sport }

    fun league(id: String): League? = leagues.firstOrNull { it.id == id }

    /**
     * The zone [id] keeps its calendar in, for working out the day one of its games is filed
     * under ([org.openscore.model.leagueDate]). Asked of the core rather than [leagues], which
     * leaves the umbrella feeds out; every provider the app can hold a game from answers here.
     *
     * The reader's own zone is the last resort and not the default: an NHL game at 20:00 Eastern
     * belongs to the NHL's day, not to whichever day it happens to be where the phone is.
     */
    fun zoneOf(leagueId: String): TimeZone =
        openScore.providerOrNull(leagueId)?.league?.zone
            ?: openScore.racingProviderOrNull(leagueId)?.league?.zone
            ?: TimeZone.currentSystemDefault()

    /**
     * Whether a league is served by a [LeagueProvider] — two-team games with a day listing.
     * The racing league (`f1`) is in [leagues] for the rail, the filter sheet and Following,
     * but has no games to ask for: a feed shows its sessions ([racingSessionsOn]) instead.
     */
    fun hasGames(leagueId: String): Boolean = openScore.providerOrNull(leagueId) != null

    fun isRacing(leagueId: String): Boolean = openScore.racingProviderOrNull(leagueId) != null

    /**
     * The sessions of a racing league on [date] in the phone's zone, from the season calendar
     * (one small read per year, cached a day by the core). Throws like any league read so the
     * feed can report the series as failed for the day.
     */
    suspend fun racingSessionsOn(date: LocalDate, leagueId: String, zone: TimeZone = TimeZone.currentSystemDefault()): List<RacingSessionEntry> =
        withContext(Dispatchers.Default) {
            val provider = openScore.racingProviderOrNull(leagueId) ?: return@withContext emptyList()
            provider.season(date.year).sessionsOn(date, zone, leagueId)
        }

    fun provider(leagueId: String): LeagueProvider = openScore.provider(leagueId)

    suspend fun f1Season(year: Int): RacingSeason = withContext(Dispatchers.Default) { openScore.racingProviderOrNull("f1")!!.season(year) }
    suspend fun f1Standings(year: Int): RacingStandings = withContext(Dispatchers.Default) { openScore.racingProviderOrNull("f1")!!.standings(year) }
    suspend fun f1Classification(year: Int, round: Int, kind: RacingSessionKind): RacingClassification = withContext(Dispatchers.Default) { openScore.racingProviderOrNull("f1")!!.classification(year, round, kind) }

    fun supports(leagueId: String, capability: Capability): Boolean =
        openScore.providerOrNull(leagueId)?.supports(capability) == true

    /**
     * Games on [date] (in each league's own date convention) across [leagueIds], fetched
     * concurrently and emitted as each league answers; the last emission has nothing
     * [GamesOnDate.pending]. The quick leagues are on screen while the slow host thinks.
     * A day the store can vouch for costs no request unless [fresh] — the reader's own pull.
     */
    fun gamesOn(date: LocalDate, leagueIds: Collection<String>, fresh: Boolean = false): Flow<GamesOnDate> {
        // A followed or selected league without games (F1) is dropped here rather than failing
        // the whole day: the aggregator throws for an id it has no provider for.
        val asked = leagueIds.filter(::hasGames)
        return if (asked.isEmpty()) flowOf(GamesOnDate(date, emptyList(), emptyList(), emptyList()))
        else openScore.gamesOnProgressively(date, asked, fresh).flowOn(Dispatchers.Default)
    }

    suspend fun cacheSizeBytes(): Long = withContext(Dispatchers.IO) { cache.sizeBytes() }

    suspend fun clearCache() = withContext(Dispatchers.IO) { cache.clear() }

    suspend fun game(leagueId: String, id: String): Game = withContext(Dispatchers.Default) { provider(leagueId).game(id) }

    /**
     * A game known only by its ids and the league's day it is filed under — what a notification
     * carries. The provider's own `game()` where it has one, else the day listing it came from.
     */
    suspend fun find(leagueId: String, id: String, date: LocalDate): Game? {
        if (supports(leagueId, Capability.GAME)) runCatchingUnlessCancelled { game(leagueId, id) }.getOrNull()?.let { return it }
        return runCatchingUnlessCancelled { gamesOn(date, listOf(leagueId)).last().games.firstOrNull { it.id == id } }.getOrNull()
    }

    suspend fun events(leagueId: String, id: String): List<GameEvent> = withContext(Dispatchers.Default) { provider(leagueId).events(id) }

    suspend fun lineups(leagueId: String, id: String): List<Lineup> = withContext(Dispatchers.Default) { provider(leagueId).lineups(id) }

    suspend fun standings(leagueId: String, seasonId: String? = null): StandingsTable = withContext(Dispatchers.Default) { provider(leagueId).standings(seasonId) }

    /**
     * A team profile is enough to open the page; each optional section checks its own capability.
     * A club known to the crosswalk opens through any league that has a profile for it, so a
     * cup or UEFA fixture leads to the same page as the domestic one.
     */
    fun hasTeamPage(team: TeamRef): Boolean = clubSources(team, leagues).any { supports(it.leagueId, Capability.TEAM) }

    suspend fun team(ref: TeamRef): Team = withContext(Dispatchers.Default) { provider(ref.leagueId).team(ref.id) }

    suspend fun roster(ref: TeamRef): List<Player> = withContext(Dispatchers.Default) { provider(ref.leagueId).roster(ref.id) }

    /** A team's games in an inclusive range of the league's calendar dates — a season window, as the team page asks. */
    suspend fun teamSchedule(ref: TeamRef, startDate: LocalDate, endDate: LocalDate): List<Game> = withContext(Dispatchers.Default) {
        provider(ref.leagueId).teamSchedule(ref.id, startDate, endDate)
    }

    suspend fun teamStats(ref: TeamRef, season: Int): TeamSeasonStats = withContext(Dispatchers.Default) {
        provider(ref.leagueId).teamStats(ref.id, season.toString())
    }

    /** The core's live flow: polling at the 10 s floor, or push where the provider has it. */
    fun live(leagueId: String, id: String): Flow<Game> = provider(leagueId).live(id).flowOn(Dispatchers.Default)
}
