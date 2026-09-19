package org.openscore.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openscore.OpenScore
import org.openscore.feed.FeedGame
import org.openscore.feed.FeedGamesResponse
import org.openscore.feed.FeedJson
import org.openscore.feed.FeedStandings
import org.openscore.providers.nhl.NhlProvider
import org.openscore.testing.MlbSamples
import org.openscore.testing.NhlSamples
import org.openscore.testing.SampleFetcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class FeedServerTest {

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.parse("${NhlSamples.OPENING_NIGHT}T12:00:00Z")
    }

    private fun app(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        val openScore = OpenScore(listOf(NhlProvider(NhlSamples.register(SampleFetcher()))))
        application { feedModule(openScore, fixedClock) }
        block(client)
    }

    @Test
    fun leagues() = app { client ->
        val res = client.get("/v1/leagues")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("1", res.headers["X-OpenScore-Feed"])
        val json = FeedJson.parseToJsonElement(res.bodyAsText()).jsonArray
        val nhl = json.single().jsonObject
        assertEquals("nhl", nhl["id"]!!.jsonPrimitive.content)
        assertEquals("HOCKEY", nhl["sport"]!!.jsonPrimitive.content)
        assertTrue((nhl["capabilities"] as JsonArray).any { it.jsonPrimitive.content == "CLOCK" })
    }

    @Test
    fun gamesDefaultsToToday() = app { client ->
        val res = client.get("/v1/games")
        assertEquals(HttpStatusCode.OK, res.status)
        val body = FeedJson.decodeFromString(FeedGamesResponse.serializer(), res.bodyAsText())
        assertEquals(NhlSamples.OPENING_NIGHT, body.date)
        assertEquals(listOf("nhl"), body.leagues)
        assertEquals(5, body.games.size)
        assertTrue(body.errors.isEmpty())
        val g = body.games.first()
        assertEquals("SCHEDULED", g.state)
        assertEquals("2026-09-29T21:00:00Z", g.startTime)
        assertNull(g.score)
        assertNull(g.events)
    }

    @Test
    fun gamesByDateAndLeagueFilter() = app { client ->
        val res = client.get("/v1/games?date=${NhlSamples.FINAL_DATE}&league=NHL")
        val body = FeedJson.decodeFromString(FeedGamesResponse.serializer(), res.bodyAsText())
        val g = body.games.single()
        assertEquals("FINAL", g.state)
        assertEquals("REGULATION", g.ending)
        assertEquals(0, g.score!!.home)
        assertEquals(3, g.score!!.away)
        assertEquals(listOf("1", "2", "3"), g.periodScores.map { it.period.label })
    }

    @Test
    fun gamesErrorsAreReportedPerLeague() = app { client ->
        // No sample is routed for this date → the NHL "404s" → reported, not fatal.
        val res = client.get("/v1/games?date=2031-01-01")
        assertEquals(HttpStatusCode.OK, res.status)
        val body = FeedJson.decodeFromString(FeedGamesResponse.serializer(), res.bodyAsText())
        assertTrue(body.games.isEmpty())
        assertEquals("nhl", body.errors.single().league)
        assertEquals("not_found", body.errors.single().code)
    }

    @Test
    fun badDate() = app { client ->
        val res = client.get("/v1/games?date=yesterday")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        val err = FeedJson.parseToJsonElement(res.bodyAsText()).jsonObject["error"]!!.jsonObject
        assertEquals("bad_request", err["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun gameWithEventsAndDetails() = app { client ->
        val res = client.get("/v1/games/nhl/${NhlSamples.SHOOTOUT_GAME_ID}")
        assertEquals(HttpStatusCode.OK, res.status)
        val g = FeedJson.decodeFromString(FeedGame.serializer(), res.bodyAsText())
        assertEquals("SHOOTOUT", g.ending)
        val events = assertNotNull(g.events)
        val goal = events.first { it.type == "goal" }
        val details = assertNotNull(goal.details)
        assertEquals("goal", (details["kind"] as JsonPrimitive).content)
        assertTrue(details["scorer"] is JsonObject)
        assertTrue(details["assists"] is JsonArray)
        assertNotNull(goal.score)
        val so = events.first { it.type == "shootout-attempt" }
        assertEquals("shootout-attempt", (so.details!!["kind"] as JsonPrimitive).content)
        assertEquals("SHOOTOUT", so.period.type)
    }

    @Test
    fun eventsAndLineups() = app { client ->
        val events = FeedJson.parseToJsonElement(client.get("/v1/games/nhl/${NhlSamples.FINAL_GAME_ID}/events").bodyAsText()).jsonArray
        assertEquals(343, events.size)
        val lineups = FeedJson.parseToJsonElement(client.get("/v1/games/nhl/${NhlSamples.FINAL_GAME_ID}/lineups").bodyAsText()).jsonArray
        assertEquals(2, lineups.size)
        assertEquals("FORWARDS", lineups[0].jsonObject["groups"]!!.jsonArray[0].jsonObject["kind"]!!.jsonPrimitive.content)
    }

    @Test
    fun standingsTeamRosterPlayer() = app { client ->
        val table = FeedJson.decodeFromString(FeedStandings.serializer(), client.get("/v1/standings/nhl").bodyAsText())
        assertEquals(4, table.groups.size)
        assertEquals("division", table.grouping)

        val team = FeedJson.parseToJsonElement(client.get("/v1/teams/nhl/TOR").bodyAsText()).jsonObject
        assertEquals("Toronto Maple Leafs", team["name"]!!.jsonPrimitive.content)
        assertEquals("Atlantic", team["division"]!!.jsonPrimitive.content)

        val roster = FeedJson.parseToJsonElement(client.get("/v1/teams/nhl/TOR/roster").bodyAsText()).jsonArray
        assertEquals(50, roster.size)

        val player = FeedJson.parseToJsonElement(client.get("/v1/players/nhl/8478403").bodyAsText()).jsonObject
        assertEquals("Jack Eichel", player["name"]!!.jsonPrimitive.content)
        assertEquals("1996-10-28", player["birthDate"]!!.jsonPrimitive.content)
    }

    @Test
    fun notFoundAndUnknownLeague() = app { client ->
        val missing = client.get("/v1/games/nhl/1")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        val err = FeedJson.parseToJsonElement(missing.bodyAsText()).jsonObject["error"]!!.jsonObject
        assertEquals("not_found", err["code"]!!.jsonPrimitive.content)
        assertEquals("nhl", err["league"]!!.jsonPrimitive.content)

        val unknown = client.get("/v1/standings/xfl")
        assertEquals(HttpStatusCode.NotFound, unknown.status)
        assertEquals("unknown_league", FeedJson.parseToJsonElement(unknown.bodyAsText()).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun liveStreamEndsOnFinal() = app { client ->
        val res = client.get("/v1/games/nhl/${NhlSamples.FINAL_GAME_ID}/live")
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.headers["Content-Type"]!!.startsWith("text/event-stream"))
        val text = res.bodyAsText()
        assertTrue(text.contains("event: game"), text.take(200))
        assertTrue(text.contains("\"state\":\"FINAL\""))
        assertTrue(text.trimEnd().endsWith("event: end") || text.contains("event: end"), "stream closes with an end event")
    }

    @Test
    fun corsIsOpen() = app { client ->
        val res = client.get("/v1/leagues") { headers.append("Origin", "https://example.app") }
        assertEquals("*", res.headers["Access-Control-Allow-Origin"])
    }
}

class MultiLeagueFeedServerTest {
    @Test
    fun allLeaguesOnOneDay() = testApplication {
        val openScore = OpenScore.default(SampleFetcher.allLeagues())
        application { feedModule(openScore) }
        val leagues = FeedJson.parseToJsonElement(client.get("/v1/leagues").bodyAsText()).jsonArray
        val ids = leagues.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("nhl", "liiga", "shl", "hockeyallsvenskan", "chl", "khl", "del"), ids.take(7))
        assertTrue(ids.containsAll(listOf("ligue1", "bundesliga", "premier-league", "championship", "carabao-cup", "serie-a", "la-liga", "malta-premier", "allsvenskan", "superettan", "fogis", "mlb", "ufc")))
        assertEquals(setOf("HOCKEY", "FOOTBALL", "BASEBALL", "MMA"), leagues.map { it.jsonObject["sport"]!!.jsonPrimitive.content }.toSet())

        val res = client.get("/v1/games?date=2026-09-11")
        assertEquals(HttpStatusCode.OK, res.status)
        val body = FeedJson.decodeFromString(FeedGamesResponse.serializer(), res.bodyAsText())
        assertEquals(ids.size, body.leagues.size)
        // Leagues whose samples do not cover 2026-09-11 report per-league errors instead of failing the call.
        assertTrue(body.errors.none { it.league in setOf("chl", "khl", "nhl", "liiga", "shl", "hockeyallsvenskan", "bundesliga", "mlb") }, body.errors.toString())
        assertTrue(body.games.map { it.league }.toSet().containsAll(setOf("chl", "khl", "bundesliga", "mlb")), body.games.map { it.league }.toSet().toString())
        assertEquals(15, body.games.count { it.league == "mlb" })
        assertEquals(body.games.sortedBy { it.startTime }, body.games)
        val bl = body.games.first { it.league == "bundesliga" }
        assertEquals("LIVE", bl.state)
        assertEquals("46'", bl.clock!!.label, "allLeagues() routes the live2 state")

        val liiga = FeedJson.decodeFromString(FeedGame.serializer(), client.get("/v1/games/liiga/2701280").bodyAsText())
        assertEquals("SHOOTOUT", liiga.ending)
        assertTrue(liiga.events!!.any { it.type == "shootout-attempt" })
        val khl = FeedJson.decodeFromString(FeedGame.serializer(), client.get("/v1/games/khl/3000051").bodyAsText())
        assertEquals("FINAL", khl.state)
        assertNull(khl.clock)
        val l1 = FeedJson.decodeFromString(FeedGame.serializer(), client.get("/v1/games/ligue1/l1_championship_match_73845").bodyAsText())
        assertEquals("FINAL", l1.state)
        assertEquals("football-goal", l1.events!!.first { it.type == "goal" }.details!!["kind"]!!.jsonPrimitive.content)
        assertEquals("31'", l1.events!!.first { it.type == "goal" }.timeLabel)
        assertTrue(l1.stats.containsKey("possession"))
        val fogis = FeedJson.decodeFromString(FeedGamesResponse.serializer(), client.get("/v1/games?date=2026-09-12&league=fogis").bodyAsText())
        assertEquals(90, fogis.games.size)
        assertTrue(fogis.games.map { it.competition }.containsAll(listOf("Allsvenskan 2026", "Div 2 Norra Svealand, herr 2026")))
        val mlb = FeedJson.decodeFromString(FeedGame.serializer(), client.get("/v1/games/mlb/${MlbSamples.FINAL_GAME_ID}").bodyAsText())
        assertEquals("FINAL", mlb.state)
        assertNull(mlb.clock)
        assertNull(mlb.situation)
        assertEquals(9, mlb.periodScores.size)
        assertEquals("plate-appearance", mlb.events!!.first().details!!["kind"]!!.jsonPrimitive.content)
        assertEquals("Top 1", mlb.events!!.first().timeLabel)
    }

    @Test
    fun baseballSituationInTheFeed() = testApplication {
        application { feedModule(OpenScore.default(MlbSamples.registerLive(SampleFetcher()))) }
        val game = FeedJson.decodeFromString(FeedGame.serializer(), client.get("/v1/games/mlb/${MlbSamples.LIVE_GAME_ID}").bodyAsText())
        assertEquals("LIVE", game.state)
        assertEquals("Bot 7", game.clock!!.label)
        assertNull(game.clock!!.elapsedSeconds)
        val situation = game.situation!!
        assertEquals("baseball", situation["kind"]!!.jsonPrimitive.content)
        assertEquals("BOTTOM", situation["half"]!!.jsonPrimitive.content)
        assertEquals(2, situation["strikes"]!!.jsonPrimitive.content.toInt())
        assertEquals("Randy Arozarena", situation["onSecond"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(situation["onFirst"] is JsonNull)
        val substitution = game.events!!.first { it.type == "substitution" }.details!!
        assertEquals("baseball-substitution", substitution["kind"]!!.jsonPrimitive.content)
        assertEquals("Jacob deGrom", substitution["replaces"]!!.jsonPrimitive.content)
    }
}

class TeamPageFeedServerTest {
    @Test
    fun teamScheduleAndStatsAreAvailableThroughFeedV1() = testApplication {
        application { feedModule(OpenScore.default(MlbSamples.register(SampleFetcher()))) }
        val schedule = client.get("/v1/teams/mlb/147/schedule?startDate=2026-01-01&endDate=2026-12-31")
        assertEquals(HttpStatusCode.OK, schedule.status)
        val games = FeedJson.parseToJsonElement(schedule.bodyAsText()).jsonArray
        assertEquals(6, games.size)
        assertEquals("2026-09-10", games.first().jsonObject["scheduleDate"]?.jsonPrimitive?.content)
        val stats = client.get("/v1/teams/mlb/147/stats?season=2026")
        assertEquals(HttpStatusCode.OK, stats.status)
        val body = FeedJson.parseToJsonElement(stats.bodyAsText()).jsonObject
        assertEquals("147", body["teamId"]?.jsonPrimitive?.content)
        assertEquals(2, body["groups"]?.jsonArray?.size)
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/teams/mlb/147/stats").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/teams/mlb/147/stats?season=bad").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/teams/mlb/147/schedule?startDate=2026-09-15&endDate=2026-09-10").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/teams/mlb/147/schedule?startDate=2020-01-01&endDate=2026-12-31").status)
        assertEquals(HttpStatusCode.NotImplemented, client.get("/v1/teams/nhl/TOR/stats?season=2026").status)
    }
}
