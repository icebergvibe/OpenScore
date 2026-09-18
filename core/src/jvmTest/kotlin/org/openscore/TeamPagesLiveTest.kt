package org.openscore

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.openscore.model.Sport
import org.openscore.net.KtorFetcher
import org.openscore.provider.Capability
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The team-page calls the app makes, against the real feeds, for every football league: the
 * current table, then the leader's profile, squad and season schedule. Off by default;
 * `./gradlew :core:jvmTest -Dopenscore.live=true --tests '*TeamPagesLiveTest*'`
 * (set `LIVE_LEAGUES=serie-a,mls` to narrow it).
 */
class TeamPagesLiveTest {

    @Test
    fun everyFootballLeagueServesItsTeamPages() {
        if (System.getProperty("openscore.live") != "true") return
        val fetcher = KtorFetcher()
        val only = System.getenv("LIVE_LEAGUES")?.split(",")?.toSet()
        val problems = ArrayList<String>()
        try {
            val all = OpenScore.default(fetcher)
            runBlocking {
                for (p in all.providers) {
                    if (p.league.sport != Sport.FOOTBALL || p.league.id in OpenScore.DEFAULT_UMBRELLAS) continue
                    if (only != null && p.league.id !in only) continue
                    if (!p.supports(Capability.STANDINGS)) { println("${p.league.id}: no table (cup)"); continue }
                    val table = runCatching { p.standings() }.getOrElse { e -> problems += "${p.league.id}: standings: $e"; continue }
                    val ref = table.rows.firstOrNull()?.team ?: run { println("${p.league.id}: empty table (pre-season)"); continue }
                    println("${p.league.id}: ${table.rows.size} rows, crests on ${table.rows.count { it.team.logoUrl != null }}, leader ${ref.name} (${ref.id})")
                    if (table.rows.any { it.team.logoUrl == null }) problems += "${p.league.id}: table rows without a crest"
                    if (p.supports(Capability.TEAM)) runCatching { p.team(ref.id) }
                        .onSuccess { println("  team: ${it.ref.name}, arena ${it.arena}") }
                        .onFailure { problems += "${p.league.id}: team(${ref.id}): $it" }
                    if (p.supports(Capability.ROSTER)) runCatching { p.roster(ref.id) }
                        .onSuccess { r -> println("  roster: ${r.size} players ${r.groupBy { it.ref.position }.mapValues { it.value.size }}"); if (r.size !in 15..60) problems += "${p.league.id}: roster of ${r.size} looks wrong" }
                        .onFailure { problems += "${p.league.id}: roster(${ref.id}): $it" }
                    if (p.supports(Capability.TEAM_SCHEDULE)) runCatching { p.teamSchedule(ref.id, LocalDate(2026, 1, 1), LocalDate(2027, 6, 30)) }
                        .onSuccess { g -> println("  schedule: ${g.size} games, ${g.count { it.state.isFinished }} played, competitions ${g.map { it.competition }.distinct()}"); if (g.isEmpty()) problems += "${p.league.id}: empty schedule" }
                        .onFailure { problems += "${p.league.id}: teamSchedule(${ref.id}): $it" }
                }
            }
        } finally {
            fetcher.close()
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }
}
