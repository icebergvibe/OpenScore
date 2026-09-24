package org.openscore.providers.ssl

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.openscore.model.GameState
import org.openscore.net.KtorFetcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Talks to the real ssl.se API: a finished day, one game with its post-game totals, the table,
 * a team, its roster and one player. Off by default;
 * `-Dopenscore.live=true --tests '*SslLiveSmokeTest*'`.
 */
class SslLiveSmokeTest {
    @Test
    fun readsADayAGameAndATeam() {
        if (System.getProperty("openscore.live") != "true") return
        runBlocking {
            val ssl = SslProvider(KtorFetcher())
            fun ms(t0: Long) = "${System.currentTimeMillis() - t0} ms"

            var t = System.currentTimeMillis()
            val day = ssl.gamesOn(LocalDate.parse("2026-09-20"))
            println("2026-09-20: ${day.size} games in ${ms(t)}")
            day.forEach { println("  ${it.id} ${it.startTime} ${it.state} ${it.away.name} @ ${it.home.name} ${it.score} ${it.ending ?: ""} · ${it.venue}") }
            assertTrue(day.isNotEmpty(), "the opening weekend has SSL Herr games")
            assertTrue(day.all { it.state == GameState.FINAL })

            t = System.currentTimeMillis()
            val game = ssl.game("msmb27in1v")
            println("game msmb27in1v: ${game.state} ${game.score} ${game.ending} stats=${game.stats} in ${ms(t)}")
            assertEquals(GameState.FINAL, game.state)
            assertEquals(10, game.score?.home)
            assertEquals(6, game.score?.away)
            assertTrue(game.stats.containsKey("shotsOnGoal"))
            assertTrue(game.events == null, "SSL has no event source")

            t = System.currentTimeMillis()
            val table = ssl.standings()
            println("table in ${ms(t)}")
            table.rows.forEach { println("  ${it.rank} ${it.team.name} ${it.played} ${it.wins}-${it.losses}-${it.otherLosses} ${it.points} pts ${it.extra}") }
            assertEquals(14, table.rows.size)
            assertTrue(table.rows.all { it.played == it.wins + it.losses + (it.otherLosses ?: 0) }, "wins + losses + OT losses must account for every game")

            t = System.currentTimeMillis()
            val team = ssl.team("2cdf-32c4bo2vU")
            println("team: ${team.name} (${team.ref.abbreviation}) ${team.ref.logoUrl} club=${team.ref.clubId} in ${ms(t)}")

            t = System.currentTimeMillis()
            val roster = ssl.roster("2cdf-32c4bo2vU")
            println("roster: ${roster.size} players in ${ms(t)}; first ${roster.first().name} #${roster.first().ref.jerseyNumber} ${roster.first().ref.position}")
            assertTrue(roster.size > 15)

            t = System.currentTimeMillis()
            val player = ssl.player("ihdjejtzeo")
            println("player: ${player.name} #${player.ref.jerseyNumber} ${player.nationality} ${player.birthDate} in ${ms(t)}")

            t = System.currentTimeMillis()
            val schedule = ssl.teamSchedule("2cdf-32c4bo2vU", LocalDate.parse("2026-09-01"), LocalDate.parse("2026-11-30"))
            println("Mullsjö schedule: ${schedule.size} games in ${ms(t)}")
            assertTrue(schedule.size > 4)
            assertTrue(schedule.all { it.home.id == "2cdf-32c4bo2vU" || it.away.id == "2cdf-32c4bo2vU" })
        }
    }
}
