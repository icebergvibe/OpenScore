package org.openscore.providers.sportality

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.openscore.cache.SeasonScheduleStore
import org.openscore.cache.SeasonSnapshot
import org.openscore.model.Game
import org.openscore.model.GameEnding
import org.openscore.model.GameState
import org.openscore.model.PeriodType
import org.openscore.model.Score
import org.openscore.model.hockey.GoalDetails
import org.openscore.model.hockey.HockeyEventType
import org.openscore.model.hockey.PenaltyDetails
import org.openscore.model.hockey.Strength
import org.openscore.net.FetchResponse
import org.openscore.net.Fetcher
import org.openscore.net.HttpException
import org.openscore.net.OpenScoreJson
import org.openscore.net.QueryFetcher
import org.openscore.provider.Capability
import org.openscore.provider.NotFoundException
import org.openscore.provider.ProviderException
import org.openscore.provider.UnsupportedCapabilityException
import org.openscore.testing.HockeyAllsvenskanSamples
import org.openscore.testing.SampleFetcher
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
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
        assertEquals(3, saved.games.size, "every date was persisted by one import")
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
        assertTrue(provider.supports(Capability.EVENTS), "the game page carries the play-by-play, so this needs no POST")
        assertNull(game.events, "a game document alone never carries a timeline; events() is the second call")
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

    /**
     * `ROSTER` and `LIVE_UPDATES` exist only where the fetcher can ask behind a POST. `EVENTS`
     * does not depend on it: the game page carries the same play-by-play document inline.
     */
    @Test
    fun thePostOnlyCapabilitiesFollowTheFetcher() = runTest {
        val withPost = HockeyAllsvenskanProvider(HockeyAllsvenskanSamples.register(SampleFetcher()), clock = clock)
        assertTrue(withPost.supports(Capability.ROSTER))
        assertTrue(withPost.supports(Capability.LIVE_UPDATES))
        assertTrue(withPost.supports(Capability.EVENTS))

        val getOnly = HockeyAllsvenskanProvider(ScriptedFetcher(page = samplePage()), clock = clock)
        assertFalse(getOnly.supports(Capability.ROSTER), "no POST, no squad route, so the tab does not appear")
        assertFalse(getOnly.supports(Capability.LIVE_UPDATES))
        assertTrue(getOnly.supports(Capability.EVENTS), "still reachable through the page")
        assertFailsWith<UnsupportedCapabilityException> { getOnly.roster("LIF") }
    }

    /**
     * The squad route is keyed by the CMS short name, which differs from the StatNet id for six
     * of the fourteen clubs and answers `200` with nothing when it is wrong. The table row is
     * what carries both spellings, so it is the join.
     */
    @Test
    fun theSquadIsAskedForByTheClubsCmsSpelling() = runTest {
        val fetcher = HockeyAllsvenskanSamples.register(SampleFetcher())
        val provider = HockeyAllsvenskanProvider(fetcher, clock = clock)

        // MoDo is asked for by `MoDo`, not the `MODO` the core keys it by. Asking with the
        // StatNet id is routed here to what the site really answers: a 200 with no players.
        val squad = provider.roster(HockeyAllsvenskanSamples.TWO_CODE_TEAM_ID)
        assertEquals(29, squad.size)
        assertEquals(
            listOf(HockeyAllsvenskanSamples.SQUAD to HockeyAllsvenskanSamples.squadBody("MoDo")),
            fetcher.postBodies,
            "one POST, and the body carries every field the route needs, not just the club",
        )
        val first = squad.first()
        assertNotNull(first.id, "the slug, so a roster row opens the profile player() serves")
        assertEquals(HockeyAllsvenskanSamples.TWO_CODE_TEAM_ID, first.teamId, "the core's id, not the CMS one")
        assertTrue(squad.any { it.birthDate != null })
        assertTrue(squad.count { it.ref.position == "GK" } >= 2, "the goalies are in the squad")

        assertFailsWith<NotFoundException> { provider.roster("XYZ") }
    }

    /**
     * An unknown club is a `200` with an empty list, never an error, so an empty squad is the
     * one answer that means the join is wrong rather than the club being small.
     */
    @Test
    fun anEmptySquadIsReportedRatherThanServedAsAnEmptyTab() = runTest {
        val fetcher = HockeyAllsvenskanSamples.register(SampleFetcher())
        // Answer MoDo's own spelling with the empty body the wrong spelling really produces.
        fetcher.postRoute(
            HockeyAllsvenskanSamples.SQUAD,
            HockeyAllsvenskanSamples.squadBody(HockeyAllsvenskanSamples.TWO_CODE_TEAM_LABEL),
            File(SampleFetcher.samplesDir("hockey", "hockeyallsvenskan"), "all-players.unknown-team.json"),
        )
        val provider = HockeyAllsvenskanProvider(fetcher, clock = clock)
        assertFailsWith<ProviderException> { provider.roster(HockeyAllsvenskanSamples.TWO_CODE_TEAM_ID) }
    }

    /**
     * The table has no shots or save-percentage column and cannot say who leads the club. Those
     * four numbers are four POSTs, and none of them may fail the screen the record is on.
     */
    @Test
    fun teamStatsGainTheLeaderboardColumnsAndSurviveWithoutThem() = runTest {
        val fetcher = HockeyAllsvenskanSamples.register(SampleFetcher())
        val provider = HockeyAllsvenskanProvider(fetcher, clock = clock)

        val stats = provider.teamStats(HockeyAllsvenskanSamples.TEAM_ID, "season_2026")
        assertEquals(listOf("record", "goals", "specialTeams", "shooting", "leaders"), stats.groups.map { it.key })
        val shooting = stats.groups.first { it.key == "shooting" }.stats.associate { it.key to it.value }
        assertEquals("86", shooting["shotsOnGoal"], "Leksand's row of the SOG leaderboard")
        assertEquals("94.03", shooting["savePercentage"])
        val leaders = stats.groups.first { it.key == "leaders" }.stats.associate { it.key to it.value }
        assertEquals("Patrik Zackrisson 4", leaders["pointsLeader"], "rank 1 of the club's points leaderboard")
        assertEquals("Marcus Gidlöf 90.00", leaders["savePercentageLeader"])

        // A leaderboard that will not answer costs its own column and nothing else.
        val degraded = HockeyAllsvenskanProvider(
            HockeyAllsvenskanSamples.register(SampleFetcher()).also { it.failPost(HockeyAllsvenskanSamples.TEAM_LEADERBOARD) },
            clock = clock,
        ).teamStats(HockeyAllsvenskanSamples.TEAM_ID, "season_2026")
        // Special teams live on that leaderboard now, so they go down with it - the record,
        // which comes from the standings row, does not.
        assertEquals(listOf("record", "goals", "leaders"), degraded.groups.map { it.key })
        assertEquals("3", degraded.groups.first().stats.first { it.key == "gamesPlayed" }.value, "the record is untouched")
    }

    /**
     * The cheap route needs the game's StatNet number, which only the season page and the game
     * page carry. The captured season row has it, so the POST is used straight away.
     */
    @Test
    fun theTimelineComesFromThePostWhenTheSeasonPageGaveItsNumber() = runTest {
        val fetcher = HockeyAllsvenskanSamples.register(SampleFetcher())
        val provider = HockeyAllsvenskanProvider(fetcher, clock = clock)

        val events = provider.events(HockeyAllsvenskanSamples.LIVE_GAME_ID)
        assertEquals(
            listOf(HockeyAllsvenskanSamples.PLAY_BY_PLAY to HockeyAllsvenskanSamples.pollPayload("23401", "AIK", "MODO", "2026-09-18T17:00:00Z")),
            fetcher.postBodies,
            "one POST carrying the season page's own game number; the 235 KB page was not read",
        )
        assertTrue(fetcher.requests.none { it == HockeyAllsvenskanSamples.VIEW })

        val goal = assertNotNull(events.firstOrNull { it.type == HockeyEventType.GOAL })
        assertEquals("MODO", goal.team?.id, "the event's StatNet id resolved against the game's sides")
        assertEquals(Score(0, 1), goal.score, "the running score, home first")
        assertEquals(5.minutes + 45.seconds, goal.time.elapsed, "seconds inside the period, counted from 0 again each period")
        assertEquals("5:45", goal.time.label)
        assertEquals(2, goal.time.period.number, "MoDo's opener came in the second")
        val details = assertIs<GoalDetails>(goal.details)
        assertEquals("Carl Mattsson", details.scorer?.name)
        assertEquals(listOf("Victor Berglund", "Elias Rosén"), details.assists.map { it.name })
        assertEquals(Strength.EV, details.strength, "`EQ` is even strength")
        assertEquals(1, details.scorerSeasonTotal)
    }

    /** The whole arc: three periods, penalties with their minutes, and shots told apart by outcome. */
    @Test
    fun aFinishedGamesTimelineCarriesPenaltiesAndShotOutcomes() = runTest {
        val fetcher = HockeyAllsvenskanSamples.register(SampleFetcher())
        val provider = HockeyAllsvenskanProvider(fetcher, clock = clock)

        val events = provider.events(HockeyAllsvenskanSamples.LIVE_GAME_ID)
        assertEquals(listOf(1, 2, 3), events.map { it.time.period.number }.distinct())
        assertEquals(113, events.size)

        val penalty = assertNotNull(events.firstOrNull { it.type == HockeyEventType.PENALTY })
        val penaltyDetails = assertIs<PenaltyDetails>(penalty.details)
        assertEquals(2, penaltyDetails.minutes)
        assertNotNull(penaltyDetails.infraction)
        assertNotNull(penaltyDetails.player)

        // `outside` and `frame hit` never reached the goalie; `save` and `covered by player` did.
        val missed = events.filter { it.type == HockeyEventType.MISSED_SHOT }
        val onGoal = events.filter { it.type == HockeyEventType.SHOT }
        assertTrue(missed.isNotEmpty() && onGoal.isNotEmpty())
        assertTrue(missed.all { it.description in setOf("outside", "frame hit") })
        assertTrue(onGoal.none { it.description in setOf("outside", "frame hit") })
        // Which way each goalie went, as SHL's feed says it: both starters in, AIK's pulled and
        // back once, and both off at the end.
        val goalies = events.filter { it.type == HockeyEventType.GOALIE_CHANGE }.map { it.description.orEmpty() }
        assertEquals(3, goalies.count { it.endsWith(" in") })
        assertEquals(3, goalies.count { it.endsWith(" out") })
        assertTrue(goalies.first().endsWith("Enroth in"), goalies.first())
    }

    /**
     * No captured game has gone to a shoot-out, so this block is built by hand in the shape of
     * the three real ones. What it pins is the rule: the block's attempts make it the shoot-out,
     * not its number - numbered 4 here, which by number alone would have read as overtime.
     */
    @Test
    fun aBlockOfShootoutAttemptsIsTheShootout() = runTest {
        val full = playByPlaySample()
        val shootout = OpenScoreJson.parseToJsonElement(
            """{"Period":"4","Events":[{"type":"ShootoutPenaltyShot","time":"0","player":{"statNetId":"5731","firstName":"Oscar","familyName":"Tellström"},"team":{"statNetId":"AIK"}}]}""",
        )
        val withShootout = JsonObject(full + ("game_events" to JsonArray(full.getValue("game_events").jsonArray + shootout)))
        val provider = HockeyAllsvenskanProvider(servingPlayByPlay { withShootout.toString() }, clock = clock)

        val attempt = provider.events(HockeyAllsvenskanSamples.LIVE_GAME_ID).last()
        assertEquals(HockeyEventType.SHOOTOUT_ATTEMPT, attempt.type)
        assertEquals(PeriodType.SHOOTOUT, attempt.time.period.type)
        assertEquals("SO", attempt.time.period.label)
    }

    /**
     * Without the number, the page answers instead - and leaves the number behind, so the next
     * call is the cheap one. This is the shape of a cold start from the durable snapshot.
     */
    @Test
    fun aGameWithNoKnownNumberFallsBackToThePageAndLearnsFromIt() = runTest {
        val dir = SampleFetcher.samplesDir("hockey", "hockeyallsvenskan")
        val fetcher = HockeyAllsvenskanSamples.register(SampleFetcher())
        // The page as it really is: the play-by-play component with the whole document inline,
        // and the game object that carries the StatNet number.
        fetcher.route(HockeyAllsvenskanSamples.VIEW, File(dir, "game-view.play-by-play.rsc.txt"), "text/x-component")
        // A cold start: the season is restored from the store, so no import has seen the
        // numbers, which is the one situation the page has to answer.
        val store = MemorySeasonStore()
        HockeyAllsvenskanProvider(HockeyAllsvenskanSamples.register(SampleFetcher()), clock = clock, scheduleStore = store)
            .gamesOn(openingDay)
        val provider = HockeyAllsvenskanProvider(fetcher, clock = clock, scheduleStore = store)

        val fromPage = provider.events(HockeyAllsvenskanSamples.LIVE_GAME_ID)
        assertEquals(113, fromPage.size, "the page carries the same document")
        assertTrue(fetcher.postBodies.isEmpty(), "no number, so no POST was possible")
        assertTrue(fetcher.requests.contains(HockeyAllsvenskanSamples.VIEW))

        // Having read the page once, the number is known and the cheap route takes over.
        val fromPost = provider.events(HockeyAllsvenskanSamples.LIVE_GAME_ID)
        assertEquals(fromPage.size, fromPost.size)
        assertEquals(listOf(HockeyAllsvenskanSamples.PLAY_BY_PLAY), fetcher.postBodies.map { it.first })
    }

    /**
     * The game route carries the number too, and a match screen reads the game before its
     * timeline - so a cold start's timeline is the POST, not the 235 KB page.
     */
    @Test
    fun aColdStartLearnsTheGameNumberFromTheGameRoute() = runTest {
        val store = MemorySeasonStore()
        HockeyAllsvenskanProvider(HockeyAllsvenskanSamples.register(SampleFetcher()), clock = clock, scheduleStore = store)
            .gamesOn(openingDay)
        val fetcher = HockeyAllsvenskanSamples.register(SampleFetcher())
        val provider = HockeyAllsvenskanProvider(fetcher, clock = clock, scheduleStore = store)

        provider.game(HockeyAllsvenskanSamples.LIVE_GAME_ID)
        assertEquals(113, provider.events(HockeyAllsvenskanSamples.LIVE_GAME_ID).size)
        assertEquals(listOf(HockeyAllsvenskanSamples.PLAY_BY_PLAY), fetcher.postBodies.map { it.first }, "the game route's number made the POST possible")
        assertTrue(fetcher.requests.none { it == HockeyAllsvenskanSamples.VIEW }, "the game page was not read")
    }

    /**
     * The document does not move on a penalty or a shot, so a live view that re-read the
     * timeline only when the score or period changed left them off until the next goal. The
     * live flow carries the timeline itself and reads it on a cadence while the document
     * stands still.
     */
    @Test
    fun theLiveFlowCarriesItsTimelineAndReadsItWithoutWaitingForAGoal() = runTest {
        val full = playByPlaySample()
        val periods = full.getValue("game_events").jsonArray
        val firstPeriodOnly = JsonObject(full + ("game_events" to JsonArray(listOf(periods.first()))))
        val firstPeriodEvents = periods.first().jsonObject.getValue("Events").jsonArray.size
        // The same live document on every read; the play-by-play fills in behind it.
        var posts = 0
        val fetcher = servingPlayByPlay { (if (posts++ == 0) firstPeriodOnly else full).toString() }

        val updates = HockeyAllsvenskanProvider(fetcher, clock = clock).live(HockeyAllsvenskanSamples.LIVE_GAME_ID).take(2).toList()
        assertEquals(firstPeriodEvents, updates.first().events?.size, "the first emission already has its timeline")
        assertEquals(113, updates.last().events?.size, "what happened since reached the timeline with no goal to prompt it")
        assertEquals(updates.first().score, updates.last().score)
        assertEquals(2, posts, "read when the document first arrived, then after 30 s of it standing still - not every tick")
    }

    /** StatNet has no sheet until the opening face-off, and says so with a 404. That is a state. */
    @Test
    fun aGameThatHasNotStartedHasNoTimelineRatherThanAnError() = runTest {
        val fetcher = HockeyAllsvenskanSamples.register(SampleFetcher())
        // The same game, before the opening face-off: StatNet has no sheet and answers 404.
        fetcher.postRoute(
            HockeyAllsvenskanSamples.PLAY_BY_PLAY,
            HockeyAllsvenskanSamples.pollPayload("23401", "AIK", "MODO", "2026-09-18T17:00:00Z"),
            File(SampleFetcher.samplesDir("hockey", "hockeyallsvenskan"), "play-by-play.new.not-started.json"),
            status = 404,
        )
        val provider = HockeyAllsvenskanProvider(fetcher, clock = clock)
        assertEquals(emptyList(), provider.events(HockeyAllsvenskanSamples.LIVE_GAME_ID))
    }

    /**
     * The table stopped being a page read on 2026-09-25: `/pages/tabell` went client-rendered
     * and the rows now come from the route its shell calls. That route gives the display code
     * (`ÖIK`, `MORA`, `NVIF`), not the StatNet id the core keys clubs by, so the season
     * snapshot does the join - and a club the snapshot has no game for keeps the display code,
     * which is what a past season's relegated clubs get.
     */
    @Test
    fun theTableIsARouteNowAndTheSnapshotSuppliesTheIds() = runTest {
        val fetcher = HockeyAllsvenskanSamples.register(SampleFetcher())
        val provider = HockeyAllsvenskanProvider(fetcher, clock = clock)
        assertTrue(provider.supports(Capability.STANDINGS))

        val table = provider.standings()
        assertEquals("season_2026", table.seasonId)
        assertEquals(1, table.groups.size)
        assertEquals(14, table.rows.size)
        assertEquals(HockeyAllsvenskanSamples.TABLE, fetcher.postBodies.first().first)

        val leader = table.rows.first()
        assertEquals(1, leader.rank)
        assertEquals("LIF", leader.team.id)
        assertEquals("Leksand", leader.team.name, "the snapshot's name, not the table's code")
        assertEquals("leksand", leader.team.clubId, "the crosswalk keys HockeyAllsvenskan by StatNet id")
        assertTrue(leader.team.logoUrl!!.startsWith("https://ha-media.hadigital.se/"), "the crest comes from the snapshot too")
        assertEquals(3, leader.played)
        assertEquals(3, leader.wins)
        assertEquals(9, leader.points)
        assertEquals(7, leader.goalDifference)
        // The row's own column is empty now; this is the team leaderboard's value.
        assertEquals("18.18", leader.extra["powerPlay"])
        assertEquals("91.67", leader.extra["penaltyKill"])

        // MoDo's seven points are one regulation win and two overtime wins: all three are wins
        // here, and the 3-2-1-0 split that explains the points is in `extra`.
        val modo = assertNotNull(table.rows.firstOrNull { it.team.id == "MODO" })
        assertEquals(3, modo.wins)
        assertEquals(0, modo.losses)
        assertEquals(0, modo.otherLosses)
        assertEquals(7, modo.points)
        assertEquals("1", modo.extra["regulationWins"])
        assertEquals("2", modo.extra["overtimeWins"])
        assertEquals("MoDo", modo.team.abbreviation, "the CMS spelling, which is what the squad route wants")

        // Östersund is `ÖIK` in the table and `OSIK` in the games. The trimmed season sample has
        // no Östersund fixture, so this is the fallback: the display code stands in for the id.
        val ostersund = assertNotNull(table.rows.firstOrNull { it.team.abbreviation == "ÖIK" })
        assertEquals("ÖIK", ostersund.team.id, "no snapshot game for this club, so no StatNet id to join to")
        assertNull(ostersund.team.logoUrl)
        assertEquals(1, ostersund.wins, "an overtime win with no regulation win")
        assertEquals(2, ostersund.otherLosses, "both past regulation")
        assertEquals(4, ostersund.points)
    }


    @Test
    fun aPastSeasonIsServedNowThatTheTableIsARoute() = runTest {
        val fetcher = HockeyAllsvenskanSamples.register(SampleFetcher())
        val provider = HockeyAllsvenskanProvider(fetcher, clock = clock)
        assertEquals(14, provider.standings("season_2026").rows.size)

        // The page ignored every parameter it was given; the route does not. This is 2025-26,
        // won by IF Björklöven with 119 points from 52 games - a club not in this season's
        // snapshot, so it keeps the table's own code.
        val past = provider.standings("season_2025")
        assertEquals("season_2025", past.seasonId)
        val champion = past.rows.first()
        assertEquals("IFB", champion.team.id)
        assertEquals(52, champion.played)
        assertEquals(119, champion.points)
        assertNull(champion.extra["powerPlay"], "the leaderboard route takes no season, so special teams are current-only")
        assertFailsWith<NotFoundException> { provider.standings("not-a-season") }
    }

    /** A club's identity, rink and fixtures are all in the snapshot the feed has already read. */
    @Test
    fun clubPagesCostNothingBeyondTheSeasonSnapshot() = runTest {
        val fetcher = HockeyAllsvenskanSamples.register(SampleFetcher())
        val provider = HockeyAllsvenskanProvider(fetcher, clock = clock)
        assertTrue(provider.supports(Capability.TEAM))
        assertTrue(provider.supports(Capability.TEAM_SCHEDULE))

        val team = provider.team("AIK")
        assertEquals("AIK", team.id)
        assertEquals("AIK Hockey", team.name)
        assertEquals("aik-hockey", team.ref.clubId)
        assertEquals("Avicii Arena", team.arena, "the rink of their home game in the captured page")
        assertEquals("SE", team.country)

        val schedule = provider.teamSchedule("AIK", LocalDate(2026, 9, 1), LocalDate(2026, 9, 30))
        assertEquals(listOf("20260918-aik-modo", "20260925-modo-aik"), schedule.map { it.id }, "home and away, in order")
        assertEquals(listOf(HockeyAllsvenskanSamples.PAGE), fetcher.requests, "one page read served both calls")

        assertEquals(0, provider.teamSchedule("AIK", LocalDate(2026, 10, 1), LocalDate(2026, 10, 31)).size)
        assertEquals(1, provider.teamSchedule("modo", LocalDate(2026, 9, 25), LocalDate(2026, 9, 25)).size, "ids are matched case-insensitively")
        assertFailsWith<NotFoundException> { provider.team("XYZ") }
    }

    @Test
    fun teamStatsAreTheClubsTableRow() = runTest {
        val provider = HockeyAllsvenskanProvider(HockeyAllsvenskanSamples.register(SampleFetcher()), clock = clock)
        assertTrue(provider.supports(Capability.TEAM_STATS))

        val stats = provider.teamStats("LIF", "season_2026")
        assertEquals("LIF", stats.teamId)
        assertEquals("season_2026", stats.seasonId)
        assertEquals(listOf("record", "goals", "specialTeams", "shooting", "leaders"), stats.groups.map { it.key })
        val record = stats.groups.first().stats.associate { it.key to it.value }
        assertEquals("3", record["gamesPlayed"])
        assertEquals("9", record["points"])
        assertEquals("91.67", stats.groups.first { it.key == "specialTeams" }.stats.single { it.key == "penaltyKill" }.value)
        assertFailsWith<NotFoundException> { provider.teamStats("XYZ", "season_2026") }
    }

    /**
     * The leaderboards take no season, so a past season's stats are its table row alone -
     * not last year's record beside this year's power play. A club this season's snapshot does
     * not know is found by the table's own code, which is the id [standings] gave it.
     */
    @Test
    fun aPastSeasonsStatsAreItsTableRowAlone() = runTest {
        val fetcher = HockeyAllsvenskanSamples.register(SampleFetcher())
        val provider = HockeyAllsvenskanProvider(fetcher, clock = clock)

        val aik = provider.teamStats("AIK", "season_2025")
        assertEquals(listOf("record", "goals"), aik.groups.map { it.key })
        assertEquals("85", aik.groups.first().stats.first { it.key == "points" }.value)
        assertTrue(
            fetcher.postBodies.none { it.first == HockeyAllsvenskanSamples.TEAM_LEADERBOARD || it.first == HockeyAllsvenskanSamples.PLAYER_LEADERBOARD },
            "no leaderboard was asked for a season it cannot answer for",
        )

        val champion = provider.teamStats("IFB", "season_2025")
        assertEquals("IFB", champion.teamId)
        assertEquals("119", champion.groups.first().stats.first { it.key == "points" }.value)
    }

    @Test
    fun playerProfilesComeFromThePlayerPage() = runTest {
        val fetcher = HockeyAllsvenskanSamples.register(SampleFetcher())
        val provider = HockeyAllsvenskanProvider(fetcher, clock = clock)
        assertTrue(provider.supports(Capability.PLAYER))

        val player = provider.player(HockeyAllsvenskanSamples.PLAYER_SLUG)
        assertEquals("patrik-zackrisson", player.id, "the profile is keyed by its slug, not the StatNet id")
        assertEquals("Patrik Zackrisson", player.name)
        assertEquals(9, player.ref.jerseyNumber)
        assertEquals("RW", player.ref.position)
        assertEquals(LocalDate(1987, 3, 27), player.birthDate)
        assertEquals("SWE", player.nationality)
        assertEquals(180, player.heightCm)
        assertEquals(83, player.weightKg)
        assertEquals("R", player.handedness)
        assertEquals("LIF", player.teamId)
        assertTrue(player.ref.headshotUrl!!.startsWith("https://ha-media.hadigital.se/"))

        // An unknown slug is answered with the site's not-found page under a 200.
        fetcher.route(
            "${HockeyAllsvenskanProvider.DEFAULT_BASE_URL}/players/no-such-player?_rsc=openscore",
            File(SampleFetcher.samplesDir("hockey", "hockeyallsvenskan"), "matcher.rsc.txt"),
            "text/x-component",
        )
        assertFailsWith<NotFoundException> { provider.player("no-such-player") }
    }

    private fun playByPlaySample(): JsonObject = OpenScoreJson.parseToJsonElement(
        File(SampleFetcher.samplesDir("hockey", "hockeyallsvenskan"), "play-by-play.new.final.json").readText(),
    ).jsonObject

    /** The samples, except that the play-by-play POST answers [document]. */
    private fun servingPlayByPlay(document: () -> String): QueryFetcher {
        val samples = HockeyAllsvenskanSamples.register(SampleFetcher())
        return object : QueryFetcher {
            override suspend fun get(url: String, headers: Map<String, String>, maxAge: Duration): FetchResponse = samples.get(url, headers, maxAge)
            override suspend fun query(url: String, body: String, contentType: String, headers: Map<String, String>, maxAge: Duration): FetchResponse =
                if (url == HockeyAllsvenskanSamples.PLAY_BY_PLAY) FetchResponse(url, 200, "application/json", document())
                else samples.query(url, body, contentType, headers, maxAge)
        }
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
