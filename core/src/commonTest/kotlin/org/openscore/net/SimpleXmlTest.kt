package org.openscore.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimpleXmlTest {
    @Test
    fun parsesAttributesEntitiesAndNesting() {
        val xml = """<?xml version="1.0"?>
            <!-- comment -->
            <root created="2026-09-12 09:21:28" status="200">
              <team id="89484" short-name="&#xD6;rebro &amp; Co" home-team="false"/>
              <events><event id="1" type="G"><participants><participant id="7" surname="Ok&#xE4;nd"/></participants></event></events>
              <note>hello <![CDATA[<world>]]></note>
            </root>"""
        val root = SimpleXml.parse(xml)
        assertEquals("root", root.name)
        assertEquals("200", root["status"])
        val team = root.child("team")!!
        assertEquals("Örebro & Co", team["short-name"])
        assertEquals(false, team.bool("home-team"))
        assertEquals(89484, team.int("id"))
        assertEquals("Okänd", root.child("events")!!.child("event")!!.child("participants")!!.child("participant")!!["surname"])
        assertEquals("hello <world>", root.child("note")!!.text)
        assertNull(root.child("missing"))
    }

    @Test
    fun truncatedBodiesFailInsteadOfLooping() {
        // A body cut off by the network: a close tag with no `>` used to rewind the parser to 0
        // and re-parse the document forever. Every cut point must either parse or throw, quickly.
        val whole = """<root a="1"><game id="7"><team name="X"/><note>hi <![CDATA[x]]></note></game></root>"""
        for (cut in 1 until whole.length) {
            try {
                SimpleXml.parse(whole.take(cut))
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message.orEmpty().isNotEmpty(), "message for cut at $cut")
            }
        }
        assertFailsWith<IllegalArgumentException> { SimpleXml.parse("""<root><game id="7"></""") }
        assertFailsWith<IllegalArgumentException> { SimpleXml.parse("""<root><team name="unterminated""") }
        assertFailsWith<IllegalArgumentException> { SimpleXml.parse("<root><![CDATA[never closed") }
        assertFailsWith<IllegalArgumentException> { SimpleXml.parse("") }
    }

    @Test
    fun boolReadsOnlyTrueAndFalse() {
        val root = SimpleXml.parse("""<r yes="true" no=" FALSE " maybe="1"/>""")
        assertEquals(true, root.bool("yes"))
        assertEquals(false, root.bool("no"))
        assertNull(root.bool("maybe"))
        assertNull(root.bool("absent"))
    }
}
