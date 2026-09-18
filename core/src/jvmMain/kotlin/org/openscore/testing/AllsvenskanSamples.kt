package org.openscore.testing

import io.ktor.http.encodeURLParameter
import kotlinx.datetime.LocalDate
import org.openscore.providers.sportomedia.SportomediaProvider
import org.openscore.providers.sportomedia.SportomediaProvider.Queries
import java.io.File

/** URL → sample routing for the Allsvenskan samples captured on 2026-09-12 (season 2026, round 21). */
public object AllsvenskanSamples {
    public const val YEAR: Int = 2026
    public const val DAY: String = "2026-09-11"
    public const val FINAL_ID: String = "6529993" // BK Häcken 1–1 Mjällby AIF
    public const val PRE_ID: String = "6529989" // AIK–Västerås SK
    public const val TEAM: String = "AIK"
    public const val PLAYER_ID: String = "1500426"
    private const val L = "allsvenskan"

    private fun url(q: String) = "${SportomediaProvider.DEFAULT_BASE_URL}?query=${q.encodeURLParameter()}"

    public fun register(fetcher: SampleFetcher, root: File = SampleFetcher.repoRoot()): SampleFetcher {
        val dir = SampleFetcher.samplesDir("football", "allsvenskan", root)
        fun r(q: String, file: String) = fetcher.route(url(q), File(dir, file))
        val day = LocalDate.parse(DAY)
        r(Queries.matchesForLeague(L, YEAR, day, LocalDate.parse("2026-09-12")), "matches-for-league.round.json")
        r(Queries.match(FINAL_ID, L, YEAR), "match.final.json")
        r(Queries.lineups(FINAL_ID, L, YEAR), "lineups.final.json")
        r(Queries.matchStats(FINAL_ID, L, YEAR), "match-stats.final.json")
        r(Queries.match(PRE_ID, L, YEAR), "match.pre.json")
        r(Queries.lineups(PRE_ID, L, YEAR), "lineups.pre.json")
        r(Queries.matchStats(PRE_ID, L, YEAR), "match-stats.pre.json")
        r(Queries.match("1", L, YEAR), "match-stats.pre.json") // "Unexpected error." is how not-found looks
        r(Queries.standings(L, YEAR), "standings-for-league.total.json")
        r(Queries.teamsForLeague(L, YEAR), "teams-for-league.json")
        r(Queries.team(TEAM), "team.json")
        r(Queries.squad(TEAM, YEAR), "squad.json")
        r(Queries.player(PLAYER_ID), "player.json")
        return fetcher
    }
}
