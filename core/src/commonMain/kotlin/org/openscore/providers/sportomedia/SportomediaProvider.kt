package org.openscore.providers.sportomedia

import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlinx.serialization.KSerializer
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
import org.openscore.provider.ProviderException
import org.openscore.provider.getJson
import org.openscore.provider.pollGame
import org.openscore.provider.runCatchingUnlessCancelled
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Leagues on Sportomedia's GraphQL API (`gql.sportomedia.se`): Allsvenskan, Superettan,
 * Damallsvenskan — see apis/football/allsvenskan/README.md. Queries go as `GET ?query=…`
 * so the read-only [Fetcher] and its cache apply unchanged.
 *
 * Ids: matches are Fogis match ids (optionally `year:id` for past seasons), teams the
 * `abbrv`, players Fogis player ids. `seasonId` is the calendar year. Dates are Swedish
 * local dates. SSE subscriptions exist but are not wired yet; `live()` polls at ≥ 20 s.
 */
public open class SportomediaProvider(
    override val league: League,
    private val fetcher: Fetcher,
    private val configLeagueName: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val clock: Clock = Clock.System,
) : BaseLeagueProvider() {

    private val mapper = SportomediaMapper(league.id)

    override val capabilities: Set<Capability> = setOf(
        Capability.GAMES_BY_DATE,
        Capability.GAME,
        Capability.EVENTS,
        Capability.LINEUPS,
        Capability.STANDINGS,
        Capability.TEAM,
        Capability.ROSTER,
        Capability.PLAYER,
        Capability.LIVE_UPDATES,
        Capability.CLOCK,
        Capability.CLOCK_RUNNING_FLAG,
        Capability.INTERMISSION_STATE,
        Capability.PERIOD_SCORES,
    )

    public fun currentSeason(): Int = clock.todayIn(SWEDEN).year

    override suspend fun gamesOn(date: LocalDate): List<Game> {
        val q = Queries.matchesForLeague(configLeagueName, date.year, date, date.plus(1, DateTimeUnit.DAY))
        val data = query(q, SmMatchesForLeagueData.serializer(), LIVE_MAX_AGE)
        return data?.matchesForLeague?.matches.orEmpty()
            .filter { Instant.parse(it.startDate).toLocalDateTime(SWEDEN).date == date }
            .map { mapper.game(it, withEvents = false) }.sortedBy { it.startTime }
    }

    override suspend fun game(id: String): Game = coroutineScope {
        val (year, matchId) = parse(id)
        val match = async { match(year, matchId) }
        val lineups = async { query(Queries.lineups(matchId, configLeagueName, year), SmLineupsData.serializer(), LINEUP_MAX_AGE)?.lineups }
        val stats = async { runCatchingUnlessCancelled { query(Queries.matchStats(matchId, configLeagueName, year), SmMatchStatsData.serializer(), STATS_MAX_AGE)?.matchStats }.getOrNull() }
        mapper.game(match.await(), withEvents = true, stats = stats.await(), players = mapper.playerIndex(lineups.await()))
    }

    override suspend fun events(gameId: String): List<GameEvent> = coroutineScope {
        val (year, matchId) = parse(gameId)
        val match = async { match(year, matchId) }
        val lineups = async { query(Queries.lineups(matchId, configLeagueName, year), SmLineupsData.serializer(), LINEUP_MAX_AGE)?.lineups }
        val m = match.await()
        mapper.events(m, mapper.homeRef(m), mapper.awayRef(m), mapper.playerIndex(lineups.await()))
    }

    override suspend fun lineups(gameId: String): List<Lineup> = coroutineScope {
        val (year, matchId) = parse(gameId)
        val match = async { match(year, matchId) }
        val lineups = async { query(Queries.lineups(matchId, configLeagueName, year), SmLineupsData.serializer(), LINEUP_MAX_AGE)?.lineups }
        val m = match.await()
        val l = lineups.await() ?: return@coroutineScope emptyList()
        mapper.lineups(gameId, l, mapper.homeRef(m), mapper.awayRef(m))
    }

    override fun live(gameId: String, interval: Duration): Flow<Game> =
        pollGame(this, gameId, maxOf(interval, LIVE_POLL_FLOOR))

    override suspend fun standings(seasonId: String?): StandingsTable = coroutineScope {
        val year = seasonId?.toIntOrNull() ?: currentSeason()
        // The `total` table carries no crests (only the home/away tables do); the season's team list has them.
        val logos = async { runCatchingUnlessCancelled { teams(year).associate { it.abbrv to it.logoImageUrl } }.getOrDefault(emptyMap()) }
        val data = query(Queries.standings(configLeagueName, year), SmStandingsData.serializer(), TABLE_MAX_AGE)?.standingsForLeague
            ?: throw NotFoundException("${league.name}: no standings for $year", league.id)
        mapper.standings(data, year.toString(), league.name, logos.await())
    }

    /**
     * The season's teams: abbreviation, names, crest and the `fogisId` that links a team to the
     * Fogis feed. One small query, cached for the day.
     */
    public suspend fun teams(year: Int = currentSeason()): List<SmTeam> =
        query(Queries.teamsForLeague(configLeagueName, year), SmTeamsData.serializer(), DIRECTORY_MAX_AGE)?.teamsForLeague?.teams.orEmpty()

    /** The abbreviation for [id], which may already be one or may be a Fogis team id (what the Swedish league wrappers carry). */
    public suspend fun abbreviation(id: String): String {
        val fogisId = id.toLongOrNull() ?: return id
        return teams().firstOrNull { it.fogisId == fogisId }?.abbrv
            ?: throw NotFoundException("${league.name}: no team with Fogis id $id this season", league.id)
    }

    override suspend fun team(id: String): Team {
        val t = query(Queries.team(abbreviation(id)), SmTeamData.serializer(), STATIC_MAX_AGE)?.team
            ?: throw NotFoundException("${league.name}: team '$id' not found", league.id)
        return mapper.team(t)
    }

    override suspend fun roster(teamId: String): List<Player> {
        val abbrv = abbreviation(teamId)
        val s = query(Queries.squad(abbrv, currentSeason()), SmSquadData.serializer(), STATIC_MAX_AGE)?.squad ?: return emptyList()
        return (s.goalkeepers + s.defenders + s.midfields + s.forwards).map { mapper.player(it, abbrv) }
    }

    override suspend fun player(id: String): Player {
        val p = query(Queries.player(id), SmPlayerData.serializer(), STATIC_MAX_AGE)?.player
            ?: throw NotFoundException("${league.name}: player '$id' not found", league.id)
        // The profile carries no id; keep the one we were asked for so the ref round-trips.
        return mapper.player(p.copy(id = id.toLongOrNull()), null)
    }

    private suspend fun match(year: Int, matchId: String): SmMatch =
        query(Queries.match(matchId, configLeagueName, year), SmMatchData.serializer(), LIVE_MAX_AGE)?.match?.match
            ?: throw NotFoundException("${league.name}: match '$matchId' not found in $year", league.id)

    /** `id` or `year:id`. */
    private fun parse(gameId: String): Pair<Int, String> {
        val parts = gameId.split(':')
        return if (parts.size == 2) (parts[0].toIntOrNull() ?: currentSeason()) to parts[1] else currentSeason() to gameId
    }

    /** GET with the query in the URL; GraphQL errors arrive as 200 with `errors[]`. */
    private suspend fun <T> query(q: String, strategy: KSerializer<T>, maxAge: Duration): T? {
        val url = "$baseUrl?query=${q.encodeURLParameter()}"
        val response = fetcher.getJson(url, GqlResponse.serializer(strategy), maxAge, league.id)
        if (response.data == null && response.errors.isNotEmpty()) {
            val msg = response.errors.joinToString { it.message ?: "?" }
            if (msg.contains("Unexpected error")) return null
            throw ProviderException("${league.name}: GraphQL error: $msg", leagueId = league.id)
        }
        return response.data
    }

    /** The field selections allsvenskan.se sends (see the README); kept compact so URLs stay short. */
    public object Queries {
        private const val MATCH_FIELDS = "id startDate homeTeamName visitingTeamName homeTeamScore visitingTeamScore status extendedStatus period round configLeagueName homeTeamAbbrv visitingTeamAbbrv leagueName matchMinute arenaName"
        private const val EVENT_FIELDS = "type typeString gameTime period description teamName homeTeamScore homeTeamPeriodScore visitingTeamScore visitingTeamPeriodScore byHomeTeam source key minuteWithStoppageTime playerName inPlayerName outPlayerName assistPlayerName assistPlayerId"
        private const val LINEUP_PLAYER = "id displayName givenName surName shirtNumber image position positionText"
        private const val STATS_FIELDS = "homeTeamPossesion visitingTeamPossesion homeTeamShots visitingTeamShots homeTeamShotsOnTarget visitingTeamShotsOnTarget homeTeamCorners visitingTeamCorners homeTeamOffsides visitingTeamOffsides homeTeamYellowCards visitingTeamYellowCards homeTeamRedCards visitingTeamRedCards homeTeamDistance visitingTeamDistance"
        private const val PLAYER_FIELDS = "id fogisId givenName surName displayName shirtNumber position nationality birthDate height weight image isGoalkeeper teamAbbrv"

        public fun matchesForLeague(league: String, year: Int, start: LocalDate, endExclusive: LocalDate): String =
            "{matchesForLeague(configLeagueName:\"$league\",configSeasonStartYear:$year,startDate:\"$start\",endDate:\"$endExclusive\"){matches{$MATCH_FIELDS}}}"

        public fun match(id: String, league: String, year: Int): String =
            "{match(id:$id,configLeagueName:\"$league\",configSeasonStartYear:$year){match{$MATCH_FIELDS matchMinuteWithStoppageTime homeTeamLogo visitingTeamLogo spectators referees matchEvents{$EVENT_FIELDS}}}}"

        public fun lineups(id: String, league: String, year: Int): String =
            "{lineups(id:$id,configLeagueName:\"$league\",configSeasonStartYear:$year){homeTeam{abbrv formation starting{$LINEUP_PLAYER} substitutes{$LINEUP_PLAYER}} visitingTeam{abbrv formation starting{$LINEUP_PLAYER} substitutes{$LINEUP_PLAYER}}}}"

        public fun matchStats(id: String, league: String, year: Int): String =
            "{matchStats(id:$id,configLeagueName:\"$league\",configSeasonStartYear:$year){id totalStats{$STATS_FIELDS}}}"

        public fun standings(league: String, year: Int): String =
            "{standingsForLeague(configLeagueName:\"$league\",configSeasonStartYear:$year,type:\"total\"){type standings{teamAbbrv teamName position previousPosition teamId logoImageUrl borderType stats{name value} form{matchResult}}}}"

        public fun team(abbrv: String): String = "{team(abbrv:\"$abbrv\"){abbrv name displayName fogisId logoImageUrl arena{name spectators}}}"

        public fun teamsForLeague(league: String, year: Int): String =
            "{teamsForLeague(configLeagueName:\"$league\",configSeasonStartYear:$year){teams{abbrv name displayName fogisId logoImageUrl}}}"

        public fun squad(abbrv: String, year: Int): String =
            "{squad(abbrv:\"$abbrv\",configSeasonStartYear:$year){goalkeepers{$PLAYER_FIELDS} defenders{$PLAYER_FIELDS} midfields{$PLAYER_FIELDS} forwards{$PLAYER_FIELDS}}}"

        /** The `Player` type behind `player(id)` has no `id`/`fogisId`/`teamAbbrv` (the API rejects them). */
        private const val PROFILE_FIELDS = "givenName surName displayName shirtNumber position nationality birthDate height weight image isGoalkeeper"

        public fun player(id: String): String = "{player(id:$id){$PROFILE_FIELDS}}"
    }

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://gql.sportomedia.se/graphql"
        private val SWEDEN = TimeZone.of("Europe/Stockholm")
        private val LIVE_MAX_AGE = 20.seconds
        private val LIVE_POLL_FLOOR = 20.seconds
        private val LINEUP_MAX_AGE = 1.minutes
        private val STATS_MAX_AGE = 1.minutes
        private val TABLE_MAX_AGE = 2.minutes
        private val STATIC_MAX_AGE = 1.hours
        private val DIRECTORY_MAX_AGE = 24.hours
    }
}

/** Allsvenskan (Sweden, tier 1). */
public class AllsvenskanProvider(fetcher: Fetcher, baseUrl: String = SportomediaProvider.DEFAULT_BASE_URL, clock: Clock = Clock.System) :
    SportomediaProvider(LEAGUE, fetcher, "allsvenskan", baseUrl, clock) {
    public companion object {
        public val LEAGUE: League = League("allsvenskan", Sport.FOOTBALL, "Allsvenskan", "SE", "https://allsvenskan.se")
    }
}

/** Superettan (Sweden, tier 2) — same API, verified 2026-09-12. */
public class SuperettanProvider(fetcher: Fetcher, baseUrl: String = SportomediaProvider.DEFAULT_BASE_URL, clock: Clock = Clock.System) :
    SportomediaProvider(LEAGUE, fetcher, "superettan", baseUrl, clock) {
    public companion object {
        public val LEAGUE: League = League("superettan", Sport.FOOTBALL, "Superettan", "SE", "https://allsvenskan.se")
    }
}
