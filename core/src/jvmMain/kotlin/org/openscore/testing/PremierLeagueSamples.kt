package org.openscore.testing

import org.openscore.providers.premierleague.PremierLeagueProvider
import java.io.File

/**
 * URL → sample routing for the Premier League samples captured on 2026-09-11 (season 2026, MW 3/4),
 * plus the live states recorded from Coventry 0-5 Brighton on 2026-09-13 (364 polls at 5 s).
 */
public object PremierLeagueSamples {
    public const val DAY: String = "2026-09-13"
    public const val FINAL_MATCH_ID: String = "2645215" // Arsenal 2–1 Chelsea
    public const val PRE_MATCH_ID: String = "2645225" // Bournemouth–Brentford

    /** Coventry 0-5 Brighton, read at `SecondHalf/94'`: the routed bodies are one poll of the capture. */
    public const val LIVE_MATCH_ID: String = "2645228"
    public const val TEAM_ID: String = "3" // Arsenal
    public const val PLAYER_ID: String = "219847" // Havertz

    public val paths: Map<String, String> = mapOf(
        "/v2/matches?competition=8&season=2026&kickoff%3E$DAY&kickoff%3C2026-09-14&_limit=100" to "matches-daterange.json",
        "/v2/matches/$FINAL_MATCH_ID" to "match.final.json",
        "/v1/matches/$FINAL_MATCH_ID/timeline" to "timeline.final.json",
        "/v1/matches/$FINAL_MATCH_ID/events" to "events.final.json",
        "/v3/matches/$FINAL_MATCH_ID/lineups" to "lineups.final.json",
        "/v3/matches/$FINAL_MATCH_ID/stats" to "stats.final.json",
        "/v2/matches/$LIVE_MATCH_ID" to "match.live-second-half.json",
        "/v1/matches/$LIVE_MATCH_ID/timeline" to "timeline.live-second-half.json",
        "/v1/matches/$LIVE_MATCH_ID/events" to "events.live-second-half.json",
        "/v3/matches/$LIVE_MATCH_ID/lineups" to "lineups.live.json",
        "/v3/matches/$LIVE_MATCH_ID/stats" to "stats.live.json",
        "/v2/matches/$PRE_MATCH_ID" to "match.pre.json",
        "/v1/matches/$PRE_MATCH_ID/timeline" to "timeline.pre.json",
        "/v1/matches/$PRE_MATCH_ID/events" to "events.pre.json",
        "/v3/matches/$PRE_MATCH_ID/lineups" to "lineups.pre.json",
        "/v5/competitions/8/seasons/2026/standings" to "standings.json",
        "/v1/competitions/8/seasons/2026/teams?_limit=30" to "teams.json",
        "/v2/competitions/8/seasons/2026/teams/$TEAM_ID/squad" to "squad.json",
        "/v1/competitions/8/seasons/2026/playerinfo/$PLAYER_ID" to "playerinfo.json",
        "/v2/matches/1" to "match.404.json",
    )

    public fun register(fetcher: SampleFetcher, root: File = SampleFetcher.repoRoot()): SampleFetcher {
        val dir = SampleFetcher.samplesDir("football", "premier-league", root)
        fetcher.routes(PremierLeagueProvider.DEFAULT_BASE_URL, dir, paths)
        fetcher.route(PremierLeagueProvider.DEFAULT_BASE_URL + "/v2/matches/1", File(dir, "match.404.json"), status = 404)
        fetcher.route(PremierLeagueProvider.DEFAULT_BASE_URL + "/v3/matches/$PRE_MATCH_ID/stats", File(dir, "timeline.pre.json")) // `[]` before kick-off
        return fetcher
    }
}
