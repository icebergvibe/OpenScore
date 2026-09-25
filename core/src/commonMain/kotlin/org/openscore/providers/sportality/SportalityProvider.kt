package org.openscore.providers.sportality

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
import org.openscore.net.EventStreamFetcher
import org.openscore.net.Fetcher
import org.openscore.net.OpenScoreJson
import org.openscore.net.ServerSentEvent
import org.openscore.provider.BaseLeagueProvider
import org.openscore.provider.Capability
import org.openscore.provider.LivePollBudget
import org.openscore.provider.NotFoundException
import org.openscore.provider.getJson
import org.openscore.provider.getJsonOrNull
import org.openscore.provider.runCatchingUnlessCancelled
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
 * Swedish local dates. The state is read off the overview's `time` (see
 * [SportalityMapper.overviewState]): `Ongoing` alone means only that the arena has opened
 * the game, up to two hours before the puck drops.
 *
 * `live()` follows the platform's SSE stream when the fetcher can open one ([EventStreamFetcher]),
 * with the REST reads as the snapshot at every connect; otherwise it polls the small overview
 * and reloads play-by-play when the overview moved.
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
    /** The live SSE endpoint; `?gameUuid=` is appended. Null leaves `live()` polling. */
    private val streamUrl: String? = null,
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
    ) + (if (streamUrl != null && fetcher is EventStreamFetcher) setOf(Capability.LIVE_PUSH) else emptySet())

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
                .filter { Instant.parse(it.rawStartDateTime).toLocalDateTime(league.zone).date == date }
                .map(mapper::game)
        }
        val teams = teamsByCode(f.ssgtUuid)
        val now = clock.now()
        val games = header.getValue(key).filter { it.seriesCode == seriesCode }
        return coroutineScope {
            games.map { h ->
                async {
                    // Only games that should be under way get the (tiny) overview call. A `played`
                    // row still reads 0-0 for some minutes after the end (no hockey game ends 0-0),
                    // so it keeps its overview until the result is in.
                    val resultMissing = h.homeTeam.result == 0 && h.awayTeam.result == 0
                    val overview = if ((!h.played || resultMissing) && Instant.parse(h.startDateTime) <= now) overview(h.uuid) else null
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

    override fun live(gameId: String, interval: Duration): Flow<Game> {
        val streamFetcher = fetcher as? EventStreamFetcher
        val url = streamUrl
        return if (streamFetcher != null && url != null) streamingLive(streamFetcher, "$url?gameUuid=$gameId", gameId) else pollingLive(gameId, interval)
    }

    /**
     * The game over the platform's stream (README, "Live updates"). What the stream taught on
     * 2026-09-19 shapes this: it replays nothing and its ids are per broadcaster instance, so
     * every connect starts from the REST snapshot; the instances behind the one host disagree,
     * one kept answering `unknown` heartbeats for a game under way, so `unknown` past the
     * start is a stale instance to leave; a healthy stream can go quiet for 100 s between
     * heartbeats, so a minute of silence is a cross-check against the overview and three are
     * a dead connection; the server resets every stream at once now and then, so a close is
     * a pause and a reconnect, never an error; old frames are re-broadcast with each
     * revision, so a `gameTime` may only move the clock forward. Messages patch the REST
     * view: `liveEvent` rows replace their revisions in the event list (a `deleted` row
     * leaves), `gameTime` is the clock, `liveState` the state; the mapper then reads the
     * game as it does for REST.
     */
    private fun streamingLive(streamFetcher: EventStreamFetcher, url: String, gameId: String): Flow<Game> = flow {
        val budget = LivePollBudget(STREAM_RECONNECT_DELAY)
        var last: Game? = null
        var backoff = STREAM_RECONNECT_DELAY
        while (budget.open) {
            // The snapshot: a failing read here is a pause, not the end of the live view, until
            // enough of them in a row say the feed is gone rather than blinking.
            val snapshot = budget.read {
                val info = gameInfo(gameId)
                Triple(info, overview(gameId), playByPlay(gameId))
            }.getOrNull()
            if (snapshot == null) {
                budget.wait(backoff)
                backoff = minOf(backoff * 2, STREAM_RECONNECT_BACKOFF_MAX)
                continue
            }
            backoff = STREAM_RECONNECT_DELAY
            val (info, snapshotOverview, snapshotRows) = snapshot
            var overview = snapshotOverview
            var raw = snapshotRows
            var current = mapper.game(info, overview, raw)
            if (current != last) { emit(current); last = current }
            if (current.state.isTerminal) return@flow
            val startTime = current.startTime
            var unknownHeartbeats = 0

            consume(
                streamFetcher.events(url),
                onQuiet = quiet@{
                    // A minute without a message: has the overview moved on without us?
                    val fresh = runCatchingUnlessCancelled { overview(gameId) }.getOrNull() ?: return@quiet true
                    val seen = overview ?: return@quiet false
                    fresh.homeGoals == seen.homeGoals && fresh.awayGoals == seen.awayGoals && fresh.time == seen.time &&
                        (fresh.state == "GameEnded") == (seen.state == "GameEnded")
                },
                onMessage = message@{ message ->
                    message.liveState?.let { state ->
                        when (state.liveState) {
                            "unknown" -> if (clock.now() >= startTime + STALE_UNKNOWN_GRACE && ++unknownHeartbeats >= STALE_UNKNOWN_HEARTBEATS) return@message false
                            "ongoing", "overtime" -> overview = (overview ?: blankOverview(gameId)).copy(state = "Ongoing")
                            "intermission" -> overview = (overview ?: blankOverview(gameId)).copy(state = "PeriodBreak")
                            "decided" -> overview = (overview ?: blankOverview(gameId)).copy(state = "GameEnded")
                        }
                    }
                    message.gameTime?.let { time ->
                        // Old frames are re-broadcast with their revisions: within a period the clock only moves forward.
                        val seen = overview?.time
                        val forward = seen == null || time.period > seen.period ||
                            (time.period == seen.period && (mapper.clockDuration(time.periodTime) ?: Duration.ZERO) >= (mapper.clockDuration(seen.periodTime) ?: Duration.ZERO))
                        if (forward) overview = (overview ?: blankOverview(gameId)).copy(time = time)
                    }
                    message.liveEvent?.let { row ->
                        raw = mapper.merge(raw, row)
                        val score = mapper.eventsScore(raw)
                        overview = (overview ?: blankOverview(gameId)).copy(homeGoals = score.home, awayGoals = score.away)
                    }
                    val next = mapper.game(info, overview, raw)
                    if (next != last) { emit(next); last = next }
                    current = next
                    !next.state.isTerminal
                },
            )
            if (current.state.isTerminal) return@flow
            // A stream that keeps closing must not be reconnected to forever, so the pause
            // between connects is charged to the same ceiling a polling provider spends.
            budget.wait(STREAM_RECONNECT_DELAY)
        }
    }

    private fun blankOverview(gameId: String) = SptOverview(gameUuid = gameId, state = "Ongoing")

    /**
     * Feeds [onMessage] until it answers false, the upstream closes or fails, [STREAM_QUIET_TIMEOUT]
     * passes in silence, or [onQuiet] (asked after every [STREAM_CHECK_INTERVAL] of silence) answers
     * false. The reader is a child so a failing connection ends the loop instead of the flow.
     */
    private suspend fun consume(
        stream: Flow<ServerSentEvent>,
        onQuiet: suspend () -> Boolean,
        onMessage: suspend (SptStreamMessage) -> Boolean,
    ): Unit = coroutineScope {
        val events = Channel<ServerSentEvent>(Channel.BUFFERED)
        val reader = launch {
            try {
                stream.collect { events.send(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A reset or a timeout: the caller reconnects from a fresh snapshot.
            } finally {
                events.close()
            }
        }
        try {
            var silence = Duration.ZERO
            while (true) {
                val received = withTimeoutOrNull(STREAM_CHECK_INTERVAL) { events.receiveCatching() }
                if (received == null) {
                    silence += STREAM_CHECK_INTERVAL
                    if (silence >= STREAM_QUIET_TIMEOUT || !onQuiet()) break
                    continue
                }
                val event = received.getOrNull() ?: break
                silence = Duration.ZERO
                val message = runCatching { OpenScoreJson.decodeFromString(SptStreamMessage.serializer(), event.data) }.getOrNull() ?: continue
                if (!onMessage(message)) break
            }
        } finally {
            reader.cancelAndJoin()
        }
    }

    /**
     * `/play-by-play` is 100+ KB while `/game-overview` is small and has the volatile score,
     * period and clock. Keep the event list and pay for play-by-play again only when the
     * overview moved: its `time` is the game time of the latest record, so a change there is
     * exactly a new event (or a period end), and the score and state catch the rest. The edge
     * sometimes answers a live game's overview or play-by-play with an empty body (observed
     * 2026-09-19); such a tick is no news, not a game that has not started.
     */
    private fun pollingLive(gameId: String, interval: Duration): Flow<Game> = flow {
        val budget = LivePollBudget(interval)
        var raw = emptyList<SptEvent>()
        var current: Game? = null
        while (budget.open) {
            val tick = budget.read { gameInfo(gameId) to overview(gameId) }.getOrNull()
            if (tick != null) {
                val (info, overview) = tick
                // An empty overview mid-game is a glitch of the edge, not a game that has not started.
                if (overview != null || current?.state?.hasStarted != true) {
                    var next = mapper.game(info, overview, raw)
                    val moved = current == null || next.score != current.score ||
                        next.state != current.state || next.clock != current.clock
                    if (moved) {
                        raw = budget.read { playByPlay(gameId) }.getOrNull()?.ifEmpty { raw } ?: raw
                        next = mapper.game(info, overview, raw)
                    }
                    if (next != current) emit(next)
                    current = next
                    if (next.state.isTerminal) return@flow
                }
            }
            budget.wait()
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
        val LIVE_MAX_AGE: Duration = 10.seconds
        val TABLE_MAX_AGE: Duration = 5.minutes
        val STATIC_MAX_AGE: Duration = 1.hours
        /** Heartbeats come every ~20 s, with gaps of up to 100 s seen on healthy streams. */
        val STREAM_CHECK_INTERVAL: Duration = 1.minutes
        val STREAM_QUIET_TIMEOUT: Duration = 3.minutes
        val STREAM_RECONNECT_DELAY: Duration = 2.seconds
        val STREAM_RECONNECT_BACKOFF_MAX: Duration = 30.seconds
        /** Games face off two to six minutes after the nominal start; `unknown` beyond this is a stale instance. */
        val STALE_UNKNOWN_GRACE: Duration = 10.minutes
        const val STALE_UNKNOWN_HEARTBEATS: Int = 3
    }
}

/** SHL via `www.shl.se/api`, live over `game-broadcaster.s8y.se` when the fetcher streams. */
public class ShlProvider(fetcher: Fetcher, baseUrl: String = DEFAULT_BASE_URL, clock: Clock = Clock.System, streamUrl: String? = DEFAULT_STREAM_URL) :
    SportalityProvider(LEAGUE, fetcher, baseUrl, seriesCode = "SHL", filterSeries = "shl", clock = clock, streamUrl = streamUrl) {
    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://www.shl.se/api"
        public const val DEFAULT_STREAM_URL: String = "https://game-broadcaster.s8y.se/live/game"
        public val LEAGUE: League = League("shl", Sport.HOCKEY, "SHL", "SE", TimeZone.of("Europe/Stockholm"), "https://www.shl.se")
    }
}
