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

    /**
     * Serie A's four match endpoints share a 90-character prefix and differ only in the last
     * segment, so a head-truncating slug gave them all one file name and three of every four
     * bodies were overwritten.
     */
    @Test
    fun slugKeepsTheEndpointNameOffTheEndOfALongPath() {
        val base = "https://api-sdp.legaseriea.it/v1/serie-a/football/seasons/serie-a::Football_Season::ed7fdc2a3e7b408b942ec177b7b956b5"
        val match = "serie-a::Football_Match::b7ecdcd497b3445490aa0f0ba58468b5"
        val slugs = listOf("$base/matches/$match/header", "$base/match/$match/summary", "$base/match/$match/lineups", "$base/match/$match/teamstats")
            .map(Capture::slug)
        assertEquals(slugs.size, slugs.toSet().size, "each endpoint needs its own file: $slugs")
        assertTrue(slugs.all { it.length <= 110 }, "still short enough for a file name: $slugs")
        assertTrue(slugs[0].endsWith("header") && slugs[3].endsWith("teamstats"), "the endpoint name survives: $slugs")
        assertTrue(slugs.all { it.startsWith("v1_serie-a_football_seasons_") }, "and the head still says what it is: $slugs")
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
