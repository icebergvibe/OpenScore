package org.openscore.providers.malta

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import org.openscore.model.Game
import org.openscore.model.GameState
import org.openscore.model.LineupGroupKind
import org.openscore.model.Score
import org.openscore.model.football.FootballEventType
import org.openscore.model.football.FootballGoalDetails
import org.openscore.model.football.GoalKind
import org.openscore.model.football.SubstitutionDetails
import org.openscore.net.OpenScoreJson
import org.openscore.provider.Capability
import org.openscore.provider.NotFoundException
import org.openscore.provider.UnsupportedCapabilityException
import org.openscore.providers.football.FootballPeriods
import org.openscore.testing.MaltaSamples
import org.openscore.testing.SampleFetcher
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
import kotlin.time.Instant

class MaltaProviderTest {

    /** The capture day: [MaltaSamples.PAST_DAY] is behind it and [MaltaSamples.NEXT_DAY] ahead. */
    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-09-11T10:00:00Z")
    }
    private val fetcher = MaltaSamples.register(SampleFetcher())
    private val mt = MaltaProvider(fetcher, clock = fixedClock)

    @Test
    fun everySampleParses() {
        val strategies: List<Pair<String, DeserializationStrategy<*>>> = listOf(
            "upcomingMatches" to ListSerializer(MtMatch.serializer()),
            "pastMatches" to ListSerializer(MtMatch.serializer()),
            "matches.live-none" to ListSerializer(MtMatch.serializer()),
            "match." to MtMatch.serializer(),
            "result." to MtResult.serializer(),
            "lineup." to MtLineup.serializer(),
            "standings" to MtStandings.serializer(),
            "teams.json" to ListSerializer(MtTeams.serializer()),
            "team-players" to ListSerializer(MtSquadPlayer.serializer()),
            "player.json" to MtPlayer.serializer(),
        )
        val failures = mutableListOf<String>()
        var typed = 0
        for (file in SampleFetcher.samplesDir("football", "malta-premier").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }) {
            val text = file.readText()
            if (text.isBlank()) continue
            val strategy = strategies.firstOrNull { (p, _) -> file.name.startsWith(p) }?.second
            try {
                if (strategy != null) { OpenScoreJson.decodeFromString(strategy, text); typed++ } else OpenScoreJson.decodeFromString(JsonElement.serializer(), text)
            } catch (e: Exception) {
                failures += "${file.name}: ${e.message?.lineSequence()?.first()}"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
        assertTrue(typed >= 17, "typed=$typed")
    }

    @Test
    fun scoreboardDays() = runTest {
        val played = mt.gamesOn(LocalDate.parse(MaltaSamples.PAST_DAY))
        assertEquals(2, played.size)
        assertTrue(played.all { it.state == GameState.FINAL && it.score != null }, "scores fetched per match")
        val g = played.first { it.id == MaltaSamples.FINAL_ID }
        assertEquals("Zabbar St.Patrick", g.home.name)
        assertEquals(Score(1, 2), g.score)
        assertNull(g.events, "scoreboard call does not carry events")

        val upcoming = mt.gamesOn(LocalDate.parse(MaltaSamples.NEXT_DAY))
        assertEquals(2, upcoming.size)
        assertTrue(upcoming.all { it.state == GameState.SCHEDULED && it.score == null })
        assertTrue(fetcher.requests.none { it.contains("/matches/53142593/result") }, "no result calls for scheduled matches")
        // The API rejects a past date on upcomingMatches and a future one on pastMatches, so neither is asked.
        assertTrue(fetcher.requests.none { it.contains("upcomingMatches?date=${MaltaSamples.PAST_DAY}") }, "no upcoming listing for a past day")
        assertTrue(fetcher.requests.none { it.contains("pastMatches?date=${MaltaSamples.NEXT_DAY}") }, "no past listing for a future day")
    }

    @Test
    fun finalMatch() = runTest {
        val g = mt.game(MaltaSamples.FINAL_ID)
        assertEquals(GameState.FINAL, g.state)
        assertEquals(Score(1, 2), g.score)
        assertEquals("Centenary Stadium, Ta' Qali", g.venue)
        assertNull(g.clock)
        assertEquals(listOf(1 to 1, 0 to 1), g.periodScores.map { it.home to it.away })
        val events = assertNotNull(g.events)
        val goals = events.filter { it.type.isGoal }
        assertEquals(3, goals.size)
        assertEquals(FootballEventType.PENALTY_GOAL, goals.first().type)
        assertEquals(GoalKind.PENALTY, assertIs<FootballGoalDetails>(goals.first().details).kind)
        assertEquals("16'", goals.first().time.label)
        assertEquals(listOf(Score(1, 0), Score(1, 1), Score(1, 2)), goals.map { it.score })
        assertEquals("Marcello Trotta", goals.last().players.first().name)
        val subs = events.filter { it.type == FootballEventType.SUBSTITUTION }
        assertEquals(9, subs.size)
        val sd = assertIs<SubstitutionDetails>(subs.first().details)
        assertEquals("Pah Franck Gouhon", sd.playerOn!!.name)
        assertEquals("Vinicius Mendonca Santa Rosa", sd.playerOff!!.name)
        assertEquals("90'+3", subs.last().time.label)
        assertEquals(2, subs.last().period.number)
        assertEquals(FootballEventType.GAME_END, events.last().type)
    }

    @Test
    fun liveClockLabels() {
        val m = MaltaMapper("malta-premier")
        assertEquals(GameState.INTERMISSION, m.gameState("LIVE", true, "HT"))
        assertEquals(GameState.LIVE, m.gameState("LIVE", true, "60'"))
        assertEquals(GameState.SCHEDULED, m.gameState("SCHEDULED", false, null))
        val match = OpenScoreJson.decodeFromString(MtMatch.serializer(), SampleFetcher.samplesDir("football", "malta-premier").resolve("match.final.json").readText())
        val result = OpenScoreJson.decodeFromString(MtResult.serializer(), SampleFetcher.samplesDir("football", "malta-premier").resolve("result.final.json").readText())
        val live = m.game(match.copy(status = "LIVE", isLive = true, matchTime = "60'"), result.copy(status = "LIVE", isLive = true, matchTime = "60'"), withEvents = true)
        assertEquals(GameState.LIVE, live.state)
        assertEquals("2H", live.clock!!.period.label)
        assertEquals("60'", live.clock.time.label)
        assertEquals(true, live.clock.running)
        assertEquals(2, live.periodScores.size)
        val ht = m.game(match.copy(status = "LIVE", isLive = true, matchTime = "HT"), result.copy(status = "LIVE", isLive = true, matchTime = "HT"), withEvents = true)
        assertEquals(GameState.INTERMISSION, ht.state)
        assertEquals(false, ht.clock!!.running)
    }

    @Test
    fun preMatchAndLineups() = runTest {
        val g = mt.game(MaltaSamples.PRE_ID)
        assertEquals(GameState.SCHEDULED, g.state)
        assertNull(g.score); assertNull(g.clock)
        assertEquals(emptyList(), g.events)
        assertTrue(mt.lineups(MaltaSamples.PRE_ID).isEmpty())

        val lineups = mt.lineups(MaltaSamples.FINAL_ID)
        assertEquals(2, lineups.size)
        val home = lineups.first()
        assertEquals("AGIUS Gilbert", home.headCoach)
        assertEquals(11, home.groups.first { it.kind == LineupGroupKind.STARTERS }.players.size)
        assertEquals(11, home.groups.first { it.kind == LineupGroupKind.BENCH }.players.size)
        assertEquals("GK", home.groups.first().players.first { it.position == "GK" }.position)
        assertNull(home.formation, "no formations in this feed")
    }

    @Test
    fun standingsTeamRosterPlayer() = runTest {
        val table = mt.standings()
        assertEquals(1, table.groups.size)
        assertEquals(12, table.rows.size)
        assertEquals("Floriana", table.rows.first().team.name)
        assertEquals(10, table.rows.first().points)
        assertNull(table.rows.first().goalsFor, "the feed only publishes goal difference")

        val last = mt.standings("2026")
        assertTrue(last.groups.size > 1, "2025/26 has several phases/groups")
        assertEquals("group", last.grouping)

        val t = mt.team(MaltaSamples.TEAM_ID)
        assertEquals("Hamrun Spartans", t.name)
        assertEquals("MLT", t.country)
        assertFailsWith<NotFoundException> { mt.team("1") }

        assertFalse(mt.supports(Capability.ROSTER), "MFA's current roster endpoint ignores the requested team")
        assertFailsWith<UnsupportedCapabilityException> { mt.roster(MaltaSamples.TEAM_ID) }

        val p = mt.player(MaltaSamples.PLAYER_ID)
        assertEquals("Marcello Trotta", p.name)
        assertEquals(LocalDate(1992, 9, 29), p.birthDate)
        assertEquals("Italy", p.nationality)
        assertEquals(MaltaSamples.TEAM_ID, p.teamId)
    }

    @Test
    fun notFound() = runTest {
        assertFailsWith<NotFoundException> { mt.game("1") }
    }

    private fun sample(name: String): String =
        SampleFetcher.samplesDir("football", "malta-premier").resolve(name).readText()

    private fun captured(state: String): Game {
        val match = OpenScoreJson.decodeFromString(MtMatch.serializer(), sample("match.$state.json"))
        val result = OpenScoreJson.decodeFromString(MtResult.serializer(), sample("result.$state.json"))
        return MaltaMapper("malta-premier").game(match, result, withEvents = true)
    }

    /**
     * The 2026-09-13 capture of match 53142621 dropped `isLive` for the last four minutes while
     * `status` still read `RUNNING`, and 12 polls mapped to UNKNOWN. UNKNOWN is not live, so
     * `wantsScorePoll` stops asking and the score freezes through stoppage time.
     */
    @Test
    fun stoppageTimeStaysLiveWhenTheFeedDropsIsLive() {
        val g = captured("live-stoppage")

        assertEquals(GameState.LIVE, g.state, "RUNNING is a live status even with isLive false")
        assertEquals(Score(2, 2), g.score)
        val clock = assertNotNull(g.clock)
        assertEquals(FootballPeriods.SECOND_HALF, clock.period)
        assertEquals("90'+4", clock.time.label)
        assertEquals(true, clock.running)
    }

    /**
     * The same `90+N'` shape one half earlier, while `isLive` was still true, always mapped
     * correctly. Kept so the next reader can see the minute format was never the fault.
     */
    @Test
    fun firstHalfStoppageWasNeverTheProblem() {
        val g = captured("live-first-half")

        assertEquals(GameState.LIVE, g.state)
        assertEquals("45'+8", assertNotNull(g.clock).time.label)
        assertEquals(FootballPeriods.FIRST_HALF, g.clock!!.period)
    }

    @Test
    fun theHalfTimeBreakStopsTheClockAndTheSecondHalfRestartsIt() {
        val ht = captured("halftime")
        assertEquals(GameState.INTERMISSION, ht.state)
        assertEquals(Score(1, 2), ht.score)
        assertEquals(false, assertNotNull(ht.clock).running)

        val second = captured("live")
        assertEquals(GameState.LIVE, second.state)
        assertEquals(FootballPeriods.SECOND_HALF, assertNotNull(second.clock).period)
        assertEquals("52'", second.clock!!.time.label)
        assertEquals(true, second.clock.running)
    }

    /**
     * `status` and `isLive` disagree at both ends of the match, so neither alone decides it.
     * Every pair here is from the capture; the old test invented `status = "LIVE"`, which this
     * feed does not send, and so never exercised the real values.
     */
    @Test
    fun statusAndIsLiveDisagreeAtBothEndsOfTheMatch() {
        val m = MaltaMapper("malta-premier")
        // Kick-off: isLive leads, status lags.
        assertEquals(GameState.LIVE, m.gameState("SCHEDULED", true, "3'"))
        // Stoppage time: status holds, isLive drops.
        assertEquals(GameState.LIVE, m.gameState("RUNNING", false, "90+4'"))
        assertEquals(GameState.INTERMISSION, m.gameState("RUNNING", true, "HT"))
        assertEquals(GameState.LIVE, m.gameState("RUNNING", true, "52'"))
        assertEquals(GameState.FINAL, m.gameState("PLAYED", false, "FT"))
        assertEquals(GameState.SCHEDULED, m.gameState("SCHEDULED", false, null))
    }
}
