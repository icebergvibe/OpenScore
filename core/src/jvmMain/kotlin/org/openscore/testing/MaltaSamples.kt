package org.openscore.testing

import org.openscore.providers.malta.MaltaProvider
import java.io.File

/** URL → sample routing for the Malta Premier samples captured on 2026-09-11 (2026/27, matchday 4 played). */
public object MaltaSamples {
    public const val PAST_DAY: String = "2026-09-06"
    public const val NEXT_DAY: String = "2026-09-12"
    public const val FINAL_ID: String = "53142564" // Zabbar St.Patrick 1–2 Hamrun Spartans
    public const val PRE_ID: String = "53142579"
    public const val TEAM_ID: String = "39639" // Hamrun Spartans
    public const val PLAYER_ID: String = "162467" // Trotta
    private const val C = MaltaProvider.MALTA_PREMIER

    public fun register(fetcher: SampleFetcher, root: File = SampleFetcher.repoRoot()): SampleFetcher {
        val dir = SampleFetcher.samplesDir("football", "malta-premier", root)
        val base = MaltaProvider.DEFAULT_BASE_URL
        fetcher.routes(base, dir, mapOf(
            "/competitions/$C/pastMatches?date=$PAST_DAY&pageSize=50" to "pastMatches.date.json",
            "/competitions/$C/upcomingMatches?date=$NEXT_DAY&pageSize=50" to "upcomingMatches.date.json",
            "/matches/$FINAL_ID" to "match.final.json",
            "/matches/$FINAL_ID/result" to "result.final.json",
            "/matches/$FINAL_ID/lineup" to "lineup.final.json",
            "/matches/53142546/result" to "result.final.json",
            "/matches/$PRE_ID" to "match.pre.json",
            "/matches/$PRE_ID/result" to "result.pre.json",
            "/matches/$PRE_ID/lineup" to "lineup.pre.json",
            "/competitions/$C/standings" to "standings.json",
            "/competitions/$C/standings?season=2026" to "standings.season-2026.json",
            "/competitions/$C/teams" to "teams.json",
            "/teams/$TEAM_ID/players?competitionTypeId=$C" to "team-players.json",
            "/players/$PLAYER_ID/GetPlayerDetails" to "player.json",
        ))
        // Empty lists for the other half of each day query.
        fetcher.route("$base/competitions/$C/upcomingMatches?date=$PAST_DAY&pageSize=50", File(dir, "matches.live-none.json"))
        fetcher.route("$base/competitions/$C/pastMatches?date=$NEXT_DAY&pageSize=50", File(dir, "matches.live-none.json"))
        return fetcher
    }
}
