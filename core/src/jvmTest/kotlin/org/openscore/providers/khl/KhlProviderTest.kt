package org.openscore.providers.khl

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
import org.openscore.net.OpenScoreJson
import org.openscore.provider.Capability
import org.openscore.provider.NotFoundException
import org.openscore.provider.UnsupportedCapabilityException
import org.openscore.testing.KhlSamples
import org.openscore.testing.SampleFetcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class KhlProviderTest {

    private val fetcher = KhlSamples.register(SampleFetcher())
    private val khl = KhlProvider(fetcher)

    @Test
    fun everySampleParses() {
        val strategies: List<Pair<String, DeserializationStrategy<*>>> = listOf(
            "events_v2" to ListSerializer(KhlEventWrapper.serializer()),
            "event_v2.not-found" to KhlErrorResponse.serializer(),
            "event_v2" to KhlEventWrapper.serializer(),
            "data.json" to KhlData.serializer(),
            "tables_v2" to ListSerializer(KhlSeasonTables.serializer()),
            "teams_v2" to ListSerializer(KhlTeamWrapper.serializer()),
        )
        val failures = mutableListOf<String>()
        var typed = 0
        for (file in SampleFetcher.samplesDir("hockey", "khl").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }) {
            var text = file.readText()
            if (text.contains("\"_truncated_array\"")) text = (OpenScoreJson.parseToJsonElement(text) as JsonObject)["_truncated_array"].toString()
            val strategy = strategies.firstOrNull { (p, _) -> file.name.startsWith(p) }?.second
            try {
                if (strategy != null) { OpenScoreJson.decodeFromString(strategy, text); typed++ } else OpenScoreJson.decodeFromString(JsonElement.serializer(), text)
            } catch (e: Exception) {
                failures += "${file.name}: ${e.message?.lineSequence()?.first()}"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
        assertTrue(typed >= 12, "typed=$typed")
    }

    @Test
    fun gamesOnAMoscowDay() = runTest {
        val games = khl.gamesOn(LocalDate.parse(KhlSamples.GAME_DATE))
        assertEquals(4, games.size)
        assertEquals(games.sortedBy { it.startTime }, games)
        val g = games.first { it.id == KhlSamples.PRE_GAME_ID }
        assertEquals(GameState.SCHEDULED, g.state)
        assertEquals("CSKA", g.home.name)
        assertEquals("16", g.home.id)
        assertEquals("2026-09-11T16:30:00Z", g.startTime.toString())
        assertNull(g.score)
        assertEquals("407", g.seasonId)
        assertTrue(fetcher.requests.single().contains("1789074000"), "Moscow midnight → epoch seconds")
    }

    @Test
    fun finalGame() = runTest {
        val g = khl.game(KhlSamples.FINAL_GAME_ID)
        assertEquals(GameState.FINAL, g.state)
        assertEquals(GameEnding.REGULATION, g.ending)
        assertEquals("Lokomotiv", g.home.name)
        assertEquals(Score(5, 1), g.score)
        assertEquals(listOf(2 to 0, 1 to 1, 2 to 0), g.periodScores.map { it.home to it.away })
        assertNull(g.clock)
        val events = assertNotNull(g.events)
        val goals = events.filter { it.type == HockeyEventType.GOAL }
        assertEquals(6, goals.size)
        assertTrue(goals.all { it.score != null && it.team != null && it.players.isNotEmpty() })
        assertTrue(goals.all { !it.players.first().id.contains('-') }, "scorers matched to the roster by shirt number: ${goals.map { it.players.first().id }}")
        assertTrue(goals.any { (it.details as GoalDetails).assists.isNotEmpty() })
        val penalties = events.filter { it.type == HockeyEventType.PENALTY }
        assertEquals(8, penalties.size)
        val pd = assertIs<PenaltyDetails>(penalties.first().details)
        assertEquals(2, pd.minutes)
        assertNotNull(pd.infraction)
        assertEquals(2, events.count { it.type == HockeyEventType.GOALIE_CHANGE })
        assertEquals(HockeyEventType.PERIOD_START, events.first().type)
        assertEquals(HockeyEventType.GAME_END, events.last().type)
        assertEquals(events.sortedWith(compareBy({ it.period.number }, { it.time.elapsed?.inWholeSeconds ?: 0L })), events)
    }

    @Test
    fun overtimeAndShootout() = runTest {
        val ot = khl.game(KhlSamples.OT_GAME_ID)
        assertEquals(GameEnding.OVERTIME, ot.ending)
        assertEquals(listOf("1", "2", "3", "OT"), ot.periodScores.map { it.period.label })
        val otGoal = ot.events!!.first { it.type == HockeyEventType.GOAL && it.period.type == PeriodType.OVERTIME }
        assertEquals(3.minutes + 5.seconds, otGoal.time.elapsed, "3785 s of game time → 3:05 of OT")
        assertEquals(1.minutes + 55.seconds, otGoal.time.remaining)

        val so = khl.game(KhlSamples.SHOOTOUT_GAME_ID)
        assertEquals(GameEnding.SHOOTOUT, so.ending)
        assertEquals(listOf("1", "2", "3", "OT", "SO"), so.periodScores.map { it.period.label })
        assertEquals(Score(2, 3), so.score)
        val events = assertNotNull(so.events)
        assertEquals(4, events.count { it.type == HockeyEventType.GOAL }, "the shootout-deciding goal is not a GOAL")
        val attempts = events.filter { it.type == HockeyEventType.SHOOTOUT_ATTEMPT }
        assertEquals(5, attempts.size)
        assertEquals(1, attempts.count { (it.details as ShootoutAttemptDetails).scored })
        assertTrue(attempts.all { it.players.size == 2 }, "shooter and goalie parsed from the text")
        assertEquals("Neftekhimik", attempts.first { (it.details as ShootoutAttemptDetails).scored }.team!!.name)
    }

    @Test
    fun bulletParsing() {
        val b = assertNotNull(KhlMapper.parseBullet("Shootout by 20.Lockhart Lucas (Spartak) vs 31.Ozolin Yaroslav (Neftekhimik). Missed."))
        assertEquals(20, b.shooterNumber); assertEquals("Lockhart Lucas", b.shooter); assertEquals("Spartak", b.shooterTeam)
        assertEquals(31, b.goalieNumber); assertEquals("Ozolin Yaroslav", b.goalie); assertEquals(false, b.scored)
        assertEquals(true, KhlMapper.parseBullet("Shootout by 69.Artamonov Nikita (Neftekhimik) vs 40.Georgiyev Alexander (Spartak). Goal.")!!.scored)
        assertEquals(Score(5, 1), KhlMapper.score("5:1"))
        assertEquals(Strength.PP, KhlMapper.strength("PP"))
    }

    @Test
    fun liveHeuristics() {
        val e = OpenScoreJson.decodeFromString(KhlEventWrapper.serializer(), SampleFetcher.samplesDir("hockey", "khl").resolve("event_v2.final.json").readText()).event
        val live = e.copy(game_state_key = "in_progress", period = 2, text_events = listOf(KhlTextEvent(type = "state", period = 2, text = "Start of 2 period")) + e.text_events)
        val g = KhlMapper.game(live, withEvents = false)
        assertEquals(GameState.LIVE, g.state)
        assertEquals("2", g.clock!!.period.label)
        assertNull(g.clock.time.elapsed, "KHL publishes the period but no clock")
        val brk = live.copy(text_events = listOf(KhlTextEvent(type = "state", period = 1, text = "End of 1 period")) + e.text_events)
        assertEquals(GameState.INTERMISSION, KhlMapper.game(brk, withEvents = false).state)
    }

    @Test
    fun preGameAndLineups() = runTest {
        val g = khl.game(KhlSamples.PRE_GAME_ID)
        assertEquals(GameState.SCHEDULED, g.state)
        assertNull(g.score)
        assertTrue(g.periodScores.isEmpty())
        assertEquals(emptyList(), g.events)
        assertTrue(khl.lineups(KhlSamples.PRE_GAME_ID).isEmpty())

        val lineups = khl.lineups(KhlSamples.FINAL_GAME_ID)
        assertEquals(2, lineups.size)
        val home = lineups.first()
        assertEquals(listOf(LineupGroupKind.STARTERS, LineupGroupKind.FORWARDS, LineupGroupKind.DEFENSE, LineupGroupKind.GOALIES), home.groups.map { it.kind })
        assertEquals(6, home.groups.first().players.size)
        assertTrue(home.groups.first { it.kind == LineupGroupKind.GOALIES }.players.all { it.position == "G" })
    }

    @Test
    fun standings() = runTest {
        val table = khl.standings(KhlSamples.PREVIOUS_STAGE)
        assertEquals("370", table.seasonId)
        assertEquals("division", table.grouping)
        assertEquals(4, table.groups.size)
        assertEquals(22, table.rows.size)
        val row = table.rows.first()
        assertEquals(1, row.rank)
        assertTrue(row.points > 0)
        assertEquals(row.played, row.wins + row.losses + row.otherLosses!!, "W+OTW+SOW+L+OTL+SOL = GP")
        assertNotNull(row.extra["conference"])
        assertFailsWith<NotFoundException> { khl.standings() } // current stage 407 is not in the truncated sample
    }

    @Test
    fun teamAndUnsupported() = runTest {
        val t = khl.team(KhlSamples.TEAM_ID)
        assertEquals("40", t.id)
        assertNotNull(t.division)
        assertNotNull(t.ref.logoUrl)
        assertFailsWith<NotFoundException> { khl.team("999") }
        assertFailsWith<NotFoundException> { khl.game("1") }
        assertFailsWith<UnsupportedCapabilityException> { khl.roster("40") }
        assertTrue(!khl.supports(Capability.ROSTER) && !khl.supports(Capability.PLAYER) && !khl.supports(Capability.CLOCK))
    }
}
