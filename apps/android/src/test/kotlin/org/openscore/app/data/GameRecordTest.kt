package org.openscore.app.data

import org.openscore.app.Fixtures
import org.openscore.model.GamePreview
import org.openscore.model.GameState
import org.openscore.model.PlayerRef
import org.openscore.model.TeamRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GameRecordTest {

    /** A scheduled MLB card shows the probable pitchers; a day served from the store must too. */
    @Test
    fun aStoredDayKeepsThePreviewItsCardsShow() {
        val pitchers = GamePreview(
            "Probable pitchers",
            home = PlayerRef("mlb", "660271", "Shohei Ohtani", 17, "P", "https://img.test/660271.png"),
            away = PlayerRef("mlb", "543037", "Gerrit Cole", 45, "P"),
        )
        val game = Fixtures.game("mlb", "776543", state = GameState.SCHEDULED).copy(preview = pitchers)

        assertEquals(pitchers, game.toRecord().toGame().preview)
        assertNull(Fixtures.game("mlb", "1").toRecord().preview, "no preview is no column value")
        assertNull(game.toRecord().copy(preview = "{not json").toGame().preview, "an unreadable value is dropped, not the row")
    }

    /**
     * Every HockeyAllsvenskan club had no club id from 2026-09-15 until 0.3.1. A season row stored
     * then is not dropped by a new build the way a day row is, so the restored team takes the id
     * the crosswalk gives it today.
     */
    @Test
    fun aRestoredTeamTakesTheClubIdTheCrosswalkGivesToday() {
        val leksand = TeamRef("hockeyallsvenskan", "LIF", "Leksands IF", clubId = null)
        val stored = Fixtures.game("hockeyallsvenskan", "20260918-lif-vik", home = leksand).toRecord()
        assertNull(stored.homeClubId)

        assertEquals("leksand", stored.toGame().home.clubId)
        assertEquals("kept", stored.copy(awayClubId = "kept").toGame().away.clubId, "an id the crosswalk cannot name is kept")
    }
}
