package org.openscore.providers.sportality

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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
import org.openscore.provider.getJsonOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Leagues on the Sportality/Statnet platform (shl.se, hockeyallsvenskan.se) — see
 * apis/hockey/shl/README.md. Subclasses only supply the host and series codes.
 *
 * Ids: games, teams and players are the platform's opaque UUIDs. Player ids in **events
 * and lineups** are Statnet numeric ids (that is what the game-day feeds carry), while
 * rosters and `player()` use athlete UUIDs — the platform does not expose a cheap bridge.
 * `seasonId` is the platform's `ssgtUuid` (season + series + game type). Dates are
 * Swedish local dates. Live push (SSE) exists but its payloads are not captured yet. Until
 * that protocol is verified, `live()` polls the small overview and only reloads play-by-play
 * when the score or state changed.
 */
public open class SportalityProvider(
    override val league: League,
    private val fetcher: Fetcher,
    private val baseUrl: String,
    /** `seriesCode` as it appears in feeds (`SHL`, `HA`). */
    private val seriesCode: String,
    /** `series` value for the filter endpoint (`shl`, `hockeyallsvenskan`). */
    private val filterSeries: String,
    private val clock: Clock = Clock.System,
) : BaseLeagueProvider() {

    private val mapper = SportalityMapper(league.id)

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
        Capability.INTERMISSION_STATE,
        Capability.PERIOD_SCORES,
        Capability.EVENT_COORDINATES,
        Capability.LINE_GROUPS,
    )

    // ---- bootstrap -----------------------------------------------------------------------

    private suspend fun filter(): SptFilter =
        get("/sports-v2/season-series-game-types-filter?series=$filterSeries", SptFilter.serializer(), STATIC_MAX_AGE)

    private suspend fun allTeams(ssgt: String): List<SptTeam> =
        get("/sports-v2/all-teams/$ssgt", ListSerializer(SptTeam.serializer()), 24.hours)

    private suspend fun teamsByCode(ssgt: String): Map<String, SptTeam> =
        allTeams(ssgt).flatMap { t -> listOfNotNull(t.teamNames.code, t.teamCode).map { it to t } }.toMap()

    // ---- games ---------------------------------------------------------------------------

    override suspend fun gamesOn(date: LocalDate): List<Game> {
        val f = filter()
        val header = get("/gameday/gameheader", MapSerializer(String.serializer(), ListSerializer(SptHeaderGame.serializer())), LIVE_MAX_AGE)
        val key = date.toString()
        if (key !in header) {
            // Outside the ~10-day gameheader window: fall back to the season schedule. Its
            // mapper does not need the all-teams index, so avoid decoding that large static body.
            val d = f.defaultSsgtFilter
            val schedule = get(
                "/sports-v2/game-schedule?seasonUuid=${d.season}&seriesUuid=${d.series}&gameTypeUuid=${d.gameType}&gamePlace=all&played=all",
                SptSchedule.serializer(), STATIC_MAX_AGE,
            )
            return schedule.gameInfo
                .filter { Instant.parse(it.rawStartDateTime).toLocalDateTime(SWEDEN).date == date }
                .map(mapper::game)
        }
        val teams = teamsByCode(f.ssgtUuid)
        val now = clock.now()
        val games = header.getValue(key).filter { it.seriesCode == seriesCode }
        return coroutineScope {
            games.map { h ->
                async {
                    // Only games that should be under way get the (tiny) overview call.
                    val overview = if (!h.played && Instant.parse(h.startDateTime) <= now) overview(h.uuid) else null
                    mapper.game(h, teams, overview)
                }
            }.awaitAll()
        }
    }

    override suspend fun game(id: String): Game = coroutineScope {
        val info = async { gameInfo(id) }
        val overview = async { overview(id) }
        val events = async { playByPlay(id) }
        mapper.game(info.await(), overview.await(), events.await())
    }

    /**
     * `/play-by-play` is 100+ KB while `/game-overview` is small and has the volatile score,
     * period and clock. Keep the full initial event list, then pay for play-by-play again only
     * at an actual score/state transition. This is the safe direct-client fallback until the
     * platform's undocumented SSE payload has been captured and typed.
     */
    override fun live(gameId: String, interval: Duration): Flow<Game> = flow {
        val effective = maxOf(interval, LIVE_MAX_AGE)
        var current = game(gameId)
        emit(current)
        while (!current.state.isTerminal) {
            delay(effective)
            val info = gameInfo(gameId)
            val overview = overview(gameId)
            val summary = mapper.game(info, overview, emptyList())
            val scoreboardChanged = summary.score != current.score || summary.state != current.state
            val next = if (scoreboardChanged) {
                mapper.game(info, overview, playByPlay(gameId))
            } else {
                summary.copy(events = current.events)
            }
            if (next != current) emit(next)
            current = next
        }
    }

    override suspend fun events(gameId: String): List<GameEvent> = coroutineScope {
        val info = async { gameInfo(gameId) }
        val events = async { playByPlay(gameId) }
        val game = info.await()
        mapper.events(events.await(), mapper.teamRef(game.homeTeam), mapper.teamRef(game.awayTeam))
    }

    override suspend fun lineups(gameId: String): List<Lineup> = coroutineScope {
        val info = async { gameInfo(gameId) }
        val box = async { getOrNull("/gameday/boxscore/$gameId", SptBoxscore.serializer(), LIVE_MAX_AGE) }
        val game = info.await()
        val lineups = box.await() ?: return@coroutineScope emptyList()
        mapper.lineups(gameId, lineups, mapper.teamRef(game.homeTeam), mapper.teamRef(game.awayTeam))
    }

    private suspend fun gameInfo(id: String): SptGameInfoResponse {
        val info = get("/sports-v2/game-info/$id", SptGameInfoResponse.serializer(), TABLE_MAX_AGE)
        // Unknown ids come back 200 with every field empty.
        if (info.gameInfo.gameUuid.isBlank()) throw NotFoundException("${league.name} game '$id' not found", league.id)
        return info
    }

    private suspend fun overview(id: String): SptOverview? =
        getOrNull("/gameday/game-overview/$id", SptOverview.serializer(), LIVE_MAX_AGE)

    private suspend fun playByPlay(id: String): List<SptEvent> =
        getOrNull("/gameday/play-by-play/$id", ListSerializer(SptEvent.serializer()), LIVE_MAX_AGE).orEmpty()

    // ---- standings / teams / players ----------------------------------------------------

    override suspend fun standings(seasonId: String?): StandingsTable {
        val ssgt = seasonId ?: filter().ssgtUuid
        return mapper.standings(get("/statistics-v2/league-standings?ssgtUuid=$ssgt", SptStandings.serializer(), TABLE_MAX_AGE), ssgt)
    }

    override suspend fun team(id: String): Team {
        val teams = allTeams(filter().ssgtUuid)
        val t = teams.firstOrNull { it.uuid == id }
            ?: teams.firstOrNull { it.teamNames.code.equals(id, true) || it.teamCode.equals(id, true) }
            ?: throw NotFoundException("${league.name} team '$id' not found", league.id)
        return mapper.team(t)
    }

    override suspend fun roster(teamId: String): List<Player> =
        mapper.roster(get("/sports-v2/athletes/by-team-uuid/$teamId", ListSerializer(SptAthleteGroup.serializer()), STATIC_MAX_AGE), teamId)

    override suspend fun player(id: String): Player =
        mapper.player(get("/statistics-v2/athlete/profile-page?playerUuid=$id&masterSiteInstanceId=", SptProfilePage.serializer(), STATIC_MAX_AGE))

    private suspend fun <T> get(path: String, strategy: DeserializationStrategy<T>, maxAge: Duration): T =
        fetcher.getJson(baseUrl + path, strategy, maxAge, league.id)

    private suspend fun <T> getOrNull(path: String, strategy: DeserializationStrategy<T>, maxAge: Duration): T? =
        fetcher.getJsonOrNull(baseUrl + path, strategy, maxAge, league.id)

    protected companion object {
        val SWEDEN: TimeZone = TimeZone.of("Europe/Stockholm")
        val LIVE_MAX_AGE: Duration = 10.seconds
        val TABLE_MAX_AGE: Duration = 5.minutes
        val STATIC_MAX_AGE: Duration = 1.hours
    }
}

/** SHL via `www.shl.se/api`. */
public class ShlProvider(fetcher: Fetcher, baseUrl: String = DEFAULT_BASE_URL, clock: Clock = Clock.System) :
    SportalityProvider(LEAGUE, fetcher, baseUrl, seriesCode = "SHL", filterSeries = "shl", clock = clock) {
    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://www.shl.se/api"
        public val LEAGUE: League = League("shl", Sport.HOCKEY, "SHL", "SE", "https://www.shl.se")
    }
}
