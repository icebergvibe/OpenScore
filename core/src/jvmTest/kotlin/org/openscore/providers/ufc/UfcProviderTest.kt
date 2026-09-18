package org.openscore.providers.ufc

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.openscore.cache.SeasonScheduleStore
import org.openscore.cache.SeasonSnapshot
import org.openscore.feed.FeedMapper
import org.openscore.model.Game
import org.openscore.model.GameState
import org.openscore.model.PeriodType
import org.openscore.model.combat.CombatEventType
import org.openscore.model.combat.FightMethod
import org.openscore.model.combat.FightOutcome
import org.openscore.model.combat.FightSituation
import org.openscore.net.OpenScoreJson
import org.openscore.provider.Capability
import org.openscore.provider.NotFoundException
import org.openscore.testing.SampleFetcher
import org.openscore.testing.UfcSamples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import java.io.File

class UfcProviderTest {

    private val fetcher = UfcSamples.register(SampleFetcher())
    /** Sample day: the morning of 2026-09-18, the day the samples were captured. */
    private val clock = object : Clock { override fun now(): Instant = Instant.parse("2026-09-18T12:00:00Z") }
    private val ufc = UfcProvider(fetcher, clock = clock)

    @Test
    fun everySampleParses() {
        val failures = mutableListOf<String>()
        var typed = 0
        for (file in SampleFetcher.samplesDir("mma", "ufc").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }) {
            val strategy = when {
                file.name.startsWith("event.") -> UfcEventResponse.serializer()
                file.name.startsWith("fight.") -> UfcFightResponse.serializer()
                else -> null
            }
            try {
                if (strategy != null) { OpenScoreJson.decodeFromString(strategy, file.readText()); typed++ } else fail("untyped sample ${file.name}")
            } catch (e: Exception) {
                failures += "${file.name}: ${e.message?.lineSequence()?.first()}"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
        assertEquals(11, typed)
    }

    @Test
    fun capabilities() {
        assertEquals(setOf(Capability.GAMES_BY_DATE, Capability.GAME, Capability.EVENTS, Capability.LIVE_UPDATES), ufc.capabilities)
    }

    @Test
    fun aColdSweepLocatesTheFrontierAndAnswersACardDay() = runTest {
        val games = ufc.gamesOn(LocalDate.parse(UfcSamples.UPCOMING_DATE))
        assertEquals(12, games.size)
        assertTrue(games.all { it.state == GameState.SCHEDULED && it.leagueId == "ufc" && it.seasonId == UfcMapper.SEASON_ID })
        assertTrue(games.all { it.competition == "UFC 331: Van vs. Pantoja 2" && it.scheduleDate == LocalDate.parse("2026-09-19") })
        // Prelims first, then the main card; within a segment the fight that walks out first comes first.
        assertEquals(listOf(12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1), games.map { it.situation().cardPosition })
        val main = games.last()
        assertEquals("1335-13017", main.id)
        assertEquals(Instant.parse("2026-09-20T01:00:00Z"), main.startTime)
        assertEquals("Joshua Van", main.home.name)
        assertEquals("Alexandre Pantoja", main.away.name)
        assertEquals("Pantoja", main.away.abbreviation)
        assertEquals("Crypto.com Arena, Los Angeles", main.venue)
        assertNull(main.score)
        assertNull(main.clock)
        assertNull(main.events) // a listing never maps timelines
        with(main.situation()) {
            assertEquals(5, scheduledRounds)
            assertEquals(listOf(5, 5, 5, 5, 5), roundMinutes)
            assertEquals("Flyweight", weightClass)
            assertEquals("UFC Flyweight Title", title)
            assertEquals("Main", cardSegment)
            assertNull(result)
        }
        assertEquals("Upcoming/Upcoming", main.rawState)

        // The sweep: the seed, jumps of 32 until an empty answer, a bisection, the window below the frontier, and one empty batch above it.
        val probed = fetcher.requests.mapNotNull { Regex("/event/live/(\\d+)\\.json").find(it)?.groupValues?.get(1)?.toInt() }.toSet()
        assertTrue(UfcProvider.SEED_EVENT_ID in probed)
        assertTrue((UfcSamples.LAST_EVENT_ID - UfcProvider.WINDOW + 1..UfcSamples.LAST_EVENT_ID).all { it in probed }, "window not read")
        assertTrue((UfcSamples.LAST_EVENT_ID + 1..UfcSamples.LAST_EVENT_ID + UfcProvider.EMPTY_RUN).all { it in probed }, "frontier not confirmed")
        assertTrue(probed.max() <= UfcSamples.LAST_EVENT_ID + 64, "probed too far: ${probed.max()}")
        assertTrue(probed.size < 60, "too many reads for a cold start: ${probed.size}")
    }

    @Test
    fun aDayWithNoCardIsEmpty() = runTest {
        assertEquals(emptyList<Game>(), ufc.gamesOn(LocalDate.parse("2026-09-18")))
    }

    @Test
    fun aFinishedCardIsReadOnceAndKeptWithItsResults() = runTest {
        val games = ufc.gamesOn(LocalDate.parse(UfcSamples.FINAL_DATE))
        assertEquals(12, games.size)
        assertTrue(games.all { it.state == GameState.FINAL })
        val main = games.last()
        assertEquals("1320-12919", main.id)
        val result = assertNotNull(main.situation().result)
        assertEquals(main.home, result.winner)
        assertEquals("Dricus Du Plessis", result.winner?.name)
        assertEquals(FightMethod.DECISION, result.method)
        assertEquals("Decision - Unanimous", result.methodLabel)
        assertEquals(5, result.round)
        assertEquals("5:00", result.time)
        assertEquals(FightOutcome.WIN, result.homeOutcome)
        assertEquals(FightOutcome.LOSS, result.awayOutcome)
        assertEquals(listOf("Sal D'amato" to (50 to 45), "David Sutherland" to (49 to 46), "Mike Bell" to (49 to 46)), result.scorecards.map { it.judge to (it.home to it.away) })
        assertTrue(main.credits.isEmpty(), "the result says who won; no credits")

        val reads = fetcher.requests.count { it.endsWith("/event/live/${UfcSamples.FINAL_EVENT_ID}.json") }
        ufc.gamesOn(LocalDate.parse(UfcSamples.FINAL_DATE))
        assertEquals(reads, fetcher.requests.count { it.endsWith("/event/live/${UfcSamples.FINAL_EVENT_ID}.json") }, "a settled card was re-read")
    }

    @Test
    fun aFinishedFightCarriesItsTimelineAndStatistics() = runTest {
        val game = ufc.game("1320-12874")
        assertEquals(GameState.FINAL, game.state)
        val result = assertNotNull(game.situation().result)
        assertEquals(FightMethod.SUBMISSION, result.method)
        assertEquals(1, result.round)
        assertEquals("2:15", result.time)
        assertEquals("Rear Naked Choke", result.detail)
        assertEquals("Chase Hooper", result.winner?.name)

        val events = assertNotNull(game.events)
        // 15 tracked actions; the broadcast beats and the post-result bookkeeping are left out.
        assertEquals(11, events.size)
        assertEquals(CombatEventType.FIGHT_START, events.first().type)
        assertEquals(0, events.first().period.number)
        val takedown = events.first { it.type == CombatEventType.TAKEDOWN }
        assertEquals("Chase Hooper", takedown.players.single().name)
        assertEquals(game.home, takedown.team)
        assertEquals(1, takedown.period.number)
        assertEquals(PeriodType.REGULATION, takedown.period.type)
        assertEquals(4.minutes + 8.seconds, takedown.time.remaining)
        assertEquals(52.seconds, takedown.time.elapsed)
        assertEquals("0:52", takedown.time.label)
        val roundEnd = events.first { it.type == CombatEventType.ROUND_END }
        assertEquals(2.minutes + 44.seconds, roundEnd.time.remaining)
        assertEquals(CombatEventType.RESULT, events.last().type)
        assertEquals("Wins by submission", events.last().description)
        assertEquals(events, ufc.events("1320-12874"))

        // The main event's fight route supplies the totals (the sample is the main event's).
        val main = ufc.game("1320-12919")
        assertEquals("136", main.stats.getValue("sigStrikesLanded").home)
        assertEquals("49.8%", main.stats.getValue("sigStrikeAccuracy").home)
        assertEquals("0:06", main.stats.getValue("controlTime").home)
        assertTrue(main.stats.getValue("takedownsAttempted").away.toInt() > 0)
    }

    @Test
    fun aPauseFoldsItsReasonIn() = runTest {
        val events = ufc.game("1320-12919").events!!
        val pauses = events.filter { it.type == CombatEventType.PAUSE }
        assertEquals(listOf("Low blow", "Eye poke", "Low blow"), pauses.map { it.description })
        assertEquals(listOf(1, 2, 5), pauses.map { it.period.number })
        assertEquals("Kamaru Usman", pauses[0].players.single().name)
        assertEquals(3, events.count { it.type == CombatEventType.RESUME })
        assertTrue(events.none { it.rawType.startsWith("pause_reason") })
    }

    @Test
    fun drawsAndNoContestsHaveNoWinner() = runTest {
        val draw = ufc.game("${UfcSamples.DRAW_EVENT_ID}-${UfcSamples.DRAW_FIGHT_ID}").situation().result!!
        assertNull(draw.winner)
        assertEquals(FightMethod.DECISION, draw.method)
        assertEquals(FightOutcome.DRAW, draw.homeOutcome)
        assertEquals(FightOutcome.DRAW, draw.awayOutcome)
        assertEquals(3, draw.scorecards.size)

        val nc = ufc.game("${UfcSamples.NO_CONTEST_EVENT_ID}-${UfcSamples.NO_CONTEST_FIGHT_ID}").situation().result!!
        assertNull(nc.winner)
        assertEquals(FightMethod.NO_CONTEST, nc.method)
        assertEquals("Could Not Continue", nc.methodLabel)
        assertEquals(FightOutcome.NO_CONTEST, nc.homeOutcome)
        assertEquals("Low Blow from Sumudaerji", nc.notes)
    }

    @Test
    fun contenderSeriesCardsAreTheSameLeague() = runTest {
        val games = ufc.gamesOn(LocalDate.parse("2026-09-08"))
        assertEquals(5, games.size)
        assertEquals("DWCS 10.5", games.first().competition)
        assertEquals("ufc", games.first().leagueId)
    }

    @Test
    fun unknownIdsAreNotFound() = runTest {
        assertFailsWith<NotFoundException> { ufc.game("999999-1") }
        assertFailsWith<NotFoundException> { ufc.game("1320-1") }
        assertFailsWith<NotFoundException> { ufc.game("nonsense") }
    }

    @Test
    fun aRestoredSnapshotIsReReadOnceForItsResults() = runTest {
        // A first provider sweeps and persists; a second one restores and must not sweep the window again.
        val store = MemoryStore()
        UfcProvider(fetcher, clock = clock, scheduleStore = store).gamesOn(LocalDate.parse(UfcSamples.FINAL_DATE))
        val saved = assertNotNull(store.saved)
        assertEquals(UfcMapper.SEASON_ID, saved.seasonId)
        assertTrue(saved.games.size > 12)

        val again = SampleFetcher().also { UfcSamples.register(it) }
        val restored = UfcProvider(again, clock = clock, scheduleStore = store)
        val games = restored.gamesOn(LocalDate.parse(UfcSamples.FINAL_DATE))
        assertEquals(12, games.size)
        assertNotNull(games.last().situation().result, "results come from the re-read, not the store")
        val eventReads = again.requests.filter { it.contains("/event/live/") }
        assertEquals(1, eventReads.count { it.endsWith("/${UfcSamples.FINAL_EVENT_ID}.json") })
        assertFalse(eventReads.any { it.endsWith("/${UfcProvider.SEED_EVENT_ID}.json") }, "restored snapshot swept from the seed")
    }

    @Test
    fun feedEncodesTheFight() = runTest {
        val json = FeedMapper.situation(ufc.game("1320-12874").situation())
        assertEquals("fight", json.getValue("kind").toString().trim('"'))
        assertEquals("\"SUBMISSION\"", json.getValue("result").let { (it as kotlinx.serialization.json.JsonObject).getValue("method") }.toString())
    }

    @Test
    fun mapperHelpers() {
        assertEquals(listOf(10, 5, 5), UfcMapper.roundMinutes(UfcRuleSet(3, "3 Rnd (10-5-5)")))
        assertEquals(listOf(5, 5, 5), UfcMapper.roundMinutes(UfcRuleSet(3, null)))
        assertEquals(1.minutes + 35.seconds, UfcMapper.parseClock("1:35"))
        assertEquals(95.seconds, UfcMapper.parseClock("95"))
        assertNull(UfcMapper.parseClock("--"))
        assertEquals(FightMethod.KO_TKO, UfcMapper.method("TKO - Doctor's Stoppage"))
        assertEquals(FightMethod.OVERTURNED, UfcMapper.method("Overturned"))
        assertEquals(1335, UfcMapper.eventIdOf("1335-13017"))
        assertEquals(13017, UfcMapper.fightIdOf("1335-13017"))
        assertNull(UfcMapper.fightIdOf("1335"))
    }

    @Test
    fun aCanceledCardHasNoFights() = runTest {
        val canceled = OpenScoreJson.decodeFromString(UfcEventResponse.serializer(), File(SampleFetcher.samplesDir("mma", "ufc"), "event.live.canceled.json").readText()).LiveEventDetail
        assertEquals("Canceled", canceled.Status)
        assertEquals(emptyList<Game>(), UfcMapper.card(canceled, UfcSamples.CANCELED_EVENT_ID, withEvents = false))
        assertEquals(LocalDate.parse("2025-08-22"), UfcMapper.localDate(canceled))
    }

    private fun Game.situation(): FightSituation = assertIs<FightSituation>(situation)

    private class MemoryStore : SeasonScheduleStore {
        var saved: SeasonSnapshot? = null
        override suspend fun load(leagueId: String): SeasonSnapshot? = saved
        override suspend fun save(leagueId: String, snapshot: SeasonSnapshot) { saved = snapshot }
        override suspend fun update(leagueId: String, seasonId: String, games: List<Game>) {
            val current = saved ?: return
            val byId = current.games.associateBy { it.id } + games.associateBy { it.id }
            saved = current.copy(games = byId.values.toList())
        }
    }
}
