package org.openscore.providers.sportality

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openscore.model.Game
import org.openscore.model.GameEnding
import org.openscore.model.GameState
import org.openscore.model.LineupGroupKind
import org.openscore.model.PeriodType
import org.openscore.model.Score
import org.openscore.model.hockey.GoalDetails
import org.openscore.model.hockey.HockeyEventType
import org.openscore.model.hockey.PenaltyDetails
import org.openscore.model.hockey.ShootoutAttemptDetails
import org.openscore.model.hockey.Strength
import org.openscore.net.EventStreamFetcher
import org.openscore.net.FetchResponse
import org.openscore.net.Fetcher
import org.openscore.net.HttpException
import org.openscore.net.OpenScoreJson
import org.openscore.net.ServerSentEvent
import org.openscore.provider.Capability
import org.openscore.provider.MAX_CONSECUTIVE_FAILURES
import org.openscore.provider.NotFoundException
import org.openscore.testing.SampleFetcher
import org.openscore.testing.SportalitySamples
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Replay coverage for the Sportality provider still used by SHL. */
abstract class SportalityProviderTestBase(
    private val samples: SportalitySamples,
    private val leagueId: String,
    private val expectedTeams: Int,
    private val finalHomeName: String,
    private val finalScore: Score,
    private val playerName: String,
) {
    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-09-11T10:00:00Z")
    }
    protected val fetcher = samples.register(SampleFetcher())
    protected abstract val provider: SportalityProvider
    protected abstract fun providerWith(fetcher: Fetcher, clock: Clock): SportalityProvider
    protected fun providerAt(clock: Clock): SportalityProvider = providerWith(fetcher, clock)
    protected val clock: Clock get() = fixedClock

    /** GETs from the samples, SSE from a script: one flow per connection, in order. */
    protected class StreamingSampleFetcher(private val delegate: SampleFetcher, private val connections: List<Flow<ServerSentEvent>>) : EventStreamFetcher {
        var opened: Int = 0
            private set
        val urls = mutableListOf<String>()
        override suspend fun get(url: String, headers: Map<String, String>, maxAge: Duration): FetchResponse = delegate.get(url, headers, maxAge)
        override fun events(url: String, headers: Map<String, String>): Flow<ServerSentEvent> {
            urls += url
            return connections.getOrNull(opened++) ?: error("no scripted connection #$opened")
        }
    }

    protected fun frame(json: String) = ServerSentEvent(data = json)
    protected fun liveState(state: String, previous: String? = null) =
        frame("""{"liveState":{"gameUuid":"x","liveState":"$state","updated":${previous != null}${previous?.let { ""","previousLiveState":"$it"""" } ?: ""}${if (state == "decided") ""","gameState":"GameEnded"""" else ""}}}""")
    protected fun gameTime(period: Int, time: String) = frame("""{"gameTime":{"gameUuid":"x","period":$period,"periodTime":"$time"}}""")
    protected fun liveEvent(json: String) = frame("""{"liveEvent":$json}""")

    private fun sample(name: String): String {
        val text = File(SampleFetcher.samplesDir("hockey", samples.sampleDir), name).readText()
        return if (text.contains("\"_truncated_array\"")) (OpenScoreJson.parseToJsonElement(text) as JsonObject)["_truncated_array"].toString() else text
    }

    @Test
    fun everySampleParses() {
        val strategies: List<Pair<String, DeserializationStrategy<*>>> = listOf(
            "season-series-game-types-filter" to SptFilter.serializer(),
            "gameheader" to MapSerializer(String.serializer(), ListSerializer(SptHeaderGame.serializer())),
            "game-schedule" to SptSchedule.serializer(),
            "game-info." to SptGameInfoResponse.serializer(),
            "game-overview" to SptOverview.serializer(),
            "play-by-play" to ListSerializer(SptEvent.serializer()),
            "boxscore" to SptBoxscore.serializer(),
            "league-standings" to SptStandings.serializer(),
            "all-teams" to ListSerializer(SptTeam.serializer()),
            "athletes-by-team" to ListSerializer(SptAthleteGroup.serializer()),
            "athlete-profile-page" to SptProfilePage.serializer(),
        )
        val failures = mutableListOf<String>()
        var typed = 0
        for (file in SampleFetcher.samplesDir("hockey", samples.sampleDir).listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }) {
            val text = sample(file.name)
            val strategy = strategies.firstOrNull { (p, _) -> file.name.startsWith(p) }?.second
            try {
                if (strategy != null) { OpenScoreJson.decodeFromString(strategy, text); typed++ } else OpenScoreJson.decodeFromString(JsonElement.serializer(), text)
            } catch (e: Exception) {
                failures += "${file.name}: ${e.message?.lineSequence()?.first()}"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
        assertTrue(typed >= 13, "typed=$typed")
    }

    @Test
    fun scoreboardFromGameheader() = runTest {
        val games = provider.gamesOn(LocalDate.parse(samples.headerDate))
        assertEquals(7, games.size)
        val g = games.first()
        assertEquals(leagueId, g.leagueId)
        assertEquals(samples.preGameId, g.id)
        assertEquals(GameState.SCHEDULED, g.state)
        assertNull(g.score)
        assertTrue(g.home.id.contains('-') || g.home.id.length == 10, "team id resolved to the platform UUID: ${g.home.id}")
        assertNotNull(g.home.abbreviation)
        assertEquals(samples.ssgtCurrent, g.seasonId)
        assertTrue(fetcher.requests.none { it.contains("game-overview") }, "no overview calls for games that have not started")
        assertTrue(games.all { it.home.id != it.home.abbreviation }, "every gameheader team resolved through all-teams")
    }

    @Test
    fun scoreboardOutsideHeaderWindowUsesSchedule() = runTest {
        val games = provider.gamesOn(LocalDate(2026, 10, 3))
        // The truncated schedule sample only holds the first games; the point is the fallback path and the mapping.
        assertTrue(fetcher.requests.any { it.contains("game-schedule") })
        assertTrue(games.all { it.state == GameState.SCHEDULED && it.score == null })
    }

    @Test
    fun finalGame() = runTest {
        val g = provider.game(samples.finalGameId)
        assertEquals(GameState.FINAL, g.state)
        assertEquals(GameEnding.REGULATION, g.ending)
        assertEquals(finalHomeName, g.home.name)
        assertEquals(finalScore, g.score)
        assertEquals(listOf("1", "2", "3"), g.periodScores.map { it.period.label })
        assertEquals(finalScore.home, g.periodScores.sumOf { it.home })
        assertEquals(finalScore.away, g.periodScores.sumOf { it.away })
        assertNull(g.clock)
        val events = assertNotNull(g.events)
        assertTrue(events.size > 50)
        assertEquals(events.sortedWith(compareBy({ it.period.number }, { it.time.elapsed })), events, "chronological, oldest first")
        assertEquals(HockeyEventType.PERIOD_START, events.first().type)
        assertEquals(HockeyEventType.PERIOD_END, events.last().type)
        val goals = events.filter { it.type == HockeyEventType.GOAL }
        assertEquals(finalScore.home + finalScore.away, goals.size)
        assertTrue(goals.all { it.score != null && it.coordinates != null && it.players.isNotEmpty() })
        val gd = assertIs<GoalDetails>(goals.first().details)
        assertNotNull(gd.strength)
        val penalty = events.first { it.type == HockeyEventType.PENALTY && it.players.isNotEmpty() }
        val pd = assertIs<PenaltyDetails>(penalty.details)
        assertEquals(2, pd.minutes)
        assertNotNull(pd.infraction)
        assertEquals("Minor", pd.severity)
        assertTrue(events.any { it.type == HockeyEventType.SHOT })
        assertTrue(events.any { it.type == HockeyEventType.GOALIE_CHANGE })
    }

    @Test
    fun preGameHasEmptyBodies() = runTest {
        val g = provider.game(samples.preGameId)
        assertEquals(GameState.SCHEDULED, g.state)
        assertNull(g.score)
        assertNull(g.clock)
        assertEquals(emptyList(), g.events)
        assertTrue(g.periodScores.isEmpty())
        assertTrue(provider.lineups(samples.preGameId).isEmpty())
    }

    @Test
    fun shootoutEvents() {
        val info = OpenScoreJson.decodeFromString(SptGameInfoResponse.serializer(), sample("game-info.final.json"))
        val pbp = OpenScoreJson.decodeFromString(ListSerializer(SptEvent.serializer()), sample("play-by-play.final-shootout.json"))
        val mapper = SportalityMapper(leagueId)
        val overview = SptOverview(gameUuid = samples.shootoutGameId, homeGoals = pbp.first().homeGoals ?: 0, awayGoals = 0, state = "GameEnded", time = SptOverviewTime(99, "00:00"))
        val g = mapper.game(info.copy(gameInfo = info.gameInfo.copy(gameUuid = samples.shootoutGameId, shootout = true, overtime = true)), overview, pbp)
        assertEquals(GameEnding.SHOOTOUT, g.ending)
        assertEquals(listOf("1", "2", "3", "OT", "SO"), g.periodScores.map { it.period.label })
        assertEquals(PeriodType.SHOOTOUT, g.periodScores.last().period.type)
        val events = assertNotNull(g.events)
        val attempts = events.filter { it.type == HockeyEventType.SHOOTOUT_ATTEMPT }
        assertTrue(attempts.size >= 6)
        assertTrue(attempts.any { assertIs<ShootoutAttemptDetails>(it.details).scored })
        assertTrue(events.none { it.type == HockeyEventType.GOAL && it.period.type == PeriodType.SHOOTOUT }, "shootout goals are attempts, not goals")
        assertEquals(5, attempts.first().period.number)
    }

    @Test
    fun liveClockFromOverview() {
        val mapper = SportalityMapper(leagueId)
        val info = OpenScoreJson.decodeFromString(SptGameInfoResponse.serializer(), sample("game-info.final.json"))
        val live = SptOverview(gameUuid = "x", homeGoals = 1, awayGoals = 0, state = "Ongoing", time = SptOverviewTime(2, "12:34"))
        val g = mapper.game(info, live, emptyList())
        assertEquals(GameState.LIVE, g.state)
        assertEquals(Score(1, 0), g.score)
        val clock = assertNotNull(g.clock)
        assertEquals("2", clock.period.label)
        assertEquals(12.minutes + 34.seconds, clock.time.elapsed)
        assertEquals(7.minutes + 26.seconds, clock.time.remaining)
        assertNull(clock.running)
        assertEquals(GameState.INTERMISSION, mapper.game(info, live.copy(state = "PeriodBreak"), emptyList()).state)
        assertEquals("SO", mapper.game(info, live.copy(time = SptOverviewTime(99, "00:00")), emptyList()).clock!!.period.label)
    }

    private fun overview(name: String) = OpenScoreJson.decodeFromString(SptOverview.serializer(), sample(name))
    private fun playByPlay(name: String) = OpenScoreJson.decodeFromString(ListSerializer(SptEvent.serializer()), sample(name))
    private fun gameInfo(name: String) = OpenScoreJson.decodeFromString(SptGameInfoResponse.serializer(), sample(name))

    /** The overview turns `Ongoing` with an empty `time` about two hours before the puck drops (2026-09-19, 14:00Z for a 16:00Z game). */
    @Test
    fun ongoingWithoutATimeIsPreGame() {
        val mapper = SportalityMapper(leagueId)
        val pregame = overview("game-overview.pregame.json")
        assertEquals("Ongoing", pregame.state)
        assertEquals(GameState.PRE_GAME, mapper.overviewState(pregame))
        val g = mapper.game(gameInfo("game-info.pre.json"), pregame, playByPlay("play-by-play.pregame.json"))
        assertEquals(GameState.PRE_GAME, g.state)
        assertNull(g.score, "0-0 before the game is no score")
        assertNull(g.clock, "no period 0 clock")
        assertEquals(emptyList(), g.events)
        assertTrue(g.periodScores.isEmpty())
        val header = OpenScoreJson.decodeFromString(MapSerializer(String.serializer(), ListSerializer(SptHeaderGame.serializer())), sample("gameheader.json"))
            .getValue(samples.headerDate).first { it.seriesCode == samples.filterSeries.uppercase() }
        val row = mapper.game(header, emptyMap(), pregame)
        assertEquals(GameState.PRE_GAME, row.state)
        assertNull(row.score)
        assertNull(row.clock)
    }

    /** Intermissions are never `PeriodBreak`: the clock sits at 20:00 and the period record says `finished` (2026-09-19, four games). */
    @Test
    fun intermissionFromTheClockAndThePeriodRecords() {
        val mapper = SportalityMapper(leagueId)
        val info = gameInfo("game-info.live.json")
        assertEquals("pre_game", info.gameInfo.state, "game-info never says live")
        val overview = overview("game-overview.intermission.json")
        assertEquals("Ongoing", overview.state)
        val events = playByPlay("play-by-play.intermission.json")
        val g = mapper.game(info, overview, events)
        assertEquals(GameState.INTERMISSION, g.state)
        val clock = assertNotNull(g.clock)
        assertEquals("1", clock.period.label, "the break is named after the period just played")
        assertEquals(20.minutes, clock.time.elapsed)
        assertEquals(Duration.ZERO, clock.time.remaining)
        assertEquals(Score(2, 1), g.score)
        assertEquals(listOf(PeriodScoreCheck("1", 2, 1)), g.periodScores.map { PeriodScoreCheck(it.period.label, it.home, it.away) })
        assertEquals(GameState.INTERMISSION, mapper.eventsState(events))
        assertEquals(GameState.LIVE, mapper.eventsState(playByPlay("play-by-play.live.json")))
        // A row entered after the period ended moves the overview's clock back; the period records still say break.
        assertEquals(GameState.INTERMISSION, mapper.game(info, overview.copy(time = SptOverviewTime(1, "19:55")), events).state)
        assertEquals(GameState.LIVE, mapper.game(info, overview.copy(time = SptOverviewTime(1, "19:55")), emptyList()).state, "without records the clock decides")
        assertEquals(GameState.LIVE, mapper.game(info, overview("game-overview.live.json"), playByPlay("play-by-play.live.json")).state)
        assertEquals(GameState.FINAL, mapper.eventsState(playByPlay("play-by-play.final.json")))
        assertEquals(GameState.PRE_GAME, mapper.eventsState(listOf(SptEvent(type = "shot"))), "records but no period started")
        assertNull(mapper.eventsState(emptyList()))
        // Overtime is five minutes long.
        assertEquals(GameState.INTERMISSION, mapper.overviewState(overview.copy(time = SptOverviewTime(4, "05:00"))))
        assertEquals(GameState.LIVE, mapper.overviewState(overview.copy(time = SptOverviewTime(4, "04:59"))))
        assertEquals(GameState.LIVE, mapper.overviewState(overview.copy(time = SptOverviewTime(99, "00:00"))), "the shootout has no length")
        // A third period at its full length: over with a lead, a break before overtime when tied (`GameEnded` comes minutes later).
        assertEquals(GameState.FINAL, mapper.overviewState(overview.copy(time = SptOverviewTime(3, "20:00"))))
        assertEquals(GameState.INTERMISSION, mapper.overviewState(overview.copy(time = SptOverviewTime(3, "20:00"), homeGoals = 2, awayGoals = 2)))
        assertEquals(GameState.LIVE, mapper.overviewState(overview.copy(time = SptOverviewTime(3, "19:59"))))
    }

    /**
     * HV71-MIF 2026-09-19, decided 4-5 in overtime: the play-by-play rows said `GameEnded`
     * within seconds, the header's `played` a minute later (with 0-0 and no overtime flag for
     * five minutes more), the overview stayed `Ongoing` at OT 00:00.
     */
    @Test
    fun finalBeforeTheOverviewSaysSo() {
        val mapper = SportalityMapper(leagueId)
        val info = gameInfo("game-info.live.json")
        val overview = overview("game-overview.overtime-decided.json")
        assertEquals("Ongoing", overview.state)
        val events = playByPlay("play-by-play.final-overtime.json")
        val g = mapper.game(info, overview, events)
        assertEquals(GameState.FINAL, g.state)
        assertEquals(Score(4, 5), g.score)
        assertEquals(GameEnding.OVERTIME, g.ending, "from the period, not the flags")
        assertNull(g.clock)
        assertEquals(listOf("1", "2", "3", "OT"), g.periodScores.map { it.period.label })
        assertEquals(PeriodScoreCheck("OT", 0, 1), g.periodScores.last().let { PeriodScoreCheck(it.period.label, it.home, it.away) })
        val goals = assertNotNull(g.events).filter { it.type == HockeyEventType.GOAL }
        assertEquals(9, goals.size)
        assertEquals(PeriodType.OVERTIME, goals.last().period.type)

        val header = OpenScoreJson.decodeFromString(MapSerializer(String.serializer(), ListSerializer(SptHeaderGame.serializer())), sample("gameheader.json"))
            .getValue(samples.headerDate).first { it.seriesCode == samples.filterSeries.uppercase() }
        val played = header.copy(played = true, homeTeam = header.homeTeam.copy(result = 0), awayTeam = header.awayTeam.copy(result = 0))
        val row = mapper.game(played, emptyMap(), overview)
        assertEquals(GameState.FINAL, row.state)
        assertEquals(Score(4, 5), row.score, "the overview's score, not the header's 0-0")
        assertEquals(GameEnding.OVERTIME, row.ending)
        assertNull(row.clock)
        assertEquals(GameState.LIVE, mapper.game(played.copy(played = false), emptyMap(), overview).state, "the overview alone still says on")
    }

    /** The day listing keeps reading a `played` row's overview while the header result is 0-0. */
    @Test
    fun scoreboardReadsTheOverviewUntilAPlayedRowHasItsResult() = runTest {
        val dir = SampleFetcher.samplesDir("hockey", samples.sampleDir)
        val header = OpenScoreJson.parseToJsonElement(sample("gameheader.json")) as JsonObject
        val day = header.getValue(samples.headerDate).jsonArray
        val edited = JsonArray(day.map { g ->
            val o = g.jsonObject
            if (o.getValue("uuid").jsonPrimitive.content != samples.preGameId) o else JsonObject(o + ("played" to JsonPrimitive(true)))
        })
        val file = File.createTempFile("gameheader", ".json").apply { deleteOnExit(); writeText(JsonObject(header + (samples.headerDate to edited)).toString()) }
        fetcher.route("${samples.baseUrl}/gameday/gameheader", file)
        fetcher.route("${samples.baseUrl}/gameday/game-overview/${samples.preGameId}", File(dir, "game-overview.overtime-decided.json"))
        for (g in day) fetcher.takeIf { g.jsonObject.getValue("uuid").jsonPrimitive.content != samples.preGameId }?.emptyRoute("${samples.baseUrl}/gameday/game-overview/${g.jsonObject.getValue("uuid").jsonPrimitive.content}")
        val later = object : Clock { override fun now(): Instant = Instant.parse("2026-09-19T20:00:00Z") }
        val games = providerAt(later).gamesOn(LocalDate.parse(samples.headerDate))
        val g = games.first { it.id == samples.preGameId }
        assertEquals(GameState.FINAL, g.state)
        assertEquals(Score(4, 5), g.score)
        assertEquals(GameEnding.OVERTIME, g.ending)
        assertEquals(1, fetcher.requests.count { it.endsWith("/game-overview/${samples.preGameId}") })
        assertEquals(games.size, fetcher.requests.count { it.contains("/game-overview/") }, "every row past its start time is read (this clock is past all of them)")
        assertTrue(games.filter { it.id != samples.preGameId }.all { it.state == GameState.SCHEDULED && it.score == null }, "an empty overview after the start is still nothing")
        samples.register(fetcher)
    }

    /** The edge answers a live game's overview or play-by-play with `200` and no body now and then (2026-09-19 13:33Z). */
    @Test
    fun emptyBodiesMidGameAreNoNews() {
        val mapper = SportalityMapper(leagueId)
        val info = gameInfo("game-info.live.json")
        val events = playByPlay("play-by-play.live.json")
        val overview = overview("game-overview.live.json")
        val full = mapper.game(info, overview, events)
        assertEquals(GameState.LIVE, full.state)
        assertEquals(Score(2, 2), full.score)

        val noOverview = mapper.game(info, null, events)
        assertEquals(GameState.LIVE, noOverview.state, "not back to scheduled")
        assertEquals(Score(2, 2), noOverview.score, "the newest goal carries the running score")
        assertNull(noOverview.clock)
        assertEquals(full.events, noOverview.events)
        assertEquals(full.periodScores, noOverview.periodScores)

        val noEvents = mapper.game(info, overview, emptyList())
        assertEquals(GameState.LIVE, noEvents.state)
        assertEquals(Score(2, 2), noEvents.score)
        assertNull(noEvents.events, "unknown, not none")
        assertTrue(noEvents.periodScores.isEmpty(), "no zeros next to a 2-2")
    }

    /** FBK-ÖRE 2026-09-19 16:16Z: the overview said 1-0 fifteen seconds before the goal row was in the play-by-play. */
    @Test
    fun linescoreFollowsTheScoreBeforeTheGoalRowLands() {
        val mapper = SportalityMapper(leagueId)
        val info = gameInfo("game-info.live.json")
        val overview = overview("game-overview.live.json")
        val events = playByPlay("play-by-play.live.json")
        val newest = events.filter { it.type == "goal" }.maxBy { it.eventId!! }
        assertEquals(2, newest.period)
        val without = events.filter { it !== newest }
        val g = mapper.game(info, overview, without)
        assertEquals(Score(2, 2), g.score)
        assertEquals(listOf(PeriodScoreCheck("1", 1, 1), PeriodScoreCheck("2", 1, 1)), g.periodScores.map { PeriodScoreCheck(it.period.label, it.home, it.away) })
        assertEquals(3, assertNotNull(g.events).count { it.type == HockeyEventType.GOAL })
        // A tie-breaking shootout goal is not credited to a period.
        val decided = mapper.game(info, overview.copy(homeGoals = 3, time = SptOverviewTime(99, "00:00")), without)
        assertEquals(listOf(1, 1, 0, 0), decided.periodScores.map { it.home })
    }

    @Test
    fun insightsAndDeletedRowsAreSkipped() {
        val mapper = SportalityMapper(leagueId)
        val info = gameInfo("game-info.live.json")
        val raw = playByPlay("play-by-play.live.json")
        assertTrue(raw.any { it.type == "insight" })
        val insight = raw.first { it.type == "insight" }
        assertTrue(raw.any { it.type == "goal" && it.eventId == insight.eventId }, "the insight reuses its goal's eventId")
        val home = mapper.teamRef(info.homeTeam)
        val away = mapper.teamRef(info.awayTeam)
        val events = mapper.events(raw, home, away)
        assertTrue(events.none { it.rawType == "insight" })
        assertEquals(events.size, events.map { it.id }.distinct().size, "ids are unique")
        val goals = events.filter { it.type == HockeyEventType.GOAL }
        assertEquals(4, goals.size)
        assertEquals(Score(2, 2), goals.last().score)
        val deleted = raw.map { if (it.type == "goal") it.copy(deleted = true) else it }
        assertTrue(mapper.events(deleted, home, away).none { it.type == HockeyEventType.GOAL })
    }

    /**
     * The live flow keeps its event list and the linescore between ticks, re-reads play-by-play
     * when the overview's clock moved (its time is the latest record's), and treats an empty
     * overview as no news.
     */
    @Test
    fun liveKeepsEventsAndReloadsThemWhenTheClockMoves() = runTest {
        val dir = SampleFetcher.samplesDir("hockey", samples.sampleDir)
        val overviewUrl = "${samples.baseUrl}/gameday/game-overview/${samples.liveGameId}"
        val playByPlayUrl = "${samples.baseUrl}/gameday/play-by-play/${samples.liveGameId}"
        fun playByPlayReads() = fetcher.requests.count { it == playByPlayUrl }
        val emitted = mutableListOf<Game>()
        val job = launch { provider.live(samples.liveGameId).collect { emitted += it } }
        runCurrent()
        assertEquals(1, emitted.size)
        val first = emitted.single()
        assertEquals(GameState.LIVE, first.state)
        assertEquals(Score(2, 2), first.score)
        assertEquals(listOf(PeriodScoreCheck("1", 1, 1), PeriodScoreCheck("2", 1, 1)), first.periodScores.map { PeriodScoreCheck(it.period.label, it.home, it.away) })
        val firstEvents = assertNotNull(first.events)
        assertEquals(1, playByPlayReads())

        // Same bodies again: nothing to say, and no play-by-play read.
        advanceTimeBy(11.seconds); runCurrent()
        assertEquals(1, emitted.size)
        assertEquals(1, playByPlayReads())

        // The clock moved (two more shots, same score): play-by-play is read once, the linescore stays right.
        fetcher.route(overviewUrl, File(dir, "game-overview.live-later.json"))
        fetcher.route(playByPlayUrl, File(dir, "play-by-play.live-later.json"))
        advanceTimeBy(11.seconds); runCurrent()
        assertEquals(2, emitted.size)
        val second = emitted.last()
        assertEquals(2, playByPlayReads())
        assertEquals(Score(2, 2), second.score)
        assertEquals(first.periodScores, second.periodScores)
        assertTrue(assertNotNull(second.events).size > firstEvents.size)
        assertTrue(second.clock!!.time.elapsed!! > first.clock!!.time.elapsed!!)

        // An empty overview mid-game: the tick is skipped, nothing flips to scheduled.
        fetcher.emptyRoute(overviewUrl)
        advanceTimeBy(11.seconds); runCurrent()
        assertEquals(2, emitted.size)
        assertEquals(2, playByPlayReads())
        job.cancel()
    }

    // ---- the stream ----------------------------------------------------------------------

    private val streamClock = object : Clock { override fun now(): Instant = Instant.parse("2026-09-19T14:30:00Z") }
    private val streamedGoal = """{"gameUuid":"x","eventId":900,"eventUuid":"test-goal-900","period":2,"time":"10:00","gameState":"Ongoing","type":"goal","homeGoals":3,"awayGoals":2,"goalStatus":"EQ","revision":1,"eventTeam":{"teamId":"FHC","place":"home","teamCode":"FHC","teamName":"Frölunda HC"},"player":{"playerId":"1","firstName":"Test","familyName":"Scorer","jerseyToday":"9"},"locationX":100,"locationY":50}"""
    private val periodTwoOver = """{"gameUuid":"x","gameId":1,"period":2,"started":true,"startedAt":"2026-09-19T16:07:03.073Z","finished":true,"finishedAt":"2026-09-19T16:40:10.066Z","type":"period"}"""

    @Test
    fun recordedStreamFramesDecode() {
        val text = File(SampleFetcher.samplesDir("hockey", samples.sampleDir), "sse.live-game.txt").readText()
        val frames = text.lineSequence().filter { it.startsWith("data: ") }.map { OpenScoreJson.decodeFromString(SptStreamMessage.serializer(), it.removePrefix("data: ")) }.toList()
        assertEquals(11, frames.size)
        assertEquals(3, frames.count { it.liveState != null })
        assertEquals(5, frames.count { it.liveEvent != null })
        assertEquals(1, frames.count { it.gameTime != null })
        assertEquals(setOf("shot", "goal", "insight", "penalty", "period"), frames.mapNotNull { it.liveEvent?.type }.toSet())
        assertTrue(frames.count { it.liveState == null && it.liveEvent == null && it.gameTime == null } == 2, "statistics frames decode to nothing")
        val transition = frames.first { it.liveState?.updated == true }.liveState!!
        assertEquals("ongoing", transition.previousLiveState)
    }

    @Test
    fun streamingCapabilityFollowsTheFetcher() {
        assertTrue(!provider.supports(Capability.LIVE_PUSH), "a plain fetcher polls")
        assertTrue(providerWith(StreamingSampleFetcher(fetcher, emptyList()), clock).supports(Capability.LIVE_PUSH))
    }

    @Test
    fun streamPatchesTheSnapshotUntilTheGameIsDecided() = runTest {
        val streaming = StreamingSampleFetcher(fetcher, listOf(flowOf(
            gameTime(2, "09:31"),
            gameTime(2, "08:00"), // a re-broadcast old frame: ignored
            liveEvent(streamedGoal),
            liveState("intermission", previous = "ongoing"),
            liveEvent(periodTwoOver),
            liveState("decided", previous = "intermission"),
        )))
        val emitted = mutableListOf<Game>()
        providerWith(streaming, streamClock).live(samples.liveGameId).collect { emitted += it }
        assertEquals(listOf("${ShlProvider.DEFAULT_STREAM_URL}?gameUuid=${samples.liveGameId}"), streaming.urls)
        assertEquals(listOf(GameState.LIVE, GameState.LIVE, GameState.LIVE, GameState.INTERMISSION, GameState.INTERMISSION, GameState.FINAL), emitted.map { it.state })
        val snapshot = emitted[0]; val clockMoved = emitted[1]; val goal = emitted[2]; val pause = emitted[3]; val final = emitted[5]
        assertEquals(assertNotNull(pause.events).size + 1, assertNotNull(emitted[4].events).size, "the period record adds its end event")
        assertEquals(Score(2, 2), snapshot.score)
        assertEquals(9.minutes + 31.seconds, clockMoved.clock!!.time.elapsed)
        assertEquals(Score(3, 2), goal.score)
        assertEquals(listOf(PeriodScoreCheck("1", 1, 1), PeriodScoreCheck("2", 2, 1)), goal.periodScores.map { PeriodScoreCheck(it.period.label, it.home, it.away) })
        assertEquals(assertNotNull(snapshot.events).size + 1, assertNotNull(goal.events).size)
        assertEquals("Test Scorer", goal.events!!.last { it.type == HockeyEventType.GOAL }.players.first().name)
        assertEquals("2", pause.clock!!.period.label)
        assertEquals(Score(3, 2), final.score)
        assertEquals(GameEnding.REGULATION, final.ending)
        assertEquals(1, fetcher.requests.count { it.contains("/play-by-play/") }, "the snapshot's read; the stream carries the rest")
    }

    @Test
    fun streamReconnectsFromAFreshSnapshotAfterAClose() = runTest {
        val streaming = StreamingSampleFetcher(fetcher, listOf(
            flowOf(gameTime(2, "09:31")),
            flowOf(liveEvent(streamedGoal), liveState("decided", previous = "ongoing")),
        ))
        val emitted = mutableListOf<Game>()
        providerWith(streaming, streamClock).live(samples.liveGameId).collect { emitted += it }
        assertEquals(2, streaming.opened)
        assertEquals(2, fetcher.requests.count { it.contains("/play-by-play/") }, "a snapshot per connect: the stream replays nothing")
        assertEquals(GameState.FINAL, emitted.last().state)
        assertEquals(Score(3, 2), emitted.last().score)
        // Snapshot, the streamed clock, the second snapshot (the sample's earlier clock again), the goal, the end.
        assertEquals(listOf(Score(2, 2), Score(2, 2), Score(2, 2), Score(3, 2), Score(3, 2)), emitted.map { it.score })
        assertEquals(listOf(7.minutes + 12.seconds, 9.minutes + 31.seconds, 7.minutes + 12.seconds), emitted.take(3).map { it.clock!!.time.elapsed })
    }

    @Test
    fun staleUnknownInstanceIsLeftOnceTheGameShouldBeOn() = runTest {
        val streaming = StreamingSampleFetcher(fetcher, listOf(
            flowOf(liveState("unknown"), liveState("unknown"), liveState("unknown"), liveState("unknown")),
            flowOf(liveState("ongoing", previous = "unknown"), liveState("decided", previous = "ongoing")),
        ))
        val emitted = mutableListOf<Game>()
        providerWith(streaming, streamClock).live(samples.liveGameId).collect { emitted += it }
        assertEquals(2, streaming.opened, "three unknown heartbeats past the start: left")
        assertEquals(GameState.FINAL, emitted.last().state)

        // Before the start, unknown is what the platform says: stay.
        val early = object : Clock { override fun now(): Instant = Instant.parse("2026-09-19T13:05:00Z") }
        val patient = StreamingSampleFetcher(fetcher, listOf(flowOf(liveState("unknown"), liveState("unknown"), liveState("unknown"), liveState("unknown"), liveState("decided"))))
        providerWith(patient, early).live(samples.liveGameId).collect {}
        assertEquals(1, patient.opened)
    }

    @Test
    fun quietStreamIsCrossCheckedThenDropped() = runTest {
        val dir = SampleFetcher.samplesDir("hockey", samples.sampleDir)
        val overviewUrl = "${samples.baseUrl}/gameday/game-overview/${samples.liveGameId}"
        val silent = flow<ServerSentEvent> { delay(30.minutes) }
        val streaming = StreamingSampleFetcher(fetcher, listOf(silent, silent, flowOf(liveState("decided"))))
        val emitted = mutableListOf<Game>()
        val job = launch { providerWith(streaming, streamClock).live(samples.liveGameId).collect { emitted += it } }
        runCurrent()
        assertEquals(1, streaming.opened)
        assertEquals(1, fetcher.requests.count { it == overviewUrl })
        advanceTimeBy(61.seconds); runCurrent()
        assertEquals(2, fetcher.requests.count { it == overviewUrl }, "a minute of silence: one cross-check")
        assertEquals(1, streaming.opened, "the overview had not moved: stay")
        advanceTimeBy(125.seconds); runCurrent()
        assertEquals(2, streaming.opened, "three minutes of silence: reconnected")
        assertEquals(1, emitted.size, "nothing new to say")
        // On the second connection the overview moves on without the stream: dropped at the first check.
        fetcher.route(overviewUrl, File(dir, "game-overview.live-later.json"))
        fetcher.route("${samples.baseUrl}/gameday/play-by-play/${samples.liveGameId}", File(dir, "play-by-play.live-later.json"))
        advanceTimeBy(61.seconds); runCurrent()
        assertEquals(3, streaming.opened)
        advanceUntilIdle()
        assertEquals(GameState.FINAL, emitted.last().state)
        assertTrue(emitted[1].clock!!.time.elapsed!! > emitted[0].clock!!.time.elapsed!!, "the fresh snapshot carried the later clock")
        job.cancel()
        samples.register(fetcher)
    }

    @Test
    fun streamSnapshotFailureIsAPauseNotTheEnd() = runTest {
        val overviewUrl = "${samples.baseUrl}/gameday/game-overview/${samples.liveGameId}"
        val streaming = StreamingSampleFetcher(fetcher, listOf(flowOf(liveState("decided"))))
        fetcher.route(overviewUrl, File(SampleFetcher.samplesDir("hockey", samples.sampleDir), "game-info.not-found.json"), status = 500)
        val emitted = mutableListOf<Game>()
        val job = launch { providerWith(streaming, streamClock).live(samples.liveGameId).collect { emitted += it } }
        runCurrent()
        assertEquals(0, streaming.opened, "no stream without a snapshot")
        advanceTimeBy(3.seconds); runCurrent()
        assertTrue(fetcher.requests.count { it == overviewUrl } >= 2, "retried")
        samples.register(fetcher)
        advanceTimeBy(10.seconds); runCurrent()
        advanceUntilIdle()
        assertEquals(1, streaming.opened)
        assertEquals(GameState.FINAL, emitted.last().state)
        job.cancel()
    }

    /**
     * The polling path used to end the whole flow on one failed read, and
     * `MatchViewModel.followLive` deliberately swallows a live failure rather than showing an
     * error - so a single timeout froze the match screen on a stale score for as long as it
     * stayed open, with nothing shown. Now it is one missed look.
     */
    @Test
    fun aFailedPollIsAMissedLookNotTheEndOfTheGame() = runTest {
        val dir = SampleFetcher.samplesDir("hockey", samples.sampleDir)
        val overviewUrl = "${samples.baseUrl}/gameday/game-overview/${samples.liveGameId}"
        val playByPlayUrl = "${samples.baseUrl}/gameday/play-by-play/${samples.liveGameId}"
        val emitted = mutableListOf<Game>()
        val job = launch { provider.live(samples.liveGameId).collect { emitted += it } }
        runCurrent()
        assertEquals(1, emitted.size)
        val first = emitted.single()

        // One bad tick, which the collector must not see as the end.
        fetcher.route(overviewUrl, File(dir, "game-overview.live.json"), status = 500)
        advanceTimeBy(11.seconds); runCurrent()
        assertEquals(1, emitted.size, "a failed read is not a change")
        assertTrue(job.isActive, "one bad read does not end the live view")

        // The feed comes back and the game goes on being reported.
        fetcher.route(overviewUrl, File(dir, "game-overview.live-later.json"))
        fetcher.route(playByPlayUrl, File(dir, "play-by-play.live-later.json"))
        advanceTimeBy(11.seconds); runCurrent()
        assertEquals(2, emitted.size, "the tick after the failure is reported normally")
        assertTrue(emitted.last().clock!!.time.elapsed!! > first.clock!!.time.elapsed!!)
        job.cancel()
    }

    /**
     * A feed that is simply gone must not be polled in silence forever: five failures in a row
     * are no longer transient, and the last one is thrown so the collector finally hears about it.
     */
    @Test
    fun aFeedThatStaysDownIsGivenUpOn() = runTest {
        val dir = SampleFetcher.samplesDir("hockey", samples.sampleDir)
        val overviewUrl = "${samples.baseUrl}/gameday/game-overview/${samples.liveGameId}"
        val emitted = mutableListOf<Game>()
        var thrown: Throwable? = null
        val job = launch {
            try {
                provider.live(samples.liveGameId).collect { emitted += it }
            } catch (e: Exception) {
                thrown = e
            }
        }
        runCurrent()
        assertEquals(1, emitted.size)

        fetcher.route(overviewUrl, File(dir, "game-overview.live.json"), status = 500)

        // The first few failures are ridden out rather than reported: this is the half that a
        // flow which dies on its first bad read would get wrong.
        repeat(MAX_CONSECUTIVE_FAILURES - 2) { advanceTimeBy(11.seconds); runCurrent() }
        assertNull(thrown, "a handful of failures in a row is still just a bad minute")
        assertTrue(job.isActive)

        // Enough of them in a row and the last one is thrown.
        repeat(3) { advanceTimeBy(11.seconds); runCurrent() }
        advanceUntilIdle()
        assertIs<HttpException>(assertNotNull(thrown), "the failure is reported, not swallowed")
        assertEquals(1, emitted.size, "nothing was emitted after the feed went down")
        assertTrue(job.isCompleted)
    }

    private data class PeriodScoreCheck(val label: String, val home: Int, val away: Int)

    @Test
    fun lineupsWithLines() = runTest {
        val lineups = provider.lineups(samples.finalGameId)
        assertEquals(2, lineups.size)
        val home = lineups.first()
        assertEquals(finalHomeName, home.team.name)
        assertEquals(LineupGroupKind.GOALIES, home.groups.first().kind)
        assertEquals(2, home.groups.first().players.size)
        assertEquals(3, home.groups.first { it.label == "Line 1" }.players.size)
        assertEquals(2, home.groups.first { it.label == "Pairing 1" }.players.size)
        assertTrue(home.players.all { it.jerseyNumber != null && it.position != null && !it.name.startsWith("#") })
    }

    @Test
    fun standingsCurrentAndPrevious() = runTest {
        val empty = provider.standings()
        assertEquals(samples.ssgtCurrent, empty.seasonId)
        assertTrue(empty.rows.isEmpty(), "2026–27 has not started in the samples")
        val table = provider.standings(samples.ssgtPrevious)
        assertEquals(expectedTeams, table.rows.size)
        val top = table.rows.first()
        assertEquals(1, top.rank)
        assertEquals(top.played, top.wins + top.losses + (top.otherLosses ?: 0), "W+OTW+L+OTL = GP")
        assertNotNull(top.extra["group"])
        assertTrue(top.team.id.contains('-'))
    }

    @Test
    fun teamRosterPlayer() = runTest {
        val t = provider.team(samples.teamId)
        assertEquals(samples.teamId, t.id)
        assertNotNull(t.ref.abbreviation)
        assertNotNull(t.ref.logoUrl)
        assertEquals(t.id, provider.team(t.ref.abbreviation).id, "lookup by display code works too")
        assertFailsWith<NotFoundException> { provider.team("nope") }

        val roster = provider.roster(samples.teamId)
        assertEquals(6, roster.size, "truncated sample: 2 per position group")
        assertEquals(listOf("GK", "GK", "D", "D", "F", "F"), roster.map { it.ref.position })
        assertTrue(roster.all { it.teamId == samples.teamId })

        val p = provider.player(samples.playerId)
        assertEquals(playerName, p.name)
        assertNotNull(p.birthDate)
        assertNotNull(p.heightCm)
        assertEquals(samples.teamId, p.teamId)
    }

    @Test
    fun notFoundAndCapabilities() = runTest {
        assertFailsWith<NotFoundException> { provider.game("does-not-exist") }
        assertTrue(provider.supports(Capability.LINE_GROUPS))
        assertTrue(provider.supports(Capability.INTERMISSION_STATE), "derived from the clock and the period records")
        assertTrue(!provider.supports(Capability.LIVE_PUSH), "SSE payloads not captured yet")
        assertEquals(Strength.PP, SportalityMapper(leagueId).strength("PP1", false))
        assertEquals(Strength.SH, SportalityMapper(leagueId).strength("SH2", false))
        assertEquals(Strength.PS, SportalityMapper(leagueId).strength("EQ", true))
    }
}

class ShlProviderTest : SportalityProviderTestBase(
    SportalitySamples.SHL, "shl", expectedTeams = 14, finalHomeName = "Frölunda HC", finalScore = Score(2, 1), playerName = "Jani Lampinen",
) {
    override val provider = ShlProvider(fetcher, clock = clock)
    override fun providerWith(fetcher: Fetcher, clock: Clock) = ShlProvider(fetcher, clock = clock)
}
