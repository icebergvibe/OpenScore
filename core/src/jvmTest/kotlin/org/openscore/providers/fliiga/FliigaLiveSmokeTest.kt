package org.openscore.providers.fliiga

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.openscore.model.GameEnding
import org.openscore.model.GameState
import org.openscore.model.LineupGroupKind
import org.openscore.net.KtorFetcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Talks to the real fliiga.com routes: a finished day, an overtime match with its events,
 * period scores and lineups, the table, a roster and a player. Off by default;
 * `-Dopenscore.live=true --tests '*FliigaLiveSmokeTest*'`.
 */
class FliigaLiveSmokeTest {
    @Test
    fun readsADayAMatchAndATeam() {
        if (System.getProperty("openscore.live") != "true") return
        runBlocking {
            val fetcher = KtorFetcher()
            val fliiga = FliigaProvider(fetcher)
            fun ms(t0: Long) = "${System.currentTimeMillis() - t0} ms"

            var t = System.currentTimeMillis()
            val day = fliiga.gamesOn(LocalDate.parse("2026-09-20"))
            println("2026-09-20: ${day.size} games in ${ms(t)}")
            day.forEach { println("  ${it.id} ${it.startTime} ${it.state} ${it.away.name} @ ${it.home.name} ${it.score} · ${it.rawState}") }
            assertTrue(day.isNotEmpty())
            assertTrue(day.all { it.state == GameState.FINAL })
            assertTrue(day.all { it.home.id.toIntOrNull() != null }, "teams carry their TorneoPal club id")

            t = System.currentTimeMillis()
            val game = fliiga.game("929774")
            println("game 929774: ${game.state} ${game.score} ${game.ending} periods=${game.periodScores.map { "${it.period.label} ${it.home}-${it.away}" }} events=${game.events?.size} stats=${game.stats} in ${ms(t)}")
            game.events?.filter { it.type.isGoal || it.type.key == "penalty" }?.forEach {
                println("  ${it.period.label} ${it.time.label} ${it.type.key} ${it.players.map { p -> p.name }} ${it.score ?: ""} ${it.description}")
            }
            assertEquals(GameState.FINAL, game.state)
            assertEquals(3, game.score?.home)
            assertEquals(4, game.score?.away)
            assertEquals(GameEnding.OVERTIME, game.ending)
            assertEquals(listOf("1", "2", "3", "OT"), game.periodScores.map { it.period.label })
            assertEquals(7, game.events!!.count { it.type.isGoal })
            assertEquals(1, game.events.count { it.type.key == "penalty" })
            assertTrue(game.events.count { it.coordinates != null } > 50, "shots carry rink coordinates")

            t = System.currentTimeMillis()
            val lineups = fliiga.lineups("929774")
            println("lineups: ${lineups.map { "${it.team.name}: ${it.groups.map { g -> "${g.label}=${g.players.size}" }} coach=${it.headCoach}" }} in ${ms(t)}")
            assertEquals(2, lineups.size)
            assertTrue(lineups.all { l -> l.groups.any { it.kind == LineupGroupKind.GOALIES } })
            assertTrue(lineups.all { l -> l.groups.count { it.kind == LineupGroupKind.LINE } >= 3 })
            assertTrue(lineups.all { it.players.size == 20 })

            t = System.currentTimeMillis()
            val table = fliiga.standings()
            println("table in ${ms(t)}")
            table.rows.forEach { println("  ${it.rank} ${it.team.name} (${it.team.id}) ${it.played} ${it.wins}-${it.losses}-${it.otherLosses} ${it.points} pts ${it.extra}") }
            assertEquals(12, table.rows.size)
            assertTrue(table.rows.all { it.played == it.wins + it.losses + (it.otherLosses ?: 0) })

            t = System.currentTimeMillis()
            val roster = fliiga.roster("393")
            println("OLS roster: ${roster.size} players in ${ms(t)}; first ${roster.first().name} #${roster.first().ref.jerseyNumber} ${roster.first().ref.position} team=${roster.first().teamId}")
            assertTrue(roster.size > 20)
            assertTrue(roster.any { it.ref.position == "MV" }, "goalkeepers come from the second board")

            t = System.currentTimeMillis()
            val player = fliiga.player("47983")
            println("player 47983: ${player.name} #${player.ref.jerseyNumber} ${player.birthDate} team=${player.teamId} in ${ms(t)}")
            assertEquals("374", player.teamId)

            t = System.currentTimeMillis()
            val schedule = fliiga.teamSchedule("393", LocalDate.parse("2026-09-01"), LocalDate.parse("2026-11-30"))
            println("OLS schedule: ${schedule.size} games in ${ms(t)}")
            assertTrue(schedule.size > 5)
            assertTrue(schedule.all { it.home.id == "393" || it.away.id == "393" })
        }
    }
}
