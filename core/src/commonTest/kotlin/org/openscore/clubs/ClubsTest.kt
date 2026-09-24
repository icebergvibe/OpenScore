package org.openscore.clubs

import org.openscore.model.TeamRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClubsTest {

    @Test
    fun slugsAreUniqueAndWellFormed() {
        val ids = Clubs.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate club ids: ${ids.groupBy { it }.filterValues { it.size > 1 }.keys}")
        val bad = ids.filterNot { Regex("[a-z0-9]+(-[a-z0-9]+)*").matches(it) }
        assertTrue(bad.isEmpty(), "club ids must be lowercase ASCII with single hyphens: $bad")
    }

    @Test
    fun noNativeIdBelongsToTwoClubs() {
        val owners = HashMap<Pair<String, String>, MutableList<String>>()
        for (club in Clubs.all) for ((ns, id) in club.ids) owners.getOrPut(ns to id) { ArrayList() } += club.id
        val shared = owners.filterValues { it.size > 1 }
        assertTrue(shared.isEmpty(), "native ids mapped to several clubs: $shared")
        assertTrue(Clubs.all.all { it.ids.isNotEmpty() }, "every club must have at least one native id")
    }

    @Test
    fun namespacesAreOnlyEverAliasedToKnownLeagues() {
        val namespaces = Clubs.all.flatMap { it.ids.keys }.toSet()
        for (ns in namespaces) assertEquals(ns, Clubs.namespace(ns), "'$ns' is used as a namespace but is itself an alias")
    }

    @Test
    fun sameClubAcrossLeagues() {
        assertEquals("manchester-united", Clubs.clubId("premier-league", "1"))
        assertEquals("manchester-united", Clubs.clubId("ucl", "52682"))
        assertEquals("manchester-united", Clubs.clubId("uel", "52682"))
        assertEquals("manchester-united", Clubs.clubId("uecl", "52682"))

        assertEquals("hammarby", Clubs.clubId(Clubs.SPORTOMEDIA, "HAM"))
        assertEquals("hammarby", Clubs.clubId("allsvenskan", "66592"))
        assertEquals("hammarby", Clubs.clubId("superettan", "66592"))
        assertEquals("hammarby", Clubs.clubId("svenska-cupen", "66592"))
        assertEquals("hammarby", Clubs.clubId("fogis", "66592"))

        assertEquals("frolunda", Clubs.clubId("shl", "087a-087aTQv9u"))
        assertEquals("frolunda", Clubs.clubId("chl", "ba55abe823bed83d7c12e7b3"))
        // HockeyAllsvenskan left the Sportality platform and keys its teams by StatNet code now;
        // a club that has played in both leagues carries one id per namespace.
        assertEquals("modo", Clubs.clubId("hockeyallsvenskan", "MODO"))
        assertEquals("modo", Clubs.clubId("shl", "110b-110bJcIAI"))
        assertNull(Clubs.clubId("hockeyallsvenskan", "110b-110bJcIAI"), "the platform uuids are not its ids any more")
    }

    @Test
    fun unknownTeamsStayUnlinked() {
        assertNull(Clubs.clubId("premier-league", "no-such-team"))
        assertNull(Clubs.clubId("no-such-league", "1"))
        assertNull(Clubs.clubId("ucl", "1"), "a Premier League id must not resolve in the UEFA namespace")
    }

    @Test
    fun teamRefFillsClubIdByDefault() {
        assertEquals("hammarby", TeamRef("fogis", "66592", "Hammarby").clubId)
        assertEquals("hammarby", TeamRef("allsvenskan", "66592", "Hammarby", "HAM").clubId)
        assertNull(TeamRef("allsvenskan", "HAM", "Hammarby", "HAM").clubId, "abbreviations are Sportomedia's, not a league id")
        assertNull(TeamRef("fogis", "123456", "Hammarby TFF").clubId)
        assertEquals(Clubs.byId("hammarby")?.name, "Hammarby IF")
    }
}
