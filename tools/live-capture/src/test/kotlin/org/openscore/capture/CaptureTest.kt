package org.openscore.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaptureTest {
    @Test
    fun slugKeepsThePathAndHashesTheQuery() {
        assertEquals("gameday_game-overview_bdhvuc5tex", Capture.slug("https://sportality.cdn.scc.se/gameday/game-overview/bdhvuc5tex"))
        assertEquals("v1_score_2026-09-13", Capture.slug("https://api-web.nhle.com/v1/score/2026-09-13"))
        val a = Capture.slug("https://khl.api.webcaster.pro/api/khl_mobile/event_v2.json?id=3000195&locale=en")
        val b = Capture.slug("https://khl.api.webcaster.pro/api/khl_mobile/event_v2.json?id=3000196&locale=en")
        assertTrue(a.startsWith("api_khl_mobile_event_v2.json_q") && a != b, "$a / $b")
    }

    @Test
    fun slugNamesGraphqlByRootField() {
        assertEquals("graphql-matchesForLeague", Capture.slug("https://gql.sportomedia.se/?query=%7BmatchesForLeague(configLeagueName%3A%22allsvenskan%22)%7Bmatches%7Bid%7D%7D%7D"))
    }

    @Test
    fun signatureDiffNamesWhatChanged() {
        val a = Signature("LIVE", "in_progress", "1-0", 1, true, "1:1-0", null, 3, 40, null)
        assertEquals(listOf("first"), a.diff(null))
        assertEquals(emptyList(), a.diff(a))
        assertEquals(listOf("state", "score", "period", "running"), a.copy(state = "INTERMISSION", score = "1-1", period = 2, running = false).diff(a))
    }
}
