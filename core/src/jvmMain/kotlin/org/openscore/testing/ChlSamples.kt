package org.openscore.testing

import org.openscore.providers.chl.ChlProvider
import java.io.File

/** URL → sample routing for the CHL samples captured on 2026-09-11 (season 2026/27). */
public object ChlSamples {
    public const val GAME_DATE: String = "2026-09-10"
    public const val PRE_DATE: String = "2026-09-11"
    public const val PRE_GAME_ID: String = "282628c3708fc7d1ce2d537d"
    public const val FINAL_GAME_ID: String = "4f36a358a795d91b2fbb0c9b"
    public const val OT_GAME_ID: String = "2598d33681bd2c7bf1310768"
    public const val SHOOTOUT_GAME_ID: String = "a0dedd257aff92689a14b8d3"
    public const val TEAM_ID: String = "c9840a203093f97cae0a9495" // KooKoo
    public const val PLAYER_ID: String = "c0add44e7a2094c45b3e5fe9"
    private const val C = ChlProvider.COMPETITION
    private const val S = ChlProvider.CURRENT_SEASON
    private const val S_PREV = "3c5f99fa605394cc65733fc9"

    public val paths: Map<String, String> = mapOf(
        "/live?q=live-events.json" to "live-events.json",
        "?q=schedule-$C-$S.json" to "schedule.json",
        "/live?q=live-event-$PRE_GAME_ID-scoreboard.json" to "live-event-scoreboard.pre.json",
        "/live?q=live-event-$FINAL_GAME_ID-scoreboard.json" to "live-event-scoreboard.final.json",
        "/live?q=live-event-$OT_GAME_ID-scoreboard.json" to "live-event-scoreboard.final-overtime.json",
        "/live?q=live-event-$SHOOTOUT_GAME_ID-scoreboard.json" to "live-event-scoreboard.final-shootout.json",
        "/live?q=live-event-$PRE_GAME_ID-lineups.json" to "live-event-lineups.pre.json",
        "/live?q=live-event-$FINAL_GAME_ID-lineups.json" to "live-event-lineups.final.json",
        "/live?q=standings-groups-$C-$S.json" to "standings-groups.json",
        "?q=teams-$C-$S.json" to "teams.json",
        "?q=team-players-info-$C-$S-$TEAM_ID.json" to "team-players-info.json",
        "?q=player-$C-$PLAYER_ID.json" to "player.json",
    )

    public fun register(fetcher: SampleFetcher, root: File = SampleFetcher.repoRoot()): SampleFetcher {
        fetcher.routes(ChlProvider.DEFAULT_BASE_URL, SampleFetcher.samplesDir("hockey", "chl", root), paths)
        // Missing files are S3 XML 403s.
        fetcher.route(ChlProvider.DEFAULT_BASE_URL + "/live?q=live-event-nope-scoreboard.json", File(SampleFetcher.samplesDir("hockey", "chl", root), "live-events.json"), status = 403, contentType = "application/xml")
        return fetcher
    }
}
