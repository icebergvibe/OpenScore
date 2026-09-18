package org.openscore.app.ui.common

import org.openscore.app.Fixtures
import org.openscore.model.GameEnding
import org.openscore.model.GameState
import org.openscore.model.PeriodType
import org.openscore.model.PeriodScore
import org.openscore.model.Score
import org.openscore.model.Sport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class GameFormatTest {

    @Test
    fun `scheduled game shows vs and a kick-off time`() {
        val game = Fixtures.game()
        assertEquals("vs", game.scoreLabel())
        assertEquals(StatusTone.SCHEDULED, game.statusLabel(Sport.FOOTBALL).tone)
    }

    @Test
    fun `football clock without a label is the cumulative minute`() {
        val game = Fixtures.game(state = GameState.LIVE, score = Score(1, 0), clock = Fixtures.clock(Fixtures.period(2, "2H"), elapsed = 22.minutes + 10.seconds))
        assertEquals("68'", game.clockLabel(Sport.FOOTBALL))
        assertEquals(StatusLabel("68'", StatusTone.LIVE), game.statusLabel(Sport.FOOTBALL))
        assertEquals(67f / 90f, game.progress(Sport.FOOTBALL)!!, 0.001f)
    }

    @Test
    fun `a provider label wins over the computed minute`() {
        val game = Fixtures.game(state = GameState.LIVE, clock = Fixtures.clock(Fixtures.period(2, "2H"), elapsed = 47.minutes, label = "90'+2"))
        assertEquals("90'+2", game.clockLabel(Sport.FOOTBALL))
    }

    @Test
    fun `football card exposes the first half score only once the second half has started`() {
        val firstHalf = PeriodScore(Fixtures.period(1, "1H"), 1, 0)
        val first = Fixtures.game(state = GameState.LIVE, score = Score(1, 0), clock = Fixtures.clock(Fixtures.period(1, "1H"))).copy(periodScores = listOf(firstHalf))
        val second = first.copy(clock = Fixtures.clock(Fixtures.period(2, "2H")), score = Score(2, 1))

        assertNull(first.halfTimeLabel(Sport.FOOTBALL))
        assertEquals("HT 1-0", second.halfTimeLabel(Sport.FOOTBALL))
        assertEquals("HT 1-0", second.copy(state = GameState.FINAL).halfTimeLabel(Sport.FOOTBALL))
    }

    @Test
    fun `hockey clock shows the period and the remaining time`() {
        val game = Fixtures.game(leagueId = "nhl", state = GameState.LIVE, clock = Fixtures.clock(Fixtures.period(2, "2"), remaining = 12.minutes + 34.seconds))
        assertEquals("2 12:34", game.clockLabel(Sport.HOCKEY))
        // 20 minutes of the first period plus 7:26 of the second, over sixty.
        assertEquals((20 + 7) / 60f, game.progress(Sport.HOCKEY)!!, 0.001f)
    }

    @Test
    fun `overtime fills the bar and final labels say how it ended`() {
        val ot = Fixtures.game(leagueId = "nhl", state = GameState.LIVE, clock = Fixtures.clock(Fixtures.period(4, "OT", PeriodType.OVERTIME), remaining = 3.minutes))
        assertEquals(1f, ot.progress(Sport.HOCKEY))
        val final = Fixtures.game(leagueId = "nhl", state = GameState.FINAL, score = Score(2, 3)).copy(ending = GameEnding.SHOOTOUT)
        assertEquals("Final/SO", final.statusLabel(Sport.HOCKEY).text)
        assertEquals("Pens", final.statusLabel(Sport.FOOTBALL).text)
    }

    @Test
    fun `baseball has no progress bar`() {
        val game = Fixtures.game(leagueId = "mlb", state = GameState.LIVE, clock = Fixtures.clock(Fixtures.period(7, "Bot 7")))
        assertNull(game.progress(Sport.BASEBALL))
        assertEquals("Bot 7", game.statusLabel(Sport.BASEBALL).text)
    }

    @Test
    fun `flags come from the country code and fall back to a globe`() {
        assertEquals("🇸🇪", flagEmoji("SE"))
        assertEquals("🇪🇺", flagEmoji("EU"))
        assertEquals("🌐", flagEmoji(null))
    }
}
