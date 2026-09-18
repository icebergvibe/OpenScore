package org.openscore.providers.nhl

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
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
import org.openscore.provider.Capability
import org.openscore.provider.NotFoundException
import org.openscore.testing.NhlSamples
import org.openscore.testing.SampleFetcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Drives [NhlProvider] end-to-end against the captured samples. */
class NhlProviderTest {

    private val fetcher = NhlSamples.register(SampleFetcher())
    private val nhl = NhlProvider(fetcher)

    @Test
    fun scheduledGamesOnOpeningNight() = runTest {
        val games = nhl.gamesOn(LocalDate(2026, 9, 29))
        assertEquals(5, games.size)
        val g = games.first()
        assertEquals("2026020001", g.id)
        assertEquals(GameState.SCHEDULED, g.state)
        assertEquals("CAR", g.home.id)
        assertEquals("FLA", g.away.id)
        assertEquals("Hurricanes", g.home.name, "/score only carries the nickname; full name via team()")
        assertEquals("2026-09-29T21:00:00Z", g.startTime.toString())
        assertNull(g.score, "no score before the game")
        assertNull(g.clock)
        assertTrue(g.periodScores.isEmpty())
        assertEquals("20262027", g.seasonId)
    }

    @Test
    fun noGamesDay() = runTest {
        assertTrue(nhl.gamesOn(LocalDate(2026, 9, 11)).isEmpty())
    }

    @Test
    fun finalGameFromScoreboard() = runTest {
        val g = nhl.gamesOn(LocalDate(2026, 6, 14)).single()
        assertEquals(GameState.FINAL, g.state)
        assertEquals(GameEnding.REGULATION, g.ending)
        assertEquals(Score(home = 0, away = 3), g.score)
        assertEquals(listOf(0 to 1, 0 to 1, 0 to 1), g.periodScores.map { it.home to it.away })
        assertEquals(listOf("1", "2", "3"), g.periodScores.map { it.period.label })
        assertNull(g.clock, "no clock once final")
        assertNull(g.events, "scoreboard call does not load events")
    }

    @Test
    fun finalGameWithEvents() = runTest {
        val g = nhl.game("2025030416")
        assertEquals(GameState.FINAL, g.state)
        assertEquals("VGK", g.home.id)
        assertEquals("Vegas Golden Knights", g.home.name, "name assembled from placeName + commonName")
        assertEquals(Score(0, 3), g.score)
        assertEquals(listOf(0 to 1, 0 to 1, 0 to 1), g.periodScores.map { it.home to it.away })

        val events = assertNotNull(g.events)
        assertEquals(343, events.size)
        assertEquals(events.sortedBy { it.sortOrder }, events, "events are in feed order")

        val goals = events.filter { it.type == HockeyEventType.GOAL }
        assertEquals(3, goals.size)
        goals.forEach { assertEquals("CAR", it.team?.id) }
        val emptyNetter = assertIs<GoalDetails>(goals.last().details)
        assertEquals(true, emptyNetter.emptyNet, "situationCode 1560: home goalie pulled")
        assertEquals(Strength.EV, emptyNetter.strength, "extra attacker is not a power play")
        assertEquals(Score(0, 3), goals.last().score)
        assertTrue(goals.all { it.players.isNotEmpty() && !it.players.first().name.startsWith("#") }, "scorers resolved from rosterSpots")
        assertTrue(goals.all { it.coordinates != null })

        val penalty = events.first { it.type == HockeyEventType.PENALTY }
        val pd = assertIs<PenaltyDetails>(penalty.details)
        assertNotNull(pd.player)
        assertNotNull(pd.minutes)
        assertNotNull(pd.infraction)

        val firstFaceoff = events.first { it.type == HockeyEventType.FACEOFF }
        assertEquals(1, firstFaceoff.period.number)
        assertEquals(20.minutes, firstFaceoff.time.remaining)
        assertEquals(0.minutes, firstFaceoff.time.elapsed)
    }

    @Test
    fun shootoutGame() = runTest {
        val g = nhl.game("2025020444")
        assertEquals(GameState.FINAL, g.state)
        assertEquals(GameEnding.SHOOTOUT, g.ending)
        assertEquals(Score(home = 1, away = 2), g.score)
        assertEquals(listOf("1", "2", "3", "OT", "SO"), g.periodScores.map { it.period.label })
        assertEquals(
            listOf(PeriodType.REGULATION, PeriodType.REGULATION, PeriodType.REGULATION, PeriodType.OVERTIME, PeriodType.SHOOTOUT),
            g.periodScores.map { it.period.type },
        )
        assertEquals(listOf(0 to 0, 0 to 1, 1 to 0, 0 to 0, 0 to 1), g.periodScores.map { it.home to it.away })

        val events = assertNotNull(g.events)
        assertEquals(2, events.count { it.type == HockeyEventType.GOAL }, "shootout goals are not goals")
        val attempts = events.filter { it.type == HockeyEventType.SHOOTOUT_ATTEMPT }
        assertEquals(5, attempts.size)
        assertEquals(listOf(false, true, true, true, false), attempts.map { assertIs<ShootoutAttemptDetails>(it.details).scored })
        assertEquals(7, events.count { it.type == HockeyEventType.PENALTY })
        assertTrue(events.any { it.type == HockeyEventType.GAME_END })
    }

    @Test
    fun preGameHasNoEventsYet() = runTest {
        val g = nhl.game("2026020001")
        assertEquals(GameState.SCHEDULED, g.state)
        assertEquals(emptyList(), g.events)
        assertNull(g.clock, "pre-game 20:00 clock is not a live clock")
        assertTrue(nhl.lineups("2026020001").isEmpty())
    }

    @Test
    fun lineupsFromBoxscore() = runTest {
        val lineups = nhl.lineups("2025030416")
        assertEquals(listOf("VGK", "CAR"), lineups.map { it.team.id })
        val home = lineups.first()
        assertEquals(listOf(LineupGroupKind.FORWARDS, LineupGroupKind.DEFENSE, LineupGroupKind.GOALIES), home.groups.map { it.kind })
        assertEquals(listOf(12, 6, 2), home.groups.map { it.players.size })
        assertTrue(home.players.all { it.jerseyNumber != null && it.position != null })
    }

    @Test
    fun standingsGroupedByDivision() = runTest {
        val table = nhl.standings()
        assertEquals("division", table.grouping)
        assertEquals("20252026", table.seasonId)
        assertEquals(listOf("Atlantic", "Metropolitan", "Central", "Pacific"), table.groups.map { it.label }, "East before West")
        assertEquals(32, table.rows.size)
        val central = table.groups.first { it.label == "Central" }
        assertEquals((1..8).toList(), central.rows.map { it.rank })
        val leader = central.rows.first()
        assertEquals("COL", leader.team.id)
        assertEquals("Western", leader.extra["conference"])
        assertEquals("1", leader.extra["leagueRank"])
        assertEquals("p", leader.extra["clinch"])
        assertTrue(leader.points > 0 && leader.played > 0)
    }

    @Test
    fun standingsForASeasonResolvesTheDate() = runTest {
        nhl.standings("20252026")
        assertTrue(fetcher.requests.last().endsWith("/standings/2026-04-17"), fetcher.requests.last())
    }

    @Test
    fun teamFromStandings() = runTest {
        val t = nhl.team("tor")
        assertEquals("TOR", t.id)
        assertEquals("Toronto Maple Leafs", t.name)
        assertEquals("Eastern", t.conference)
        assertEquals("Atlantic", t.division)
        assertNotNull(t.ref.logoUrl)
    }

    @Test
    fun rosterAndPlayer() = runTest {
        val roster = nhl.roster("TOR")
        assertEquals(50, roster.size)
        assertTrue(roster.all { it.teamId == "TOR" && it.birthDate != null })
        assertTrue(roster.any { it.ref.position == "G" })

        val eichel = nhl.player("8478403")
        assertEquals("Jack Eichel", eichel.name)
        assertEquals("VGK", eichel.teamId)
        assertEquals(LocalDate(1996, 10, 28), eichel.birthDate)
        assertEquals("USA", eichel.nationality)
        assertEquals("R", eichel.handedness)
        assertEquals(188, eichel.heightCm)
        assertEquals("North Chelmsford, Massachusetts", eichel.birthPlace)
    }

    @Test
    fun unknownIdIsNotFound() = runTest {
        assertFailsWith<NotFoundException> { nhl.game("999") }
        assertFailsWith<NotFoundException> { nhl.team("ZZZ") }
    }

    @Test
    fun capabilitiesMatchImplementation() {
        assertTrue(nhl.supports(Capability.CLOCK))
        assertTrue(nhl.supports(Capability.LIVE_UPDATES))
        assertTrue(!nhl.supports(Capability.LINE_GROUPS), "NHL boxscore has no lines")
        assertTrue(!nhl.supports(Capability.LIVE_PUSH))
    }
}
