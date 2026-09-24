package org.openscore.providers.sportality

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.openscore.net.KtorFetcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Talks to the real site: the league table, a club's identity, its season and its table row, and
 * a player's profile. Off by default;
 * `-Dopenscore.live=true --tests '*HockeyAllsvenskanLiveSmokeTest*'`.
 */
class HockeyAllsvenskanLiveSmokeTest {
    @Test
    fun readsTheTableAClubAndAPlayer() {
        if (System.getProperty("openscore.live") != "true") return
        runBlocking {
            val ha = HockeyAllsvenskanProvider(KtorFetcher())
            fun ms(t0: Long) = "${System.currentTimeMillis() - t0} ms"

            var t = System.currentTimeMillis()
            val table = ha.standings()
            println("table (${table.seasonId}): ${table.rows.size} clubs in ${ms(t)}")
            table.rows.forEach {
                println(
                    "  ${it.rank}. ${it.team.name} (${it.team.id}/${it.team.abbreviation}, club=${it.team.clubId}) " +
                        "${it.played} ${it.wins}-${it.losses}-${it.otherLosses} ${it.points} p " +
                        "${it.goalsFor}:${it.goalsAgainst} PP ${it.extra["powerPlay"]} PK ${it.extra["penaltyKill"]}",
                )
            }
            assertEquals(14, table.rows.size)
            assertTrue(table.rows.all { it.team.clubId != null }, "every club is in the crosswalk")
            assertTrue(table.rows.all { it.played == it.wins + it.losses + (it.otherLosses ?: 0) }, "every game is in a column")

            // The table's team ids are the ones the games use, which is what a club page turns on.
            val leader = table.rows.first()
            t = System.currentTimeMillis()
            val team = ha.team(leader.team.id)
            println("team ${team.id}: ${team.name}, ${team.arena}, ${team.country} in ${ms(t)}")
            assertEquals(leader.team.id, team.id)
            assertNotNull(team.arena)

            t = System.currentTimeMillis()
            val season = ha.teamSchedule(leader.team.id, LocalDate.parse("2026-09-01"), LocalDate.parse("2027-03-31"))
            println("${team.name}: ${season.size} games in ${ms(t)}, ${season.count { it.state.isFinished }} played")
            assertTrue(season.size > 40, "a club plays every other club four times")
            assertTrue(season.all { it.home.id == team.id || it.away.id == team.id })

            t = System.currentTimeMillis()
            val stats = ha.teamStats(leader.team.id, "2026")
            println("stats: ${stats.groups.map { g -> "${g.label}: " + g.stats.joinToString(" ") { "${it.label} ${it.value}" } }} in ${ms(t)}")
            assertTrue(stats.groups.isNotEmpty())

            // A profile is keyed by its page slug; a lineup entry carries one for every dressed player.
            t = System.currentTimeMillis()
            val player = ha.player("patrik-zackrisson")
            println("player: ${player.name} #${player.ref.jerseyNumber} ${player.ref.position} ${player.nationality} ${player.heightCm} cm ${player.teamId} in ${ms(t)}")
            assertEquals("Patrik Zackrisson", player.name)
            assertNotNull(player.birthDate)
        }
    }
}
