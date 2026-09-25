package org.openscore.app.data

import kotlinx.datetime.TimeZone
import org.openscore.app.Fixtures
import org.openscore.app.alerts.AlertSettings
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
    fun `a team followed before the crosswalk knew it matches its games again`() {
        // Every HockeyAllsvenskan club had no club id from 2026-09-15 until 0.3.1, so a club
        // followed then was stored on its league and id.
        val followed = Favorite.Team(null, "hockeyallsvenskan", "LIF", "Leksand", Sport.HOCKEY)
        val game = Fixtures.game("hockeyallsvenskan", "g", home = TeamRef("hockeyallsvenskan", "LIF", "Leksands IF"))
        assertEquals("leksand", game.home.favoriteKey(), "today's games carry the club id")
        assertFalse(FavoriteFilter(setOf(followed)).matches(game), "which the stored key never meets")

        val rekeyed = rekey(listOf(followed))
        assertEquals(listOf("leksand"), rekeyed.favorites.map { it.key })
        assertEquals(mapOf("hockeyallsvenskan/LIF" to "leksand"), rekeyed.renamed)
        assertTrue(FavoriteFilter(rekeyed.favorites).matches(game))
    }

    @Test
    fun `a club id the crosswalk no longer resolves is kept`() {
        // Followed while HockeyAllsvenskan still used Sportality uuids, which no longer resolve.
        val followed = Favorite.Team("leksand", "hockeyallsvenskan", "9541-95418PpkP", "Leksand", Sport.HOCKEY)
        val rekeyed = rekey(listOf(followed))
        assertEquals(setOf<Favorite>(followed), rekeyed.favorites)
        assertTrue(rekeyed.renamed.isEmpty())
    }

    @Test
    fun `a team followed twice under two keys becomes one favourite and keeps its alert`() {
        val orphan = Favorite.Team(null, "hockeyallsvenskan", "LIF", "Leksand", Sport.HOCKEY)
        val again = Favorite.team(TeamRef("hockeyallsvenskan", "LIF", "Leksand"), Sport.HOCKEY)
        val rekeyed = rekey(listOf(orphan, again))
        assertEquals(listOf("leksand"), rekeyed.favorites.map { it.key })

        val alerts = AlertSettings(keys = setOf("hockeyallsvenskan/LIF", "nhl/TOR")).renamed(rekeyed.renamed)
        assertEquals(setOf("leksand", "nhl/TOR"), alerts.keys)
        assertTrue(alerts.notifies(rekeyed.favorites.single()))
    }

    @Test
    fun `a followed league matches every game in it`() {
        val filter = FavoriteFilter(setOf(Favorite.league(leagues[0])))
        assertTrue(filter.matches(Fixtures.game("premier-league", "p")))
        assertFalse(filter.matches(Fixtures.game("ucl", "u")))
        assertEquals(listOf("premier-league"), filter.leaguesToFetch(leagues))
    }
}
