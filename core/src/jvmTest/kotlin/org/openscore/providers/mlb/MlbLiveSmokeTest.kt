package org.openscore.providers.mlb

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.openscore.model.GameState
import org.openscore.model.baseball.BaseballSituation
import org.openscore.net.KtorFetcher
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Real requests against statsapi.mlb.com: today's schedule, one live/final feed, its
 * boxscore, the standings. Off by default;
 * `./gradlew :core:jvmTest -Dopenscore.live=true --tests '*MlbLiveSmokeTest*'`.
 */
class MlbLiveSmokeTest {

    @Test
    fun todayLiveAndFinalGamesParse() {
        if (System.getProperty("openscore.live") != "true") return
        val fetcher = KtorFetcher()
        try {
            val mlb = MlbProvider(fetcher)
            runBlocking {
                val today = Clock.System.todayIn(TimeZone.of("America/New_York"))
                val games = mlb.gamesOn(today)
                println("MLB games on $today: ${games.size}")
                games.forEach { println("  ${it.id} ${it.away.abbreviation} @ ${it.home.abbreviation} ${it.state} ${it.score ?: ""} ${it.clock?.time?.label ?: ""} ${it.rawState}") }

                val pick = games.firstOrNull { it.state.isLive } ?: games.firstOrNull { it.state == GameState.FINAL } ?: games.firstOrNull()
                if (pick != null) {
                    val g = mlb.game(pick.id)
                    println("  feed ${g.id}: ${g.state} ${g.score} clock=${g.clock?.time?.label} events=${g.events?.size} stats=${g.stats}")
                    (g.situation as? BaseballSituation)?.let { println("  situation: ${it.half} ${it.inning}, ${it.outs} out, ${it.balls}-${it.strikes}, on: ${listOfNotNull(it.onFirst, it.onSecond, it.onThird).map { p -> p.name }}, up: ${it.batter?.name} vs ${it.pitcher?.name}") }
                    g.events?.takeLast(5)?.forEach { println("    ${it.time.label} ${it.type.key} ${it.description}") }
                    val lineups = mlb.lineups(g.id)
                    println("  lineups: ${lineups.map { l -> "${l.team.abbreviation}: " + l.groups.joinToString { "${it.label}=${it.players.size}" } }}")
                }
                val table = mlb.standings()
                println("  standings: ${table.groups.map { it.label + " → " + it.rows.first().team.abbreviation }}")
                assertTrue(table.rows.size == 30, "expected 30 teams, got ${table.rows.size}")
            }
        } finally {
            fetcher.close()
        }
    }
}
