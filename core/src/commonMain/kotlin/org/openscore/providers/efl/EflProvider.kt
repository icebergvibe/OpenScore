package org.openscore.providers.efl

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlinx.serialization.DeserializationStrategy
import org.openscore.model.Game
import org.openscore.model.GameEvent
import org.openscore.model.League
import org.openscore.model.Lineup
import org.openscore.model.Sport
import org.openscore.model.StandingsTable
import org.openscore.model.Team
import org.openscore.net.Fetcher
import org.openscore.provider.BaseLeagueProvider
import org.openscore.provider.Capability
import org.openscore.provider.NotFoundException
import org.openscore.provider.getJson
import org.openscore.provider.pollGame
import org.openscore.provider.runCatchingUnlessCancelled
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The EFL's competitions via the Gamechanger `multi-club-matches` API behind efl.com - see
 * apis/football/efl/README.md. One API for every EFL competition, selected by
 * [competitionId]: [ChampionshipProvider] (10) and [CarabaoCupProvider] (2).
 *
 * Ids are Opta's with a letter prefix (match `g2685170`, team `t43`, player `p466052`);
 * `seasonId` is the start year (`2026`). Kick-offs are UTC, and so are the dates asked for:
 * no English fixture crosses midnight UTC. One document carries the header, lineups and
 * events, so a game costs one call before kick-off and two (plus the stats rows) after it.
 * Nothing is edge-cached upstream, so [live] polls no faster than every 15 s.
 */
public open class EflProvider(
    override val league: League,
    private val fetcher: Fetcher,
    public val competitionId: Int,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val clock: Clock = Clock.System,
) : BaseLeagueProvider() {

    private val mapper = EflMapper(league.id, competitionId)

    override val capabilities: Set<Capability> = (if (competitionId == EflMapper.CHAMPIONSHIP) setOf(Capability.STANDINGS) else emptySet()) + setOf(
        Capability.GAMES_BY_DATE,
        Capability.GAME,
        Capability.EVENTS,
        Capability.LINEUPS,
        Capability.TEAM,
        Capability.TEAM_SCHEDULE,
        Capability.LIVE_UPDATES,
        Capability.CLOCK,
        Capability.CLOCK_RUNNING_FLAG,
        Capability.INTERMISSION_STATE,
        Capability.PERIOD_SCORES,
    )

    /** Seasons are keyed by start year; the cup's first round is played in early August. */
    public fun currentSeason(): String = seasonFor(clock.todayIn(LONDON))

    private fun seasonFor(date: LocalDate): String = (if (date.month >= Month.JULY) date.year else date.year - 1).toString()

    /** A day with no match answers 404 "No matches found": an empty list, not an error. */
    override suspend fun gamesOn(date: LocalDate): List<Game> {
        val list = try {
            get("/matches?from=$date%2000:00:00Z&to=$date%2023:59:59Z&competitionID=$competitionId&seasonID=${seasonFor(date)}&page.size=100", EflList.serializer(EflMatchRow.serializer()), LIST_MAX_AGE)
        } catch (_: NotFoundException) {
            return emptyList()
        }
        return list.data.filter { it.attributes.kickOffDateUTC != null }.map(mapper::game).sortedBy { it.startTime }
    }

    override suspend fun game(id: String): Game {
        val doc = match(id)
        val summary = mapper.game(doc)
        if (!summary.state.hasStarted) return summary
        // `{"data": []}` until kick-off, then the five team totals; a failing read costs the stats, not the game.
        val stats = runCatchingUnlessCancelled { get("/stats/match/$id", EflList.serializer(EflMatchStats.serializer()), STATS_MAX_AGE).data }.getOrDefault(emptyList())
        return mapper.game(doc, stats)
    }

    override suspend fun events(gameId: String): List<GameEvent> {
        val doc = match(gameId)
        val game = mapper.game(doc)
        return mapper.events(doc.attributes, game.home, game.away)
    }

    override suspend fun lineups(gameId: String): List<Lineup> {
        val doc = match(gameId)
        val game = mapper.game(doc)
        return mapper.lineups(gameId, doc.attributes, game.home, game.away)
    }

    /** Polls the match document; the API has no edge cache, so never faster than 15 s. */
    override fun live(gameId: String, interval: Duration): Flow<Game> = pollGame(this, gameId, maxOf(interval, LIVE_POLL_FLOOR))

    /** The Championship table (a snapshot upstream, `meta.snapshotDate`); the cup has none. */
    override suspend fun standings(seasonId: String?): StandingsTable {
        if (competitionId != EflMapper.CHAMPIONSHIP) unsupported(Capability.STANDINGS)
        val season = seasonId ?: currentSeason()
        val table = get("/league-tables?competitionID=$competitionId&seasonID=$season", EflList.serializer(EflTableRow.serializer()), TABLE_MAX_AGE)
        return mapper.standings(table.data, season, league.name)
    }

    override suspend fun team(id: String): Team {
        val t = teams().firstOrNull { it.teamID == id }
            ?: throw NotFoundException("${league.name}: team '$id' not found", league.id)
        return mapper.team(t)
    }

    /**
     * `teamID=` on the season's match list spans every competition the club plays, so the
     * Championship and the cup share one read (the fetcher coalesces it) and each keeps its own rows.
     */
    override suspend fun teamSchedule(teamId: String, startDate: LocalDate, endDate: LocalDate): List<Game> {
        val list = try {
            get("/matches?seasonID=${currentSeason()}&teamID=$teamId&page.size=100", EflList.serializer(EflMatchRow.serializer()), SCHEDULE_MAX_AGE)
        } catch (_: NotFoundException) {
            return emptyList()
        }
        return list.data.filter { it.attributes.competitionID == competitionId && it.attributes.kickOffDateUTC != null }.map(mapper::game)
            .filter { it.startTime.toLocalDateTime(TimeZone.UTC).date in startDate..endDate }.sortedBy { it.startTime }
    }

    /** The competition's clubs this season: 24 for the Championship, 93 for the cup (92 clubs and a `TBC` placeholder). */
    private suspend fun teams(): List<EflTeam> =
        get("/teams?competitionID=$competitionId&seasonID=${currentSeason()}&page.size=100", EflList.serializer(EflTeamEntry.serializer()), STATIC_MAX_AGE).data.mapNotNull { it.attributes.teams }

    private suspend fun match(id: String): EflRow<EflMatch> = get("/matches/$id", EflDocument.serializer(EflMatch.serializer()), LIVE_MAX_AGE).data

    private suspend fun <T> get(path: String, strategy: DeserializationStrategy<T>, maxAge: Duration): T =
        fetcher.getJson(baseUrl + path, strategy, maxAge, league.id)

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://multi-club-matches.webapi.gc.eflservices.co.uk/v2"
        private val LONDON = TimeZone.of("Europe/London")
        private val LIVE_MAX_AGE = 10.seconds
        private val LIVE_POLL_FLOOR = 15.seconds
        private val LIST_MAX_AGE = 30.seconds
        private val STATS_MAX_AGE = 30.seconds
        private val TABLE_MAX_AGE = 2.minutes
        private val SCHEDULE_MAX_AGE = 2.minutes
        private val STATIC_MAX_AGE = 24.hours
    }
}

/** The Championship (competition 10), play-offs included. */
public class ChampionshipProvider(fetcher: Fetcher, baseUrl: String = EflProvider.DEFAULT_BASE_URL, clock: Clock = Clock.System) :
    EflProvider(LEAGUE, fetcher, EflMapper.CHAMPIONSHIP, baseUrl, clock) {
    public companion object {
        public val LEAGUE: League = League("championship", Sport.FOOTBALL, "Championship", "GB", "https://www.efl.com/")
    }
}

/** The League Cup (competition 2), every round from August's first to the Wembley final. */
public class CarabaoCupProvider(fetcher: Fetcher, baseUrl: String = EflProvider.DEFAULT_BASE_URL, clock: Clock = Clock.System) :
    EflProvider(LEAGUE, fetcher, EflMapper.CARABAO_CUP, baseUrl, clock) {
    public companion object {
        public val LEAGUE: League = League("carabao-cup", Sport.FOOTBALL, "Carabao Cup", "GB", "https://www.efl.com/")
    }
}
