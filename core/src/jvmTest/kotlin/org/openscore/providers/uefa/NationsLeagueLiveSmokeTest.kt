package org.openscore.providers.uefa

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.openscore.model.GameState
import org.openscore.net.KtorFetcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Talks to the real uefa.com services as the Nations League: a matchday, one match with its
 * timeline and line-ups, the fourteen group tables, a national side and its fixtures. Off by
 * default; `-Dopenscore.live=true --tests '*NationsLeagueLiveSmokeTest*'`.
 */
class NationsLeagueLiveSmokeTest {
    @Test
    fun readsAMatchdayAMatchAndATable() {
        if (System.getProperty("openscore.live") != "true") return
        runBlocking {
            val unl = NationsLeagueProvider(KtorFetcher())
            fun ms(t0: Long) = "${System.currentTimeMillis() - t0} ms"

            var t = System.currentTimeMillis()
            val day = unl.gamesOn(LocalDate.parse("2026-09-24"))
            println("2026-09-24: ${day.size} matches in ${ms(t)}")
            day.forEach { println("  ${it.id} ${it.startTime} ${it.state} ${it.home.name} v ${it.away.name} ${it.score ?: ""} · ${it.competition} · ${it.venue}") }
            assertTrue(day.isNotEmpty(), "matchday 1 of the 2026/27 edition")
            assertTrue(day.all { it.leagueId == "unl" && it.seasonId == "2027" })
            assertTrue(day.all { it.home.clubId == null }, "national sides are not clubs")

            // The 2025 final: 2-2, Portugal on penalties.
            t = System.currentTimeMillis()
            val final = unl.game("2044949")
            println("final: ${final.home.name} ${final.score} ${final.away.name} ${final.ending}, ${final.events?.size} events in ${ms(t)}")
            assertEquals(GameState.FINAL, final.state)
            assertTrue((final.events?.size ?: 0) > 20)
            val lineups = unl.lineups("2044949")
            println("lineups: ${lineups.map { "${it.team.name} ${it.players.size}" }}")
            assertEquals(2, lineups.size)

            t = System.currentTimeMillis()
            val table = unl.standings()
            println("standings: ${table.groups.size} groups, ${table.rows.size} teams in ${ms(t)}")
            table.groups.forEach { g -> println("  ${g.label}: " + g.rows.joinToString { "${it.rank} ${it.team.name} ${it.points}" }) }
            assertEquals(14, table.groups.size, "four tiers, fourteen groups")
            assertEquals(54, table.rows.size)
            assertTrue(table.groups.first().label.startsWith("League A"))

            t = System.currentTimeMillis()
            val malta = unl.team("88")
            println("team: ${malta.name} (${malta.ref.abbreviation}) ${malta.country} ${malta.ref.logoUrl} in ${ms(t)}")
            assertEquals("Malta", malta.name)
            assertNull(malta.ref.clubId)

            t = System.currentTimeMillis()
            val fixtures = unl.teamSchedule("88", LocalDate(2026, 7, 1), LocalDate(2027, 6, 30))
            println("Malta: ${fixtures.size} fixtures in ${ms(t)}")
            fixtures.forEach { println("  ${it.startTime} ${it.home.name} v ${it.away.name} ${it.score ?: ""}") }
            assertTrue(fixtures.isNotEmpty())

            // The season is biennial: the even years in between hold nothing at all.
            assertEquals(2027, unl.currentSeason())
            assertTrue(unl.gamesOn(LocalDate.parse("2026-09-13")).isEmpty(), "no match outside an international window")
        }
    }
}
