package org.openscore.providers.del

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.openscore.clubs.Clubs
import org.openscore.model.GameEnding
import org.openscore.model.GameState
import org.openscore.model.LineupGroupKind
import org.openscore.model.PeriodType
import org.openscore.model.Score
import org.openscore.model.StageKind
import org.openscore.model.hockey.GoalDetails
import org.openscore.model.hockey.HockeyEventType
import org.openscore.model.hockey.PenaltyDetails
import org.openscore.model.hockey.ShootoutAttemptDetails
import org.openscore.model.hockey.ShotDetails
import org.openscore.model.hockey.ShotOutcome
import org.openscore.model.hockey.Strength
import org.openscore.net.OpenScoreJson
import org.openscore.provider.Capability
import org.openscore.provider.NotFoundException
import org.openscore.provider.UnsupportedCapabilityException
import org.openscore.testing.DelSamples
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

class DelProviderTest {

    private val fetcher = DelSamples.register(SampleFetcher())
    private val del = DelProvider(fetcher)

    @Test
    fun everySampleParses() {
        val strategies: List<Pair<String, DeserializationStrategy<*>>> = listOf(
            "tournament-list" to ListSerializer(DelTournament.serializer()),
            "games." to ListSerializer(DelGame.serializer().nullable),
            "game-situations" to ListSerializer(DelSituation.serializer()),
            "game-results" to ListSerializer(DelPeriodResult.serializer()),
            "game-lineup" to ListSerializer(DelLineupSlot.serializer()),
            "game-officials" to ListSerializer(DelOfficial.serializer()),
            "team-list" to ListSerializer(DelTeam.serializer()),
            "team-standings" to ListSerializer(DelStandingsRow.serializer()),
            "team-members" to ListSerializer(DelMember.serializer()),
            "statistics.points" to ListSerializer(DelMember.serializer()),
            "statistics.goals" to ListSerializer(DelMember.serializer()),
            "error" to DelErrorResponse.serializer(),
        )
        val failures = mutableListOf<String>()
        var typed = 0
        for (file in SampleFetcher.samplesDir("hockey", "del").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }) {
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
        assertTrue(typed >= 30, "typed=$typed")
    }

    @Test
    fun gameIds() {
        assertEquals(4389 to 77, DelMapper.parseGameId("4389t77"))
        assertEquals("4389t77", DelMapper.gameId(4389, 77))
        assertFailsWith<IllegalArgumentException> { DelMapper.parseGameId("4389") }
    }

    @Test
    fun gamesOnAGermanDay() = runTest {
        val games = del.gamesOn(LocalDate.parse(DelSamples.FINAL_DAY))
        assertEquals(6, games.size)
        assertEquals(games.sortedBy { it.startTime }, games)
        val url = fetcher.requests.first { it.contains("requestName=games") }
        assertTrue(url.contains("dateFrom=2026-09-17%2022:00:00&dateTo=2026-09-18%2021:59:59"), "Berlin day → UTC window: $url")

        val g = games.first { it.id == DelSamples.SHOOTOUT_GAME_ID }
        assertEquals("del", g.leagueId)
        assertEquals("77", g.seasonId)
        assertEquals(StageKind.REGULAR, g.stage)
        assertNull(g.competition)
        assertEquals("2026-09-18T17:30:00Z", g.startTime.toString())
        assertEquals("RBM", g.home.id)
        assertEquals("EHC Red Bull München", g.home.name)
        assertEquals("Adler Mannheim", g.away.name)
        assertEquals("red-bull-munchen", g.home.clubId)
        assertEquals("adler-mannheim", g.away.clubId)
        assertEquals("https://www.penny-del.org/fileadmin/images/teams/2023/team_12.svg", g.home.logoUrl)
        assertEquals(GameState.FINAL, g.state)
        assertEquals(Score(4, 3), g.score)
        assertEquals(GameEnding.SHOOTOUT, g.ending)
        assertEquals(listOf("1", "2", "3", "OT", "SO"), g.periodScores.map { it.period.label })
        assertEquals(PeriodType.SHOOTOUT, g.periodScores.last().period.type)
        assertEquals(1 to 0, g.periodScores.last().let { it.home to it.away })
        assertNull(g.clock)
        assertNull(g.events, "the listing carries no events")

        val regulation = games.first { it.id == "4390t77" }
        assertEquals(GameEnding.REGULATION, regulation.ending)
        assertEquals(3, regulation.periodScores.size)
        assertEquals("Lanxess Arena", regulation.venue)
    }

    @Test
    fun scheduledAndEmptyDays() = runTest {
        val games = del.gamesOn(LocalDate.parse(DelSamples.SCHEDULED_DAY))
        assertEquals(7, games.size)
        val g = games.first { it.id == DelSamples.SCHEDULED_GAME_ID }
        assertEquals(GameState.SCHEDULED, g.state)
        assertNull(g.score)
        assertTrue(g.periodScores.isEmpty())
        assertEquals("2026-09-20T12:00:00Z", g.startTime.toString())
        assertEquals("MAN", g.home.id)
        assertEquals("EBB", g.away.id)

        assertEquals(emptyList(), del.gamesOn(LocalDate.parse(DelSamples.EMPTY_DAY)))
    }

    @Test
    fun finalGameWithEventsAndStats() = runTest {
        val g = del.game(DelSamples.FINAL_GAME_ID)
        assertEquals(GameState.FINAL, g.state)
        assertEquals(Score(4, 2), g.score)
        assertEquals(GameEnding.REGULATION, g.ending)
        assertEquals("Uber Arena", g.venue)
        assertEquals("Eisbären Berlin", g.home.name)
        assertEquals("Game Completed/100%", g.rawState)

        val events = assertNotNull(g.events)
        assertEquals(19, events.size)
        assertEquals(events.sortedWith(compareBy({ it.period.number }, { it.time.elapsed?.inWholeSeconds ?: 0 }, { it.sortOrder ?: 0 })), events)

        val goals = events.filter { it.type == HockeyEventType.GOAL }
        assertEquals(6, goals.size)
        val first = goals.first()
        assertEquals("1", first.period.label)
        assertEquals(16.minutes + 27.seconds, first.time.elapsed)
        assertEquals(Score(1, 0), first.score)
        assertEquals("EBB", first.team?.id)
        val details = assertIs<GoalDetails>(first.details)
        assertEquals("Manuel Wiederer", details.scorer?.name, "family name resolved through the roster")
        assertEquals("1823", details.scorer?.id)
        assertEquals(21, details.scorer?.jerseyNumber)
        assertEquals(listOf("Brandon Davidson", "Mitchell Reinke"), details.assists.map { it.name }, "assists resolved by jersey number")
        assertEquals(listOf("44", "5").map { it }, details.assists.map { it.jerseyNumber.toString() })
        assertEquals(Strength.EV, details.strength)
        assertEquals(false, details.emptyNet)

        val secondPeriodGoal = goals[1]
        assertEquals("2", secondPeriodGoal.period.label)
        assertEquals(1.minutes + 47.seconds, secondPeriodGoal.time.elapsed, "game-elapsed 21:47 is 01:47 of period 2")

        val emptyNet = goals.last()
        assertEquals(true, assertIs<GoalDetails>(emptyNet.details).emptyNet)
        assertEquals("Eric Mik", emptyNet.players.first().name)
        assertEquals(Score(4, 2), emptyNet.score)

        val penalties = events.filter { it.type == HockeyEventType.PENALTY }
        assertEquals(9, penalties.size)
        val p = assertIs<PenaltyDetails>(penalties.first().details)
        assertEquals(2, p.minutes)
        assertEquals("Too Many Players on the Ice", p.infraction)
        assertEquals("Leonhard Pföderl", p.player?.name)

        val goalieChanges = events.filter { it.type == HockeyEventType.GOALIE_CHANGE }
        assertEquals(4, goalieChanges.size)
        assertEquals("Jack LaFontaine", goalieChanges.first().players.single().name)
        assertEquals("GOL_KPR_OUT", goalieChanges[2].rawType)

        assertEquals("21", g.stats["shotsOnGoal"]?.home)
        assertEquals("30", g.stats["shotsOnGoal"]?.away)
        assertEquals("0/3", g.stats["powerPlays"]?.home)
        assertEquals("05:11", g.stats["powerPlayTime"]?.home)
        assertEquals("10", g.stats["penaltyMinutes"]?.home)
        assertEquals("28", g.stats["faceoffsWon"]?.home)
    }

    @Test
    fun shootoutGame() = runTest {
        val g = del.game(DelSamples.SHOOTOUT_GAME_ID)
        assertEquals(GameEnding.SHOOTOUT, g.ending)
        assertEquals("Game Completed/GWS/100%", g.rawState)
        val events = assertNotNull(g.events)
        val goals = events.filter { it.type == HockeyEventType.GOAL }
        assertEquals(6, goals.size, "the deciding shoot-out GOL is not a goal event")
        assertTrue(goals.none { it.period.type == PeriodType.SHOOTOUT })
        val attempts = events.filter { it.type == HockeyEventType.SHOOTOUT_ATTEMPT }
        assertEquals(6, attempts.size)
        assertTrue(attempts.all { it.period.label == "SO" && it.time.elapsed == null })
        assertEquals(listOf(true, false, false, true, true, false), attempts.map { assertIs<ShootoutAttemptDetails>(it.details).scored })
        assertEquals("RBM", attempts.first().team?.id)
        assertEquals(events.takeLast(6), attempts, "attempts sort after everything else")
    }

    @Test
    fun playoffOvertimeGame() = runTest {
        val g = del.game(DelSamples.PLAYOFF_OT_GAME_ID)
        assertEquals("76", g.seasonId)
        assertEquals(StageKind.PLAYOFF, g.stage)
        assertEquals("Final, game 4", g.competition)
        assertEquals(GameEnding.OVERTIME, g.ending)
        assertEquals(listOf("1", "2", "3", "OT"), g.periodScores.map { it.period.label })
        val ot = assertNotNull(g.events).last { it.type == HockeyEventType.GOAL }
        assertEquals("OT", ot.period.label)
        assertEquals(23.minutes + 45.seconds, ot.time.elapsed, "83:45 counts from 60:00 whatever the overtime number")
        assertEquals("EBB", g.home.id)
        assertEquals("EBB", g.home.name, "no team list for the playoff tournament in the samples: the code stands in")
        assertEquals("Esposito", ot.players.first().name, "no roster either: the feed's family name stands in")
        assertEquals("MAN", ot.team?.id)
    }

    @Test
    fun scheduledGameAndUnknownGame() = runTest {
        val g = del.game(DelSamples.SCHEDULED_GAME_ID)
        assertEquals(GameState.SCHEDULED, g.state)
        assertNull(g.score)
        assertEquals(emptyList(), g.events)
        assertTrue(g.stats.isEmpty())
        assertTrue(fetcher.requests.none { it.contains("gameResults") }, "no results read before the game")

        assertFailsWith<NotFoundException> { del.game(DelSamples.UNKNOWN_GAME_ID) }
    }

    @Test
    fun eventsWithShots() = runTest {
        val events = del.events(DelSamples.FINAL_GAME_ID)
        assertEquals(127, events.size)
        val shots = events.filter { it.details is ShotDetails }
        assertEquals(108, shots.size)
        assertEquals(51, shots.count { (it.details as ShotDetails).outcome == ShotOutcome.ON_GOAL }, "45 saved + 6 goals = the two teams' shots on goal")
        assertEquals(36, shots.count { it.type == HockeyEventType.BLOCKED_SHOT })
        assertEquals(21, shots.count { it.type == HockeyEventType.MISSED_SHOT })
        val s = shots.first()
        assertNotNull(s.coordinates)
        assertEquals(0.683, s.coordinates!!.x)
        assertEquals("Korbinian Geibel", s.players.single().name)
        assertEquals(6, events.count { it.type == HockeyEventType.GOAL })
    }

    @Test
    fun lineupsAsLines() = runTest {
        val lineups = del.lineups(DelSamples.FINAL_GAME_ID)
        assertEquals(2, lineups.size)
        val home = lineups.first()
        assertEquals("EBB", home.team.id)
        assertEquals(listOf(LineupGroupKind.GOALIES, LineupGroupKind.LINE, LineupGroupKind.PAIRING, LineupGroupKind.LINE, LineupGroupKind.PAIRING, LineupGroupKind.LINE, LineupGroupKind.PAIRING, LineupGroupKind.LINE, LineupGroupKind.PAIRING), home.groups.map { it.kind })
        assertEquals(listOf("Jack LaFontaine", "Jonas Neffin"), home.groups.first().players.map { it.name })
        assertEquals("GK", home.groups.first().players.first().position)
        val line1 = home.groups.first { it.label == "Line 1" }
        assertEquals(listOf("Ty Ronning", "Gabriel Fontaine", "Liam Kirk"), line1.players.map { it.name })
        assertEquals(listOf(9, 97, 94), line1.players.map { it.jerseyNumber })
        assertEquals("F", line1.players.first().position)
        val pairing1 = home.groups.first { it.label == "Pairing 1" }
        assertEquals(listOf("Adam Smith", "Jonas Müller"), pairing1.players.map { it.name })
        assertEquals("D", pairing1.players.first().position)
        assertEquals(20, home.players.size, "two empty slots in line 4 are dropped")
        assertEquals(21, lineups.last().players.size)
        assertTrue(home.players.all { it.headshotUrl != null })
    }

    @Test
    fun standings() = runTest {
        val table = del.standings()
        assertEquals("77", table.seasonId)
        assertEquals(1, table.groups.size)
        assertEquals(14, table.rows.size)
        val top = table.rows.first()
        assertEquals(1, top.rank)
        assertEquals("FRA", top.team.id)
        assertEquals("Löwen Frankfurt", top.team.name)
        assertEquals("lowen-frankfurt", top.team.clubId)
        assertEquals(3, top.points)
        assertEquals(5, top.goalDifference)
        assertTrue(fetcher.requests.any { it.contains("requestName=tournamentList") }, "the current season comes from tournamentList")

        val last = del.standings("75")
        assertEquals("75", last.seasonId)
        assertTrue(last.rows.all { it.played == 52 })
        val row = last.rows.first()
        assertEquals(row.wins, row.extra["regulationWins"]!!.toInt() + row.extra["overtimeWins"]!!.toInt())
        assertEquals(52, row.wins + row.losses + (row.otherLosses ?: 0))

        assertEquals("75", del.standings("76").seasonId, "a playoff id is answered with its regular season's table")
    }

    @Test
    fun teamRosterAndPlayer() = runTest {
        val team = del.team("ebb")
        assertEquals("EBB", team.id)
        assertEquals("Eisbären Berlin", team.name)
        assertEquals("eisbaren-berlin", team.ref.clubId)
        assertFailsWith<NotFoundException> { del.team("XXX") }

        val roster = del.roster("EBB")
        assertEquals(26, roster.size, "the head coach is not a player")
        val ronning = roster.first { it.id == DelSamples.PLAYER_ID }
        assertEquals("Ty Ronning", ronning.name)
        assertEquals("F", ronning.ref.position)
        assertEquals(9, ronning.ref.jerseyNumber)
        assertEquals(LocalDate.parse("1997-10-20"), ronning.birthDate)
        assertEquals("USA", ronning.nationality)
        assertEquals(178, ronning.heightCm)
        assertEquals(82, ronning.weightKg)
        assertEquals("R", ronning.handedness)
        assertEquals("EBB", ronning.teamId)
        assertEquals("Minnesota", ronning.birthPlace)

        val player = del.player(DelSamples.PLAYER_ID)
        assertEquals("Ty Ronning", player.name)
        assertEquals("Ronning", player.lastName)
        assertFailsWith<NotFoundException> { del.player("abc") }
    }

    @Test
    fun teamSchedule() = runTest {
        val games = del.teamSchedule("EBB", LocalDate.parse("2026-09-17"), LocalDate.parse("2026-09-19"))
        assertEquals(listOf(DelSamples.FINAL_GAME_ID), games.map { it.id })
    }

    @Test
    fun unsupportedAndCapabilities() = runTest {
        assertTrue(del.supports(Capability.LINE_GROUPS))
        assertTrue(del.supports(Capability.EVENT_COORDINATES))
        assertTrue(!del.supports(Capability.CLOCK), "not claimed until the elapsed seconds are seen to move live")
        assertFailsWith<UnsupportedCapabilityException> { del.teamStats("EBB", "77") }
    }

    @Test
    fun mapperRules() {
        assertEquals(GameState.INTERMISSION, DelMapper.gameState(row(progressCode = "Period 1 Ended", progressPerc = 33)))
        assertEquals(GameState.LIVE, DelMapper.gameState(row(progressCode = "Period 2", progressPerc = 50)))
        assertEquals(GameState.LIVE, DelMapper.gameState(row(progressCode = "Something New", progressPerc = 50)))
        assertEquals(GameState.PRE_GAME, DelMapper.gameState(row(progressCode = "Pre Game", progressPerc = 0)))
        assertEquals(GameState.FINAL, DelMapper.gameState(row(progressCode = "Game Completed", progressPerc = 100, scoreByPeriod = "1:0|0:0|0:0|-:-|-:-")))
        assertEquals(GameState.CANCELLED, DelMapper.gameState(row(progressCode = "Game Completed", progressPerc = 100, scoreByPeriod = "-:-|-:-|-:-|-:-|-:-")), "a series game that was never needed")

        val live = DelMapper.game(row(progressCode = "Period 2", progressPerc = 50, gameTime = "1307", scoreByPeriod = "1:0|0:1|-:-|-:-|-:-"), emptyMap())
        assertEquals(GameState.LIVE, live.state)
        assertEquals("2", live.clock?.period?.label)
        assertEquals(1.minutes + 47.seconds, live.clock?.time?.elapsed)
        assertNull(live.clock?.running)
        assertEquals(Score(1, 1), live.score)
        assertEquals(2, live.periodScores.size)

        val overtime = DelMapper.game(row(progressCode = "Overtime", progressPerc = 90, gameTime = "3700"), emptyMap())
        assertEquals("OT", overtime.clock?.period?.label)
        assertEquals(100.seconds, overtime.clock?.time?.elapsed)
        val shootout = DelMapper.game(row(progressCode = "Game Winning Shots", progressPerc = 95, gameTime = "3906"), emptyMap())
        assertEquals("SO", shootout.clock?.period?.label)
        assertNull(shootout.clock?.time?.elapsed)
        val intermission = DelMapper.game(row(progressCode = "Period 2 Ended", progressPerc = 66, gameTime = "2400"), emptyMap())
        assertEquals(false, intermission.clock?.running)

        assertEquals(Strength.PP, DelMapper.strength(setOf("PP1")))
        assertEquals(Strength.SH, DelMapper.strength(setOf("SH1", "EN")))
        assertEquals(Strength.EV, DelMapper.strength(setOf("EQ", "EN")))
        assertNull(DelMapper.strength(emptySet()))
        assertEquals(1307, DelMapper.gameSeconds("21:47"))
        assertNull(DelMapper.gameSeconds(""))
        assertNull(Clubs.clubId("del", "XXX"))
    }

    private fun row(progressCode: String, progressPerc: Int, gameTime: String? = null, scoreByPeriod: String = "-:-|-:-|-:-|-:-|-:-") = DelGame(
        dateTime = 1789905600, gameNumber = 4396, tournamentID = 77, uniqueID = "4396t77", gamePhase = "TAG",
        homeTeam = "MAN", guestTeam = "EBB", scoreByPeriod = scoreByPeriod, progressCode = progressCode, progressCodeName = progressCode,
        progressPerc = progressPerc, homeTeamScore = 1, guestTeamScore = 1, gameTime = gameTime,
    )
}
