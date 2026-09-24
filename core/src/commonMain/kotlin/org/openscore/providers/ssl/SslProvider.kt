package org.openscore.providers.ssl

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.openscore.model.Game
import org.openscore.model.League
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
import org.openscore.provider.runCatchingUnlessCancelled
import org.openscore.providers.sportality.SptAthleteGroup
import org.openscore.providers.sportality.SptFilter
import org.openscore.providers.sportality.SptGameInfoResponse
import org.openscore.providers.sportality.SptHeaderGame
import org.openscore.providers.sportality.SptProfilePage
import org.openscore.providers.sportality.SptSchedule
import org.openscore.providers.sportality.SptTeam
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * SSL Herr, the Swedish men's top floorball division, from `www.ssl.se/api` - see
 * apis/floorball/ssl/README.md.
 *
 * ssl.se runs on the same Sportality platform as shl.se, so the bootstrap, scoreboard,
 * schedule, game, team and athlete routes are the platform's and decode with the shared
 * `Spt*` DTOs. The similarity stops at the game day: `game-overview`, `play-by-play`,
 * `team-stats` and `player-stats` answer `200` with an empty body for a floorball game and
 * `boxscore` answers `500`, so this provider has no events, no lineups, no clock and no
 * period scores, and does not claim live updates until an in-play capture proves where the
 * score and state come from while a game runs.
 *
 * Ids: games, teams and athletes are the platform's opaque UUIDs; `seasonId` is the
 * `ssgtUuid` that combines season, series and game type (`qqing3frfp` for SSL Herr 2026/27).
 * Dates are Swedish calendar dates. The bootstrap lists SSL Dam and both Swedish Cups on the
 * same host; each would be a provider of its own, keyed on its own series UUID.
 */
public class SslProvider(
    private val fetcher: Fetcher,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val clock: Clock = Clock.System,
) : BaseLeagueProvider() {

    override val league: League = LEAGUE

    private val mapper = SslMapper(league.id)

    override val capabilities: Set<Capability> = setOf(
        Capability.GAMES_BY_DATE,
        Capability.GAME,
        Capability.STANDINGS,
        Capability.TEAM,
        Capability.TEAM_SCHEDULE,
        Capability.ROSTER,
        Capability.PLAYER,
    )

    // ---- games -------------------------------------------------------------------------------

    /**
     * The Swedish calendar day. The rolling `gameheader` covers about a week around today and
     * is the cheap path; any other date is cut from the season schedule, which is large but
     * static enough to keep for an hour. A game whose start has passed while the header still
     * says unplayed is read once from `game-info`, the only route that carries a state.
     */
    override suspend fun gamesOn(date: LocalDate): List<Game> {
        val header = header()[date.toString()]
            ?: return schedule().gameInfo
                .filter { Instant.parse(it.rawStartDateTime).toLocalDateTime(SWEDEN).date == date }
                .map(mapper::game)
                .sortedBy { it.startTime }
        val rows = header.filter { it.seriesCode == SERIES_CODE }
        if (rows.isEmpty()) return emptyList()
        val teams = teamsByCode()
        val now = clock.now()
        return coroutineScope {
            rows.map { h ->
                async {
                    val row = mapper.game(h, teams)
                    if (h.played || Instant.parse(h.startDateTime) > now) row
                    else runCatchingUnlessCancelled { mapper.game(gameInfo(h.uuid)) }.getOrDefault(row)
                }
            }.awaitAll()
        }.sortedBy { it.startTime }
    }

    /** One game; a finished one also carries the post-game team totals from the promo bar. */
    override suspend fun game(id: String): Game {
        val info = gameInfo(id)
        val game = mapper.game(info)
        if (!game.state.isFinished) return game
        // Totals are a bonus on top of a complete result: a promo bar that fails leaves the game whole.
        val totals = runCatchingUnlessCancelled { promoStats(info) }.getOrNull() ?: return game
        return mapper.game(info, totals)
    }

    override suspend fun teamSchedule(teamId: String, startDate: LocalDate, endDate: LocalDate): List<Game> =
        schedule().gameInfo
            .filter { it.homeTeamInfo.uuid == teamId || it.awayTeamInfo.uuid == teamId }
            .map(mapper::game)
            .filter { it.startTime.toLocalDateTime(SWEDEN).date in startDate..endDate }
            .sortedBy { it.startTime }

    // ---- standings / teams / players ----------------------------------------------------------

    override suspend fun standings(seasonId: String?): StandingsTable {
        val ssgt = seasonId ?: currentSeason()
        return mapper.standings(get("/statistics-v2/league-standings?ssgtUuid=$ssgt", SslStandings.serializer(), TABLE_MAX_AGE), ssgt)
    }

    override suspend fun team(id: String): Team = mapper.team(teamRecord(id))

    override suspend fun roster(teamId: String): List<Player> {
        val uuid = teamRecord(teamId).uuid
        val groups = get("/sports-v2/athletes/by-team-uuid/$uuid", ListSerializer(SptAthleteGroup.serializer()), STATIC_MAX_AGE)
        return mapper.roster(groups, uuid)
    }

    override suspend fun player(id: String): Player =
        mapper.player(get("/statistics-v2/athlete/profile-page?playerUuid=$id&masterSiteInstanceId=", SptProfilePage.serializer(), STATIC_MAX_AGE))

    // ---- reads ---------------------------------------------------------------------------------

    /** The `ssgtUuid` of the site's current default selection: season + series + game type in one id. */
    public suspend fun currentSeason(): String = filter().ssgtUuid

    private suspend fun filter(): SptFilter =
        get("/sports-v2/season-series-game-types-filter", SptFilter.serializer(), STATIC_MAX_AGE)

    /** The whole SSL Herr season, keyed by the three component UUIDs rather than the combined one. */
    private suspend fun schedule(): SptSchedule {
        val d = filter().defaultSsgtFilter
        return get(
            "/sports-v2/game-schedule?seasonUuid=${d.season}&seriesUuid=${d.series}&gameTypeUuid=${d.gameType}&gamePlace=all&played=all",
            SptSchedule.serializer(), SCHEDULE_MAX_AGE,
        )
    }

    /** The rolling scoreboard, keyed by Swedish date and mixing every SSL series. */
    private suspend fun header(): Map<String, List<SptHeaderGame>> =
        get("/gameday/gameheader", MapSerializer(String.serializer(), ListSerializer(SptHeaderGame.serializer())), LIVE_MAX_AGE)

    private suspend fun gameInfo(id: String): SptGameInfoResponse {
        val info = get("/sports-v2/game-info/$id", SptGameInfoResponse.serializer(), LIVE_MAX_AGE)
        // An unknown id answers 200 with every field empty.
        if (info.gameInfo.gameUuid.isBlank()) throw NotFoundException("SSL game '$id' not found", league.id)
        return info
    }

    private suspend fun promoStats(info: SptGameInfoResponse): SslPromoStats? {
        val ssgt = info.ssgtUuid ?: return null
        val home = info.homeTeam.uuid.ifBlank { return null }
        val away = info.awayTeam.uuid.ifBlank { return null }
        return getOrNull(
            "/gameday/post-game-data/promo-bar-stats/${info.gameInfo.gameUuid}/$home/$away?ssgtUuid=$ssgt",
            SslPromoStats.serializer(), STATS_MAX_AGE,
        )
    }

    private suspend fun allTeams(): List<SptTeam> =
        get("/sports-v2/all-teams/${currentSeason()}", ListSerializer(SptTeam.serializer()), STATIC_MAX_AGE)

    /** `gameheader` teams carry only a display code, so the season's teams are indexed by every code they answer to. */
    private suspend fun teamsByCode(): Map<String, SptTeam> =
        allTeams().flatMap { t -> listOfNotNull(t.teamNames.code, t.teamCode).map { it to t } }.toMap()

    /** A team by platform UUID, or by either of the codes the feeds show (`MAIS`, `Mullsjö`). */
    private suspend fun teamRecord(id: String): SptTeam {
        val teams = allTeams()
        return teams.firstOrNull { it.uuid == id }
            ?: teams.firstOrNull { it.teamNames.code.equals(id, true) || it.teamCode.equals(id, true) }
            ?: throw NotFoundException("SSL team '$id' not found", league.id)
    }

    private suspend fun <T> get(path: String, strategy: DeserializationStrategy<T>, maxAge: Duration): T =
        fetcher.getJson(baseUrl + path, strategy, maxAge, league.id)

    private suspend fun <T> getOrNull(path: String, strategy: DeserializationStrategy<T>, maxAge: Duration): T? =
        fetcher.getJsonOrNull(baseUrl + path, strategy, maxAge, league.id)

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://www.ssl.se/api"

        public val LEAGUE: League = League(
            id = "ssl",
            sport = Sport.FLOORBALL,
            name = "SSL",
            country = "SE",
            websiteUrl = "https://www.ssl.se",
        )

        /** The bootstrap's code for the men's division; `gameheader` mixes it with `SSLDam`. */
        private const val SERIES_CODE = "SSLHerr"

        private val SWEDEN = TimeZone.of("Europe/Stockholm")
        private val LIVE_MAX_AGE = 10.seconds
        private val STATS_MAX_AGE = 5.minutes
        private val TABLE_MAX_AGE = 5.minutes
        private val SCHEDULE_MAX_AGE = 1.hours
        private val STATIC_MAX_AGE = 24.hours
    }
}
