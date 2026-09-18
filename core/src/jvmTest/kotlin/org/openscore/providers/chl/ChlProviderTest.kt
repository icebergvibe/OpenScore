package org.openscore.providers.chl

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import org.openscore.model.GameEnding
import org.openscore.model.GameState
import org.openscore.model.LineupGroupKind
import org.openscore.model.PeriodType
import org.openscore.model.Score
import org.openscore.model.StageKind
import org.openscore.model.hockey.GoalDetails
import org.openscore.model.hockey.HockeyEventType
import org.openscore.model.hockey.ShootoutAttemptDetails
import org.openscore.model.hockey.Strength
import org.openscore.net.OpenScoreJson
import org.openscore.provider.Capability
import org.openscore.provider.NotFoundException
import org.openscore.testing.ChlSamples
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

class ChlProviderTest {

    private val fetcher = ChlSamples.register(SampleFetcher())
    private val chl = ChlProvider(fetcher)

    @Test
    fun everySampleParses() {
        val strategies: List<Pair<String, DeserializationStrategy<*>>> = listOf(
            "live-events" to ChlResponse.serializer(ListSerializer(ChlMatch.serializer())),
            "schedule" to ChlResponse.serializer(ListSerializer(ChlMatch.serializer())),
            "team-schedule" to ChlResponse.serializer(ListSerializer(ChlMatch.serializer())),
            "live-event-scoreboard" to ChlResponse.serializer(ChlMatch.serializer()),
            "live-event-lineups" to ChlResponse.serializer(ChlLineups.serializer()),
            "standings-groups" to ChlResponse.serializer(ListSerializer(ChlSeries.serializer())),
            "teams.json" to ChlResponse.serializer(ListSerializer(ChlTeamRef.serializer())),
            "team-players-info" to ChlResponse.serializer(ChlTeamWithAthletes.serializer()),
            "player.json" to ChlResponse.serializer(ChlPlayer.serializer()),
        )
        val failures = mutableListOf<String>()
        var typed = 0
        for (file in SampleFetcher.samplesDir("hockey", "chl").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }) {
            val text = file.readText()
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
    fun scoreboardDay() = runTest {
        val games = chl.gamesOn(LocalDate.parse(ChlSamples.GAME_DATE))
        assertEquals(6, games.size)
        assertTrue(games.all { it.state == GameState.FINAL && it.score != null && it.ending != null })
        val g = games.first { it.id == ChlSamples.FINAL_GAME_ID }
        assertEquals("KooKoo Kouvola", g.home.name)
        assertEquals("KOO", g.home.abbreviation)
        assertTrue(g.home.logoUrl!!.endsWith("/teams/107.png"))
        assertEquals(Score(1, 4), g.score)
        assertEquals(StageKind.REGULAR, g.stage)
        assertNull(g.clock, "CHL has no clock")

        val upcoming = chl.gamesOn(LocalDate.parse(ChlSamples.PRE_DATE))
        assertEquals(6, upcoming.size)
        assertTrue(upcoming.all { it.state == GameState.SCHEDULED && it.score == null })
        assertTrue(fetcher.requests.none { it.contains("schedule-") }, "dates inside the live window never touch the schedule")
    }

    @Test
    fun scheduleFallback() = runTest {
        val games = chl.gamesOn(LocalDate(2026, 11, 10))
        assertTrue(fetcher.requests.any { it.contains("schedule-") })
        assertTrue(games.all { it.state == GameState.SCHEDULED })

        val scheduleReads = fetcher.requests.count { it.contains("schedule-") }
        assertTrue(chl.gamesOn(LocalDate(2026, 9, 25)).isEmpty(), "a quiet day inside the live window has no games")
        assertEquals(scheduleReads, fetcher.requests.count { it.contains("schedule-") }, "inside the live window the schedule is never read")
    }

    @Test
    fun finalGameEvents() = runTest {
        val g = chl.game(ChlSamples.FINAL_GAME_ID)
        assertEquals(GameState.FINAL, g.state)
        assertEquals(GameEnding.REGULATION, g.ending)
        assertEquals(listOf("1", "2", "3"), g.periodScores.map { it.period.label })
        assertEquals(g.score!!.home, g.periodScores.sumOf { it.home })
        assertEquals(g.score.away, g.periodScores.sumOf { it.away })
        val events = assertNotNull(g.events)
        assertEquals(HockeyEventType.PERIOD_START, events.first().type)
        assertEquals(HockeyEventType.GAME_END, events.last().type)
        val goals = events.filter { it.type == HockeyEventType.GOAL }
        assertEquals(5, goals.size)
        assertTrue(goals.all { it.score != null && it.players.isNotEmpty() && it.team != null })
        val gd = assertIs<GoalDetails>(goals.first().details)
        assertNotNull(gd.scorer)
        assertNull(goals.first().coordinates, "no coordinates in CHL")
        assertTrue(events.any { it.type == HockeyEventType.PENALTY })
        assertTrue(events.any { it.type == HockeyEventType.GOALIE_CHANGE })
        // regularTime is absolute: 1491 s → 04:51 of period 2
        val p2 = events.first { it.period.number == 2 && it.time.elapsed != null && it.time.elapsed > 0.seconds }
        assertTrue(p2.time.elapsed!! < 20.minutes)
    }

    @Test
    fun overtimeAndShootout() = runTest {
        val ot = chl.game(ChlSamples.OT_GAME_ID)
        assertEquals(GameEnding.OVERTIME, ot.ending)
        assertEquals(listOf("1", "2", "3", "OT"), ot.periodScores.map { it.period.label })
        assertEquals(Score(3, 2), ot.score)

        val so = chl.game(ChlSamples.SHOOTOUT_GAME_ID)
        assertEquals(GameEnding.SHOOTOUT, so.ending)
        assertEquals(listOf("1", "2", "3", "OT", "SO"), so.periodScores.map { it.period.label })
        assertEquals(0 to 1, so.periodScores.last().let { it.home to it.away })
        val events = assertNotNull(so.events)
        val attempts = events.filter { it.type == HockeyEventType.SHOOTOUT_ATTEMPT }
        assertEquals(12, attempts.size)
        assertEquals(1, attempts.count { assertIs<ShootoutAttemptDetails>(it.details).scored })
        assertEquals(6, events.count { it.type == HockeyEventType.GOAL }, "3+3 regulation goals; the shootout 'goal' is not a goal")
        assertTrue(events.filter { it.type == HockeyEventType.GOAL }.none { it.period.type == PeriodType.SHOOTOUT })
        val pp = events.first { it.type == HockeyEventType.GOAL && it.rawType == "goal" && (it.details as GoalDetails).strength == Strength.PP }
        assertEquals(Score(2, 1), pp.score)
    }

    @Test
    fun titleParsing() {
        assertEquals(Score(2, 1), ChlMapper.scoreFromTitle("Goal, 2:1, PP"))
        assertEquals(Strength.PP, ChlMapper.strength("Goal, 2:1, PP"))
        assertEquals(Strength.EV, ChlMapper.strength("Goal, 1:0"))
        assertEquals(Strength.PS, ChlMapper.strength("Goal, 3:4, Penalty Shot"))
        assertEquals(Strength.SH, ChlMapper.strength("Goal, 1:1, SH"))
        assertNull(ChlMapper.scoreFromTitle("Penalty"))
    }

    @Test
    fun preGame() = runTest {
        val g = chl.game(ChlSamples.PRE_GAME_ID)
        assertEquals(GameState.SCHEDULED, g.state)
        assertNull(g.score)
        assertTrue(g.periodScores.isEmpty())
        assertEquals(emptyList(), g.events)
        val lineups = chl.lineups(ChlSamples.PRE_GAME_ID)
        assertEquals(2, lineups.size)
        assertTrue(lineups.first().players.isNotEmpty(), "lineups are published pre-game")
    }

    @Test
    fun lineupsHaveLinesPairsAndCoach() = runTest {
        val home = chl.lineups(ChlSamples.FINAL_GAME_ID).first()
        assertEquals("KooKoo Kouvola", home.team.name)
        assertEquals("Jouko Myrrä", home.headCoach)
        assertEquals(LineupGroupKind.GOALIES, home.groups.first().kind)
        assertEquals(2, home.groups.first().players.size)
        assertEquals(3, home.groups.first { it.label == "Line 1" }.players.size)
        assertEquals(2, home.groups.first { it.label == "Pairing 1" }.players.size)
        assertEquals(4, home.groups.count { it.kind == LineupGroupKind.LINE })
        assertTrue(home.players.all { it.jerseyNumber != null && it.position in setOf("GK", "DE", "FW") })
    }

    @Test
    fun standings() = runTest {
        val table = chl.standings()
        assertEquals(ChlProvider.CURRENT_SEASON, table.seasonId)
        assertEquals("group", table.grouping)
        assertEquals(listOf("Regular Season"), table.groups.map { it.label })
        assertEquals(24, table.rows.size)
        val top = table.rows.first()
        assertEquals("Skellefteå AIK", top.team.name)
        assertEquals(1, top.rank)
        assertEquals(9, top.points)
        assertEquals(3, top.played)
        assertEquals(3, top.wins)
        assertEquals("3", top.extra["regulationWins"])
    }

    @Test
    fun teamRosterPlayer() = runTest {
        val t = chl.team(ChlSamples.TEAM_ID)
        assertEquals("KooKoo Kouvola", t.name)
        assertEquals("FIN", t.country)
        assertEquals(t.id, chl.team("KOO").id)
        assertFailsWith<NotFoundException> { chl.team("ZZZ") }

        val roster = chl.roster(ChlSamples.TEAM_ID)
        assertTrue(roster.size > 20)
        assertTrue(roster.all { it.teamId == ChlSamples.TEAM_ID && it.ref.position != null })
        val first = roster.first()
        assertEquals("Samuel Valkeejärvi", first.name)
        assertEquals("FIN", first.nationality)
        assertEquals(182, first.heightCm)
        assertEquals("L", first.handedness)
        assertEquals(LocalDate(2001, 5, 11), first.birthDate)

        val p = chl.player(ChlSamples.PLAYER_ID)
        assertEquals("Roope Tossavainen", p.name)
        assertEquals(ChlSamples.TEAM_ID, p.teamId)
        assertEquals(LocalDate(2004, 12, 5), p.birthDate)
        assertEquals(84, p.weightKg)
    }

    @Test
    fun missingFileIsNotFound() = runTest {
        assertFailsWith<NotFoundException> { chl.game("nope") }
        assertTrue(!chl.supports(Capability.CLOCK))
        assertTrue(!chl.supports(Capability.EVENT_COORDINATES))
        assertTrue(chl.supports(Capability.LINE_GROUPS))
    }
}
