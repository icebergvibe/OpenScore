package org.openscore.testing

import org.openscore.providers.ssl.SslProvider
import java.io.File

/**
 * URL → sample routing for the SSL Herr samples captured on 2026-09-21: the 2026/27 season
 * (`ssgtUuid` `qqing3frfp`), the rolling scoreboard for 2026-09-20 to 2026-09-26, and the
 * 10–6 final `msmb27in1v` with its post-game totals.
 */
public object SslSamples {
    /** In the scoreboard's window: one SSL Herr final, and one SSL Dam game that must be filtered out. */
    public const val FINAL_DAY: String = "2026-09-20"
    /** In the window, both games still to come. */
    public const val SCHEDULED_DAY: String = "2026-09-22"
    /** Outside the window: answered from the season schedule instead. */
    public const val SCHEDULE_ONLY_DAY: String = "2026-09-19"
    public const val FINAL_GAME_ID: String = "msmb27in1v"
    public const val SCHEDULED_GAME_ID: String = "l3vlqyge1x"
    public const val SEASON_ID: String = "qqing3frfp"
    /** Mullsjö AIS, the home side of the final. */
    public const val TEAM_ID: String = "2cdf-32c4bo2vU"
    public const val AWAY_TEAM_ID: String = "c699-cd81gmNJ2"
    public const val PLAYER_ID: String = "ihdjejtzeo"

    public val paths: Map<String, String> = mapOf(
        "/sports-v2/season-series-game-types-filter" to "season-series-game-types-filter.json",
        "/gameday/gameheader" to "gameheader.json",
        "/sports-v2/game-schedule?seasonUuid=vt4kt77vxk&seriesUuid=qRl-8B5kOFjKL&gameTypeUuid=qQ9-af37Ti40B&gamePlace=all&played=all" to "game-schedule.json",
        "/sports-v2/game-info/$FINAL_GAME_ID" to "game-info.final.json",
        "/sports-v2/game-info/$SCHEDULED_GAME_ID" to "game-info.pre.json",
        "/statistics-v2/league-standings?ssgtUuid=$SEASON_ID" to "league-standings.json",
        "/sports-v2/all-teams/$SEASON_ID" to "all-teams.json",
        "/sports-v2/teams/$TEAM_ID" to "team.json",
        "/sports-v2/athletes/by-team-uuid/$TEAM_ID" to "athletes-by-team.json",
        "/statistics-v2/athlete/profile-page?playerUuid=$PLAYER_ID&masterSiteInstanceId=" to "athlete-profile-page.json",
        "/gameday/post-game-data/promo-bar-stats/$FINAL_GAME_ID/$TEAM_ID/$AWAY_TEAM_ID?ssgtUuid=$SEASON_ID" to "promo-bar-stats.final.json",
    )

    public fun register(fetcher: SampleFetcher, root: File = SampleFetcher.repoRoot()): SampleFetcher =
        fetcher.routes(SslProvider.DEFAULT_BASE_URL, SampleFetcher.samplesDir("floorball", "ssl", root), paths)
}
