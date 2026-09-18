package org.openscore.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class ScoreboardPresentationTest {
    private fun score(number: Int, label: String, home: Int, away: Int, type: PeriodType = PeriodType.REGULATION) =
        PeriodScore(Period(number, type, label), home, away)

    private fun game(score: Score) = Game(
        leagueId = "test", id = "game", startTime = Instant.parse("2026-09-14T12:00:00Z"),
        home = TeamRef("test", "home", "Home"), away = TeamRef("test", "away", "Away"),
        state = GameState.FINAL, score = score,
    )

    @Test
    fun `football keeps both halves and labels extra time and penalties distinctly`() {
        val game = game(Score(2, 2)).copy(
            ending = GameEnding.SHOOTOUT,
            periodScores = listOf(
                score(1, "First half", 1, 0), score(2, "Second half", 0, 1),
                score(3, "Extra time first half", 1, 0, PeriodType.OVERTIME),
                score(4, "Extra time second half", 0, 1, PeriodType.OVERTIME),
                score(5, "Penalty shootout", 4, 5, PeriodType.SHOOTOUT),
            ),
        )

        val board = game.scoreboardPresentation(Sport.FOOTBALL)
        assertEquals(listOf("1H", "2H", "ET1", "ET2", "PENS"), board.periods.map { it.label })
        assertEquals(listOf(1 to 0, 0 to 1, 1 to 0, 0 to 1, 4 to 5), board.periods.map { it.score.home to it.score.away })
        assertEquals("T", board.totalLabel)
        assertEquals("Penalty shootout shown separately from the match score.", board.note)
    }

    @Test
    fun `hockey renders all three regulation periods then overtime and shootout`() {
        val game = game(Score(3, 2)).copy(
            ending = GameEnding.SHOOTOUT,
            periodScores = listOf(
                score(1, "Period one", 1, 0), score(2, "Period two", 0, 1), score(3, "Period three", 1, 0),
                score(4, "Overtime", 0, 0, PeriodType.OVERTIME), score(5, "Shootout", 1, 0, PeriodType.SHOOTOUT),
            ),
        )

        val board = game.scoreboardPresentation(Sport.HOCKEY)
        assertEquals(listOf("1", "2", "3", "OT", "SO"), board.periods.map { it.label })
        assertEquals(listOf(1 to 0, 0 to 1, 1 to 0, 0 to 0, 1 to 0), board.periods.map { it.score.home to it.score.away })
        assertEquals("Shootout winner shown separately.", board.note)
    }

    @Test
    fun `additional hockey overtimes remain visible and regulation results need no qualifier`() {
        val game = game(Score(4, 3)).copy(
            ending = GameEnding.OVERTIME,
            periodScores = listOf(
                score(1, "1", 1, 1), score(2, "2", 1, 0), score(3, "3", 1, 2),
                score(4, "OT", 0, 0, PeriodType.OVERTIME), score(5, "2OT", 1, 0, PeriodType.OVERTIME),
            ),
        )

        val board = game.scoreboardPresentation(Sport.HOCKEY)
        assertEquals(listOf("1", "2", "3", "OT", "2OT"), board.periods.map { it.label })
        assertNull(board.note)
    }
}
