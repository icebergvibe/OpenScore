package org.openscore.providers.ligue1

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.DeserializationStrategy
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
import org.openscore.provider.getJson
import org.openscore.provider.pollGame
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Ligue 1 via `ma-api.ligue1.fr` (MPG) — see apis/football/ligue-1/README.md. The same
 * API serves Ligue 2 (championship 4) and the cups; pass another [championshipId] and
 * [league] to expose them.
 *
 * Ids: match ids `l1_championship_match_<n>`, club ids `l1_championship_club_<season>_<n>`
 * (they change every season), player ids likewise. `seasonId` is the start year. Dates
 * are French local dates. The match resource is 150–500 KB with no lighter live
 * endpoint, so [live] polls no faster than every 20 s.
 */
public class Ligue1Provider(
    private val fetcher: Fetcher,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val championshipId: Int = LIGUE_1,
    override val league: League = LEAGUE,
    private val clock: Clock = Clock.System,
) : BaseLeagueProvider() {

    private val mapper = Ligue1Mapper(league.id)

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

    override suspend fun gamesOn(date: LocalDate): List<Game> {
        val cal = get(
            "/championships-daily-calendars/matches?timezone=$TIMEZONE&daysLimit=1&lookAfter=true&fromDate=$date",
            L1DailyCalendars.serializer(), LIVE_MAX_AGE,
        )
        val ids = cal.results.byDate[date.toString()]?.get(championshipId.toString())?.values?.flatMap { it.matchesIds }.orEmpty()
        return ids.mapNotNull { cal.results.matches[it] }.map(mapper::game).sortedBy { it.startTime }
    }

    override suspend fun game(id: String): Game = mapper.game(match(id), clock.now())

    override suspend fun events(gameId: String): List<GameEvent> = mapper.events(match(gameId))

    override suspend fun lineups(gameId: String): List<Lineup> = mapper.lineups(match(gameId))

    override fun live(gameId: String, interval: Duration): Flow<Game> =
        pollGame(this, gameId, maxOf(interval, LIVE_POLL_FLOOR))

    override suspend fun standings(seasonId: String?): StandingsTable {
        val query = seasonId?.let { "?season=$it" } ?: ""
        return mapper.standings(get("/championship-standings/$championshipId/general$query", L1Standings.serializer(), TABLE_MAX_AGE), league.name)
    }

    override suspend fun team(id: String): Team {
        val identity = get("/championship-club/$id/identity", L1ClubIdentity.serializer(), STATIC_MAX_AGE)
        return mapper.team(identity, null)
    }

    override suspend fun roster(teamId: String): List<Player> =
        mapper.roster(get("/championship-club-summary/$teamId", L1ClubSummary.serializer(), STATIC_MAX_AGE), teamId)

    /** The club-page pack carries the season's fixtures (this championship only) as calendar summaries. */
    override suspend fun teamSchedule(teamId: String, startDate: LocalDate, endDate: LocalDate): List<Game> =
        get("/championship-club-summary/$teamId", L1ClubSummary.serializer(), SCHEDULE_MAX_AGE).matches.values
            .filter { it.championshipId == championshipId }
            .map(mapper::game)
            .filter { it.startTime.toLocalDateTime(PARIS).date in startDate..endDate }
            .sortedBy { it.startTime }

    override suspend fun player(id: String): Player =
        mapper.player(get("/championship-player/$id", L1Player.serializer(), STATIC_MAX_AGE), championshipId)

    private suspend fun match(id: String): L1Match = get("/championship-match/$id", L1Match.serializer(), LIVE_MAX_AGE)

    private suspend fun <T> get(path: String, strategy: DeserializationStrategy<T>, maxAge: Duration): T =
        fetcher.getJson(baseUrl + path, strategy, maxAge, league.id)

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://ma-api.ligue1.fr"
        public const val LIGUE_1: Int = 1
        public const val LIGUE_2: Int = 4
        public const val TIMEZONE: String = "Europe/Paris"
        private val PARIS = TimeZone.of(TIMEZONE)
        public val LEAGUE: League = League("ligue1", Sport.FOOTBALL, "Ligue 1", "FR", "https://ligue1.com")

        private val LIVE_MAX_AGE = 20.seconds
        private val LIVE_POLL_FLOOR = 20.seconds
        private val TABLE_MAX_AGE = 2.minutes
        private val STATIC_MAX_AGE = 1.hours
        /** The club pack's fixture list changes with every result the club plays. */
        private val SCHEDULE_MAX_AGE = 2.minutes
    }
}
