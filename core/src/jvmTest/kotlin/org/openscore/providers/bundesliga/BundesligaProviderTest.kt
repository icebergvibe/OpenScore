package org.openscore.providers.bundesliga

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import org.openscore.model.GameState
import org.openscore.model.LineupGroupKind
import org.openscore.model.Score
import org.openscore.model.football.FootballEventType
import org.openscore.model.football.FootballGoalDetails
import org.openscore.net.EventStreamFetcher
import org.openscore.net.FetchResponse
import org.openscore.net.Fetcher
import org.openscore.net.OpenScoreJson
import org.openscore.net.ServerSentEvent
import org.openscore.provider.Capability
import org.openscore.provider.NotFoundException
import org.openscore.provider.UnsupportedCapabilityException
import org.openscore.testing.BundesligaSamples
import org.openscore.testing.SampleFetcher
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class BundesligaProviderTest {

    /** GETs from [delegate], SSE from a script: one flow per connection, in order. */
    private class StreamingFetcher(
        private val delegate: Fetcher,
        private vararg val connections: Flow<ServerSentEvent>,
    ) : EventStreamFetcher {
        var opened: Int = 0
            private set

        override suspend fun get(url: String, headers: Map<String, String>, maxAge: Duration): FetchResponse =
            delegate.get(url, headers, maxAge)

        override fun events(url: String, headers: Map<String, String>): Flow<ServerSentEvent> =
            connections.getOrNull(opened++) ?: error("no scripted connection #$opened")
    }

    private fun sample(name: String): String = File(SampleFetcher.samplesDir("football", "bundesliga"), name).readText()

    /** The patch Firebase sends at the final whistle of the sampled match: status and the 1-3 score. */
    private fun finalWhistle(): ServerSentEvent {
        val final = OpenScoreJson.parseToJsonElement(sample("match-basic.final.json")).jsonObject
        return ServerSentEvent("patch", """{"path":"/","data":{"matchStatus":"FINAL_WHISTLE","score":${final.getValue("score")}}}""")
    }

    private fun provider(state: String): Pair<BundesligaProvider, SampleFetcher> {
        val f = BundesligaSamples.register(SampleFetcher(), state)
        return BundesligaProvider(f) to f
    }

    @Test
    fun everySampleParses() {
        val strategies: List<Pair<String, DeserializationStrategy<*>>> = listOf(
            "config" to MapSerializer(String.serializer(), BlConfig.serializer()),
            "matches" to MapSerializer(String.serializer(), BlMatch.serializer()),
            "match-basic" to BlMatch.serializer(),
            "match." to BlMatch.serializer(),
            "match-lineup" to BlLineup.serializer(),
            "match-stats" to BlStats.serializer(),
            "liveTable" to BlTable.serializer(),
            "homeTable" to BlTable.serializer(),
            "awayTable" to BlTable.serializer(),
            "formTable" to BlTable.serializer(),
        )
        val failures = mutableListOf<String>()
        var typed = 0
        for (file in SampleFetcher.samplesDir("football", "bundesliga").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }) {
            val text = file.readText()
            if (text.trim() == "null") continue
            val strategy = strategies.firstOrNull { (p, _) -> file.name.startsWith(p) && !file.name.startsWith("match-liveBlog") && !file.name.startsWith("match-matchStatus") && !file.name.startsWith("match-all") && !file.name.startsWith("match-data") && !file.name.startsWith("matchday") }?.second
            try {
                if (strategy != null) { OpenScoreJson.decodeFromString(strategy, text); typed++ } else OpenScoreJson.decodeFromString(JsonElement.serializer(), text)
            } catch (e: Exception) {
                failures += "${file.name}: ${e.message?.lineSequence()?.first()}"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
        assertTrue(typed >= 30, "typed=$typed")
    }

    @Test
    fun scoreboardDay() = runTest {
        val (bl, f) = provider("live")
        val games = bl.gamesOn(LocalDate.parse(BundesligaSamples.DAY))
        assertEquals(1, games.size, "one Friday match on MD 3")
        val g = games.single()
        assertEquals(BundesligaSamples.MATCH_ID, g.id)
        assertEquals(GameState.LIVE, g.state)
        assertEquals("1. FC Union Berlin", g.home.name)
        assertEquals("FCU", g.home.abbreviation)
        assertEquals("DFL-CLU-00000V", g.home.id)
        assertEquals(Score(0, 0), g.score)
        assertEquals("20'", g.clock!!.time.label)
        assertEquals(true, g.clock.running)
        assertEquals("2026-09-11T18:30:00Z", g.startTime.toString())
        assertTrue(f.requests.any { it.contains("equalTo=3") }, "matchday query used for fresh scores")
    }

    @Test
    fun directFirebaseStreamPatchesTheScoreboardWithoutPollingTheFullGame() = runTest {
        val delegate = BundesligaSamples.register(SampleFetcher(), "live")
        val provider = BundesligaProvider(StreamingFetcher(delegate, flowOf(finalWhistle())))

        val updates = provider.live(BundesligaSamples.MATCH_ID).toList()
        assertTrue(provider.supports(Capability.LIVE_PUSH))
        assertEquals(listOf(GameState.LIVE, GameState.FINAL), updates.map { it.state })
        assertEquals(Score(1, 3), updates.last().score)
        assertEquals(1, delegate.requests.count { it.endsWith("/matches/${BundesligaSamples.MATCH_ID}.json") }, "stream supplied the changed fields")
    }

    @Test
    fun aDroppedConnectionReconnectsInsteadOfEndingTheLiveView() = runTest {
        val delegate = BundesligaSamples.register(SampleFetcher(), "live")
        val streams = StreamingFetcher(delegate, flow { throw IOException("connection reset") }, flowOf(finalWhistle()))

        val updates = BundesligaProvider(streams).live(BundesligaSamples.MATCH_ID).toList()
        assertEquals(listOf(GameState.LIVE, GameState.FINAL), updates.map { it.state })
        assertEquals(2, streams.opened, "reconnected after the reset")
        assertEquals(2, delegate.requests.count { it.endsWith("/matches/${BundesligaSamples.MATCH_ID}.json") }, "each connect starts from a fresh snapshot")
    }

    @Test
    fun aGoalOnTheStreamReachesTheTimelineAndNotJustTheScore() = runTest {
        val delegate = BundesligaSamples.register(SampleFetcher(), "live")
        val halfTime = flow {
            // By the time the stream says 0-1 at the break, the ticker has the goal too.
            BundesligaSamples.register(delegate, "halftime")
            emit(ServerSentEvent("put", """{"path":"/","data":${sample("match-basic.halftime.json")}}"""))
        }

        val updates = BundesligaProvider(StreamingFetcher(delegate, halfTime)).live(BundesligaSamples.MATCH_ID).take(2).toList()
        assertEquals(0, updates.first().events!!.count { it.type.isGoal })
        val atBreak = updates.last()
        assertEquals(GameState.INTERMISSION, atBreak.state)
        assertEquals(Score(0, 1), atBreak.score)
        assertEquals(1, atBreak.events!!.count { it.type.isGoal }, "the ticker was read again once the stream got ahead of it")
    }

    @Test
    fun aTickerTrailingTheFinalWhistleIsChasedBeforeTheFlowEnds() = runTest {
        val live = BundesligaSamples.register(SampleFetcher(), "live")
        val final = BundesligaSamples.register(SampleFetcher(), "final")
        var tickerReads = 0
        // The ticker (the `/en/` node) still reads 0-0 for the snapshot, the whistle and the
        // first chase, and has the four goals from the read after that.
        val trailing = object : Fetcher {
            override suspend fun get(url: String, headers: Map<String, String>, maxAge: Duration): FetchResponse {
                val ticker = url.contains("/en/")
                if (ticker) tickerReads++
                return (if (ticker && tickerReads > 3) final else live).get(url, headers, maxAge)
            }
        }

        val updates = BundesligaProvider(StreamingFetcher(trailing, flowOf(finalWhistle()))).live(BundesligaSamples.MATCH_ID).toList()
        assertEquals(GameState.FINAL, updates.last().state)
        assertEquals(Score(1, 3), updates.last().score)
        assertEquals(4, updates.last().events!!.count { it.type.isGoal }, "the timeline the reader is left with has every goal")
        assertEquals(4, tickerReads)
    }

    @Test
    fun lifecycle() = runTest {
        val (pre, _) = provider("pre")
        val gPre = pre.game(BundesligaSamples.MATCH_ID)
        assertEquals(GameState.SCHEDULED, gPre.state)
        assertNull(gPre.score); assertNull(gPre.clock)
        assertTrue(gPre.periodScores.isEmpty())
        assertTrue(gPre.events!!.none { it.type.isGoal })
        assertEquals("An der Alten Försterei", gPre.venue)

        val (ht, _) = provider("halftime")
        val gHt = ht.game(BundesligaSamples.MATCH_ID)
        assertEquals(GameState.INTERMISSION, gHt.state)
        assertEquals(Score(0, 1), gHt.score)
        assertEquals(listOf(0 to 1), gHt.periodScores.map { it.home to it.away })
        assertEquals("1H", gHt.clock!!.period.label)
        assertEquals("45'+3", gHt.clock.time.label)
        assertEquals(false, gHt.clock.running)

        val (l2, _) = provider("live2")
        val g2 = l2.game(BundesligaSamples.MATCH_ID)
        assertEquals(GameState.LIVE, g2.state)
        assertEquals("2H", g2.clock!!.period.label)
        assertEquals("46'", g2.clock.time.label)
        assertEquals(1.minutes, g2.clock.time.elapsed)
        assertEquals(listOf(0 to 1, 0 to 0), g2.periodScores.map { it.home to it.away })
        assertEquals("61", g2.stats["possession"]!!.home)
        assertNotNull(g2.stats["xg"])

        val (fin, _) = provider("final")
        val gFin = fin.game(BundesligaSamples.MATCH_ID)
        assertEquals(GameState.FINAL, gFin.state)
        assertEquals(Score(1, 3), gFin.score)
        assertEquals(listOf(0 to 1, 1 to 2), gFin.periodScores.map { it.home to it.away })
        assertNull(gFin.clock)
        val events = assertNotNull(gFin.events)
        val goals = events.filter { it.type.isGoal }
        assertEquals(4, goals.size)
        assertEquals(listOf("25'", "46'", "90'+6", "90'+13"), goals.map { it.time.label })
        assertEquals(listOf(1, 2, 2, 2), goals.map { it.period.number })
        assertEquals(Score(1, 3), goals.last().score)
        val gd = assertIs<FootballGoalDetails>(goals.first().details)
        assertEquals("Robin Gosens", gd.scorer!!.name)
        assertTrue(events.any { it.type == FootballEventType.VAR })
        assertEquals(4, events.count { it.type == FootballEventType.YELLOW_CARD })
        assertEquals(10, events.count { it.type == FootballEventType.SUBSTITUTION })
        assertEquals(FootballEventType.PERIOD_START, events.first().type)
        assertEquals(FootballEventType.GAME_END, events.last().type)
        assertTrue(events.none { it.rawType in setOf("freetext", "image", "video", "embed", "stats", "lineup") }, "editorial entries are dropped")
    }

    @Test
    fun lineups() = runTest {
        val (bl, _) = provider("final")
        val lineups = bl.lineups(BundesligaSamples.MATCH_ID)
        assertEquals(2, lineups.size)
        val home = lineups.first()
        assertEquals("4231", home.formation)
        assertEquals(11, home.groups.first { it.kind == LineupGroupKind.STARTERS }.players.size)
        assertEquals(9, home.groups.first { it.kind == LineupGroupKind.BENCH }.players.size)
        assertEquals("Mauro Lustrinelli", home.headCoach)
        assertEquals("GK", home.groups.first().players.first().position)
        assertTrue(home.players.all { it.jerseyNumber != null && it.id.startsWith("DFL-OBJ-") })
    }

    @Test
    fun standingsAndTeam() = runTest {
        val (bl, _) = provider("live")
        val table = bl.standings()
        assertEquals(BundesligaSamples.SEASON, table.seasonId)
        assertEquals(18, table.rows.size)
        val top = table.rows.first()
        assertEquals("FC Augsburg", top.team.name)
        assertEquals(1, top.rank)
        assertEquals(6, top.points)
        assertEquals("UEFA_CHAMPIONS_LEAGUE", top.extra["qualification"])
        val t = bl.team(BundesligaSamples.CLUB_ID)
        assertEquals("FC Augsburg", t.name)
        assertEquals("FCA", t.ref.abbreviation)
        assertEquals(t.id, bl.team("fca").id)
    }

    @Test
    fun notFoundAndUnsupported() = runTest {
        val (bl, _) = provider("live")
        assertFailsWith<NotFoundException> { bl.game("DFL-MAT-000000") }
        assertFailsWith<UnsupportedCapabilityException> { bl.roster("x") }
        assertTrue(!bl.supports(Capability.ROSTER) && !bl.supports(Capability.PLAYER))
        assertTrue(bl.supports(Capability.CLOCK_RUNNING_FLAG))
    }
}
