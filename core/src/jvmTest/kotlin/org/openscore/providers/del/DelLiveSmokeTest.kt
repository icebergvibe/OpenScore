package org.openscore.providers.del

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.openscore.model.GameState
import org.openscore.net.KtorFetcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Talks to the real API: a finished day, one game with events and statistics, its lineups and
 * shots, a roster, a player, a club's schedule. Off by default;
 * `-Dopenscore.live=true --tests '*DelLiveSmokeTest*'`.
 */
class DelLiveSmokeTest {
    @Test
    fun readsADayAGameAndATeam() {
        if (System.getProperty("openscore.live") != "true") return
        runBlocking {
            val fetcher = KtorFetcher()
            val del = DelProvider(fetcher)
            fun ms(t0: Long) = "${System.currentTimeMillis() - t0} ms"

            var t = System.currentTimeMillis()
            val day = del.gamesOn(LocalDate.parse("2026-09-18"))
            println("2026-09-18: ${day.size} games in ${ms(t)}")
            day.forEach { println("  ${it.id} ${it.startTime} ${it.state} ${it.away.name} @ ${it.home.name} ${it.score} ${it.ending ?: ""} · ${it.rawState}") }
            assertEquals(6, day.size)
            assertTrue(day.all { it.state == GameState.FINAL })

            t = System.currentTimeMillis()
            val game = del.game("4389t77")
            println("game 4389t77: ${game.state} ${game.score} ${game.ending} events=${game.events?.size} stats=${game.stats} in ${ms(t)}")
            game.events!!.forEach { println("  ${it.period.label} ${it.time.elapsed} ${it.type} ${it.description}") }
            assertEquals(GameState.FINAL, game.state)
            assertEquals(19, game.events!!.size)
            assertTrue(game.stats.isNotEmpty())

            t = System.currentTimeMillis()
            val lineups = del.lineups("4389t77")
            println("lineups: ${lineups.map { "${it.team.name}: ${it.groups.map { g -> "${g.label}=${g.players.size}" }}" }} in ${ms(t)}")
            assertEquals(2, lineups.size)

            t = System.currentTimeMillis()
            val events = del.events("4389t77")
            println("events with shots: ${events.size} (${events.count { it.coordinates != null }} with coordinates) in ${ms(t)}")
            assertTrue(events.size > 100)

            t = System.currentTimeMillis()
            val roster = del.roster("EBB")
            println("EBB roster: ${roster.size} players in ${ms(t)}; first ${roster.first().name} ${roster.first().ref.position} ${roster.first().birthDate}")
            assertTrue(roster.size > 20)

            t = System.currentTimeMillis()
            val player = del.player("2209")
            println("player 2209: ${player.name} #${player.ref.jerseyNumber} ${player.nationality} ${player.heightCm} cm in ${ms(t)}")

            t = System.currentTimeMillis()
            val schedule = del.teamSchedule("EBB", LocalDate.parse("2026-09-01"), LocalDate.parse("2026-10-31"))
            println("EBB schedule Sep-Oct: ${schedule.size} games in ${ms(t)}")
            assertTrue(schedule.isNotEmpty())
            assertTrue(schedule.all { it.home.id == "EBB" || it.away.id == "EBB" })

            t = System.currentTimeMillis()
            val table = del.standings()
            println("table: ${table.rows.size} rows, ${table.rows.first().team.name} first, in ${ms(t)}")

            val playoff = del.game("4384t76")
            println("playoff final G4: ${playoff.competition} ${playoff.state} ${playoff.score} ${playoff.ending} ${playoff.periodScores.map { it.period.label }}")
            fetcher.close()
        }
    }
}
