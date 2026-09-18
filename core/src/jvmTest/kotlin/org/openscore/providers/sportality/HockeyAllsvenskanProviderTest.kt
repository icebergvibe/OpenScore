package org.openscore.providers.sportality

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.openscore.cache.SeasonScheduleStore
import org.openscore.cache.SeasonSnapshot
import org.openscore.model.Game
import org.openscore.model.GameEnding
import org.openscore.model.GameState
import org.openscore.model.Score
import org.openscore.net.FetchResponse
import org.openscore.net.Fetcher
import org.openscore.net.HttpException
import org.openscore.provider.Capability
import org.openscore.provider.NotFoundException
import org.openscore.testing.HockeyAllsvenskanSamples
import org.openscore.testing.SampleFetcher
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class HockeyAllsvenskanProviderTest {
    private val openingDay = LocalDate(2026, 9, 18)
    private val clock = MutableClock(Instant.parse("2026-09-15T10:00:00Z"))

    @Test
    fun importsTheWholeSeasonAndFiltersLocally() = runTest {
        val fetcher = HockeyAllsvenskanSamples.register(SampleFetcher())
        val store = MemorySeasonStore()
        val provider = HockeyAllsvenskanProvider(fetcher, clock = clock, scheduleStore = store)

        val day = provider.gamesOn(openingDay)
        assertEquals(1, day.size)
        assertEquals(HockeyAllsvenskanSamples.PRE_GAME_ID, day.single().id)
        assertEquals("AIK Hockey", day.single().home.name)
        assertEquals("MoDo", day.single().away.abbreviation)
        val saved = assertNotNull(store.snapshot)
        assertEquals("season_2026", saved.seasonId)
        assertEquals(clock.now(), saved.savedAt)
        assertEquals(2, saved.games.size, "both dates were persisted by one import")
        assertEquals(1, fetcher.requests.size)

        assertEquals(1, provider.gamesOn(LocalDate(2026, 9, 25)).size)
        assertEquals(1, fetcher.requests.size, "another date reuses the in-memory season")
    }

    @Test
    fun aNewProviderUsesTheDurableSeasonWithoutNetwork() = runTest {
        val store = MemorySeasonStore()
        HockeyAllsvenskanProvider(HockeyAllsvenskanSamples.register(SampleFetcher()), clock = clock, scheduleStore = store)
            .gamesOn(openingDay)

        val offline = SampleFetcher()
        val restored = HockeyAllsvenskanProvider(offline, clock = clock, scheduleStore = store).gamesOn(LocalDate(2026, 9, 25))
        assertEquals(1, restored.size)
        assertTrue(offline.requests.isEmpty(), "a future day needs nothing from the network")
    }

    @Test
    fun aDueGameIsReadFromTheGameRouteAndAFinishedOneNeverAgain() = runTest {
        val fetcher = ScriptedFetcher(page = samplePage())
        val store = MemorySeasonStore()
        val provider = HockeyAllsvenskanProvider(fetcher, clock = clock, scheduleStore = store)
        clock.now = Instant.parse("2026-09-18T16:00:00Z")
        provider.gamesOn(openingDay)
        assertEquals(listOf(PAGE), fetcher.requests, "an hour before the puck drop the snapshot answers alone")

        // Five minutes after it the snapshot cannot vouch for the game any more.
        clock.now = Instant.parse("2026-09-18T17:05:00Z")
        fetcher.game(HockeyAllsvenskanSamples.PRE_GAME_ID, gameDocument(period = "1", home = 1, away = 0))
        val live = provider.gamesOn(openingDay).single()
        assertEquals(GameState.LIVE, live.state)
        assertEquals(Score(1, 0), live.score)
        assertEquals(listOf(PAGE, GAME), fetcher.requests)
        assertEquals(0, store.updates, "a running game is not written through")

        clock.now = Instant.parse("2026-09-18T19:30:00Z")
        fetcher.game(HockeyAllsvenskanSamples.PRE_GAME_ID, gameDocument(completed = true, decidedIn = "OT", home = 3, away = 2))
        val final = provider.gamesOn(openingDay).single()
        assertEquals(GameState.FINAL, final.state)
        assertEquals(GameEnding.OVERTIME, final.ending)
        assertEquals(4, final.periodScores.size)
        assertEquals(1, store.updates)
        assertEquals(GameState.FINAL, assertNotNull(store.snapshot).games.single { it.id == HockeyAllsvenskanSamples.PRE_GAME_ID }.state)

        provider.gamesOn(openingDay)
        clock.now = Instant.parse("2026-09-18T21:30:00Z")
        provider.gamesOn(openingDay)
        assertEquals(listOf(PAGE, GAME, GAME), fetcher.requests, "a final is served from the snapshot from then on")
    }

    @Test
    fun aGameLongOverWithoutAResultIsLeftToTheNextImport() = runTest {
        val fetcher = ScriptedFetcher(page = samplePage())
        val provider = HockeyAllsvenskanProvider(fetcher, clock = clock, scheduleStore = MemorySeasonStore())
        provider.gamesOn(openingDay)
        // Two days after the game the stale page is read again and still says scheduled; that is
        // the page's problem now, not one the game route is asked about on every visit.
        clock.now = Instant.parse("2026-09-20T15:00:00Z")
        assertEquals(GameState.SCHEDULED, provider.gamesOn(openingDay).single().state)
        assertEquals(listOf(PAGE, PAGE), fetcher.requests)
    }

    @Test
    fun aStaleSnapshotIsReimportedAndAFinalNeverRegresses() = runTest {
        val fetcher = ScriptedFetcher(page = samplePage())
        val store = MemorySeasonStore()
        val provider = HockeyAllsvenskanProvider(fetcher, clock = clock, scheduleStore = store)
        provider.gamesOn(openingDay)
        clock.now = Instant.parse("2026-09-18T20:00:00Z")
        fetcher.game(HockeyAllsvenskanSamples.PRE_GAME_ID, gameDocument(completed = true, decidedIn = "Reg", home = 2, away = 1))
        provider.gamesOn(openingDay)
        assertEquals(listOf(PAGE, PAGE, GAME), fetcher.requests, "the snapshot had aged past six hours, then the due game was read")

        // Seven hours on, the page is read again; it still lists the game as scheduled, which must not undo the result.
        clock.now = Instant.parse("2026-09-19T03:00:00Z")
        val game = provider.gamesOn(openingDay).single()
        assertEquals(listOf(PAGE, PAGE, GAME, PAGE), fetcher.requests)
        assertEquals(GameState.FINAL, game.state)
        assertEquals(Score(2, 1), game.score)
        assertEquals(GameState.FINAL, assertNotNull(store.snapshot).games.single { it.id == HockeyAllsvenskanSamples.PRE_GAME_ID }.state)
        assertEquals(clock.now, store.snapshot?.savedAt)
    }

    @Test
    fun anUnreadablePageServesTheOldSnapshotAndIsNotRetriedAtOnce() = runTest {
        val store = MemorySeasonStore()
        HockeyAllsvenskanProvider(ScriptedFetcher(page = samplePage()), clock = clock, scheduleStore = store).gamesOn(openingDay)

        val fetcher = ScriptedFetcher(page = null)
        clock.now += 7.hours
        val provider = HockeyAllsvenskanProvider(fetcher, clock = clock, scheduleStore = store)
        assertEquals(1, provider.gamesOn(LocalDate(2026, 9, 25)).size, "the stored season answers while the page is down")
        assertEquals(1, provider.gamesOn(openingDay).size)
        assertEquals(listOf(PAGE), fetcher.requests, "one failed read is not repeated within the retry window")
        clock.now += 6.minutes
        provider.gamesOn(openingDay)
        assertEquals(listOf(PAGE, PAGE), fetcher.requests)

        assertFailsWith<HttpException> {
            HockeyAllsvenskanProvider(ScriptedFetcher(page = null), clock = clock, scheduleStore = MemorySeasonStore()).gamesOn(openingDay)
        }
    }

    @Test
    fun aFinishedGameStandsInForAnUnreadableGameRoute() = runTest {
        val fetcher = ScriptedFetcher(page = samplePage())
        val store = MemorySeasonStore()
        val provider = HockeyAllsvenskanProvider(fetcher, clock = clock, scheduleStore = store)
        provider.gamesOn(openingDay)
        clock.now = Instant.parse("2026-09-18T20:00:00Z")
        fetcher.game(HockeyAllsvenskanSamples.PRE_GAME_ID, gameDocument(completed = true, decidedIn = "SO", home = 4, away = 3))
        assertEquals(GameEnding.SHOOTOUT, provider.game(HockeyAllsvenskanSamples.PRE_GAME_ID).ending)
        assertEquals(1, store.updates, "a match page read of a final is written through too")
        assertEquals(listOf(PAGE, GAME), fetcher.requests, "the match page does not reimport a stale season")

        fetcher.game(HockeyAllsvenskanSamples.PRE_GAME_ID, null)
        assertEquals(Score(4, 3), provider.game(HockeyAllsvenskanSamples.PRE_GAME_ID).score)
        assertFailsWith<HttpException> { provider.game("20260925-modo-aik") }
    }

    @Test
    fun mapsACompletedGameFromTheNewEndpoint() = runTest {
        val fetcher = HockeyAllsvenskanSamples.register(SampleFetcher())
        val provider = HockeyAllsvenskanProvider(fetcher, clock = clock)
        val game = provider.game(HockeyAllsvenskanSamples.FINAL_GAME_ID)

        assertEquals(GameState.FINAL, game.state)
        assertEquals(Score(1, 4), game.score)
        assertEquals(GameEnding.REGULATION, game.ending)
        assertEquals(listOf("1", "2", "3"), game.periodScores.map { it.period.label })
        assertEquals("Södertälje SK", game.home.name)
        assertTrue(provider.supports(Capability.PERIOD_SCORES))
        assertFalse(provider.supports(Capability.EVENTS))
        assertFalse(provider.supports(Capability.LIVE_UPDATES))
        assertNull(HockeyAllsvenskanProvider(SampleFetcher(), clock = clock).let { runCatching { it.game("nope") }.getOrNull() })
    }

    /** AIK v MoDo captured in the first period on the opening night (`currentPeriod: "P1"`). */
    @Test
    fun mapsALiveGameFromTheGameRoute() = runTest {
        val fetcher = HockeyAllsvenskanSamples.register(SampleFetcher())
        val provider = HockeyAllsvenskanProvider(fetcher, clock = clock)
        val game = provider.game(HockeyAllsvenskanSamples.LIVE_GAME_ID)

        assertEquals(GameState.LIVE, game.state)
        assertEquals("P1", game.rawState)
        assertEquals(Score(0, 0), game.score)
        assertEquals(listOf("1"), game.periodScores.map { it.period.label }, "only the period under way has a score yet")
        assertNull(game.ending)
        assertNull(game.clock, "the game route has no clock")
        assertEquals("Avicii Arena", game.venue)
    }

    /** The lineup component of the game page, captured 17:35Z for the 17:00Z game: 22 home and 26 away rows including four officials. */
    @Test
    fun lineupsComeFromTheGamePageInLineOrder() = runTest {
        val fetcher = HockeyAllsvenskanSamples.register(SampleFetcher())
        val provider = HockeyAllsvenskanProvider(fetcher, clock = clock)
        assertTrue(provider.supports(Capability.LINEUPS))
        assertTrue(provider.supports(Capability.LINE_GROUPS))
        provider.gamesOn(openingDay)

        val lineups = provider.lineups(HockeyAllsvenskanSamples.LIVE_GAME_ID)
        assertEquals(2, lineups.size)
        val home = lineups.first()
        assertEquals("AIK", home.team.id)
        assertEquals(22, home.players.size, "two goalies and twenty skaters, the extra fourth-line forward among them")
        val goalies = home.groups.first()
        assertEquals("Goalies", goalies.label)
        assertEquals(listOf("Jhonas Enroth", "Leon Contreras"), goalies.players.map { it.name }, "the starter first")
        assertEquals("GK", goalies.players.first().position)
        assertEquals(listOf("Goalies", "Line 1", "Pairing 1", "Line 2", "Pairing 2", "Line 3", "Pairing 3", "Line 4", "Pairing 4"), home.groups.map { it.label })
        val line1 = home.groups[1]
        assertEquals(listOf("LW", "CE", "RW"), line1.players.map { it.position })
        assertEquals(listOf(12, 23, 71), line1.players.map { it.jerseyNumber })
        assertEquals("Scott Pooley", line1.players.first().name)
        assertEquals("5731", line1.players.first().id, "StatNet player id, the one the play-by-play uses")
        assertTrue(line1.players.first().headshotUrl!!.startsWith("https://ha-media.hadigital.se/"))
        assertEquals(4, home.groups[7].players.size, "the fourth line carries the extra forward")
        assertEquals(2, home.groups[2].players.size)

        val away = lineups.last()
        assertEquals("MODO", away.team.id)
        assertEquals(22, away.players.size, "the four officials in the away array are not players")
        assertTrue(away.players.none { it.name.contains("Granrud") }, "referees are left out")
        assertEquals(listOf(HockeyAllsvenskanSamples.PAGE, HockeyAllsvenskanSamples.VIEW), fetcher.requests, "the snapshot names the sides; the page is one read")

        // A page rendered without the lineup component (the sheets are not out yet) is an empty answer, not an error.
        fetcher.route("${HockeyAllsvenskanProvider.DEFAULT_BASE_URL}/games/20260925-modo-aik/view?_rsc=openscore", File(SampleFetcher.samplesDir("hockey", "hockeyallsvenskan"), "matcher.rsc.txt"), "text/x-component")
        assertTrue(provider.lineups("20260925-modo-aik").isEmpty())
        assertFailsWith<NotFoundException> { provider.lineups("20261231-no-such") }
    }

    private fun samplePage(): String =
        File(SampleFetcher.samplesDir("hockey", "hockeyallsvenskan"), "matcher.rsc.txt").readText()

    /** The opening game's `/api/game` document in a later state than the captured page has it. */
    private fun gameDocument(period: String? = null, completed: Boolean = false, decidedIn: String? = null, home: Int, away: Int): String {
        val ot = if (decidedIn == "OT" || decidedIn == "SO") "\"homeOtScore\":\"1\",\"awayOtScore\":\"0\"," else ""
        val so = if (decidedIn == "SO") "\"homeSoScore\":\"1\",\"awaySoScore\":\"0\"," else ""
        return """{"data":[{"season":"season_2026","slug":"${HockeyAllsvenskanSamples.PRE_GAME_ID}","scheduledDateTime":"2026-09-18T17:00:00.000Z",""" +
            """"venue":"Avicii Arena","gameType":"HA","homeStatNetId":"AIK","awayStatNetId":"MODO",""" +
            """"playedDateTime":"2026-09-18T17:00:00.000Z","currentPeriod":${period?.let { "\"$it\"" }},""" +
            """"homeScore":"$home","awayScore":"$away","homeScoreP1":"1","awayScoreP1":"0","homeScoreP2":"1","awayScoreP2":"1","homeScoreP3":"0","awayScoreP3":"1",$ot$so""" +
            """"isCompleted":${if (completed) "true" else "null"},"endedDateTime":${if (completed) "\"2026-09-18T19:25:00.000Z\"" else "null"},""" +
            """"decidedIn":${decidedIn?.let { "\"$it\"" }},"homeTeam":{"name":"AIK Hockey","shortName":"AIK"},"awayTeam":{"name":"MoDo Hockey","shortName":"MoDo"}}]}"""
    }

    private class MutableClock(var now: Instant) : Clock {
        override fun now(): Instant = now
    }

    /** The season page and per-game documents as strings, so a test can move a game through its states. */
    private class ScriptedFetcher(private var page: String?) : Fetcher {
        private val games = HashMap<String, String?>()
        val requests = mutableListOf<String>()

        fun game(slug: String, body: String?) { games[slug] = body }

        override suspend fun get(url: String, headers: Map<String, String>, maxAge: Duration): FetchResponse {
            val path = url.removePrefix(HockeyAllsvenskanProvider.DEFAULT_BASE_URL)
            requests += path.substringBefore('?')
            val body = when {
                path == PAGE_PATH -> page
                path.startsWith("/api/game?slug=") -> games[path.substringAfter("slug=")]
                else -> null
            }
            return if (body == null) FetchResponse(url, 503, "text/plain", "down")
            else FetchResponse(url, 200, if (path == PAGE_PATH) "text/x-component" else "application/json", body)
        }
    }

    private class MemorySeasonStore : SeasonScheduleStore {
        var snapshot: SeasonSnapshot? = null
        var updates = 0

        override suspend fun load(leagueId: String): SeasonSnapshot? = snapshot

        override suspend fun save(leagueId: String, snapshot: SeasonSnapshot) { this.snapshot = snapshot }

        override suspend fun update(leagueId: String, seasonId: String, games: List<Game>) {
            val held = snapshot?.takeIf { it.seasonId == seasonId } ?: return
            updates++
            val byId = games.associateBy { it.id }
            snapshot = held.copy(games = held.games.map { byId[it.id] ?: it } + games.filter { g -> held.games.none { it.id == g.id } })
        }
    }

    private companion object {
        const val PAGE_PATH = "/pages/matcher?_rsc=openscore"
        const val PAGE = "/pages/matcher"
        const val GAME = "/api/game"
    }
}
