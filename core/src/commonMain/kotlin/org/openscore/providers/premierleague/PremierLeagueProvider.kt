package org.openscore.providers.premierleague

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
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
import org.openscore.provider.NotFoundException
import org.openscore.provider.getJson
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Premier League via the Pulselive SDP API behind premierleague.com — see
 * apis/football/premier-league/README.md.
 *
 * Ids are Opta ids as strings (match `2645215`, team `3`, player `154561`); `seasonId`
 * is the start year (`2026`). Dates are English local dates. A full game needs five
 * small calls (match, timeline, events, lineups, stats).
 *
 * Live states were recorded end to end on 2026-09-13: `clock` is whole cumulative
 * minutes with no seconds, it freezes through `HalfTime` and steps back to 45 at the
 * restart, and a zeroed score block appears about an hour before kick-off while
 * `period` is still `PreMatch` - which is why the state is read from `period` alone.
 */
public class PremierLeagueProvider(
    private val fetcher: Fetcher,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val competitionId: String = PREMIER_LEAGUE,
    override val league: League = LEAGUE,
    private val clock: Clock = Clock.System,
) : BaseLeagueProvider() {

    private val mapper = PremierLeagueMapper(league.id)

    override val capabilities: Set<Capability> = setOf(
        Capability.GAMES_BY_DATE,
        Capability.GAME,
        Capability.EVENTS,
        Capability.LINEUPS,
        Capability.STANDINGS,
        Capability.TEAM,
        Capability.ROSTER,
        Capability.TEAM_SCHEDULE,
        Capability.PLAYER,
        Capability.LIVE_UPDATES,
        Capability.CLOCK,
        Capability.CLOCK_RUNNING_FLAG,
        Capability.INTERMISSION_STATE,
        Capability.PERIOD_SCORES,
    )

    /** Seasons are keyed by start year; a new one begins in July. */
    public fun currentSeason(): String {
        val today = clock.todayIn(league.zone)
        return (if (today.month >= Month.JULY) today.year else today.year - 1).toString()
    }

    override suspend fun gamesOn(date: LocalDate): List<Game> {
        val season = seasonFor(date)
        // `kickoff` is compared as a string ("2026-09-12 15:00:00"); date-only bounds select the whole day,
        // a "T00:00:00" bound would sort after every kick-off of that day.
        val next = date.plus(1, DateTimeUnit.DAY)
        val page = get(
            "/v2/matches?competition=$competitionId&season=$season&kickoff%3E$date&kickoff%3C$next&_limit=100",
            PlPage.serializer(PlMatch.serializer()), LIVE_MAX_AGE,
        )
        return page.data.filter { it.kickoff != null }.map { mapper.game(it, withEvents = false) }.sortedBy { it.startTime }
    }

    private fun seasonFor(date: LocalDate): String = (if (date.month >= Month.JULY) date.year else date.year - 1).toString()

    override suspend fun game(id: String): Game {
        // State is available in the sub-kilobyte match resource. Before kick-off the other
        // four resources are empty, so read them only after play has actually started.
        val match = get("/v2/matches/$id", PlMatch.serializer(), LIVE_MAX_AGE)
        val summary = mapper.game(match, withEvents = false)
        if (!summary.state.hasStarted) return summary.copy(events = emptyList())

        return coroutineScope {
            // These four resources are independent. Parallel reads reduce detail latency without
            // increasing the number of upstream requests; KtorFetcher still coalesces overlaps.
            val timeline = async { get("/v1/matches/$id/timeline", ListSerializer(PlTimelineEvent.serializer()), LIVE_MAX_AGE) }
            val events = async { get("/v1/matches/$id/events", PlEvents.serializer(), LIVE_MAX_AGE) }
            val lineups = async { get("/v3/matches/$id/lineups", PlLineups.serializer(), LINEUP_MAX_AGE) }
            val stats = async { get("/v3/matches/$id/stats", ListSerializer(PlTeamStats.serializer()), STATS_MAX_AGE) }
            mapper.game(match, withEvents = true, timeline = timeline.await(), events = events.await(), lineups = lineups.await(), stats = stats.await())
        }
    }

    override suspend fun events(gameId: String): List<GameEvent> = coroutineScope {
        // Event callers (notably background alerts) do not need the stats endpoint.
        val match = async { get("/v2/matches/$gameId", PlMatch.serializer(), LIVE_MAX_AGE) }
        val timeline = async { get("/v1/matches/$gameId/timeline", ListSerializer(PlTimelineEvent.serializer()), LIVE_MAX_AGE) }
        val events = async { get("/v1/matches/$gameId/events", PlEvents.serializer(), LIVE_MAX_AGE) }
        val lineups = async { get("/v3/matches/$gameId/lineups", PlLineups.serializer(), LINEUP_MAX_AGE) }
        val m = match.await()
        mapper.events(timeline.await(), events.await(), lineups.await(), mapper.teamRef(m.homeTeam), mapper.teamRef(m.awayTeam))
    }

    override suspend fun lineups(gameId: String): List<Lineup> = coroutineScope {
        val match = async { get("/v2/matches/$gameId", PlMatch.serializer(), LIVE_MAX_AGE) }
        val lineups = async { get("/v3/matches/$gameId/lineups", PlLineups.serializer(), LINEUP_MAX_AGE) }
        val m = match.await()
        mapper.lineups(gameId, lineups.await(), mapper.teamRef(m.homeTeam), mapper.teamRef(m.awayTeam))
    }

    override suspend fun standings(seasonId: String?): StandingsTable {
        val season = seasonId ?: currentSeason()
        return mapper.standings(get("/v5/competitions/$competitionId/seasons/$season/standings", PlStandings.serializer(), TABLE_MAX_AGE), league.name)
    }

    override suspend fun team(id: String): Team {
        val teams = get("/v1/competitions/$competitionId/seasons/${currentSeason()}/teams?_limit=30", PlPage.serializer(PlTeam.serializer()), 24.hours).data
        val t = teams.firstOrNull { it.id == id } ?: teams.firstOrNull { it.abbr.equals(id, true) }
            ?: throw NotFoundException("${league.name}: team '$id' not found", league.id)
        return mapper.team(t)
    }

    /** `team=` narrows the season's matches to one club — 38 rows, one call (`teams=` is the parameter that is ignored). */
    override suspend fun teamSchedule(teamId: String, startDate: LocalDate, endDate: LocalDate): List<Game> {
        val page = get("/v2/matches?competition=$competitionId&season=${currentSeason()}&team=$teamId&_limit=100", PlPage.serializer(PlMatch.serializer()), SCHEDULE_MAX_AGE)
        return page.data.filter { it.kickoff != null }.map { mapper.game(it, withEvents = false) }
            .filter { it.startTime.toLocalDateTime(league.zone).date in startDate..endDate }.sortedBy { it.startTime }
    }

    override suspend fun roster(teamId: String): List<Player> {
        val squad = get("/v2/competitions/$competitionId/seasons/${currentSeason()}/teams/$teamId/squad", PlSquad.serializer(), STATIC_MAX_AGE)
        return squad.players.map { mapper.player(it, squad.team?.id ?: teamId) }
    }

    override suspend fun player(id: String): Player =
        mapper.player(get("/v1/competitions/$competitionId/seasons/${currentSeason()}/playerinfo/$id", PlSquadPlayer.serializer(), STATIC_MAX_AGE), null)

    private suspend fun <T> get(path: String, strategy: DeserializationStrategy<T>, maxAge: Duration): T =
        fetcher.getJson(baseUrl + path, strategy, maxAge, league.id)

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://sdp-prem-prod.premier-league-prod.pulselive.com/api"
        public const val PREMIER_LEAGUE: String = "8"
        public val LEAGUE: League = League("premier-league", Sport.FOOTBALL, "Premier League", "GB", TimeZone.of("Europe/London"), "https://www.premierleague.com")
        private val LIVE_MAX_AGE = 10.seconds
        private val LINEUP_MAX_AGE = 1.minutes
        private val STATS_MAX_AGE = 30.seconds
        private val TABLE_MAX_AGE = 1.minutes
        private val SCHEDULE_MAX_AGE = 2.minutes
        private val STATIC_MAX_AGE = 1.hours
    }
}
