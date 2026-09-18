package org.openscore.testing

import org.openscore.providers.bundesliga.BundesligaProvider
import java.io.File

/** URL → sample routing for the Bundesliga samples captured on 2026-09-11 (Matchday 3, Union–Schalke). */
public object BundesligaSamples {
    public const val SEASON: String = "DFL-SEA-0001KA"
    public const val MATCHDAY: String = "DFL-DAY-004CBV"
    public const val MATCH_ID: String = "DFL-MAT-J043GZ"
    public const val DAY: String = "2026-09-11"
    public const val CLUB_ID: String = "DFL-CLU-000010" // Augsburg

    private const val S = "${BundesligaProvider.DEFAULT_BASE_URL}/all/${BundesligaProvider.BUNDESLIGA}/seasons/$SEASON"

    /** Routes one life-cycle state (`pre`, `live`, `halftime`, `live2`, `final`) of the match. */
    public fun register(fetcher: SampleFetcher, state: String = "live2", root: File = SampleFetcher.repoRoot()): SampleFetcher {
        val dir = SampleFetcher.samplesDir("football", "bundesliga", root)
        fetcher.route(BundesligaProvider.DEFAULT_CONFIG_URL, File(dir, "config.json"))
        fetcher.route("$S/matches.json", File(dir, "matches.json"))
        fetcher.route("$S/matches.json?orderBy=%22matchday%22&equalTo=3", File(dir, "matches-matchday.$state.json").takeIf { it.isFile } ?: File(dir, "matches-matchday.json"))
        fetcher.route("$S/matches/$MATCH_ID.json", File(dir, "match-basic.$state.json"))
        fetcher.route("${BundesligaProvider.DEFAULT_BASE_URL}/en/${BundesligaProvider.BUNDESLIGA}/seasons/$SEASON/matchdays/$MATCHDAY/$MATCH_ID.json", File(dir, "match.$state.json"))
        fetcher.route("$S/matchdays/$MATCHDAY/$MATCH_ID/stats.json", File(dir, "match-stats.$state.json"))
        fetcher.route("$S/matchdays/$MATCHDAY/$MATCH_ID/lineup.json", File(dir, "match-lineup.$state.json"))
        fetcher.route("$S/liveTable.json", File(dir, "liveTable.json"))
        fetcher.nullRoute("$S/matches/DFL-MAT-000000.json") // Firebase: missing node → 200 "null"
        return fetcher
    }
}
