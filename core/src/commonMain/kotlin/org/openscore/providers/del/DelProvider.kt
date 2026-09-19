package org.openscore.providers.del

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import org.openscore.model.Game
import org.openscore.model.GameEvent
import org.openscore.model.League
import org.openscore.model.Lineup
import org.openscore.model.Player
import org.openscore.model.Sport
import org.openscore.model.StandingsTable
import org.openscore.model.Team
import org.openscore.net.Fetcher
import org.openscore.net.OpenScoreJson
import org.openscore.provider.BaseLeagueProvider
import org.openscore.provider.Capability
import org.openscore.provider.NotFoundException
import org.openscore.provider.ProviderException
import org.openscore.provider.decodeJson
import org.openscore.provider.runCatchingUnlessCancelled
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * DEL (Germany) via the official app's backend on appticore.com - see apis/hockey/del/README.md.
 *
 * Ids: games are the feed's `uniqueID` (`4389t77` = game 4389 of tournament 77; every per-game
 * read needs both halves), teams the three-letter `noc` codes (`EBB`), players `memberID`s,
 * `seasonId` a tournament id (odd = regular season, the following even = its playoffs). Dates
 * are German local dates, converted to the UTC window the feed filters on.
 *
 * One URL serves everything; `requestName` selects the dataset. Nothing is edge-cached and
 * there are no validators, so every cadence here is the fetcher's own memory cache. The live
 * codes (`Period 1`, `Period 1 Ended`, `Overtime` …) come from the app's string table and have
 * not been seen in a sample yet; `CLOCK` is not claimed until the elapsed seconds the feed
 * carries are seen to move between events.
 */
public class DelProvider(
    private val fetcher: Fetcher,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : BaseLeagueProvider() {

    override val league: League = LEAGUE

    override val capabilities: Set<Capability> = setOf(
        Capability.GAMES_BY_DATE,
        Capability.GAME,
        Capability.EVENTS,
        Capability.LINEUPS,
        Capability.STANDINGS,
        Capability.TEAM,
        Capability.TEAM_SCHEDULE,
        Capability.ROSTER,
        Capability.PLAYER,
        Capability.LIVE_UPDATES,
        Capability.INTERMISSION_STATE,
        Capability.PERIOD_SCORES,
        Capability.EVENT_COORDINATES,
        Capability.LINE_GROUPS,
    )

    /** The games of a German calendar day, across the regular season and the playoffs. */
    override suspend fun gamesOn(date: LocalDate): List<Game> = gamesBetween(date, date)

    override suspend fun game(id: String): Game {
        val (number, tournament) = DelMapper.parseGameId(id)
        return coroutineScope {
            val row = async { gameRow(number, tournament) }
            val situations = async { situations(number, tournament, extended = false) }
            val names = async { teamNames(tournament) }
            val g = row.await()
            val roster = async { roster(tournament, g.homeTeam, g.guestTeam) }
            val results = async { if (g.progressPerc > 0) query("gameResults", gameParams(number, tournament), ListSerializer(DelPeriodResult.serializer()), STATS_MAX_AGE) else emptyList() }
            DelMapper.game(g, names.await(), events = DelMapper.events(g, situations.await(), roster.await(), names.await()), stats = DelMapper.stats(results.await()))
        }
    }

    /** Every situation row, shots with coordinates included (`gameSituationsExtended`, ~8× the plain list). */
    override suspend fun events(gameId: String): List<GameEvent> {
        val (number, tournament) = DelMapper.parseGameId(gameId)
        return coroutineScope {
            val rows = async { situations(number, tournament, extended = true) }
            val g = gameRow(number, tournament)
            val roster = roster(tournament, g.homeTeam, g.guestTeam)
            DelMapper.events(g, rows.await(), roster, teamNames(tournament))
        }
    }

    override suspend fun lineups(gameId: String): List<Lineup> {
        val (number, tournament) = DelMapper.parseGameId(gameId)
        return coroutineScope {
            val slots = async { query("gameLineup", gameParams(number, tournament), ListSerializer(DelLineupSlot.serializer()), LINEUP_MAX_AGE) }
            val g = gameRow(number, tournament)
            val roster = roster(tournament, g.homeTeam, g.guestTeam)
            DelMapper.lineups(g, slots.await(), roster, teamNames(tournament))
        }
    }

    /** The regular-season table; a playoff tournament has none, so the season's odd id is used. */
    override suspend fun standings(seasonId: String?): StandingsTable {
        val tournament = seasonId?.toIntOrNull()?.let { if (it % 2 == 0) it - 1 else it } ?: currentSeason()
        val rows = query("teamStandings", listOf("tournamentId" to tournament.toString()), ListSerializer(DelStandingsRow.serializer()), TABLE_MAX_AGE)
        if (rows.isEmpty()) throw NotFoundException("DEL: no table for tournament $tournament", league.id)
        return DelMapper.standings(rows, teamNames(tournament), tournament.toString())
    }

    override suspend fun team(id: String): Team {
        val noc = id.uppercase()
        val t = teams(currentSeason()).firstOrNull { it.noc == noc }
            ?: throw NotFoundException("DEL team '$id' not in teamList", league.id)
        return DelMapper.team(t)
    }

    override suspend fun teamSchedule(teamId: String, startDate: LocalDate, endDate: LocalDate): List<Game> {
        val noc = teamId.uppercase()
        return gamesBetween(startDate, endDate).filter { it.home.id == noc || it.away.id == noc }
    }

    override suspend fun roster(teamId: String): List<Player> =
        members(currentSeason(), teamId.uppercase()).filterNot(DelMapper::isCoach).map(DelMapper::player)

    /** `teamMembers` with `memberId` answers the player (and the head coach's row) without a team. */
    override suspend fun player(id: String): Player {
        val wanted = id.toLongOrNull() ?: throw NotFoundException("DEL player id must be numeric, got '$id'", league.id)
        val rows = query("teamMembers", listOf("tournamentId" to currentSeason().toString(), "memberId" to id), ListSerializer(DelMember.serializer()), ROSTER_MAX_AGE)
        val m = rows.firstOrNull { it.memberID == wanted } ?: throw NotFoundException("DEL player $id not found", league.id)
        return DelMapper.player(m)
    }

    // ---- reads ---------------------------------------------------------------------------------

    /** One `games` window in UTC covering the German days [start]..[end]; never-needed series games are dropped. */
    private suspend fun gamesBetween(start: LocalDate, end: LocalDate): List<Game> {
        val from = start.atStartOfDayIn(BERLIN)
        val to = end.plus(1, DateTimeUnit.DAY).atStartOfDayIn(BERLIN) - 1.seconds
        val rows = query("games", listOf("dateFrom" to utcStamp(from), "dateTo" to utcStamp(to)), ListSerializer(DelGame.serializer().nullable), LIVE_MAX_AGE)
            .filterNotNull()
            .filter { it.deleted == 0 && !DelMapper.isUnplayed(it) }
        val names = rows.map { it.tournamentID }.distinct().associateWith { teamNames(it) }
        return rows.map { DelMapper.game(it, names[it.tournamentID].orEmpty()) }.sortedBy { it.startTime }
    }

    private suspend fun gameRow(number: Int, tournament: Int): DelGame =
        query("games", gameParams(number, tournament), ListSerializer(DelGame.serializer().nullable), LIVE_MAX_AGE).firstOrNull()
            ?: throw NotFoundException("DEL game ${DelMapper.gameId(number, tournament)} not found", league.id)

    private suspend fun situations(number: Int, tournament: Int, extended: Boolean): List<DelSituation> =
        query(if (extended) "gameSituationsExtended" else "gameSituations", gameParams(number, tournament), ListSerializer(DelSituation.serializer()), LIVE_MAX_AGE)

    /** Both rosters as a name index; a roster that fails to load leaves the feed's own names in place. */
    private suspend fun roster(tournament: Int, vararg nocs: String): DelMapper.Roster = coroutineScope {
        val sides = nocs.map { noc -> async { runCatchingUnlessCancelled { members(tournament, noc) }.getOrElse { emptyList() } } }
        DelMapper.Roster(sides.flatMap { it.await() })
    }

    private suspend fun members(tournament: Int, noc: String): List<DelMember> =
        query("teamMembers", listOf("tournamentId" to tournament.toString(), "noc" to noc), ListSerializer(DelMember.serializer()), ROSTER_MAX_AGE)

    private suspend fun teams(tournament: Int): List<DelTeam> =
        query("teamList", listOf("tournamentId" to tournament.toString()), ListSerializer(DelTeam.serializer()), STATIC_MAX_AGE)

    private suspend fun teamNames(tournament: Int): Map<String, String> =
        runCatchingUnlessCancelled { teams(tournament) }.getOrElse { emptyList() }.associate { it.noc to it.name }

    /** The regular season the path's `tournamentList` shows (category 11; the highest id if several). */
    public suspend fun currentSeason(): Int {
        val list = query("tournamentList", emptyList(), ListSerializer(DelTournament.serializer()), STATIC_MAX_AGE)
        return (list.filter { it.category == 11 }.ifEmpty { list }).maxOfOrNull { it.tournamentID }
            ?: throw ProviderException("DEL: tournamentList is empty on $baseUrl", leagueId = league.id)
    }

    private fun gameParams(number: Int, tournament: Int): List<Pair<String, String>> =
        listOf("tournamentId" to tournament.toString(), "gameNumber" to number.toString())

    /**
     * `GET query.php?os=android&lastUpdate=0&requestName=…`. Errors come back `200` with an
     * `{"error": …}` object, and a missing game as `[null]`, which the caller's serializer allows.
     */
    private suspend fun <T> query(name: String, params: List<Pair<String, String>>, strategy: DeserializationStrategy<T>, maxAge: Duration): T {
        val url = buildString {
            append(baseUrl).append("?os=android&lastUpdate=0&requestName=").append(name)
            // Only the timestamps carry a space; ids and codes are plain.
            for ((k, v) in params) append('&').append(k).append('=').append(v.replace(" ", "%20"))
        }
        val response = fetcher.get(url, maxAge = maxAge)
        if (response.status == 404) throw NotFoundException("DEL: $url → 404", league.id)
        response.requireSuccess()
        val body = response.body.trimStart()
        if (body.startsWith("{") && body.contains("\"error\"")) {
            val err = runCatching { OpenScoreJson.decodeFromString(DelErrorResponse.serializer(), body).error }.getOrNull()
            if (err != null) throw ProviderException("DEL: $name → ${err.message ?: "error"}", leagueId = league.id)
        }
        return decodeJson(response, strategy, league.id)
    }

    /** `2026-09-17 22:00:00`: the feed compares `dateFrom`/`dateTo` in UTC and needs the full form. */
    private fun utcStamp(instant: Instant): String {
        val t = instant.toLocalDateTime(TimeZone.UTC)
        fun two(n: Int) = n.toString().padStart(2, '0')
        return "${t.date} ${two(t.hour)}:${two(t.minute)}:${two(t.second)}"
    }

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://del-services.appticore.com/2026/query.php"

        public val LEAGUE: League = League(
            id = "del",
            sport = Sport.HOCKEY,
            name = "DEL",
            country = "DE",
            websiteUrl = "https://www.penny-del.org",
        )

        private val BERLIN = TimeZone.of("Europe/Berlin")
        private val LIVE_MAX_AGE = 10.seconds
        private val STATS_MAX_AGE = 30.seconds
        private val LINEUP_MAX_AGE = 5.minutes
        private val TABLE_MAX_AGE = 5.minutes
        private val ROSTER_MAX_AGE = 1.hours
        private val STATIC_MAX_AGE = 24.hours
    }
}
