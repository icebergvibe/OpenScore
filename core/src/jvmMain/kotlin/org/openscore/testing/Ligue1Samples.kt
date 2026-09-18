package org.openscore.testing

import org.openscore.providers.ligue1.Ligue1Provider
import java.io.File

/** URL → sample routing for the Ligue 1 samples captured on 2026-09-11 (season 2026, game-week 4). */
public object Ligue1Samples {
    public const val DAY: String = "2026-09-12"
    public const val LIVE_MATCH_ID: String = "l1_championship_match_73855" // Rennes–OM, secondHalf 1-0
    public const val FINAL_MATCH_ID: String = "l1_championship_match_73845" // PSG–Monaco 1-2, post-processed
    public const val PRE_MATCH_ID: String = "l1_championship_match_74174" // Ligue 2, preMatch
    public const val CLUB_ID: String = "l1_championship_club_2026_13" // PSG
    public const val PLAYER_ID: String = "l1_championship_player_2026_13_77349" // Chevalier
    /** Wall clock at which the live sample was captured (52' of the second half). */
    public const val LIVE_NOW: String = "2026-09-11T19:54:40Z"

    public val paths: Map<String, String> = mapOf(
        "/championships-daily-calendars/matches?timezone=Europe/Paris&daysLimit=1&lookAfter=true&fromDate=$DAY" to "championships-daily-calendars-matches.json",
        "/championship-match/$LIVE_MATCH_ID" to "championship-match.live.json",
        "/championship-match/$FINAL_MATCH_ID" to "championship-match.final.json",
        "/championship-match/$PRE_MATCH_ID" to "championship-match.pre.json",
        "/championship-standings/1/general" to "championship-standings.json",
        "/championship-club/$CLUB_ID/identity" to "championship-club-identity.json",
        "/championship-club-summary/$CLUB_ID" to "championship-club-summary.json",
        "/championship-player/$PLAYER_ID" to "championship-player.json",
    )

    public fun register(fetcher: SampleFetcher, root: File = SampleFetcher.repoRoot()): SampleFetcher =
        fetcher.routes(Ligue1Provider.DEFAULT_BASE_URL, SampleFetcher.samplesDir("football", "ligue-1", root), paths)
}
