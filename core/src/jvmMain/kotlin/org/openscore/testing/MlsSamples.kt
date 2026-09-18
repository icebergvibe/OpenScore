package org.openscore.testing

import org.openscore.providers.mls.MlsProvider
import java.io.File

/** URL routes for the MLS samples captured on 2026-09-14. */
public object MlsSamples {
    public const val SEASON: String = "MLS-SEA-0001KA"
    public const val DAY: String = "2026-09-20"
    public const val FINAL_MATCH_ID: String = "MLS-MAT-0009LH"
    public const val PRE_MATCH_ID: String = "MLS-MAT-0009LX"
    public const val TEAM_ID: String = "MLS-CLU-000065"

    public fun register(fetcher: SampleFetcher, root: File = SampleFetcher.repoRoot()): SampleFetcher {
        val dir = SampleFetcher.samplesDir("football", "mls", root)
        fetcher.routes(MlsProvider.DEFAULT_STATS_URL, dir, mapOf(
            "/competitions/${MlsProvider.COMPETITION_ID}/seasons" to "seasons.json",
            "/matches/seasons/$SEASON?competition_id=${MlsProvider.COMPETITION_ID}&match_date=$DAY&per_page=1000" to "matches.day.pre.json",
            "/matches/$FINAL_MATCH_ID" to "match.final.json",
            "/matches/$FINAL_MATCH_ID/key_events?per_page=1000" to "match-events.final.json",
            "/matches/$FINAL_MATCH_ID/key_events?per_page=1000&page_token=eyJQayI6Im1hdGNoRXZlbnRFeHRlbmRlZCNldmVudCMxMjQzNzAwMDAxMjE0IiwiU2siOiJldmVudCMxMjQzNzAwMDAxMjE0IiwiR1MyUGsiOiJtYXRjaEtleUV2ZW50I21hdGNoI01MUy1NQVQtMDAwOUxIIiwiR1MyU2siOiJ0aW1lIzIwMjYtMDktMTRUMDI6NTY6MTJaIn0%3D" to "match-events.final.page-2.json",
            "/statistics/clubs/matches/$FINAL_MATCH_ID" to "match-stats.final.json",
            "/competitions/${MlsProvider.COMPETITION_ID}/seasons/$SEASON/standings" to "standings.2026.json",
            "/players/seasons/$SEASON/clubs/$TEAM_ID?per_page=100" to "roster.san-diego.json",
            "/clubs/$TEAM_ID" to "club.san-diego.json",
            "/matches/seasons/$SEASON?match_date%5Bgte%5D=2026-01-01&match_date%5Blte%5D=2026-12-31&team_id=$TEAM_ID&per_page=200&sort=match_date" to "matches.club.json",
            // The API filters the window itself; the replay serves the whole season and lets the provider's own filter show.
            "/matches/seasons/$SEASON?match_date%5Bgte%5D=2026-09-01&match_date%5Blte%5D=2026-09-30&team_id=$TEAM_ID&per_page=200&sort=match_date" to "matches.club.json",
        ))
        fetcher.routes(MlsProvider.DEFAULT_METADATA_URL, dir, mapOf(
            "/api/matches/$PRE_MATCH_ID" to "match-metadata.pre.json",
            "/api/matches/$FINAL_MATCH_ID" to "match-metadata.final.json",
        ))
        return fetcher
    }
}
