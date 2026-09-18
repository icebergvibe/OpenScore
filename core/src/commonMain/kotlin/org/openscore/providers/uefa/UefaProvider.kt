package org.openscore.providers.uefa

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import org.openscore.model.Game
import org.openscore.model.GameEvent
import org.openscore.model.League
import org.openscore.model.Lineup
import org.openscore.model.Player
import org.openscore.model.Sport
import org.openscore.model.StandingsTable
import org.openscore.model.Team
import org.openscore.net.Fetcher
import org.openscore.provider.BaseLeagueProvider
import org.openscore.provider.Capability
import org.openscore.provider.MIN_LIVE_POLL_INTERVAL
import org.openscore.provider.NotFoundException
import org.openscore.provider.getJson
import org.openscore.provider.runCatchingUnlessCancelled
import org.openscore.providers.espn.EspnRosters
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The four uefa.com micro-service roots; overridable for tests. */
public data class UefaHosts(
    val match: String = "https://match.uefa.com/v5",
    val comp: String = "https://comp.uefa.com/v2",
    val standings: String = "https://standings.uefa.com/v1",
    val stats: String = "https://matchstats.uefa.com/v1",
)

/**
 * UEFA club competitions via the key-less uefa.com micro-services — see
 * apis/football/uefa/README.md. One API for every UEFA competition, selected by
 * [competitionId]: [ChampionsLeagueProvider] (1), [EuropaLeagueProvider] (14),
 * [ConferenceLeagueProvider] (2019).
 *
 * Ids are UEFA's global digit strings (match `2049553`, team `50051`, player `250076574`).
 * `seasonId` is the season's **end year** (`2027` = 2026/27). Dates are the venue's local
 * dates (`kickOffTime.date`). No roster endpoint exists. [live] polls the 300-byte
 * `livescore` list and re-reads the match only when its `hash` changed.
 */
public open class UefaProvider(
    override val league: League,
    private val fetcher: Fetcher,
    public val competitionId: Int,
    private val hosts: UefaHosts = UefaHosts(),
    private val clock: Clock = Clock.System,
    /** Squads: `comp.uefa.com` has no squad endpoint, so they come from ESPN through the crosswalk's `espn` ids; null leaves ROSTER unsupported. */
    private val rosters: EspnRosters? = null,
) : BaseLeagueProvider() {

    private val mapper = UefaMapper(league.id)

    override suspend fun roster(teamId: String): List<Player> {
        val source = rosters ?: unsupported(Capability.ROSTER)
        val slug = when (competitionId) {
            CHAMPIONS_LEAGUE -> EspnRosters.CHAMPIONS_LEAGUE_SLUG
            EUROPA_LEAGUE -> EspnRosters.EUROPA_LEAGUE_SLUG
            else -> EspnRosters.CONFERENCE_LEAGUE_SLUG
        }
        return source.rosterFor(league.id, teamId, slug, currentSeason() - 1)
            ?: throw NotFoundException("${league.name}: no ESPN squad id for club '$teamId' in the crosswalk", league.id)
    }

    override val capabilities: Set<Capability> = (if (rosters != null) setOf(Capability.ROSTER) else emptySet()) + setOf(
        Capability.GAMES_BY_DATE,
        Capability.GAME,
        Capability.EVENTS,
        Capability.LINEUPS,
        Capability.STANDINGS,
        Capability.TEAM,
        Capability.TEAM_SCHEDULE,
        Capability.PLAYER,
        Capability.LIVE_UPDATES,
        Capability.CLOCK,
        Capability.CLOCK_RUNNING_FLAG,
        Capability.INTERMISSION_STATE,
        Capability.PERIOD_SCORES,
        Capability.EVENT_COORDINATES,
    )

    /** UEFA seasons are named by their end year and start with July's qualifiers. */
    public fun currentSeason(): Int = clock.todayIn(TimeZone.UTC).let { if (it.month >= Month.JULY) it.year + 1 else it.year }

    override suspend fun gamesOn(date: LocalDate): List<Game> {
        val list = get(
            "${hosts.match}/matches?competitionId=$competitionId&fromDate=$date&toDate=$date&limit=100&offset=0&order=ASC",
            ListSerializer(UefaMatch.serializer()), LIST_MAX_AGE,
        )
        return list.map { mapper.game(it, now = clock.now()) }.sortedBy { it.startTime }
    }

    /** `teamId=` on the season's match list: the drawn fixtures so far (league phase first, knock-out ties as they are drawn). */
    override suspend fun teamSchedule(teamId: String, startDate: LocalDate, endDate: LocalDate): List<Game> {
        val list = get(
            "${hosts.match}/matches?competitionId=$competitionId&seasonYear=${currentSeason()}&teamId=$teamId&limit=100&offset=0&order=ASC",
            ListSerializer(UefaMatch.serializer()), LIST_MAX_AGE,
        )
        val now = clock.now()
        return list.map { mapper.game(it, now = now) }
            .filter { it.startTime.toLocalDateTime(TimeZone.UTC).date in startDate..endDate }
            .sortedBy { it.startTime }
    }

    override suspend fun game(id: String): Game {
        val match = match(id)
        if (match.status == "UPCOMING") return mapper.game(match, emptyList(), null, clock.now())
        // The timeline and the team statistics are independent resources; read them together.
        return coroutineScope {
            val events = async { events(id, EVENTS_MAX_AGE) }
            val stats = async { runCatchingUnlessCancelled { teamStatistics(id) }.getOrNull() }
            mapper.game(match, events.await(), stats.await(), clock.now())
        }
    }

    override suspend fun events(gameId: String): List<GameEvent> = mapper.events(match(gameId), events(gameId, EVENTS_MAX_AGE))

    override suspend fun lineups(gameId: String): List<Lineup> =
        mapper.lineups(get("${hosts.match}/matches/$gameId/lineups", UefaLineups.serializer(), LINEUP_MAX_AGE), match(gameId))

    override suspend fun standings(seasonId: String?): StandingsTable {
        val season = seasonId ?: currentSeason().toString()
        val list = get("${hosts.standings}/standings?competitionId=$competitionId&seasonYear=$season&phase=TOURNAMENT", ListSerializer(UefaStandings.serializer()), TABLE_MAX_AGE)
        if (list.isEmpty()) throw NotFoundException("${league.name}: no standings for $season", league.id)
        return mapper.standings(list, season, league.name)
    }

    override suspend fun team(id: String): Team {
        val t = get("${hosts.comp}/teams?teamIds=$id", ListSerializer(UefaTeam.serializer()), STATIC_MAX_AGE).firstOrNull()
            ?: throw NotFoundException("${league.name}: team '$id' not found", league.id)
        return mapper.team(t)
    }

    override suspend fun player(id: String): Player {
        val p = get("${hosts.comp}/players?playerIds=$id", ListSerializer(UefaPerson.serializer()), STATIC_MAX_AGE).firstOrNull()
            ?: throw NotFoundException("${league.name}: player '$id' not found", league.id)
        return mapper.player(p)
    }

    /**
     * Polls `/livescore` every [interval] (≥ 10 s) and re-reads the match when its `hash`
     * changes, when it is missing from the list (more than an hour from kick-off), or at
     * least every [LIVE_REFRESH]; ends once the game is final.
     */
    override fun live(gameId: String, interval: Duration): Flow<Game> = flow {
        val effective = maxOf(interval, MIN_LIVE_POLL_INTERVAL)
        var last: Game? = null
        var lastHash: String? = null
        var lastRead = Instant.DISTANT_PAST
        while (true) {
            val entry = runCatchingUnlessCancelled { livescore().firstOrNull { it.id == gameId } }.getOrNull()
            val now = clock.now()
            val stale = now - lastRead >= LIVE_REFRESH
            if (entry == null || entry.hash != lastHash || stale) {
                val game = game(gameId)
                lastRead = now
                lastHash = entry?.hash
                if (game != last) emit(game)
                last = game
                if (game.state.isTerminal) return@flow
            }
            delay(effective)
        }
    }

    public suspend fun livescore(): List<UefaLivescore> =
        get("${hosts.match}/livescore", ListSerializer(UefaLivescore.serializer()), LIVESCORE_MAX_AGE)

    private suspend fun match(id: String): UefaMatch =
        get("${hosts.match}/matches/$id", UefaMatch.serializer(), MATCH_MAX_AGE)

    private suspend fun events(id: String, maxAge: Duration): List<UefaEvent> =
        get("${hosts.match}/matches/$id/events?filter=MAIN&order=ASC&limit=500&offset=0", ListSerializer(UefaEvent.serializer()), maxAge)

    private suspend fun teamStatistics(id: String): List<UefaTeamStatistics> =
        get("${hosts.stats}/team-statistics/$id", ListSerializer(UefaTeamStatistics.serializer()), STATS_MAX_AGE)

    private suspend fun <T> get(url: String, strategy: DeserializationStrategy<T>, maxAge: Duration): T =
        fetcher.getJson(url, strategy, maxAge, league.id)

    public companion object {
        public const val CHAMPIONS_LEAGUE: Int = 1
        public const val EUROPA_LEAGUE: Int = 14
        public const val CONFERENCE_LEAGUE: Int = 2019

        private val LIVESCORE_MAX_AGE = 5.seconds
        private val MATCH_MAX_AGE = 10.seconds
        private val EVENTS_MAX_AGE = 10.seconds
        private val LIST_MAX_AGE = 30.seconds
        private val STATS_MAX_AGE = 1.minutes
        private val LINEUP_MAX_AGE = 1.minutes
        private val TABLE_MAX_AGE = 1.minutes
        private val STATIC_MAX_AGE = 1.hours
        /** Re-read a live match at least this often even when the livescore hash is unchanged. */
        private val LIVE_REFRESH = 60.seconds
    }
}

/** UEFA Champions League (competition 1). */
public class ChampionsLeagueProvider(fetcher: Fetcher, hosts: UefaHosts = UefaHosts(), clock: Clock = Clock.System, rosters: EspnRosters? = null) :
    UefaProvider(LEAGUE, fetcher, UefaProvider.CHAMPIONS_LEAGUE, hosts, clock, rosters) {
    public companion object {
        public val LEAGUE: League = League("ucl", Sport.FOOTBALL, "UEFA Champions League", "EU", "https://www.uefa.com/uefachampionsleague/")
    }
}

/** UEFA Europa League (competition 14). */
public class EuropaLeagueProvider(fetcher: Fetcher, hosts: UefaHosts = UefaHosts(), clock: Clock = Clock.System, rosters: EspnRosters? = null) :
    UefaProvider(LEAGUE, fetcher, UefaProvider.EUROPA_LEAGUE, hosts, clock, rosters) {
    public companion object {
        public val LEAGUE: League = League("uel", Sport.FOOTBALL, "UEFA Europa League", "EU", "https://www.uefa.com/uefaeuropaleague/")
    }
}

/** UEFA Conference League (competition 2019). */
public class ConferenceLeagueProvider(fetcher: Fetcher, hosts: UefaHosts = UefaHosts(), clock: Clock = Clock.System, rosters: EspnRosters? = null) :
    UefaProvider(LEAGUE, fetcher, UefaProvider.CONFERENCE_LEAGUE, hosts, clock, rosters) {
    public companion object {
        public val LEAGUE: League = League("uecl", Sport.FOOTBALL, "UEFA Conference League", "EU", "https://www.uefa.com/uefaconferenceleague/")
    }
}
