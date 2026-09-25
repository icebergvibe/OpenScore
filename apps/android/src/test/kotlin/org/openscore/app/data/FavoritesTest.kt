package org.openscore.app.data

import kotlinx.datetime.TimeZone
import org.openscore.app.Fixtures
import org.openscore.model.League
import org.openscore.model.Sport
import org.openscore.model.TeamRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FavoritesTest {

    private val leagues = listOf(
        League("premier-league", Sport.FOOTBALL, "Premier League", "GB", TimeZone.of("Europe/London")),
        League("ucl", Sport.FOOTBALL, "UEFA Champions League", "EU", TimeZone.UTC),
        League("uel", Sport.FOOTBALL, "UEFA Europa League", "EU", TimeZone.UTC),
        League("nhl", Sport.HOCKEY, "NHL", "US", TimeZone.of("America/New_York")),
    )

    @Test
    fun `a club known to the crosswalk is followed across its leagues`() {
        // Manchester United is in the club table as premier-league 1 and ucl 52682 (ClubTable.kt).
        val united = TeamRef("premier-league", "1", "Manchester United")
        val favorite = Favorite.team(united, Sport.FOOTBALL)
        assertEquals("manchester-united", favorite.key)
        val filter = FavoriteFilter(setOf(favorite))

        // The UEFA namespace covers the Europa League too, so both are asked; the NHL is not.
        assertEquals(listOf("premier-league", "ucl", "uel"), filter.leaguesToFetch(leagues))

        val uclGame = Fixtures.game("ucl", "u1", home = TeamRef("ucl", "52682", "Man United"))
        assertTrue(filter.matches(uclGame))
        assertFalse(filter.matches(Fixtures.game("ucl", "u2")))
    }

    @Test
    fun `an unknown team is keyed on its league and id`() {
        val team = TeamRef("nhl", "ZZZ", "Nowhere")
        val favorite = Favorite.team(team, Sport.HOCKEY)
        assertEquals("nhl/ZZZ", favorite.key)
        val filter = FavoriteFilter(setOf(favorite))
        assertTrue(filter.matches(Fixtures.game("nhl", "g", home = team)))
        assertFalse(filter.matches(Fixtures.game("nhl", "g2")))
        assertEquals(listOf("nhl"), filter.leaguesToFetch(leagues))
    }

    @Test
    fun `a followed league matches every game in it`() {
        val filter = FavoriteFilter(setOf(Favorite.league(leagues[0])))
        assertTrue(filter.matches(Fixtures.game("premier-league", "p")))
        assertFalse(filter.matches(Fixtures.game("ucl", "u")))
        assertEquals(listOf("premier-league"), filter.leaguesToFetch(leagues))
    }
}
