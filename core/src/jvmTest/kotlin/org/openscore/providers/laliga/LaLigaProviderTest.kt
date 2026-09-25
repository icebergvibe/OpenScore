package org.openscore.providers.laliga

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.openscore.model.GameState
import org.openscore.model.LineupGroupKind
import org.openscore.model.Score
import org.openscore.model.football.FootballEventType
import org.openscore.model.football.FootballGoalDetails
import org.openscore.model.football.SubstitutionDetails
import org.openscore.net.FetchResponse
import org.openscore.net.Fetcher
import org.openscore.net.HttpException
import org.openscore.net.OpenScoreJson
import org.openscore.provider.NotFoundException
import org.openscore.testing.LaLigaSamples
import org.openscore.testing.SampleFetcher
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

class LaLigaProviderTest {

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-09-12T10:00:00Z")
    }
    private val fetcher = LaLigaSamples.register(SampleFetcher())
    private val ll = LaLigaProvider(fetcher, clock = fixedClock)

    private fun sample(name: String): String {
        val text = SampleFetcher.samplesDir("football", "la-liga").resolve(name).readText()
        return if (text.contains("\"_truncated_array\"")) (OpenScoreJson.parseToJsonElement(text) as JsonObject)["_truncated_array"].toString() else text
    }

    @Test
    fun everySampleParses() {
        val strategies: List<Pair<String, DeserializationStrategy<*>>> = listOf(
            "subscriptions" to LlSubscriptions.serializer(),
            "subscription.json" to LlSubscriptionWrapper.serializer(),
            "calendar" to LlCalendar.serializer(),
            "matches-" to LlMatches.serializer(),
            "match." to LlMatchWrapper.serializer(),
            "wv-match." to LlMatchWrapper.serializer(),
            "wv-match-events" to LlEvents.serializer(),
            "wv-match-lineups" to LlLineups.serializer(),
            "wv-match-stats" to LlMatchStats.serializer(),
            "subscription-standing" to LlStandings.serializer(),
            "wv-subscription-standing" to LlStandings.serializer(),
            "team.json" to LlTeamWrapper.serializer(),
            "team-squad" to LlSquads.serializer(),
            "squads" to LlSquads.serializer(),
            "player.json" to LlPlayerWrapper.serializer(),
            "wv-subscription-week-matches" to LlMatches.serializer(),
        )
        val failures = mutableListOf<String>()
        var typed = 0
        for (file in SampleFetcher.samplesDir("football", "la-liga").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }) {
            val text = sample(file.name)
            val strategy = strategies.firstOrNull { (p, _) -> file.name.startsWith(p) && file.name != "subscription-standing-gameweeks.json" }?.second
            try {
                if (strategy != null) { OpenScoreJson.decodeFromString(strategy, text); typed++ } else OpenScoreJson.decodeFromString(JsonElement.serializer(), text)
            } catch (e: Exception) {
                failures += "${file.name}: ${e.message?.lineSequence()?.first()}"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
        assertTrue(typed >= 25, "typed=$typed")
    }

    @Test
    fun keysFallBackWhenPageUnavailable() = runTest {
        val keys = LaLigaKeys.discover(fetcher)
        assertEquals(LaLigaKeys.DOCUMENTED, keys)
        val html = """<script>{"runtimeConfig":{"backendSubscription":"0123456789abcdef0123456789abcdef","webviewSubscription":"fedcba9876543210fedcba9876543210"}}</script>"""
        val f = SampleFetcher()
        val tmp = kotlin.io.path.createTempFile(suffix = ".html").toFile().apply { writeText(html); deleteOnExit() }
        f.route("https://www.laliga.com/en-GB", tmp, contentType = "text/html")
        assertEquals("0123456789abcdef0123456789abcdef", LaLigaKeys.discover(f).publicService)
    }

    @Test
    fun rotatedKeysAreReadFromThePageOnA401() = runTest {
        val rotated = "0123456789abcdef0123456789abcdef"
        val page = """<script>{"runtimeConfig":{"backendSubscription":"$rotated","webviewSubscription":"fedcba9876543210fedcba9876543210"}}</script>"""
        // Answers 401 to the documented key and the sample to the rotated one; the page carries the rotated pair.
        val keysSent = mutableListOf<String?>()
        val keyed = object : Fetcher {
            override suspend fun get(url: String, headers: Map<String, String>, maxAge: Duration): FetchResponse {
                if (url == "https://www.laliga.com/en-GB") return FetchResponse(url, 200, "text/html", page)
                val key = headers["Ocp-Apim-Subscription-Key"].also { keysSent += it }
                if (key != rotated) return FetchResponse(url, 401, "application/json", """{"statusCode":401}""")
                return fetcher.get(url, headers, maxAge)
            }
        }
        val ll = LaLigaProvider(keyed, clock = fixedClock)
        assertEquals(4, ll.gamesOn(LocalDate.parse(LaLigaSamples.DAY)).size)
        assertEquals(LaLigaKeys.DOCUMENTED.publicService, keysSent.first(), "documented key tried first")
        assertEquals(rotated, keysSent.last(), "rotated key from then on: $keysSent")
        assertTrue(keysSent.count { it == LaLigaKeys.DOCUMENTED.publicService } <= 2, "the documented key is not retried once the page has been read: $keysSent")

        // A 401 that is not a rotation (the page still says the same keys) is the caller's error to see.
        val stuck = object : Fetcher {
            override suspend fun get(url: String, headers: Map<String, String>, maxAge: Duration): FetchResponse =
                if (url == "https://www.laliga.com/en-GB") FetchResponse(url, 200, "text/html", "<html>no config</html>")
                else FetchResponse(url, 401, "application/json", """{"statusCode":401}""")
        }
        assertFailsWith<HttpException> { LaLigaProvider(stuck, clock = fixedClock).standings() }
    }

    @Test
    fun scoreboardDay() = runTest {
        val games = ll.gamesOn(LocalDate.parse(LaLigaSamples.DAY))
        assertEquals(4, games.size, "Saturday of matchday 5")
        assertTrue(games.all { it.state == GameState.SCHEDULED && it.score == null })
        val rma = games.first { it.home.abbreviation == "RMA" }
        assertEquals("Real Madrid", rma.home.name)
        assertEquals("real-madrid", rma.home.id)
        assertNotNull(rma.home.logoUrl)
        assertEquals("laliga-easports-2026", rma.seasonId)
        assertTrue(fetcher.requests.any { it.contains("week=5") }, "matchday resolved through the calendar")
    }

    @Test
    fun finalMatch() = runTest {
        val g = ll.game(LaLigaSamples.FINAL_SLUG)
        assertEquals(GameState.FINAL, g.state)
        assertEquals("Sevilla FC", g.home.name)
        assertEquals(Score(1, 0), g.score)
        assertEquals("Ramón Sánchez-Pizjuán", g.venue)
        assertNull(g.clock)
        assertEquals("45.7", g.stats["possession"]!!.home)
        assertEquals("13", g.stats["shots"]!!.home)
        val events = assertNotNull(g.events)
        assertEquals(FootballEventType.PERIOD_START, events.first().type)
        assertEquals(FootballEventType.GAME_END, events.last().type)
        val goal = events.single { it.type.isGoal }
        assertEquals("81'", goal.time.label)
        assertEquals(80.minutes + 30.seconds - 45.minutes, goal.time.elapsed)
        assertEquals(Score(1, 0), goal.score)
        val gd = assertIs<FootballGoalDetails>(goal.details)
        assertEquals("Juan Iglesias", gd.scorer!!.name)
        assertEquals("Giorgi Kochorashvili", gd.assist!!.name)
        assertEquals(listOf(0 to 0, 1 to 0), g.periodScores.map { it.home to it.away })
        val sub = events.first { it.type == FootballEventType.SUBSTITUTION }
        val sd = assertIs<SubstitutionDetails>(sub.details)
        assertEquals("injury", sd.reason)
        assertNotNull(sd.playerOff)
        assertTrue(events.any { it.type == FootballEventType.YELLOW_CARD })
    }

    @Test
    fun liveClockFromPeriodTimestamps() {
        val header = OpenScoreJson.decodeFromString(LlMatchWrapper.serializer(), sample("wv-match.final.json")).match
        val live = header.copy(status = "SecondHalf", period_started = header.period_started.mapValues { (k, v) -> if (k == "SecondHalf") v.copy(stop = null) else v })
        val g = LaLigaMapper("la-liga").game(live, Instant.parse("2026-09-11T20:21:24Z"))
        assertEquals(GameState.LIVE, g.state)
        assertEquals("2H", g.clock!!.period.label)
        assertEquals(10.minutes, g.clock.time.elapsed)
        assertEquals("56'", g.clock.time.label)
        assertEquals(true, g.clock.running)
        val ht = header.copy(status = "HalfTime")
        val gHt = LaLigaMapper("la-liga").game(ht, Instant.parse("2026-09-11T20:00:00Z"))
        assertEquals(GameState.INTERMISSION, gHt.state)
        assertEquals(51.minutes + 5.seconds, gHt.clock!!.time.elapsed)
        assertEquals(false, gHt.clock.running)
    }

    @Test
    fun preMatchAndLineups() = runTest {
        val g = ll.game(LaLigaSamples.PRE_SLUG)
        assertEquals(GameState.SCHEDULED, g.state)
        assertNull(g.score); assertNull(g.clock)
        assertEquals(emptyList(), g.events)
        assertEquals(1, fetcher.requests.size, "pre-match detail needs only the webview header")
        assertTrue(ll.lineups(LaLigaSamples.PRE_SLUG).isEmpty())

        val lineups = ll.lineups(LaLigaSamples.FINAL_SLUG)
        assertEquals(2, lineups.size)
        val home = lineups.first()
        assertEquals("4231", home.formation)
        assertEquals(11, home.groups.first { it.kind == LineupGroupKind.STARTERS }.players.size)
        assertEquals(12, home.groups.first { it.kind == LineupGroupKind.BENCH }.players.size)
        assertEquals("Luis García", home.headCoach)
        assertEquals("GK", home.groups.first().players.first().position)
    }

    @Test
    fun standingsTeamRosterPlayer() = runTest {
        val table = ll.standings()
        assertEquals("laliga-easports-2026", table.seasonId)
        assertEquals(20, table.rows.size)
        assertEquals("FC Barcelona", table.rows.first().team.name)
        assertEquals(15, table.rows.first().goalDifference)

        val t = ll.team(LaLigaSamples.TEAM_SLUG)
        assertEquals("Real Madrid", t.name)
        assertEquals("RMA", t.ref.abbreviation)
        assertEquals("Bernabéu", t.arena)

        val roster = ll.roster(LaLigaSamples.TEAM_SLUG)
        assertEquals(5, roster.size, "truncated sample")
        assertEquals("Thibaut Courtois", roster.first().name)
        assertEquals("courtois", roster.first().id)
        assertEquals("GK", roster.first().ref.position)
        assertEquals(200, roster.first().heightCm)
        assertEquals("BE", roster.first().nationality)

        val p = ll.player(LaLigaSamples.PLAYER_SLUG)
        assertEquals("Jude Bellingham", p.name)
        assertEquals(5, p.ref.jerseyNumber)
        assertEquals("MF", p.ref.position)
        assertEquals("GB-ENG", p.nationality)
        assertEquals("real-madrid", p.teamId)
        assertEquals(LocalDate(2003, 6, 29), p.birthDate)
    }

    @Test
    fun notFound() = runTest {
        assertFailsWith<NotFoundException> { ll.game("nope") }
    }

    @Test
    fun liveDayReadsTheMatchResourceForTheClock() = runTest {
        // The week listing says only `FirstHalf` for a running match; the match resource has the period timestamps.
        val f = LaLigaSamples.register(SampleFetcher())
        val dir = SampleFetcher.samplesDir("football", "la-liga")
        val pub = LaLigaProvider.DEFAULT_PUBLIC_URL
        f.route("$pub/api/v1/calendar?startDate=2026-09-13&endDate=2026-09-13&competitionSlug=primera-division", dir.resolve("calendar.json"))
        f.route("$pub/api/v1/matches?subscriptionSlug=${LaLigaSamples.SUBSCRIPTION}&week=5&limit=100&orderField=date&orderType=asc", dir.resolve("matches-week.live.json"))
        f.route("${LaLigaProvider.DEFAULT_WEBVIEW_URL}/api/web/matches/temporada-2026-2027-laliga-ea-sports-rc-celta-malaga-cf-5", dir.resolve("wv-match.live.json"))
        val at = object : Clock {
            override fun now(): Instant = Instant.parse("2026-09-13T12:47:00Z")
        }
        val games = LaLigaProvider(f, clock = at).gamesOn(LocalDate.parse("2026-09-13"))
        val celta = games.first { it.home.abbreviation == "CEL" }
        assertEquals(GameState.LIVE, celta.state)
        assertEquals("45'", celta.clock?.time?.label, "12:02:33Z kick-off, 44:27 elapsed")
        assertEquals(true, celta.clock?.running)
        assertEquals(1, f.requests.count { it.contains("/api/web/matches/temporada") }, "one match resource, for the one live match")
        assertTrue(games.filter { it.state == GameState.SCHEDULED }.all { it.clock == null })
    }

    /**
     * The same match at half time (poll 248 of the 2026-09-13 capture): `status: "HalfTime"` and
     * `period_started.FirstHalf.stop` set, so the clock stands still at the minute the half
     * actually ended rather than at 45.
     */
    @Test
    fun halfTimeFreezesTheClockWhereTheHalfEnded() {
        val dir = SampleFetcher.samplesDir("football", "la-liga")
        val m = OpenScoreJson.decodeFromString(LlMatchWrapper.serializer(), dir.resolve("wv-match.halftime.json").readText()).match
        val at = Instant.parse("2026-09-13T12:51:14Z")
        val g = LaLigaMapper("la-liga").game(m, now = at)
        assertEquals(GameState.INTERMISSION, g.state)
        assertEquals("HalfTime/48'", g.rawState)
        assertEquals(Score(1, 0), g.score)
        val clock = assertNotNull(g.clock)
        assertEquals("45'+3", clock.time.label, "12:02:33Z to 12:50:31Z is 47:58 of first half")
        assertEquals(false, clock.running, "`stop` is what says the half is over")
    }

    /** `status: "SecondHalf"` with a second `period_started` entry; `match_time` restarts at 45. */
    @Test
    fun theSecondHalfClockRunsFromItsOwnPeriodStart() {
        val dir = SampleFetcher.samplesDir("football", "la-liga")
        val m = OpenScoreJson.decodeFromString(LlMatchWrapper.serializer(), dir.resolve("wv-match.live-second-half.json").readText()).match
        val g = LaLigaMapper("la-liga").game(m, now = Instant.parse("2026-09-13T13:08:08Z"))
        assertEquals(GameState.LIVE, g.state)
        assertEquals("SecondHalf/45'", g.rawState)
        assertEquals(2, assertNotNull(g.clock).time.period.number)
        assertEquals("47'", g.clock?.time?.label, "13:06:49Z restart, 1:19 in")
        assertEquals(true, g.clock?.running)
    }
}
