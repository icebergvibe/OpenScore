package org.openscore.providers.liiga

import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
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
 * Liiga (Finland) via `liiga.fi/api/v2` — see apis/hockey/liiga/README.md.
 *
 * Ids: games are Liiga game ids (`2701274`; the season is derived from the first two
 * digits, or pass `season:id` for pre-2019 short ids), teams the numeric half of Liiga's
 * `"id:slug"`, players the FIHA id. Dates are Finnish local dates.
 */
public class LiigaProvider(
    private val fetcher: Fetcher,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val clock: Clock = Clock.System,
) : BaseLeagueProvider() {

    override val league: League = LEAGUE

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

    override suspend fun gamesOn(date: LocalDate): List<Game> =
        get("/games?tournament=all&date=$date", LiigaGamesByDate.serializer(), LIVE_MAX_AGE)
            .games.map { LiigaMapper.game(it, withEvents = false) }

    override suspend fun game(id: String): Game {
        val (d, game) = detail(id)
        return LiigaMapper.game(game, LiigaMapper.playerIndex(d), withEvents = true)
    }

    /** Goals, penalties and shootout attempts from the game detail plus every shot from `/shotmap`. */
    override suspend fun events(gameId: String): List<GameEvent> {
        val (season, id) = parseGameId(gameId)
        val (d, game) = detail(gameId)
        val players = LiigaMapper.playerIndex(d)
        val shots = get("/shotmap/$season/$id", ListSerializer(LiigaShot.serializer()), LIVE_MAX_AGE)
        return (LiigaMapper.events(game, players) + LiigaMapper.shotEvents(game, shots, players))
            .sortedWith(compareBy({ it.period.number }, { it.time.elapsed?.inWholeSeconds ?: 0L }, { it.sortOrder ?: 0 }))
    }

    override suspend fun lineups(gameId: String): List<Lineup> {
        val (d, g) = detail(gameId)
        return listOf(
            LiigaMapper.lineup(g.id.toString(), LiigaMapper.teamRef(g.homeTeam), d.homeTeamPlayers),
            LiigaMapper.lineup(g.id.toString(), LiigaMapper.teamRef(g.awayTeam), d.awayTeamPlayers),
        )
    }

    override suspend fun standings(seasonId: String?): StandingsTable {
        val season = seasonId ?: currentSeason()
        return LiigaMapper.standings(get("/standings?season=$season", LiigaStandings.serializer(), TABLE_MAX_AGE), season)
    }

    override suspend fun team(id: String): Team {
        val wanted = LiigaMapper.teamId(id)
        val info = get("/teams/info", LiigaTeamsInfo.serializer(), 24.hours)
        val t = info.teams.values.firstOrNull { it.id == wanted }
            ?: throw NotFoundException("Liiga team '$id' not in /teams/info", league.id)
        return LiigaMapper.team(t)
    }

    override suspend fun roster(teamId: String): List<Player> {
        val wanted = LiigaMapper.teamId(teamId)
        val season = currentSeason()
        val all = get(
            "/players/info?tournament=runkosarja&fromSeason=$season&toSeason=$season",
            ListSerializer(LiigaLineupPlayer.serializer()), STATIC_MAX_AGE,
        )
        return all.filter { LiigaMapper.teamId(it.teamId) == wanted }.map(LiigaMapper::rosterPlayer)
    }

    override suspend fun player(id: String): Player =
        LiigaMapper.player(get("/players/info/$id", LiigaPlayerInfo.serializer(), STATIC_MAX_AGE))

    /** The game detail and the game inside it — unknown ids come back 200 with no `game` key. */
    private suspend fun detail(gameId: String): Pair<LiigaGameDetail, LiigaGame> {
        val (season, id) = parseGameId(gameId)
        val d = get("/games/$season/$id", LiigaGameDetail.serializer(), LIVE_MAX_AGE)
        val game = d.game ?: throw NotFoundException("Liiga game $gameId not found", league.id)
        return d to game
    }

    private suspend fun <T> get(path: String, strategy: DeserializationStrategy<T>, maxAge: Duration): T =
        fetcher.getJson(baseUrl + path, strategy, maxAge, league.id)

    /** Liiga seasons are keyed by their ending year; a new one starts with the August pre-season. */
    public fun currentSeason(): String {
        val today = clock.todayIn(league.zone)
        return (if (today.month >= Month.JUNE) today.year + 1 else today.year).toString()
    }

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://liiga.fi/api/v2"

        public val LEAGUE: League = League(
            id = "liiga",
            sport = Sport.HOCKEY,
            name = "Liiga",
            country = "FI",
            zone = TimeZone.of("Europe/Helsinki"),
            websiteUrl = "https://liiga.fi",
        )

        private val LIVE_MAX_AGE = 10.seconds
        private val TABLE_MAX_AGE = 5.minutes
        private val STATIC_MAX_AGE = 1.hours

        /**
         * `2701274` → (2027, 2701274); `2027:55080` → (2027, 55080). Seven-digit ids carry the
         * season in their first two digits; older short ids need the explicit form.
         */
        public fun parseGameId(gameId: String): Pair<String, String> {
            gameId.split(':', '/').takeIf { it.size == 2 }?.let { (s, i) -> return s to i }
            require(gameId.length == 7 && gameId.all { it.isDigit() }) {
                "Liiga game id must be a 7-digit id or 'season:id', got '$gameId'"
            }
            return "20${gameId.take(2)}" to gameId
        }
    }
}
