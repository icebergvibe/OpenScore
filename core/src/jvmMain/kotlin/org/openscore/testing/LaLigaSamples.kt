package org.openscore.testing

import org.openscore.providers.laliga.LaLigaProvider
import java.io.File

/** URL → sample routing for the LaLiga samples captured on 2026-09-12 (laliga-easports-2026, matchday 5). */
public object LaLigaSamples {
    public const val SUBSCRIPTION: String = "laliga-easports-2026"
    public const val DAY: String = "2026-09-12"
    public const val FINAL_SLUG: String = "temporada-2026-2027-laliga-ea-sports-sevilla-fc-valencia-cf-5"
    public const val FINAL_ID: Int = 102297
    public const val FINAL_OPTA: String = "g2650810"
    public const val PRE_SLUG: String = "temporada-2026-2027-laliga-ea-sports-real-madrid-rayo-vallecano-5"
    public const val PRE_ID: Int = 102295
    public const val TEAM_SLUG: String = "real-madrid"
    public const val PLAYER_SLUG: String = "jude-bellingham"

    public fun register(fetcher: SampleFetcher, root: File = SampleFetcher.repoRoot()): SampleFetcher {
        val dir = SampleFetcher.samplesDir("football", "la-liga", root)
        val pub = LaLigaProvider.DEFAULT_PUBLIC_URL
        val wv = LaLigaProvider.DEFAULT_WEBVIEW_URL
        fetcher.routes(pub, dir, mapOf(
            "/api/v1/subscriptions?competitionSlug=primera-division" to "subscriptions.json",
            "/api/v1/calendar?startDate=$DAY&endDate=$DAY&competitionSlug=primera-division" to "calendar.json",
            "/api/v1/matches?subscriptionSlug=$SUBSCRIPTION&week=5&limit=100&orderField=date&orderType=asc" to "matches-week.pre.json",
            "/api/v1/subscriptions/$SUBSCRIPTION/standing" to "subscription-standing.json",
            "/api/v1/teams/$TEAM_SLUG" to "team.json",
            "/api/v1/teams/$TEAM_SLUG/squad?seasonYear=2026&limit=50" to "team-squad.json",
            "/api/v1/players/$PLAYER_SLUG" to "player.json",
        ))
        fetcher.routes(wv, dir, mapOf(
            "/api/web/matches/$FINAL_SLUG" to "wv-match.final.json",
            "/api/web/matches/$FINAL_ID/events" to "wv-match-events.final.json",
            "/api/web/matches/opta/$FINAL_OPTA/stats" to "wv-match-stats.final.json",
            "/api/web/matches/$FINAL_ID/lineups" to "wv-match-lineups.final.json",
            "/api/web/matches/$PRE_SLUG" to "wv-match.pre.json",
            "/api/web/matches/$PRE_ID/events" to "wv-match-events.pre.json",
            "/api/web/matches/$PRE_ID/lineups" to "wv-match-lineups.pre.json",
        ))
        fetcher.route("$wv/api/web/matches/opta/g2650808/stats", File(dir, "wv-match-stats.pre.json"))
        return fetcher
    }
}
