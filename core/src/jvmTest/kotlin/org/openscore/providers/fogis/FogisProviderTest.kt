package org.openscore.providers.fogis

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.openscore.model.GameEnding
import org.openscore.model.GameState
import org.openscore.model.LineupGroupKind
import org.openscore.model.PeriodType
import org.openscore.model.Score
import org.openscore.model.football.FootballEventType
import org.openscore.model.football.FootballGoalDetails
import org.openscore.model.football.SubstitutionDetails
import org.openscore.net.SimpleXml
import org.openscore.provider.Capability
import org.openscore.provider.NotFoundException
import org.openscore.testing.FogisSamples
import org.openscore.testing.SampleFetcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class FogisProviderTest {

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-09-12T08:00:00Z")
    }
    private val fetcher = FogisSamples.register(SampleFetcher())
    private val fogis = FogisProvider(fetcher, clock = fixedClock)

    @Test
    fun everySampleParses() {
        val dir = SampleFetcher.samplesDir("football", "fogis-livescore")
        val files = dir.listFiles { f -> f.extension == "xml" }!!
        assertTrue(files.size >= 15)
        for (f in files) {
            val root = SimpleXml.parse(f.readText())
            assertTrue(root.name.isNotBlank(), f.name)
        }
    }

    @Test
    fun allNationalGamesOnADay() = runTest {
        val games = fogis.gamesOn(LocalDate.parse(FogisSamples.DAY))
        assertEquals(90, games.size)
        assertTrue(games.all { it.state == GameState.SCHEDULED && it.score == null })
        assertEquals(games.sortedWith(compareBy({ it.startTime }, { it.competition })), games)
        val first = games.first()
        assertEquals("2026-09-12T10:00:00Z", first.startTime.toString(), "12:00 CEST → 10:00Z")
        assertEquals("Div 2 Norra Svealand, herr 2026", first.competition)
        assertEquals("Enskede IK", first.home.name)
        assertEquals("26019", first.home.id)
        assertTrue(games.any { it.competition == "Allsvenskan 2026" })
        assertTrue(games.map { it.competition }.distinct().size > 10, "many competitions in one feed")

        assertTrue(fogis.gamesOn(LocalDate(2025, 12, 1)).isEmpty(), "status=403 outside the season")
        assertTrue(fogis.gamesOn(LocalDate(2026, 12, 31)).isEmpty())
    }

    @Test
    fun finalLeagueMatch() = runTest {
        val g = fogis.game(FogisSamples.FINAL_ID)
        assertEquals(GameState.FINAL, g.state)
        assertEquals(GameEnding.REGULATION, g.ending)
        assertEquals("Superettan 2026", g.competition)
        assertEquals("GIF Sundsvall", g.home.name)
        assertEquals("Örebro", g.away.name, "entity-decoded")
        assertEquals(Score(0, 3), g.score)
        assertEquals("NP3 Arena", g.venue)
        assertEquals(listOf(0 to 1, 0 to 2), g.periodScores.map { it.home to it.away })
        assertEquals("8", g.stats["shots"]!!.home)
        assertEquals("4", g.stats["shotsOnTarget"]!!.home)
        assertNull(g.clock)
        val events = assertNotNull(g.events)
        assertEquals(FootballEventType.PERIOD_START, events.first().type)
        assertEquals(FootballEventType.GAME_END, events.last().type)
        val goals = events.filter { it.type.isGoal }
        assertEquals(3, goals.size)
        assertEquals(listOf("17'", "59'", "80'"), goals.map { it.time.label })
        assertEquals(listOf(Score(0, 1), Score(0, 2), Score(0, 3)), goals.map { it.score })
        assertEquals(2, goals.last().period.number)
        val gd = assertIs<FootballGoalDetails>(goals.first().details)
        assertNotNull(gd.scorer)
        assertTrue(gd.scorer.headshotUrl!!.contains("/img/players/"))
        val subs = events.filter { it.type == FootballEventType.SUBSTITUTION }
        assertEquals(10, subs.size)
        val sd = assertIs<SubstitutionDetails>(subs.first().details)
        assertNotNull(sd.playerOn); assertNotNull(sd.playerOff)
        assertEquals(2, events.count { it.type == FootballEventType.YELLOW_CARD })
        assertTrue(events.any { it.rawType.startsWith("F:12") && it.type == FootballEventType.OTHER }, "shots kept as OTHER")
    }

    @Test
    fun cupShootout() = runTest {
        val g = fogis.game(FogisSamples.SHOOTOUT_ID)
        assertEquals(GameState.FINAL, g.state)
        assertEquals(GameEnding.SHOOTOUT, g.ending)
        assertEquals(Score(4, 4), g.score, "shootout goals stripped from the feed's 10–11")
        assertEquals(listOf("1H", "2H", "ET1", "ET2", "PENS"), g.periodScores.map { it.period.label })
        assertEquals(6 to 7, g.periodScores.last().let { it.home to it.away })
        assertEquals(PeriodType.SHOOTOUT, g.periodScores.last().period.type)
        val events = assertNotNull(g.events)
        val attempts = events.filter { it.type == FootballEventType.SHOOTOUT_ATTEMPT }
        assertTrue(attempts.size >= 13)
        assertEquals(8, events.count { it.type.isGoal })
        assertTrue(events.filter { it.type.isGoal }.none { it.period.type == PeriodType.SHOOTOUT })
    }

    @Test
    fun liveClockFromHalfStarted() {
        val root = SimpleXml.parse(SampleFetcher.samplesDir("football", "fogis-livescore").resolve("game-info.final.xml").readText())
        val text = SampleFetcher.samplesDir("football", "fogis-livescore").resolve("game-info.final.xml").readText()
            .replace("<status id=\"6\" desc=\"FINISHED\"/>", "<status id=\"2\" desc=\"SECOND_HALF_IN_PROGRESS\"/>")
        val live = SimpleXml.parse(text)
        val mapper = FogisMapper("fogis")
        // Second half started 20:00:32 local (18:00:32Z); now = 18:12:32Z → 12:00 into the half.
        val g = mapper.game(live.child("game")!!, live.child("events"), Instant.parse("2026-09-10T18:12:32Z"), "1")
        assertEquals(GameState.LIVE, g.state)
        assertEquals("2H", g.clock!!.period.label)
        assertEquals(12.minutes, g.clock.time.elapsed)
        assertEquals("58'", g.clock.time.label)
        assertEquals(true, g.clock.running)
        val ht = SimpleXml.parse(text.replace("id=\"2\" desc=\"SECOND_HALF_IN_PROGRESS\"", "id=\"3\" desc=\"HALFTIME\""))
        val gHt = mapper.game(ht.child("game")!!, ht.child("events"), Instant.parse("2026-09-10T17:50:00Z"), "1")
        assertEquals(GameState.INTERMISSION, gHt.state)
        assertEquals(false, gHt.clock!!.running)
        assertNotNull(root)
    }

    @Test
    fun preMatchAndLineups() = runTest {
        val g = fogis.game(FogisSamples.PRE_ID)
        assertEquals(GameState.SCHEDULED, g.state)
        assertEquals("Allsvenskan 2026", g.competition)
        assertNull(g.score); assertNull(g.clock)
        assertEquals(emptyList(), g.events)
        assertTrue(fogis.lineups(FogisSamples.PRE_ID).isEmpty())

        val lineups = fogis.lineups(FogisSamples.FINAL_ID)
        assertEquals(2, lineups.size)
        val home = lineups.first()
        assertEquals("442", home.formation)
        assertEquals("GIF Sundsvall", home.team.name)
        assertEquals(11, home.groups.first { it.kind == LineupGroupKind.STARTERS }.players.size)
        assertEquals(9, home.groups.first { it.kind == LineupGroupKind.BENCH }.players.size)
        assertEquals("GK", home.groups.first().players.first().position)
        assertEquals(13, home.groups.first().players.first().jerseyNumber)
        assertEquals("532", lineups.last().formation)
    }

    @Test
    fun notFoundAndCapabilities() = runTest {
        assertFailsWith<NotFoundException> { fogis.game("1") }
        assertTrue(!fogis.supports(Capability.STANDINGS) && !fogis.supports(Capability.TEAM) && !fogis.supports(Capability.ROSTER))
    }

    @Test
    fun tournamentIdsByCompetition() = runTest {
        assertEquals(setOf(FogisSamples.ALLSVENSKAN_2026), fogis.tournamentIds(FogisCompetition.ALLSVENSKAN))
        assertEquals(setOf(FogisSamples.SUPERETTAN_2026), fogis.tournamentIds(FogisCompetition.SUPERETTAN))
        // Two "Svenska Cupen 2026/27 omg. …" tournaments, indistinguishable by name; the men's one shares teams with the men's leagues.
        assertEquals(setOf(FogisSamples.CUP_MEN_R1_2), fogis.tournamentIds(FogisCompetition.SVENSKA_CUPEN))
        val cupNames = fogis.tournaments().filter { it.name.startsWith("Svenska Cupen") }
        assertEquals(2, cupNames.size)
    }

    @Test
    fun gamesOnNarrowedToATournament() = runTest {
        val all = fogis.gamesOn(LocalDate.parse(FogisSamples.DAY))
        val allsvenskan = fogis.gamesOn(LocalDate.parse(FogisSamples.DAY), setOf(FogisSamples.ALLSVENSKAN_2026))
        assertTrue(allsvenskan.isNotEmpty())
        assertTrue(allsvenskan.all { it.competition == "Allsvenskan 2026" })
        assertEquals(all.filter { it.competition == "Allsvenskan 2026" }, allsvenskan)
    }

    @Test
    fun liveOverviewHasClockScoreAndPeriodScoresButNoTimeline() = runTest {
        val at = object : Clock {
            override fun now(): Instant = Instant.parse("2026-09-13T12:38:17Z") // the overview's own `created` stamp
        }
        val games = FogisProvider(fetcher, clock = at).gamesOn(LocalDate.parse(FogisSamples.LIVE_DAY))
        assertEquals(49, games.size)
        assertEquals(9, games.count { it.state.isLive })
        val hammarby = games.first { it.id == FogisSamples.LIVE_ID }
        assertEquals(GameState.LIVE, hammarby.state)
        assertEquals(Score(1, 1), hammarby.score)
        // Kick-off (HALFSTARTED day-time 14:00:xx local) is in the overview, so the minute is known on the day view.
        val clock = assertNotNull(hammarby.clock)
        assertEquals(1, clock.period.number)
        assertTrue(clock.time.label!!.removeSuffix("'").toInt() in 36..40, clock.time.label)
        assertEquals(true, clock.running)
        assertNull(hammarby.events, "the overview's event list is partial, so it is not the timeline")
        val secondHalf = games.first { it.rawState?.startsWith("SECOND_HALF") == true }
        assertEquals(2, secondHalf.clock?.period?.number)
        assertTrue(secondHalf.periodScores.any { it.period.number == 1 }, "first-half score from the overview's HALFENDED/half-time attributes")
    }
}
