package org.openscore

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.openscore.net.KtorFetcher
import org.openscore.provider.Capability
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Real requests against every league: today's games and the standings (2–4 calls
 * per league). Off by default; `./gradlew :core:jvmTest -Dopenscore.live=true --tests '*AllLeaguesLiveSmokeTest*'`.
 */
class AllLeaguesLiveSmokeTest {

    @Test
    fun everyLeagueAnswers() {
        if (System.getProperty("openscore.live") != "true") return
        val fetcher = KtorFetcher()
        try {
            val all = OpenScore.default(fetcher)
            runBlocking {
                val today = Clock.System.todayIn(TimeZone.UTC)
                val result = all.gamesOn(today)
                println("games on $today: ${result.games.size}; errors: ${result.errors.map { it.leagueId + ": " + it.cause.message }}")
                result.games.forEach { println("  [${it.leagueId}] ${it.away.name} @ ${it.home.name} ${it.state} ${it.score ?: ""} ${it.clock?.let { c -> "P${c.period.label} ${c.time.elapsed}" } ?: ""}") }
                assertTrue(result.errors.isEmpty(), result.errors.joinToString { "${it.leagueId}: ${it.cause}" })

                for (p in all.providers) {
                    if (!p.supports(Capability.STANDINGS)) continue
                    val table = runCatching { p.standings() }
                    table.onSuccess { t -> println("  standings ${p.league.id} (${t.seasonId}): ${t.rows.size} rows, leader ${t.rows.firstOrNull()?.team?.name}") }
                        .onFailure { e -> println("  standings ${p.league.id} FAILED: $e") }
                    assertTrue(table.isSuccess, "${p.league.id} standings: ${table.exceptionOrNull()}")
                }
            }
        } finally {
            fetcher.close()
        }
    }
}
