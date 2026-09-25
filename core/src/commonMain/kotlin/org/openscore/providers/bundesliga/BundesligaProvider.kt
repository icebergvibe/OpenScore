package org.openscore.providers.bundesliga

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openscore.model.Game
import org.openscore.model.GameEvent
import org.openscore.model.League
import org.openscore.model.Lineup
import org.openscore.model.Player
import org.openscore.model.Sport
import org.openscore.model.StandingsTable
import org.openscore.model.Team
import org.openscore.net.EventStreamFetcher
import org.openscore.net.Fetcher
import org.openscore.net.OpenScoreJson
import org.openscore.net.ServerSentEvent
import org.openscore.provider.BaseLeagueProvider
import org.openscore.provider.Capability
import org.openscore.provider.Dates
import org.openscore.provider.LivePollBudget
import org.openscore.provider.NotFoundException
import org.openscore.provider.decodeJson
import org.openscore.provider.getJson
import org.openscore.providers.espn.EspnRosters
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Bundesliga (and 2. Bundesliga via [competitionId]) from the Firebase Realtime Database
 * behind bundesliga.com — see apis/football/bundesliga/README.md.
 *
 * Ids are DFL Datalibrary ids (`DFL-MAT-…`, `DFL-CLU-…`, `DFL-OBJ-…`); `seasonId` is the
 * `DFL-SEA-…` id. Dates are German local dates. Rosters and player profiles are only
 * on the key-gated REST API, so they are not supported. The database streams ticker changes
 * over direct SSE; clients without an event-stream transport use the normal polling fallback.
 */
public class BundesligaProvider(
    private val fetcher: Fetcher,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val configUrl: String = DEFAULT_CONFIG_URL,
    private val competitionId: String = BUNDESLIGA,
    override val league: League = LEAGUE,
    /** Squads: the DFL's own person/club resources are key-gated, so they come from ESPN through the crosswalk's `espn` ids; null leaves ROSTER unsupported. */
    private val rosters: EspnRosters? = null,
) : BaseLeagueProvider() {

    private val mapper = BundesligaMapper(league.id)

    override val capabilities: Set<Capability> = BASE_CAPABILITIES +
        (if (fetcher is EventStreamFetcher) setOf(Capability.LIVE_PUSH) else emptySet()) +
        (if (rosters != null) setOf(Capability.ROSTER) else emptySet())

    override suspend fun roster(teamId: String): List<Player> {
        val source = rosters ?: unsupported(Capability.ROSTER)
        return source.rosterFor(league.id, teamId, EspnRosters.BUNDESLIGA_SLUG, seasonYear())
            ?: throw NotFoundException("${league.name}: no ESPN squad id for club '$teamId' in the crosswalk", league.id)
    }

    /** The season's start year from the config node's season name (`"2026-2027"`). */
    private suspend fun seasonYear(): Int =
        fetcher.getJson(configUrl, MapSerializer(String.serializer(), BlConfig.serializer()), STATIC_MAX_AGE, league.id)[competitionId]?.season?.name?.take(4)?.toIntOrNull()
            ?: throw NotFoundException("${league.name}: no season name in configNode for $competitionId", league.id)

    /**
     * Firebase's single-match node sends an initial `put` and shallow `patch` events. The
     * renderer keeps the richer first REST result (venue, events and stats) while the stream
     * replaces its volatile scoreboard fields. If comments stop arriving for a minute, reconnect
     * and take a fresh direct snapshot rather than showing a silently stale score.
     */
    override fun live(gameId: String, interval: Duration): Flow<Game> {
        val streamFetcher = fetcher as? EventStreamFetcher ?: return super.live(gameId, interval)
        return flow {
            val budget = LivePollBudget(interval)
            var last: Game? = null
            while (budget.open) {
                // The snapshot each connect starts from. Before this it was unguarded, so one
                // failed read between reconnects ended the stream for good.
                val opening = budget.read {
                    val season = seasonId()
                    val basic = basic(season, gameId)
                    Triple(season, basic, detailedGame(season, basic))
                }.getOrNull()
                if (opening == null) {
                    budget.wait(STREAM_RECONNECT_DELAY)
                    continue
                }
                val (season, opened, openedDetail) = opening
                var current = opened
                var detailed = openedDetail
                if (detailed != last) emit(detailed)
                last = detailed
                if (detailed.state.isTerminal) return@flow

                val path = "${seasonPath(season)}/matches/$gameId.json"
                collectStreamUntilQuiet(streamFetcher.events(path)) { event ->
                    current = current.applyFirebaseEvent(event) ?: return@collectStreamUntilQuiet
                    val next = mapper.game(current, withEvents = false).copy(
                        venue = detailed.venue,
                        events = detailed.events,
                        stats = detailed.stats,
                    )
                    if (next != last) emit(next)
                    last = next
                    detailed = next
                }
                if (last.state.isTerminal) return@flow
                // A graceful close and a quiet connection both need a short pause. This avoids a
                // reconnect spin while still recovering much faster than polling the full game.
                budget.wait(STREAM_RECONNECT_DELAY)
            }
        }
    }

    /** Feeds [onEvent] until the upstream closes the stream or stays silent for [STREAM_QUIET_TIMEOUT]. */
    private suspend fun collectStreamUntilQuiet(
        stream: Flow<ServerSentEvent>,
        onEvent: suspend (ServerSentEvent) -> Unit,
    ): Unit = coroutineScope {
        val events = Channel<ServerSentEvent>(Channel.BUFFERED)
        val reader = launch {
            try {
                stream.collect { events.send(it) }
            } finally {
                events.close()
            }
        }
        try {
            while (true) {
                val event = withTimeoutOrNull(STREAM_QUIET_TIMEOUT) { events.receiveCatching().getOrNull() } ?: break
                onEvent(event)
            }
        } finally {
            reader.cancelAndJoin()
        }
    }

    private suspend fun seasonId(): String =
        fetcher.getJson(configUrl, MapSerializer(String.serializer(), BlConfig.serializer()), STATIC_MAX_AGE, league.id)[competitionId]?.season?.dflDatalibrarySeasonId
            ?: throw NotFoundException("${league.name}: no season in configNode for $competitionId", league.id)

    private fun seasonPath(season: String) = "$baseUrl/all/$competitionId/seasons/$season"

    override suspend fun gamesOn(date: LocalDate): List<Game> {
        val season = seasonId()
        // Whole-season fixture list (cached) → which matchday(s) play on that date → fresh matchday query.
        val all = node("${seasonPath(season)}/matches.json", MapSerializer(String.serializer(), BlMatch.serializer()), STATIC_MAX_AGE).orEmpty()
        val matchdays = all.values.filter { localDate(it.plannedKickOff) == date }.mapNotNull { it.matchday }.distinct()
        return matchdays.flatMap { md ->
            node("${seasonPath(season)}/matches.json?orderBy=%22matchday%22&equalTo=$md", MapSerializer(String.serializer(), BlMatch.serializer()), LIVE_MAX_AGE)
                .orEmpty().values.filter { localDate(it.plannedKickOff) == date }
        }.map { mapper.game(it, withEvents = false) }.sortedBy { it.startTime }
    }

    override suspend fun game(id: String): Game {
        val season = seasonId()
        val basic = basic(season, id)
        return detailedGame(season, basic)
    }

    /**
     * A club's fixtures out of the whole-season list the day view already keeps warm (its rows
     * carry `score`/`matchStatus`); the results of past matchdays do not move, and today's
     * rows are what the day listing refreshes.
     */
    override suspend fun teamSchedule(teamId: String, startDate: LocalDate, endDate: LocalDate): List<Game> {
        val season = seasonId()
        val all = node("${seasonPath(season)}/matches.json", MapSerializer(String.serializer(), BlMatch.serializer()), STATIC_MAX_AGE).orEmpty()
        return all.values
            .filter { it.teams.home.dflDatalibraryClubId == teamId || it.teams.away.dflDatalibraryClubId == teamId }
            .filter { localDate(it.plannedKickOff) in startDate..endDate }
            .map { mapper.game(it, withEvents = false) }
            .sortedBy { it.startTime }
    }

    private suspend fun detailedGame(season: String, basic: BlMatch): Game = coroutineScope {
        val md = basic.dflDatalibraryMatchdayId ?: throw NotFoundException("${league.name}: match ${basic.matchId} has no matchday", league.id)
        val id = basic.matchId
        val ticker = async { node("$baseUrl/en/$competitionId/seasons/$season/matchdays/$md/$id.json", BlMatch.serializer(), LIVE_MAX_AGE) }
        val stats = async { node("${seasonPath(season)}/matchdays/$md/$id/stats.json", BlStats.serializer(), LIVE_MAX_AGE) }
        mapper.game(ticker.await() ?: basic, withEvents = true, stats = stats.await())
    }

    override suspend fun events(gameId: String): List<GameEvent> {
        val season = seasonId()
        val basic = basic(season, gameId)
        val md = basic.dflDatalibraryMatchdayId ?: throw NotFoundException("${league.name}: match $gameId has no matchday", league.id)
        val ticker = node("$baseUrl/en/$competitionId/seasons/$season/matchdays/$md/$gameId.json", BlMatch.serializer(), LIVE_MAX_AGE) ?: basic
        return mapper.events(ticker, mapper.teamRef(ticker.teams.home), mapper.teamRef(ticker.teams.away))
    }

    override suspend fun lineups(gameId: String): List<Lineup> {
        val season = seasonId()
        val basic = basic(season, gameId)
        val md = basic.dflDatalibraryMatchdayId ?: return emptyList()
        val lineup = node("${seasonPath(season)}/matchdays/$md/$gameId/lineup.json", BlLineup.serializer(), LIVE_MAX_AGE) ?: return emptyList()
        return mapper.lineups(gameId, lineup, mapper.teamRef(basic.teams.home), mapper.teamRef(basic.teams.away))
    }

    override suspend fun standings(seasonId: String?): StandingsTable {
        val season = seasonId ?: seasonId()
        val table = node("${seasonPath(season)}/liveTable.json", BlTable.serializer(), TABLE_MAX_AGE)
            ?: throw NotFoundException("${league.name}: no liveTable for season $season", league.id)
        return mapper.standings(table, league.name)
    }

    override suspend fun team(id: String): Team {
        val table = node("${seasonPath(seasonId())}/liveTable.json", BlTable.serializer(), STATIC_MAX_AGE)
        val entry = table?.entries?.firstOrNull { it.club.dflDatalibraryClubId == id || it.club.id == id || it.club.threeLetterCode.equals(id, true) }
            ?: throw NotFoundException("${league.name}: team '$id' not in the table", league.id)
        return mapper.team(entry.club)
    }

    private suspend fun basic(season: String, id: String): BlMatch =
        node("${seasonPath(season)}/matches/$id.json", BlMatch.serializer(), LIVE_MAX_AGE)
            ?: throw NotFoundException("${league.name}: match '$id' not found", league.id)

    /** Firebase answers a missing node with a `200 null`. */
    private suspend fun <T> node(url: String, strategy: DeserializationStrategy<T>, maxAge: Duration): T? {
        val response = fetcher.get(url, maxAge = maxAge)
        response.requireSuccess()
        if (response.body.trim() == "null" || response.body.isBlank()) return null
        return decodeJson(response, strategy, league.id)
    }

    private fun localDate(iso: String): LocalDate = Dates.instant(iso).toLocalDateTime(league.zone).date

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://bundesliga-web-prod.europe-west1.firebasedatabase.app"
        public const val DEFAULT_CONFIG_URL: String = "https://wapp.bapi.bundesliga.com/config/configNode.json"
        public const val BUNDESLIGA: String = "DFL-COM-000001"
        public const val BUNDESLIGA_2: String = "DFL-COM-000002"
        public val LEAGUE: League = League("bundesliga", Sport.FOOTBALL, "Bundesliga", "DE", TimeZone.of("Europe/Berlin"), "https://www.bundesliga.com")
        public val LEAGUE_2: League = League("bundesliga2", Sport.FOOTBALL, "2. Bundesliga", "DE", TimeZone.of("Europe/Berlin"), "https://www.bundesliga.com")

        private val BASE_CAPABILITIES: Set<Capability> = setOf(
            Capability.GAMES_BY_DATE,
            Capability.GAME,
            Capability.EVENTS,
            Capability.LINEUPS,
            Capability.STANDINGS,
            Capability.TEAM,
            Capability.TEAM_SCHEDULE,
            Capability.LIVE_UPDATES,
            Capability.CLOCK,
            Capability.CLOCK_RUNNING_FLAG,
            Capability.INTERMISSION_STATE,
            Capability.PERIOD_SCORES,
        )
        private val LIVE_MAX_AGE = 10.seconds
        private val STREAM_QUIET_TIMEOUT = 60.seconds
        private val STREAM_RECONNECT_DELAY = 2.seconds
        private val TABLE_MAX_AGE = 2.minutes
        private val STATIC_MAX_AGE = 1.hours
    }
}

/** Applies Firebase's top-level `put`/`patch` event envelope to the streamed match node. */
private fun BlMatch.applyFirebaseEvent(event: ServerSentEvent): BlMatch? {
    if (event.event != "put" && event.event != "patch") return null
    return try {
        val envelope = OpenScoreJson.parseToJsonElement(event.data).jsonObject
        if (envelope["path"]?.jsonPrimitive?.content != "/") return null
        val data = envelope["data"] ?: return null
        if (data is JsonNull) return null
        val next: JsonElement = if (event.event == "put") {
            data
        } else {
            val patch = data as? JsonObject ?: return null
            val base = OpenScoreJson.encodeToJsonElement(BlMatch.serializer(), this).jsonObject
            JsonObject(patch.entries.fold(base.toMap()) { merged, (key, value) ->
                if (value is JsonNull) merged - key else merged + (key to value)
            })
        }
        OpenScoreJson.decodeFromJsonElement(BlMatch.serializer(), next)
    } catch (_: Exception) {
        // An unknown Firebase event is not a reason to end a user's live score view. The next
        // put/reconnect re-establishes the complete node.
        null
    }
}
