package org.openscore.health

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShapeAndPlaceholdersTest {

    @Test
    fun jsonPathsUnionArraysAndRespectDepth() {
        val e = Shape.parseJson("""{"games":[{"id":1},{"id":2,"clock":{"running":true}}],"date":"x","empty":[],"nothing":null}""")
        assertEquals(setOf("games", "games[]", "games[].id", "games[].clock", "date", "empty", "empty[]", "nothing"), Shape.jsonPaths(e, 2))
        assertTrue("games[].clock.running" in Shape.jsonPaths(e, 3))
        val shape = Shape.jsonShape(e, 3)
        assertEquals(setOf("empty", "nothing"), shape.leaves)
        assertEquals(listOf("games[].period"), shape.missing(setOf("games[].period", "empty[].id", "nothing.x", "date")))
    }

    @Test
    fun truncatedSamplesAreUnwrappedAndMarkersIgnored() {
        val e = Shape.unwrapSample(Shape.parseJson("""{"_openscore_note":"TRUNCATED","_truncated_array":[{"id":1}]}"""))
        assertEquals(setOf("[].id"), Shape.jsonPaths(e, 2))
        val obj = Shape.parseJson("""{"_openscore_note":"x","a":1}""")
        assertEquals(setOf("a"), Shape.jsonPaths(obj, 1))
    }

    @Test
    fun mapOfObjectsComparesTheFirstValue() {
        val e = Shape.parseJson("""{"DFL-MAT-1":{"matchId":"a","score":{}},"DFL-MAT-2":{"matchId":"b"}}""")
        assertEquals(setOf("matchId", "score"), Shape.jsonPaths(e, 1, mapOfObjects = true))
    }

    @Test
    fun hasJsonPathSemantics() {
        val e = Shape.parseJson("""{"games":[{"id":1},{"state":"LIVE"}],"empty":[],"nil":null,"obj":{"k":[1]}}""")
        assertTrue(Shape.hasJsonPath(e, "games[].id"))
        assertTrue(Shape.hasJsonPath(e, "games[].state"))
        assertFalse(Shape.hasJsonPath(e, "games[].clock"))
        assertTrue(Shape.hasJsonPath(e, "empty[]"))
        assertFalse(Shape.hasJsonPath(e, "empty[].id"))
        assertFalse(Shape.hasJsonPath(e, "nil"))
        assertTrue(Shape.hasJsonPath(e, "obj.k[]"))
        assertFalse(Shape.hasJsonPath(e, "games.id"))
        assertTrue(Shape.hasJsonPath(Shape.parseJson("[{\"a\":1}]"), "[].a"))
    }

    @Test
    fun xmlPathsAndLookup() {
        val n = Shape.parseXml("""<schedule created="1"><tournament id="9"><round id="1"><game id="5"/></round></tournament></schedule>""")
        val paths = Shape.xmlPaths(n, 3)
        assertTrue("schedule/@created" in paths)
        assertTrue("schedule/tournament/round" in paths)
        assertFalse("schedule/tournament/round/game" in paths)
        assertTrue(Shape.hasXmlPath(n, "schedule/tournament/round/game/@id"))
        assertFalse(Shape.hasXmlPath(n, "schedule/tournament/team"))
        assertFalse(Shape.hasXmlPath(n, "other"))
    }

    @Test
    fun placeholders() = runBlocking {
        val p = Placeholders(LocalDate(2026, 9, 12), SecretResolver { if (it == "laliga.webview") "w" else null })
        assertEquals("/score/2026-09-12", p.resolve("/score/{today}"))
        assertEquals("d=20260913&y=2026", p.resolve("d={today+1|yyyyMMdd}&y={year}"))
        assertEquals("from=1789084800", p.resolve("from={today-1|epoch}"))
        assertEquals("w", p.resolve("{secret.laliga.webview}"))
        // GraphQL selection sets are not placeholders.
        assertEquals("{team(abbrv:\"AIK\"){abbrv name}}", p.resolve("{team(abbrv:\"AIK\"){abbrv name}}"))
        val failed = runCatching { p.resolve("{secret.laliga.other}") }.exceptionOrNull()
        assertTrue(failed is UnresolvedPlaceholder, "$failed")
    }
}
