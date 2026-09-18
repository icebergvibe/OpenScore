package org.openscore.testing

import org.openscore.providers.efl.EflProvider
import java.io.File

/** URL → sample routing for the EFL samples captured on 2026-09-17 (season 2026: Championship round 8, Carabao Cup round 3). */
public object EflSamples {
    public const val CUP_DAY: String = "2026-09-17" // Manchester City 5-0 Norwich, the one cup tie that night
    public const val CHAMPIONSHIP_DAY: String = "2026-09-19" // round 8, nine fixtures, all pre-match
    public const val EMPTY_DAY: String = "2026-09-21" // a Monday with nothing on: 404
    public const val FINAL_MATCH_ID: String = "g2685170" // Manchester City 5-0 Norwich City (cup)
    public const val SHOOTOUT_MATCH_ID: String = "g2685182" // Peterborough 3-3 Barnsley, 6-7 pens (cup)
    public const val EXTRA_TIME_MATCH_ID: String = "g2634379" // Southampton 2-1 Middlesbrough aet, play-off semi 2nd leg
    public const val PLAYOFF_FINAL_MATCH_ID: String = "g2634991" // Hull City 1-0 Middlesbrough, Wembley
    public const val PRE_MATCH_ID: String = "g2647335" // Bristol City v Watford
    public const val TEAM_ID: String = "t109" // Wrexham
    public const val SEASON: String = "2026"
    /** Wall clock for the tests: the capture day, so `currentSeason()` is 2026. */
    public const val NOW: String = "2026-09-18T12:00:00Z"

    private fun day(date: String, competition: Int) = "/matches?from=$date%2000:00:00Z&to=$date%2023:59:59Z&competitionID=$competition&seasonID=$SEASON&page.size=100"

    public val paths: Map<String, String> = mapOf(
        day(CUP_DAY, 2) to "matches-date.final.json",
        day(CHAMPIONSHIP_DAY, 10) to "matches-date.pre.json",
        "/matches/$FINAL_MATCH_ID" to "match.final.json",
        "/stats/match/$FINAL_MATCH_ID" to "stats-match.final.json",
        "/matches/$SHOOTOUT_MATCH_ID" to "match.final.shootout.json",
        "/matches/$EXTRA_TIME_MATCH_ID" to "match.final.extra-time.json",
        "/matches/$PLAYOFF_FINAL_MATCH_ID" to "match.final.playoff-final.json",
        "/matches/$PRE_MATCH_ID" to "match.pre.json",
        "/stats/match/$PRE_MATCH_ID" to "stats-match.pre.json",
        "/league-tables?competitionID=10&seasonID=$SEASON" to "league-tables.json",
        "/teams?competitionID=10&seasonID=$SEASON&page.size=100" to "teams.json",
        "/teams?competitionID=2&seasonID=$SEASON&page.size=100" to "teams-cup.json",
        "/matches?seasonID=$SEASON&teamID=$TEAM_ID&page.size=100" to "matches-team.json",
    )

    public fun register(fetcher: SampleFetcher, root: File = SampleFetcher.repoRoot()): SampleFetcher {
        val dir = SampleFetcher.samplesDir("football", "efl", root)
        fetcher.routes(EflProvider.DEFAULT_BASE_URL, dir, paths)
        for (competition in listOf(2, 10)) fetcher.route(EflProvider.DEFAULT_BASE_URL + day(EMPTY_DAY, competition), File(dir, "matches-date.empty.json"), status = 404)
        fetcher.route(EflProvider.DEFAULT_BASE_URL + "/matches/g1", File(dir, "match.404.json"), status = 404)
        fetcher.route(EflProvider.DEFAULT_BASE_URL + "/league-tables?competitionID=2&seasonID=$SEASON", File(dir, "league-tables.404.json"), status = 404)
        // The shoot-out, extra-time and play-off documents have no stats sample: serve "nothing yet" so game() still maps.
        for (id in listOf(SHOOTOUT_MATCH_ID, EXTRA_TIME_MATCH_ID, PLAYOFF_FINAL_MATCH_ID)) {
            fetcher.route(EflProvider.DEFAULT_BASE_URL + "/stats/match/$id", File(dir, "stats-match.pre.json"))
        }
        return fetcher
    }
}
