package org.openscore

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.openscore.model.Sport
import org.openscore.net.KtorFetcher
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.measureTime

/**
 * Wall time of one football day through the aggregator, cold and warm, then each league on
 * its own with a fresh fetcher so the straggler is named. Off by default;
 * `./gradlew :core:jvmTest -Dopenscore.live=true --tests '*GamesOnTimingLiveTest*' --rerun-tasks -i`.
 */
class GamesOnTimingLiveTest {

    @Test
    fun footballDayTiming() {
        if (System.getProperty("openscore.live") != "true") return
        val fetcher = KtorFetcher()
        try {
            val all = OpenScore.default(fetcher)
            val football = all.leagues.filter { it.sport == Sport.FOOTBALL && it.id !in OpenScore.DEFAULT_UMBRELLAS }.map { it.id }
            runBlocking {
                val today = Clock.System.todayIn(TimeZone.UTC)
                repeat(2) { round ->
                    val t = measureTime {
                        val r = all.gamesOn(today, football)
                        println("round $round: ${r.games.size} games, errors=${r.errors.map { it.leagueId }}")
                    }
                    println("round $round took $t")
                }
                val fresh = KtorFetcher()
                val core = OpenScore.default(fresh)
                coroutineScope {
                    football.map { id -> async { id to measureTime { runCatching { core.provider(id).gamesOn(today) }.onFailure { println("  $id failed: $it") } } } }.awaitAll()
                }.sortedByDescending { it.second }.forEach { println("  concurrent ${it.first}: ${it.second}") }
                fresh.close()
            }
        } finally {
            fetcher.close()
        }
    }
}
