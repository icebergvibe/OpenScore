package org.openscore.providers.seriea

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
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
import org.openscore.provider.NotFoundException
import org.openscore.provider.getJson
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Serie A via the Deltatre SDP API behind legaseriea.it — see apis/football/serie-a/README.md.
 *
 * Ids are the full `serie-a::Football_<Type>::<hex>` strings; `seasonId` likewise. Dates
 * are Italian local dates. The edge caches bodies for 40 s, so nothing is polled faster.
 * `roster()` is the platform's all-time list (not season-scoped); there is no
 * per-player endpoint.
 */
public class SerieAProvider(
    private val fetcher: Fetcher,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val competitionId: String = SERIE_A,
    override val league: League = LEAGUE,
) : BaseLeagueProvider() {

    private val mapper = SerieAMapper(league.id)

    override val capabilities: Set<Capability> = setOf(
        Capability.GAMES_BY_DATE,
        Capability.GAME,
        Capability.EVENTS,
        Capability.LINEUPS,
        Capability.STANDINGS,
        Capability.TEAM,
        Capability.ROSTER,
        Capability.TEAM_SCHEDULE,
        Capability.LIVE_UPDATES,
        Capability.CLOCK,
        Capability.CLOCK_RUNNING_FLAG,
        Capability.INTERMISSION_STATE,
        Capability.PERIOD_SCORES,
    )

    private suspend fun currentSeason(): String =
        get("/competitions/$competitionId/seasons", SaSeasons.serializer(), STATIC_MAX_AGE).seasons.firstOrNull()?.seasonId
            ?: throw NotFoundException("${league.name}: no seasons", league.id)

    override suspend fun gamesOn(date: LocalDate): List<Game> {
        val season = currentSeason()
        val matchdays = get("/seasons/$season/matchdays", SaMatchdays.serializer(), STATIC_MAX_AGE).matchdays
        val candidates = matchdays.filter { md ->
            val start = md.startDateUtc?.let(::localDate) ?: return@filter false
            val end = (md.endDateUtc?.let(::localDate) ?: start).plus(1, DateTimeUnit.DAY)
            date >= start && date <= end
        }
        return candidates.flatMap { md ->
            get("/seasons/$season/matches?matchDayId=${md.matchSetId}", SaMatches.serializer(), LIVE_MAX_AGE).matches
        }.filter { it.matchDateUtc?.let(::localDate) == date }
            .map { mapper.game(it, summary = null, stats = null) }
            .sortedBy { it.startTime }
    }

    /**
     * The season list ignores `teamId` and has no paging (380 rows, 126 KB gzipped, `max-age=60`),
     * so a club's fixtures are one cached download filtered here — cheaper than 38 matchday reads.
     */
    override suspend fun teamSchedule(teamId: String, startDate: LocalDate, endDate: LocalDate): List<Game> {
        val season = currentSeason()
        return get("/seasons/$season/matches", SaMatches.serializer(), SCHEDULE_MAX_AGE).matches
            .filter { it.home?.teamId == teamId || it.away?.teamId == teamId }
            .filter { m -> m.matchDateUtc?.let(::localDate)?.let { it in startDate..endDate } == true }
            .map { mapper.game(it, summary = null, stats = null) }
            .sortedBy { it.startTime }
    }

    override suspend fun game(id: String): Game {
        val season = currentSeason()
        val header = header(season, id)
        val summaryOnly = mapper.game(header, summary = null, stats = null)
        // Summary and team stats are empty before kick-off. Avoid two edge requests whose
        // only useful answer is already present in the small header.
        if (!summaryOnly.state.hasStarted) return summaryOnly.copy(events = emptyList())

        return coroutineScope {
            val summary = async { get("/seasons/$season/match/$id/summary", SaSummary.serializer(), LIVE_MAX_AGE) }
            val stats = async { get("/seasons/$season/match/$id/teamstats", SaTeamStats.serializer(), LIVE_MAX_AGE) }
            mapper.game(header, summary.await(), stats.await())
        }
    }

    override suspend fun events(gameId: String): List<GameEvent> = coroutineScope {
        val season = currentSeason()
        val header = async { header(season, gameId) }
        val summary = async { get("/seasons/$season/match/$gameId/summary", SaSummary.serializer(), LIVE_MAX_AGE) }
        val match = header.await()
        mapper.events(summary.await(), mapper.teamRef(match.home), mapper.teamRef(match.away))
    }

    override suspend fun lineups(gameId: String): List<Lineup> {
        val season = currentSeason()
        header(season, gameId)
        return mapper.lineups(gameId, get("/seasons/$season/matches/$gameId/lineups", SaLineups.serializer(), LIVE_MAX_AGE))
    }

    override suspend fun standings(seasonId: String?): StandingsTable {
        val season = seasonId ?: currentSeason()
        return mapper.standings(get("/seasons/$season/standings", SaStandings.serializer(), LIVE_MAX_AGE), season, league.name)
    }

    override suspend fun team(id: String): Team {
        val teams = get("/seasons/${currentSeason()}/standings", SaStandings.serializer(), STATIC_MAX_AGE).teams
        val t = teams.firstOrNull { it.teamId == id } ?: teams.firstOrNull { it.acronymName.equals(id, true) }
            ?: throw NotFoundException("${league.name}: team '$id' not found", league.id)
        return mapper.team(t)
    }

    /** Plain `roster` is the platform's all-time list (387 for Inter); `seasonId` cuts it to the season's registrations (45). */
    override suspend fun roster(teamId: String): List<Player> =
        get("/teams/$teamId/roster?seasonId=${currentSeason()}", SaRoster.serializer(), STATIC_MAX_AGE).players.map { mapper.rosterPlayer(it, teamId) }

    private suspend fun header(season: String, id: String): SaMatch {
        val h = get("/seasons/$season/matches/$id/header", SaMatch.serializer(), LIVE_MAX_AGE)
        // Unknown ids come back 200 with every field null.
        if (h.matchId == null) throw NotFoundException("${league.name}: match '$id' not found", league.id)
        return h
    }

    /** `matchDateUtc` is an instant, or a bare `YYYY-MM-DDZ` while the kick-off time is unknown. */
    private fun localDate(iso: String): LocalDate =
        if (iso.contains('T')) Instant.parse(iso).toLocalDateTime(ROME).date else LocalDate.parse(iso.removeSuffix("Z"))

    private suspend fun <T> get(path: String, strategy: DeserializationStrategy<T>, maxAge: Duration): T =
        fetcher.getJson(baseUrl + path, strategy, maxAge, league.id)

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api-sdp.legaseriea.it/v1/serie-a/football"
        public const val SERIE_A: String = "serie-a::Football_Competition::ec93b94f74294dc98ab5bcfd67fc0d88"
        public val LEAGUE: League = League("serie-a", Sport.FOOTBALL, "Serie A", "IT", "https://www.legaseriea.it")
        private val ROME = TimeZone.of("Europe/Rome")
        private val LIVE_MAX_AGE = 40.seconds
        private val SCHEDULE_MAX_AGE = 10.minutes
        private val STATIC_MAX_AGE = 1.hours
    }
}
