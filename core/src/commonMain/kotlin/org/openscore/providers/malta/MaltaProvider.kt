package org.openscore.providers.malta

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
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
import org.openscore.provider.runCatchingUnlessCancelled
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Malta Premier via the MFA match centre API (COMET) — see apis/football/malta-premier/README.md.
 *
 * Ids are COMET ids (match `53142564`, team `39639`, player `162467`); `seasonId` is the
 * season's **end year**. Dates are Maltese local dates. Match lists carry no scores, so
 * [gamesOn] fetches `result` for every started match. No positions, formations or match
 * stats exist in this feed; events have minute labels only.
 */
public class MaltaProvider(
    private val fetcher: Fetcher,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val competitionTypeId: Int = MALTA_PREMIER,
    override val league: League = LEAGUE,
    private val clock: Clock = Clock.System,
) : BaseLeagueProvider() {

    private val mapper = MaltaMapper(league.id)

    override val capabilities: Set<Capability> = setOf(
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
    )

    override suspend fun gamesOn(date: LocalDate): List<Game> {
        // `pastMatches` answers 400 "Can't accept a future date" and `upcomingMatches` the
        // reverse (checked 2026-09-13), so only today needs both. The two are independent, and
        // this host is the slowest of the football set.
        val today = clock.now().toLocalDateTime(league.zone).date
        val (past, upcoming) = coroutineScope {
            val past = async { if (date > today) emptyList() else get("/competitions/$competitionTypeId/pastMatches?date=$date&pageSize=50", ListSerializer(MtMatch.serializer()), LIVE_MAX_AGE) }
            val upcoming = async { if (date < today) emptyList() else get("/competitions/$competitionTypeId/upcomingMatches?date=$date&pageSize=50", ListSerializer(MtMatch.serializer()), LIVE_MAX_AGE) }
            past.await() to upcoming.await()
        }
        val matches = (past + upcoming).distinctBy { it.id }.filter { localDate(it) == date }
        return withResults(matches, tolerateFailures = false)
    }

    /**
     * `pastMatches` takes `teamId`; `upcomingMatches` answers 400 to it (checked 2026-09-16) but
     * only ever lists the next round (six games), so it is read league-wide and filtered.
     * Neither carries a score, so each played match costs its `result` once (kept for a day,
     * as on the day view). Bounded by a season's fixtures.
     */
    override suspend fun teamSchedule(teamId: String, startDate: LocalDate, endDate: LocalDate): List<Game> {
        val (past, upcoming) = coroutineScope {
            val past = async { get("/competitions/$competitionTypeId/pastMatches?teamId=$teamId&pageSize=60", ListSerializer(MtMatch.serializer()), SCHEDULE_MAX_AGE) }
            val upcoming = async { get("/competitions/$competitionTypeId/upcomingMatches?pageSize=60", ListSerializer(MtMatch.serializer()), SCHEDULE_MAX_AGE) }
            past.await() to upcoming.await().filter { it.homeTeam.id.toString() == teamId || it.awayTeam.id.toString() == teamId }
        }
        val matches = (past + upcoming).distinctBy { it.id }.filter { localDate(it) in startDate..endDate }
        // One missing report must not lose a club's whole season, unlike the day view's handful.
        return withResults(matches, tolerateFailures = true)
    }

    /**
     * Each started match with its `result` (the listing carries no score). A final report never
     * changes during normal browsing, so it is kept for a day rather than revalidated and decoded
     * on every refresh; live reports stay at the 15 s cadence.
     */
    private suspend fun withResults(matches: List<MtMatch>, tolerateFailures: Boolean): List<Game> = coroutineScope {
        matches.map { m ->
            async {
                val result = when {
                    m.status == "SCHEDULED" && !m.isLive -> null
                    tolerateFailures -> runCatchingUnlessCancelled { result(m) }.getOrNull()
                    else -> result(m)
                }
                mapper.game(m, result, withEvents = false)
            }
        }.awaitAll().sortedBy { it.startTime }
    }

    private suspend fun result(m: MtMatch): MtResult = get("/matches/${m.id}/result", MtResult.serializer(), resultMaxAge(m))

    private fun localDate(m: MtMatch): LocalDate = Instant.parse(m.startDate).toLocalDateTime(league.zone).date

    override suspend fun game(id: String): Game {
        val match = get("/matches/$id", MtMatch.serializer(), LIVE_MAX_AGE)
        val result = get("/matches/$id/result", MtResult.serializer(), resultMaxAge(match))
        return mapper.game(match, result, withEvents = true)
    }

    override suspend fun events(gameId: String): List<GameEvent> = game(gameId).events.orEmpty()

    override suspend fun lineups(gameId: String): List<Lineup> = coroutineScope {
        val match = async { get("/matches/$gameId", MtMatch.serializer(), LIVE_MAX_AGE) }
        val lineups = async { get("/matches/$gameId/lineup", MtLineup.serializer(), LINEUP_MAX_AGE) }
        val m = match.await()
        mapper.lineups(gameId, lineups.await(), mapper.teamRef(m.homeTeam), mapper.teamRef(m.awayTeam))
    }

    override suspend fun standings(seasonId: String?): StandingsTable {
        val query = seasonId?.let { "?season=$it" } ?: ""
        return mapper.standings(get("/competitions/$competitionTypeId/standings$query", MtStandings.serializer(), TABLE_MAX_AGE), seasonId)
    }

    override suspend fun team(id: String): Team {
        val teams = get("/competitions/$competitionTypeId/teams", ListSerializer(MtTeams.serializer()), STATIC_MAX_AGE).flatMap { it.data }
        val t = teams.firstOrNull { it.id.toString() == id } ?: throw NotFoundException("${league.name}: team '$id' not found", league.id)
        return mapper.team(t)
    }

    override suspend fun player(id: String): Player =
        mapper.player(get("/players/$id/GetPlayerDetails", MtPlayer.serializer(), STATIC_MAX_AGE))

    private suspend fun <T> get(path: String, strategy: DeserializationStrategy<T>, maxAge: Duration): T =
        fetcher.getJson(baseUrl + path, strategy, maxAge, league.id, headers = mapOf("x-version" to "2"))

    private fun resultMaxAge(match: MtMatch): Duration =
        if (match.status == "PLAYED" && !match.isLive) FINAL_RESULT_MAX_AGE else LIVE_MAX_AGE

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api.mfa.com.mt/api"
        public const val MALTA_PREMIER: Int = 58539
        public val LEAGUE: League = League("malta-premier", Sport.FOOTBALL, "Malta Premier", "MT", TimeZone.of("Europe/Malta"), "https://matchcentre.mfa.com.mt")
        private val LIVE_MAX_AGE = 15.seconds
        private val SCHEDULE_MAX_AGE = 2.minutes
        private val FINAL_RESULT_MAX_AGE = 24.hours
        private val LINEUP_MAX_AGE = 5.minutes
        private val TABLE_MAX_AGE = 2.minutes
        private val STATIC_MAX_AGE = 1.hours
    }
}
