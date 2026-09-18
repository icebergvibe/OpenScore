package org.openscore.net

import kotlin.test.Test
import kotlin.test.assertEquals

class Utf8LengthTest {
    @Test
    fun matchesTheEncodedSize() {
        val samples = listOf(
            "",
            "plain ascii {\"json\": 1}",
            "Örebro & Malmö — Göteborg",
            "日本語のテキスト",
            "emoji 🏒 and a pair 🥅",
            "lone high surrogate \uD83C end",
            "lone low surrogate \uDFD2 end",
        )
        for (s in samples) assertEquals(s.encodeToByteArray().size.toLong(), s.utf8Length(), s)
    }
}
