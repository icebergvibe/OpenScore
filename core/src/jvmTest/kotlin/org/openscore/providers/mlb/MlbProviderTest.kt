package org.openscore.providers.mlb

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.openscore.model.GameEnding
import org.openscore.model.GameState
import org.openscore.model.LineupGroupKind
import org.openscore.model.PeriodType
import org.openscore.model.Score
import org.openscore.model.StageKind
import org.openscore.model.StatPair
import org.openscore.model.baseball.BaseballEventType
import org.openscore.model.baseball.BaseballSituation
import org.openscore.model.baseball.InningHalf
import org.openscore.model.baseball.PlateAppearanceDetails
import org.openscore.provider.Capability
import org.openscore.provider.NotFoundException
import org.openscore.testing.MlbSamples
import org.openscore.testing.SampleFetcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Drives [MlbProvider] end-to-end against the captured samples. */
class MlbProviderTest {

    private val fetcher = MlbSamples.register(SampleFetcher())
    private val mlb = MlbProvider(fetcher)
    private val live = MlbProvider(MlbSamples.registerLive(SampleFetcher()))

    @Test
    fun scheduledGamesTheDayBefore() = runTest {
        val games = mlb.gamesOn(LocalDate(2026, 9, 11))
        assertEquals(15, games.size)
        val g = games.first { it.id == MlbSamples.SCHEDULED_GAME_ID }
        assertEquals(GameState.SCHEDULED, g.state)
        assertEquals("147", g.home.id)
        assertEquals("New York Yankees", g.home.name)
        assertEquals("New York Mets", g.away.name)
        assertNull(g.home.abbreviation, "unhydrated schedule rows carry no abbreviation")
        assertEquals("2026-09-11T23:05:00Z", g.startTime.toString())
        assertEquals("Yankee Stadium", g.venue)
        assertEquals("2026", g.seasonId)
        assertEquals(StageKind.REGULAR, g.stage)
        assertNull(g.score)
        assertNull(g.clock)
        assertNull(g.situation)
        assertTrue(g.periodScores.isEmpty())
        assertEquals("S/Scheduled", g.rawState)
    }

    @Test
    fun preGameRowsHaveNoScoreYet() = runTest {
        val games = mlb.gamesOn(LocalDate(2026, 9, 12))
        assertEquals(15, games.size)
        val g = games.first { it.id == MlbSamples.PRE_GAME_ID }
        assertEquals(GameState.PRE_GAME, g.state)
        assertEquals("DET", g.home.abbreviation, "hydrate=team gives abbreviations")
        assertEquals("Probable pitchers", g.preview?.label)
        assertNotNull(g.preview?.home)
        assertNotNull(g.preview.away)
        assertNull(g.score, "the feed already says 0–0 in the 1st, which is not a score")
        assertNull(g.clock)
        assertEquals(games.sortedBy { it.startTime }.map { it.id }.toSet(), games.map { it.id }.toSet())
    }

    @Test
    fun finalGamesFromHydratedSchedule() = runTest {
        val games = mlb.gamesOn(LocalDate(2026, 9, 10))
        assertEquals(5, games.size)
        val g = games.first { it.id == MlbSamples.FINAL_GAME_ID }
        assertEquals(GameState.FINAL, g.state)
        assertEquals(GameEnding.REGULATION, g.ending)
        assertEquals(Score(home = 4, away = 3), g.score)
        assertEquals("SEA", g.home.abbreviation)
        assertEquals(9, g.periodScores.size)
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9"), g.periodScores.map { it.period.label })
        assertTrue(g.periodScores.all { it.period.type == PeriodType.REGULATION })
        assertEquals(3, g.periodScores.sumOf { it.away })
        assertEquals(4, g.periodScores.sumOf { it.home })
        assertEquals(StatPair("4", "4"), g.stats["hits"])
        assertEquals(StatPair("1", "0"), g.stats["errors"])
        assertNull(g.clock, "no clock once final")
        assertNull(g.situation)
        assertNull(g.events, "scoreboard call does not load events")
        assertTrue(g.credits.map { it.role }.containsAll(listOf("Winning pitcher", "Losing pitcher")))
    }

    @Test
    fun noGamesDay() = runTest {
        assertTrue(mlb.gamesOn(LocalDate(2026, 12, 25)).isEmpty())
    }

    @Test
    fun postponedGameStaysOnItsOriginalDate() = runTest {
        val games = mlb.gamesOn(LocalDate(2026, 4, 3))
        assertEquals(15, games.size)
        val ppd = games.single { it.state == GameState.POSTPONED }
        assertEquals("824134", ppd.id)
        assertNull(ppd.score)
        assertEquals("DR/Postponed/Rain", ppd.rawState)

        val makeup = mlb.gamesOn(LocalDate(2026, 4, 4)).first { it.id == "824134" }
        assertEquals(GameState.FINAL, makeup.state)
        assertNotNull(makeup.score)
    }

    @Test
    fun liveGameHasSituationAndInningClock() = runTest {
        val g = live.game(MlbSamples.LIVE_GAME_ID)
        assertEquals(GameState.LIVE, g.state)
        assertEquals(Score(home = 0, away = 3), g.score)

        val clock = assertNotNull(g.clock)
        assertEquals(7, clock.period.number)
        assertEquals(PeriodType.REGULATION, clock.period.type)
        assertEquals("Bot 7", clock.time.label)
        assertNull(clock.time.elapsed)
        assertNull(clock.running)

        val s = assertIs<BaseballSituation>(g.situation)
        assertEquals(7, s.inning)
        assertEquals(InningHalf.BOTTOM, s.half)
        assertEquals(0, s.outs)
        assertEquals(1, s.balls)
        assertEquals(2, s.strikes)
        assertEquals("Randy Arozarena", s.onSecond?.name)
        assertNull(s.onFirst)
        assertNull(s.onThird)
        assertEquals(1, s.runnersOn)
        assertEquals("Dominic Canzone", s.batter?.name)
        assertEquals("Jakob Junis", s.pitcher?.name)
        assertEquals("Cal Raleigh", s.onDeck?.name)
        assertNotNull(s.batter?.jerseyNumber, "resolved through gameData.players")

        assertEquals(7, g.periodScores.size)
        assertEquals(0 to 0, g.periodScores.last().let { it.home to it.away }, "unplayed half-inning reads 0")
        assertEquals("I/In Progress", g.rawState)

        val events = assertNotNull(g.events)
        val pas = events.filter { it.details is PlateAppearanceDetails }
        assertEquals(listOf("45", "46"), pas.map { it.id }, "the in-progress plate appearance is not an event yet")
        assertEquals(BaseballEventType.RUNNER_OUT, pas[0].type)
        assertEquals(BaseballEventType.HIT_BY_PITCH, pas[1].type)
        assertEquals("Seattle Mariners", pas[1].team?.name, "bottom of the inning: the home team bats")
        val change = events.first { it.type == BaseballEventType.SUBSTITUTION }
        assertEquals("Texas Rangers", change.team?.name, "the fielding side changes pitchers")
        assertEquals("Jakob Junis", change.players.single().name, "resolved through gameData.players")
        assertTrue(change.sortOrder!! < pas[1].sortOrder!!, "the pitching change came before the plate appearance")
    }

    @Test
    fun finalGameFromFeed() = runTest {
        val g = mlb.game(MlbSamples.FINAL_GAME_ID)
        assertEquals(GameState.FINAL, g.state)
        assertEquals("Seattle Mariners", g.home.name)
        assertEquals("136", g.home.id)
        assertEquals("T-Mobile Park", g.venue)
        assertEquals(Score(4, 3), g.score)
        assertEquals(GameEnding.REGULATION, g.ending)
        assertNull(g.clock)
        assertNull(g.situation)
        assertEquals("F/Final", g.rawState)

        val events = assertNotNull(g.events)
        assertEquals(8, events.size, "the sample keeps the first eight plate appearances; no actions among them are events")
        assertTrue(events.all { it.type != BaseballEventType.OTHER })
        val first = events.first()
        assertEquals(BaseballEventType.STRIKEOUT, first.type)
        assertEquals("Joc Pederson", first.players.first().name)
        assertEquals("Logan Gilbert", first.players[1].name)
        assertEquals("Top 1", first.time.label)
        val d = assertIs<PlateAppearanceDetails>(first.details)
        assertEquals(5, d.pitches)
        assertEquals(1, d.outsAfter)
        assertTrue(d.out)
        assertEquals(1, d.runners.size)
        assertTrue(d.runners.single().out)
        assertNull(first.score, "score only on scoring plays")
        val ground = events.first { it.type == BaseballEventType.FIELD_OUT }
        assertNotNull(assertIs<PlateAppearanceDetails>(ground.details).battedBall?.launchSpeedMph)
        assertNotNull(ground.coordinates, "spray-chart coordinates on balls in play")
        assertEquals(events.sortedBy { it.sortOrder }, events)
    }

    @Test
    fun scheduledFeedHasNoEventsAndNoLineups() = runTest {
        val g = mlb.game(MlbSamples.SCHEDULED_GAME_ID)
        assertEquals(GameState.SCHEDULED, g.state)
        assertEquals(emptyList(), g.events)
        assertNull(g.score)
        assertTrue(mlb.lineups(MlbSamples.SCHEDULED_GAME_ID).isEmpty(), "no batting order before the game")
    }

    @Test
    fun preGameFeed() = runTest {
        val g = mlb.game(MlbSamples.PRE_GAME_ID)
        assertEquals(GameState.PRE_GAME, g.state)
        assertNull(g.score)
        assertTrue(g.stats.isEmpty(), "0 hits / 0 errors before first pitch is not a stat line")
        assertNull(g.clock)
        assertNull(g.situation)
        assertEquals(emptyList(), g.events, "the 'Status Change - Pre-Game' advisory is not an event")
        assertEquals("P/Pre-Game", g.rawState)
    }

    @Test
    fun lineupsFromBoxscore() = runTest {
        val lineups = mlb.lineups(MlbSamples.FINAL_GAME_ID)
        assertEquals(listOf("136", "140"), lineups.map { it.team.id })
        val home = lineups.first()
        assertEquals(listOf("Batting order", "Pitchers", "Substituted", "Bench", "Bullpen"), home.groups.map { it.label })
        assertEquals(LineupGroupKind.STARTERS, home.groups.first().kind)
        val order = home.groups.first().players
        assertEquals(9, order.size)
        assertEquals("J.P. Crawford", order.first().name)
        assertEquals("SS", order.first().position)
        assertEquals(3, order.first().jerseyNumber)
        assertEquals("Weston Wilson", order.last().name, "the current occupant of the 9-hole, not the starter")
        assertEquals(listOf("Brock Rodden", "Taylor Ward", "Victor Robles"), home.groups[2].players.map { it.name }, "replaced 9-hole starter, pinch-hitter and pinch-runner")
        assertEquals(3, home.groups[1].players.size)
        assertEquals("Logan Gilbert", home.groups[1].players.first().name)
        assertTrue(home.players.all { it.headshotUrl != null })
    }

    @Test
    fun standingsGroupedByDivision() = runTest {
        val table = mlb.standings()
        assertEquals("division", table.grouping)
        assertEquals("2026", table.seasonId)
        assertEquals(StageKind.REGULAR, table.stage)
        assertEquals(
            listOf("American League East", "American League Central", "American League West", "National League East", "National League Central", "National League West"),
            table.groups.map { it.label },
        )
        assertEquals(30, table.rows.size)
        val east = table.groups.first()
        assertEquals((1..5).toList(), east.rows.map { it.rank })
        val leader = east.rows.first()
        assertEquals("TB", leader.team.abbreviation)
        assertEquals("Tampa Bay Rays", leader.team.name)
        assertEquals(leader.wins, leader.points, "no points in baseball; wins rank")
        assertEquals("-", leader.extra["gamesBack"])
        assertNotNull(leader.extra["pct"])
        assertNotNull(leader.extra["streak"])
        assertNotNull(leader.extra["home"])
        assertNotNull(leader.extra["last10"])
        assertEquals("true", leader.extra["divisionLeader"])
        assertNotNull(leader.goalDifference)
    }

    @Test
    fun standingsForASeasonPassesTheParameter() = runTest {
        mlb.standings("2026")
        assertTrue(fetcher.requests.last().endsWith("&season=2026"), fetcher.requests.last())
    }

    @Test
    fun teamRosterAndPlayer() = runTest {
        val t = mlb.team("147")
        assertEquals("New York Yankees", t.name)
        assertEquals("NYY", t.ref.abbreviation)
        assertEquals("Bronx", t.placeName)
        assertEquals("Yankees", t.commonName)
        assertEquals("Yankee Stadium", t.arena)
        assertEquals("American League", t.conference)
        assertEquals("American League East", t.division)
        assertEquals("https://www.mlbstatic.com/team-logos/147.svg", t.ref.logoUrl)
        assertNotNull(t.logoDarkUrl)

        val roster = mlb.roster("147")
        assertEquals(27, roster.size)
        assertTrue(roster.all { it.teamId == "147" && it.active == true && it.ref.position != null })

        val judge = mlb.player("592450")
        assertEquals("Aaron Judge", judge.name)
        assertEquals(99, judge.ref.jerseyNumber)
        assertEquals("RF", judge.ref.position)
        assertEquals(LocalDate(1992, 4, 26), judge.birthDate)
        assertEquals("Linden, CA", judge.birthPlace)
        assertEquals("USA", judge.nationality)
        assertEquals(201, judge.heightCm)
        assertEquals(128, judge.weightKg)
        assertEquals("R/R", judge.handedness)
        assertEquals("147", judge.teamId)
        assertEquals(true, judge.active)
    }

    @Test
    fun unknownIdsAreNotFound() = runTest {
        assertFailsWith<NotFoundException> { mlb.game("1") }
        assertFailsWith<NotFoundException> { mlb.team("1") }
        assertFailsWith<NotFoundException> { mlb.player("1") }
    }

    @Test
    fun capabilitiesMatchImplementation() {
        assertTrue(!mlb.supports(Capability.CLOCK), "no time clock in baseball")
        assertTrue(mlb.supports(Capability.INTERMISSION_STATE))
        assertTrue(mlb.supports(Capability.LIVE_UPDATES))
        assertTrue(mlb.supports(Capability.EVENT_COORDINATES))
        assertTrue(!mlb.supports(Capability.LIVE_PUSH))
    }
}
