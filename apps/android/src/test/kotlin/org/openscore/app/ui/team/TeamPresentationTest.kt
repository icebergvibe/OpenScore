package org.openscore.app.ui.team

import org.openscore.app.Fixtures.game
import org.openscore.app.Fixtures.team
import org.openscore.model.GameState
import org.openscore.model.League
import org.openscore.model.Sport
import org.openscore.model.TeamRef
import org.openscore.model.Score
import org.openscore.model.StageKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

class TeamPresentationTest {
    private val yankees = team("mlb", "147")
    private val mets = team("mlb", "121")
    private val now = Instant.parse("2026-09-14T12:00:00Z")
    private fun fixture(id: String, state: GameState = GameState.SCHEDULED, score: Score? = null) =
        game(leagueId = "mlb", id = id, home = yankees, away = mets, state = state, score = score,
            startTime = Instant.parse("2026-09-14T23:00:00Z")).copy(stage = StageKind.REGULAR)

    @Test
    fun maltaPremierUsesTheApiSeasonEndYear() {
        assertEquals(2027, teamSeasonYear("malta-premier", LocalDate(2026, 9, 14)))
        assertEquals(2026, teamSeasonYear("mlb", LocalDate(2026, 9, 14)))
    }

    @Test
    fun resultsAreFromTheSelectedTeamsPerspectiveAndOnlyForFinals() {
        val final = fixture("1", GameState.FINAL, Score(5, 2))
        assertEquals("W", final.resultFor(yankees))
        assertEquals("L", final.resultFor(mets))
        assertNull(final.resultFor(team("mlb", "110")))
        assertNull(final.copy(state = GameState.LIVE).resultFor(yankees))
        assertNull(final.copy(score = null).resultFor(yankees))
        assertEquals("T", final.copy(score = Score(2, 2)).resultFor(yankees))
    }

    @Test
    fun doubleheadersRemainSeparateAndPostponementsAreVisibleInAllGames() {
        val games = listOf(fixture("game-1"), fixture("game-2"), fixture("postponed", GameState.POSTPONED), fixture("cancelled", GameState.CANCELLED))
        assertEquals(listOf("game-1", "game-2"), teamGames(games, yankees, GameFilter.UPCOMING, now).map { it.id })
        assertEquals(4, teamGames(games, yankees, GameFilter.ALL, now).size)
        assertEquals(2, teamGames(games + games, yankees, GameFilter.UPCOMING, now).size)
    }

    @Test
    fun liveAndSuspendedGamesRemainUpcomingEvenWhenStartedYesterday() {
        val yesterday = Instant.parse("2026-09-13T23:00:00Z")
        val games = listOf(fixture("live", GameState.LIVE), fixture("suspended", GameState.SUSPENDED), fixture("stale"), fixture("final", GameState.FINAL, Score(1, 0)))
            .map { it.copy(startTime = yesterday) }
        assertEquals(listOf("live", "suspended"), teamGames(games, yankees, GameFilter.UPCOMING, now).map { it.id })
        assertEquals(listOf("final"), teamGames(games, yankees, GameFilter.RESULTS, now).map { it.id })
    }

    @Test
    fun scorePollingWaitsUntilFirstPitchAndKeepsOvernightGames() {
        val live = fixture("overnight", GameState.LIVE).copy(scheduleDate = LocalDate(2026, 9, 13))
        val imminent = fixture("soon").copy(startTime = Instant.parse("2026-09-14T12:03:00Z"))
        val postponed = fixture("postponed", GameState.POSTPONED).copy(startTime = Instant.parse("2026-09-14T11:00:00Z"))
        assertEquals(emptyMap(), scoreRefreshDates(listOf(fixture("tonight"), postponed), now))
        assertEquals(mapOf("mlb" to setOf(LocalDate(2026, 9, 13), LocalDate(2026, 9, 14))), scoreRefreshDates(listOf(live, imminent), now))
    }

    @Test
    fun seasonWindowsFollowTheLeagueCalendar() {
        assertEquals(SeasonWindow("2026", LocalDate(2026, 1, 1), LocalDate(2026, 12, 31)), seasonWindow("mlb", LocalDate(2026, 9, 16)))
        assertEquals(SeasonWindow("2026", LocalDate(2026, 1, 1), LocalDate(2026, 12, 31)), seasonWindow("allsvenskan", LocalDate(2026, 9, 16)))
        assertEquals(SeasonWindow("2026/27", LocalDate(2026, 7, 1), LocalDate(2027, 6, 30)), seasonWindow("premier-league", LocalDate(2026, 9, 16)))
        assertEquals(SeasonWindow("2026/27", LocalDate(2026, 7, 1), LocalDate(2027, 6, 30)), seasonWindow("ucl", LocalDate(2027, 3, 1)))
        assertEquals(SeasonWindow("2025/26", LocalDate(2025, 7, 1), LocalDate(2026, 6, 30)), seasonWindow("shl", LocalDate(2026, 5, 1)))
    }

    @Test
    fun aClubIsFoundInEveryLeagueTheCrosswalkNamesItIn() {
        val leagues = listOf(
            League("ucl", Sport.FOOTBALL, "Champions League", "EU"),
            League("uel", Sport.FOOTBALL, "Europa League", "EU"),
            League("premier-league", Sport.FOOTBALL, "Premier League", "GB"),
            League("nhl", Sport.HOCKEY, "NHL", "US"),
        )
        val fromUefa = TeamRef("ucl", "52280", "Arsenal", "ARS")
        val sources = clubSources(fromUefa, leagues)
        assertEquals(listOf("premier-league", "ucl", "uel"), sources.map { it.leagueId }, "domestic first, then the league it was opened from")
        assertEquals("3", sources.first().id, "the Premier League's own id for the club")
        assertEquals("arsenal", sources.first().clubId)
        assertTrue(sources.all { it.name == "Arsenal" })

        val unknown = TeamRef("premier-league", "no-such-team", "Someone")
        assertEquals(listOf(unknown), clubSources(unknown, leagues))
    }

    @Test
    fun gamesAndRowsMatchTheClubAcrossIdSpaces() {
        val arsenalPl = TeamRef("premier-league", "3", "Arsenal")
        val arsenalUefa = TeamRef("ucl", "52280", "Arsenal")
        val napoli = TeamRef("ucl", "50136", "Napoli")
        val tie = game(leagueId = "ucl", id = "2049560", home = napoli, away = arsenalUefa, state = GameState.FINAL, score = Score(1, 2))
        assertTrue(tie.belongsTo(arsenalPl))
        assertEquals("W", tie.resultFor(arsenalPl))
        assertTrue(arsenalUefa.isSameClub(arsenalPl))
        assertTrue(!TeamRef("premier-league", "3", "Arsenal", clubId = null).isSameClub(TeamRef("ucl", "52280", "Arsenal", clubId = null)))
    }

    @Test
    fun recentFormIsTheLastFiveRegularSeasonResultsInChronologicalOrder() {
        val finals = (1..7).map { fixture("$it", GameState.FINAL, Score(it, 0)).copy(startTime = Instant.parse("2026-09-0${it}T23:00:00Z")) }
        val spring = fixture("spring", GameState.FINAL, Score(10, 0)).copy(stage = StageKind.PRESEASON)
        assertEquals(listOf("3", "4", "5", "6", "7"), recentForm(finals.reversed() + spring + fixture("live", GameState.LIVE), yankees).map { it.id })
        assertEquals(listOf("7", "6", "5", "4", "3", "2", "1"), teamGames(finals, yankees, GameFilter.RESULTS, now).map { it.id })
    }
}
