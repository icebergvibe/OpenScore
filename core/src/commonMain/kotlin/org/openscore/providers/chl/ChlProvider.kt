package org.openscore.providers.chl

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.KSerializer
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
import org.openscore.net.HttpException
import org.openscore.provider.BaseLeagueProvider
import org.openscore.provider.Capability
import org.openscore.provider.NotFoundException
import org.openscore.provider.ProviderException
import org.openscore.provider.getJson
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Champions Hockey League via the Corebine S3 files behind chl.hockey — see
 * apis/hockey/chl/README.md.
 *
 * Ids: matches, teams and players are 24-hex Corebine entity ids; `seasonId` is the
 * 24-hex season id (there is no season list endpoint — see [SEASONS]). Dates are Central
 * European local dates. No clock is published, so [Game.clock] is always null.
 */
public class ChlProvider(
    private val fetcher: Fetcher,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val seasonId: String = CURRENT_SEASON,
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
        // `duration.regularTime`, elapsed play in seconds; no play/stop flag, so not CLOCK_RUNNING_FLAG.
        Capability.CLOCK,
        Capability.PERIOD_SCORES,
        Capability.LINE_GROUPS,
    )

    override suspend fun gamesOn(date: LocalDate): List<Game> {
        val window = live("live-events.json", ListSerializer(ChlMatch.serializer()), 30.seconds)
        val inWindow = window.filter { localDate(it.startDate) == date }
        if (inWindow.isNotEmpty()) return inWindow.map { ChlMapper.game(it, withEvents = false) }
        val first = window.minOfOrNull { Instant.parse(it.startDate) }
        val last = window.maxOfOrNull { Instant.parse(it.startDate) }
        if (first != null && last != null && date in localDate(first)..localDate(last)) return emptyList()
        // Outside the live window: the season schedule.
        return static("schedule-$COMPETITION-$seasonId.json", ListSerializer(ChlMatch.serializer()), 1.hours)
            .filter { localDate(it.startDate) == date }
            .map { ChlMapper.game(it, withEvents = false) }
    }

    override suspend fun game(id: String): Game =
        ChlMapper.game(scoreboard(id), withEvents = true)

    override suspend fun events(gameId: String): List<GameEvent> {
        val m = scoreboard(gameId)
        return ChlMapper.events(m, ChlMapper.stage(m))
    }

    override suspend fun lineups(gameId: String): List<Lineup> =
        ChlMapper.lineups(live("live-event-$gameId-lineups.json", ChlLineups.serializer(), LIVE_MAX_AGE))

    override suspend fun standings(seasonId: String?): StandingsTable {
        val season = seasonId ?: this.seasonId
        return ChlMapper.standings(live("standings-groups-$COMPETITION-$season.json", ListSerializer(ChlSeries.serializer()), TABLE_MAX_AGE), season)
    }

    override suspend fun team(id: String): Team {
        val teams = static("teams-$COMPETITION-$seasonId.json", ListSerializer(ChlTeamRef.serializer()), 24.hours)
        val t = teams.firstOrNull { it.entityId == id } ?: teams.firstOrNull { it.shortName.equals(id, true) }
            ?: throw NotFoundException("CHL team '$id' not found", league.id)
        return ChlMapper.team(t)
    }

    /**
     * The club's whole season in one file, filtered to the window. CHL is a short competition
     * (six group games plus the knock-out rounds), so there is no cheaper route and no paging;
     * without this a club that plays in CHL and a domestic league showed only the domestic
     * fixtures on its page, because the merge is per member competition.
     */
    override suspend fun teamSchedule(teamId: String, startDate: LocalDate, endDate: LocalDate): List<Game> =
        static("team-schedule-$COMPETITION-$seasonId-$teamId.json", ListSerializer(ChlMatch.serializer()), STATIC_MAX_AGE)
            .filter { localDate(it.startDate) in startDate..endDate }
            .map { ChlMapper.game(it, withEvents = false) }

    override suspend fun roster(teamId: String): List<Player> {
        val t = static("team-players-info-$COMPETITION-$seasonId-$teamId.json", ChlTeamWithAthletes.serializer(), STATIC_MAX_AGE)
        return t.athletes.map { ChlMapper.player(it, teamId = t.entityId) }
    }

    override suspend fun player(id: String): Player =
        ChlMapper.player(static("player-$COMPETITION-$id.json", ChlPlayer.serializer(), STATIC_MAX_AGE))

    private suspend fun scoreboard(id: String): ChlMatch =
        live("live-event-$id-scoreboard.json", ChlMatch.serializer(), LIVE_MAX_AGE)

    private fun localDate(iso: String): LocalDate = localDate(Instant.parse(iso))

    private fun localDate(instant: Instant): LocalDate = instant.toLocalDateTime(league.zone).date

    private suspend fun <T> live(file: String, strategy: KSerializer<T>, maxAge: Duration): T =
        file("$baseUrl/live?q=$file", strategy, maxAge)

    private suspend fun <T> static(file: String, strategy: KSerializer<T>, maxAge: Duration): T =
        file("$baseUrl?q=$file", strategy, maxAge)

    /** Unwraps the Corebine envelope; a missing file is an S3 `403 AccessDenied` XML → not found. */
    private suspend fun <T> file(url: String, strategy: KSerializer<T>, maxAge: Duration): T {
        val envelope = try {
            fetcher.getJson(url, ChlResponse.serializer(strategy), maxAge, league.id)
        } catch (e: HttpException) {
            if (e.status == 403) throw NotFoundException("CHL: $url → 403 (no such file)", league.id) else throw e
        }
        return envelope.data ?: throw ProviderException("CHL: $url has no data: ${envelope.errors}", leagueId = league.id)
    }

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://www.chl.hockey/api/s3"
        public const val COMPETITION: String = "21ec9dad81abe2e0240460d0"
        public const val CURRENT_SEASON: String = "fc954f6d33272fdf4a8b95bb"

        /** Season label → id, from the schedule page's selector (no list endpoint exists). */
        public val SEASONS: Map<String, String> = mapOf(
            "2026/27" to "fc954f6d33272fdf4a8b95bb", "2025/26" to "3c5f99fa605394cc65733fc9",
            "2024/25" to "65772c03f5465c804a4fe7de", "2023/24" to "384dfd08cf1b5e6e93cd19ba",
            "2022/23" to "42d2f45345814558d4daff38", "2021/22" to "f73bbb143cc88c3ebe188d77",
            "2020/21" to "cb8140fcc60dfdbd9fd298a5", "2019/20" to "8f7d5c9a161f121955e7a148",
            "2018/19" to "3b8d1d7295522f7481d65ded", "2017/18" to "7ff8d796a87a86b861bf9e22",
            "2016/17" to "741bae588668e5c4a0eeade1", "2015/16" to "512ec0d6dfe80058c7fed4f6",
            "2014/15" to "0acaee27317455a72a914215",
        )

        public val LEAGUE: League = League("chl", Sport.HOCKEY, "Champions Hockey League", "EU", TimeZone.of("Europe/Zurich"), "https://www.chl.hockey")

        private val LIVE_MAX_AGE = 10.seconds
        private val TABLE_MAX_AGE = 5.minutes
        private val STATIC_MAX_AGE = 1.hours
    }
}
