package org.openscore.providers.nhl

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.openscore.net.KtorFetcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Hits the real NHL API. Off by default; run with
 * `./gradlew :core:jvmTest -Dopenscore.live=true --tests '*NhlLiveSmokeTest*'`.
 * Makes three requests, all cacheable — well within docs/principles.md.
 */
class NhlLiveSmokeTest {

    private val enabled = System.getProperty("openscore.live") == "true"

    @Test
    fun todayStandingsAndOneTeam() {
        if (!enabled) return
        val fetcher = KtorFetcher()
        try {
            val nhl = NhlProvider(fetcher)
            runBlocking {
                val today = Clock.System.todayIn(TimeZone.of("America/New_York"))
                val games = nhl.gamesOn(today)
                println("NHL games on $today: ${games.size}")
                games.forEach { println("  ${it.id} ${it.away.name} @ ${it.home.name} ${it.state} ${it.score} ${it.clock}") }

                val table = nhl.standings()
                assertEquals(32, table.rows.size)
                println("Standings ${table.seasonId}: leader ${table.rows.minBy { it.extra["leagueRank"]!!.toInt() }.team.name}")

                val tor = nhl.team("TOR")
                assertEquals("Toronto Maple Leafs", tor.name)
                assertTrue(fetcher.get(NhlProvider.DEFAULT_BASE_URL + "/standings/now").fromCache, "team() reused the cached standings")
            }
        } finally {
            fetcher.close()
        }
    }
}
