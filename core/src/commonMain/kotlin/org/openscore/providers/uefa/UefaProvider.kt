package org.openscore.providers.uefa

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
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
import org.openscore.provider.LivePollBudget
import org.openscore.provider.NotFoundException
import org.openscore.provider.getJson
import org.openscore.provider.runCatchingUnlessCancelled
import org.openscore.providers.espn.EspnRosters
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The four uefa.com micro-service roots; overridable for tests. */
public data class UefaHosts(
    val match: String = "https://match.uefa.com/v5",
    val comp: String = "https://comp.uefa.com/v2",
    val standings: String = "https://standings.uefa.com/v1",
    val stats: String = "https://matchstats.uefa.com/v1",
)

/**
 * UEFA competitions via the key-less uefa.com micro-services - see
 * apis/football/uefa/README.md. One API for every UEFA competition, selected by
 * [competitionId]: [ChampionsLeagueProvider] (1), [EuropaLeagueProvider] (14),
 * [ConferenceLeagueProvider] (2019), [NationsLeagueProvider] (2014).
 *
 * Ids are UEFA's global digit strings (match `2049553`, team `50051`, player `250076574`).
 * `seasonId` is the season's **end year** (`2027` = 2026/27). Dates are the venue's local
 * dates (`kickOffTime.date`). No roster endpoint exists. [live] polls the `livescore` list
 * (~300 B on a quiet day, 7-15 KB once finished matches carry a `winner.team`) and re-reads
 * the match when its `hash` changed, trusting that hash only once the match document reports
 * the same phase - see [caughtUp].
 */
public open class UefaProvider(
    override val league: League,
    private val fetcher: Fetcher,
    public val competitionId: Int,
    private val hosts: UefaHosts = UefaHosts(),
    private val clock: Clock = Clock.System,
    /** Squads: `comp.uefa.com` has no squad endpoint, so they come from ESPN through the crosswalk's `espn` ids; null leaves ROSTER unsupported. */
    private val rosters: EspnRosters? = null,
) : BaseLeagueProvider() {

    private val mapper = UefaMapper(league.id)

    override suspend fun roster(teamId: String): List<Player> {
        val source = rosters ?: unsupported(Capability.ROSTER)
        // Named one by one: a competition with no ESPN slug of its own must not inherit another's squads.
        val slug = when (competitionId) {
            CHAMPIONS_LEAGUE -> EspnRosters.CHAMPIONS_LEAGUE_SLUG
            EUROPA_LEAGUE -> EspnRosters.EUROPA_LEAGUE_SLUG
            CONFERENCE_LEAGUE -> EspnRosters.CONFERENCE_LEAGUE_SLUG
            else -> unsupported(Capability.ROSTER)
        }
        return source.rosterFor(league.id, teamId, slug, currentSeason() - 1)
            ?: throw NotFoundException("${league.name}: no ESPN squad id for club '$teamId' in the crosswalk", league.id)
    }

    override val capabilities: Set<Capability> = (if (rosters != null) setOf(Capability.ROSTER) else emptySet()) + setOf(
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
        Capability.EVENT_COORDINATES,
    )

    /** UEFA seasons are named by their end year and start with July's qualifiers. */
    public open fun currentSeason(): Int = clock.todayIn(TimeZone.UTC).let { if (it.month >= Month.JULY) it.year + 1 else it.year }

    override suspend fun gamesOn(date: LocalDate): List<Game> {
        val list = get(
            "${hosts.match}/matches?competitionId=$competitionId&fromDate=$date&toDate=$date&limit=100&offset=0&order=ASC",
            ListSerializer(UefaMatch.serializer()), LIST_MAX_AGE,
        )
        return list.map { mapper.game(it, now = clock.now()) }.sortedBy { it.startTime }
    }

    /** `teamId=` on the season's match list: the drawn fixtures so far (league phase first, knock-out ties as they are drawn). */
    override suspend fun teamSchedule(teamId: String, startDate: LocalDate, endDate: LocalDate): List<Game> {
        val list = get(
            "${hosts.match}/matches?competitionId=$competitionId&seasonYear=${currentSeason()}&teamId=$teamId&limit=100&offset=0&order=ASC",
            ListSerializer(UefaMatch.serializer()), LIST_MAX_AGE,
        )
        val now = clock.now()
        return list.map { mapper.game(it, now = now) }
            .filter { it.startTime.toLocalDateTime(TimeZone.UTC).date in startDate..endDate }
            .sortedBy { it.startTime }
    }

    override suspend fun game(id: String): Game {
        val match = match(id)
        if (match.status == "UPCOMING") return mapper.game(match, emptyList(), null, clock.now())
        // The timeline and the team statistics are independent resources; read them together.
        return coroutineScope {
            val events = async { events(id, EVENTS_MAX_AGE) }
            val stats = async { runCatchingUnlessCancelled { teamStatistics(id) }.getOrNull() }
            mapper.game(match, events.await(), stats.await(), clock.now())
        }
    }

    override suspend fun events(gameId: String): List<GameEvent> = mapper.events(match(gameId), events(gameId, EVENTS_MAX_AGE))

    override suspend fun lineups(gameId: String): List<Lineup> =
        mapper.lineups(get("${hosts.match}/matches/$gameId/lineups", UefaLineups.serializer(), LINEUP_MAX_AGE), match(gameId))

    override suspend fun standings(seasonId: String?): StandingsTable {
        val season = seasonId ?: currentSeason().toString()
        val list = get("${hosts.standings}/standings?competitionId=$competitionId&seasonYear=$season&phase=TOURNAMENT", ListSerializer(UefaStandings.serializer()), TABLE_MAX_AGE)
        if (list.isEmpty()) throw NotFoundException("${league.name}: no standings for $season", league.id)
        return mapper.standings(list, season, league.name)
    }

    override suspend fun team(id: String): Team {
        val t = get("${hosts.comp}/teams?teamIds=$id", ListSerializer(UefaTeam.serializer()), STATIC_MAX_AGE).firstOrNull()
            ?: throw NotFoundException("${league.name}: team '$id' not found", league.id)
        return mapper.team(t)
    }

    override suspend fun player(id: String): Player {
        val p = get("${hosts.comp}/players?playerIds=$id", ListSerializer(UefaPerson.serializer()), STATIC_MAX_AGE).firstOrNull()
            ?: throw NotFoundException("${league.name}: player '$id' not found", league.id)
        return mapper.player(p)
    }

    /**
     * Polls `/livescore` every [interval] (≥ 10 s) and re-reads the match when its `hash`
     * changes, when it is missing from the list (more than an hour from kick-off), when the
     * document last read had not yet caught up with the list's phase (see [caughtUp]), or at
     * least every [LIVE_REFRESH]; ends once the game is final.
     */
    override fun live(gameId: String, interval: Duration): Flow<Game> = flow {
        val budget = LivePollBudget(interval)
        var last: Game? = null
        var lastHash: String? = null
        var lastRead = Instant.DISTANT_PAST
        var catchUps = 0
        while (budget.open) {
            val entry = budget.read { livescore().firstOrNull { it.id == gameId } }.getOrNull()
            val now = clock.now()
            val stale = now - lastRead >= LIVE_REFRESH
            if (entry == null || entry.hash != lastHash || catchUps > 0 || stale) {
                // The full reload is read through the budget too: it is the expensive call, and
                // before this it was the one unguarded read that could end the flow outright.
                val game = budget.read { game(gameId) }.getOrNull()
                if (game != null) {
                    lastRead = now
                    lastHash = entry?.hash
                    catchUps = when {
                        caughtUp(entry, game) -> 0
                        // Chasing forever would turn the expensive read into a per-tick poll if a
                        // mismatch never resolved; give up at the point LIVE_REFRESH would have
                        // asked anyway, so the worst case is no worse than before.
                        catchUps + 1 >= MAX_CATCH_UP_READS -> 0
                        else -> catchUps + 1
                    }
                    if (game != last) emit(game)
                    last = game
                    if (game.state.isTerminal) return@flow
                }
            }
            budget.wait()
        }
    }

    /**
     * Whether the match document just read reports the phase `/livescore` already does.
     *
     * The two routes are not in step: the livescore list flips 15-20 s before the match document
     * (observed at half time and full time of Andorra v Malta, 2026-09-24). Recording the new
     * hash off a document read inside that window left the old state standing, because during a
     * break the entry carries no `minute` and its hash then stops changing - so nothing asked
     * again until [LIVE_REFRESH] and the app showed `45'+3` for a minute after the whistle.
     */
    private fun caughtUp(entry: UefaLivescore?, game: Game): Boolean {
        val marker = UefaMapper.phaseMarker(entry?.status, entry?.phase).ifEmpty { return true }
        val raw = game.rawState ?: return true
        return raw == marker || raw.startsWith("$marker/")
    }

    public suspend fun livescore(): List<UefaLivescore> =
        get("${hosts.match}/livescore", ListSerializer(UefaLivescore.serializer()), LIVESCORE_MAX_AGE)

    private suspend fun match(id: String): UefaMatch =
        get("${hosts.match}/matches/$id", UefaMatch.serializer(), MATCH_MAX_AGE)

    private suspend fun events(id: String, maxAge: Duration): List<UefaEvent> =
        get("${hosts.match}/matches/$id/events?filter=MAIN&order=ASC&limit=500&offset=0", ListSerializer(UefaEvent.serializer()), maxAge)

    private suspend fun teamStatistics(id: String): List<UefaTeamStatistics> =
        get("${hosts.stats}/team-statistics/$id", ListSerializer(UefaTeamStatistics.serializer()), STATS_MAX_AGE)

    private suspend fun <T> get(url: String, strategy: DeserializationStrategy<T>, maxAge: Duration): T =
        fetcher.getJson(url, strategy, maxAge, league.id)

    public companion object {
        public const val CHAMPIONS_LEAGUE: Int = 1
        public const val EUROPA_LEAGUE: Int = 14
        public const val CONFERENCE_LEAGUE: Int = 2019
        public const val NATIONS_LEAGUE: Int = 2014

        private val LIVESCORE_MAX_AGE = 5.seconds
        private val MATCH_MAX_AGE = 10.seconds
        private val EVENTS_MAX_AGE = 10.seconds
        private val LIST_MAX_AGE = 30.seconds
        private val STATS_MAX_AGE = 1.minutes
        private val LINEUP_MAX_AGE = 1.minutes
        private val TABLE_MAX_AGE = 1.minutes
        private val STATIC_MAX_AGE = 1.hours
        /** Re-read a live match at least this often even when the livescore hash is unchanged. */
        private val LIVE_REFRESH = 60.seconds

        /**
         * How many ticks in a row `live` will re-read a match document that has not caught up
         * with `/livescore`. Six at the 10 s floor is [LIVE_REFRESH], the interval that would
         * have asked anyway.
         */
        private const val MAX_CATCH_UP_READS = 6
    }
}

/** UEFA Champions League (competition 1). */
public class ChampionsLeagueProvider(fetcher: Fetcher, hosts: UefaHosts = UefaHosts(), clock: Clock = Clock.System, rosters: EspnRosters? = null) :
    UefaProvider(LEAGUE, fetcher, UefaProvider.CHAMPIONS_LEAGUE, hosts, clock, rosters) {
    public companion object {
        public val LEAGUE: League = League("ucl", Sport.FOOTBALL, "UEFA Champions League", "EU", TimeZone.UTC, "https://www.uefa.com/uefachampionsleague/")
    }
}

/** UEFA Europa League (competition 14). */
public class EuropaLeagueProvider(fetcher: Fetcher, hosts: UefaHosts = UefaHosts(), clock: Clock = Clock.System, rosters: EspnRosters? = null) :
    UefaProvider(LEAGUE, fetcher, UefaProvider.EUROPA_LEAGUE, hosts, clock, rosters) {
    public companion object {
        public val LEAGUE: League = League("uel", Sport.FOOTBALL, "UEFA Europa League", "EU", TimeZone.UTC, "https://www.uefa.com/uefaeuropaleague/")
    }
}

/** UEFA Conference League (competition 2019). */
public class ConferenceLeagueProvider(fetcher: Fetcher, hosts: UefaHosts = UefaHosts(), clock: Clock = Clock.System, rosters: EspnRosters? = null) :
    UefaProvider(LEAGUE, fetcher, UefaProvider.CONFERENCE_LEAGUE, hosts, clock, rosters) {
    public companion object {
        public val LEAGUE: League = League("uecl", Sport.FOOTBALL, "UEFA Conference League", "EU", TimeZone.UTC, "https://www.uefa.com/uefaconferenceleague/")
    }
}

/**
 * UEFA Nations League (competition 2014): the same services, national teams instead of clubs.
 *
 * Teams are `NATIONAL_MEN_TEAM_A` with the country's flag as the logo, so there is nothing to
 * cross-walk: [org.openscore.model.TeamRef.clubId] stays null and a team page is single-league.
 * No squads either (UEFA has no squad endpoint and ESPN's ids in the crosswalk are club ids),
 * so ROSTER is unsupported.
 */
public class NationsLeagueProvider(fetcher: Fetcher, hosts: UefaHosts = UefaHosts(), clock: Clock = Clock.System) :
    UefaProvider(LEAGUE, fetcher, UefaProvider.NATIONS_LEAGUE, hosts, clock, rosters = null) {

    /**
     * The competition is biennial and its seasons are the **odd** end years (2025 = 2024/25,
     * 2027 = 2026/27); an edition also runs past its name, the 2027 relegation play-offs being
     * played in March 2028. An even year is therefore still the previous odd season. Verified
     * 2026-09-24: `seasonYear` 2022 / 2024 / 2026 / 2028 all answer `[]`, and standings 404.
     */
    override fun currentSeason(): Int = super.currentSeason().let { if (it % 2 == 0) it - 1 else it }

    public companion object {
        public val LEAGUE: League = League("unl", Sport.FOOTBALL, "UEFA Nations League", "EU", TimeZone.UTC, "https://www.uefa.com/uefanationsleague/")
    }
}
