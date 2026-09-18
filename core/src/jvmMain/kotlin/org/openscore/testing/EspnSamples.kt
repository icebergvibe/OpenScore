package org.openscore.testing

import org.openscore.providers.espn.EspnRosters
import java.io.File

/** URL → sample routing for the ESPN roster samples captured on 2026-09-16 (season 2026/27). */
public object EspnSamples {
    public const val SEASON: Int = 2026
    public const val BAYERN: String = "132"
    public const val STURM_GRAZ: String = "3746"

    public val paths: Map<String, String> = mapOf(
        "/apis/common/v3/sports/soccer/${EspnRosters.BUNDESLIGA_SLUG}/teams/$BAYERN/roster?season=$SEASON" to "roster.bayern.json",
        "/apis/common/v3/sports/soccer/${EspnRosters.CHAMPIONS_LEAGUE_SLUG}/teams/$BAYERN/roster?season=$SEASON" to "roster.bayern.json",
        "/apis/common/v3/sports/soccer/${EspnRosters.EUROPA_LEAGUE_SLUG}/teams/$STURM_GRAZ/roster?season=$SEASON" to "roster.sturm-graz.json",
    )

    public fun register(fetcher: SampleFetcher, root: File = SampleFetcher.repoRoot()): SampleFetcher {
        fetcher.routes(EspnRosters.DEFAULT_BASE_URL, SampleFetcher.samplesDir("football", "espn", root), paths)
        return fetcher
    }
}
