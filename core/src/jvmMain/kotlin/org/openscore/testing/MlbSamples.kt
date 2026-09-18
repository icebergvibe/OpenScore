package org.openscore.testing

import org.openscore.providers.mlb.MlbProvider
import java.io.File

/** URL → sample routing for the MLB samples captured on 2026-09-11/12. */
public object MlbSamples {
    public const val FINAL_DATE: String = "2026-09-10"
    public const val PRE_DATE: String = "2026-09-11"
    public const val PRE_GAME_DATE: String = "2026-09-12"
    public const val NO_GAMES_DATE: String = "2026-12-25"
    public const val POSTPONED_DATE: String = "2026-04-03"
    public const val DOUBLEHEADER_DATE: String = "2026-04-04"

    /** TEX @ SEA 2026-09-10, final 4–3 in nine. Also the game behind [LIVE_GAME_ID] when [registerLive] is used. */
    public const val FINAL_GAME_ID: String = "823088"
    /** NYM @ NYY 2026-09-11, `Scheduled` ~10 h before first pitch. */
    public const val SCHEDULED_GAME_ID: String = "823498"
    /** COL @ DET 2026-09-12, `Pre-Game` ~80 min before first pitch. */
    public const val PRE_GAME_ID: String = "824224"
    /** [FINAL_GAME_ID] replayed with `timecode=20260910_220000`: bottom 7, runner on second. */
    public const val LIVE_GAME_ID: String = FINAL_GAME_ID

    public const val TEAM_ID: String = "147"
    public const val PLAYER_ID: String = "592450"

    private const val SCHEDULE = "/v1/schedule?sportId=1&date="
    private const val HYDRATE = "&hydrate=" + MlbProvider.SCHEDULE_HYDRATE

    public val paths: Map<String, String> = mapOf(
        "$SCHEDULE$FINAL_DATE$HYDRATE" to "schedule.hydrated.json",
        "$SCHEDULE$PRE_DATE$HYDRATE" to "schedule.pre.json",
        "$SCHEDULE$PRE_GAME_DATE$HYDRATE" to "schedule.hydrated.pre-game.json",
        "$SCHEDULE$NO_GAMES_DATE$HYDRATE" to "schedule.no-games.json",
        "$SCHEDULE$POSTPONED_DATE$HYDRATE" to "schedule.postponed.json",
        "$SCHEDULE$DOUBLEHEADER_DATE$HYDRATE" to "schedule.doubleheader.json",
        "/v1.1/game/$FINAL_GAME_ID/feed/live" to "feed-live.final.json",
        "/v1.1/game/$SCHEDULED_GAME_ID/feed/live" to "feed-live.pre.json",
        "/v1.1/game/$PRE_GAME_ID/feed/live" to "feed-live.pre-game.json",
        "/v1/game/$FINAL_GAME_ID/boxscore" to "boxscore.final.json",
        "/v1/game/$SCHEDULED_GAME_ID/boxscore" to "boxscore.pre.json",
        "/v1/standings?leagueId=103,104&hydrate=team,division" to "standings.hydrated.json",
        "/v1/standings?leagueId=103,104&hydrate=team,division&season=2026" to "standings.hydrated.json",
        "/v1/teams/$TEAM_ID" to "team.json",
        "/v1/schedule?sportId=1&teamId=$TEAM_ID&startDate=2026-01-01&endDate=2026-12-31$HYDRATE" to "schedule.team-season.json",
        "/v1/teams/$TEAM_ID/stats?stats=season&group=hitting,pitching&season=2026" to "team-stats.season.json",
        "/v1/teams/$TEAM_ID/roster" to "roster.active.json",
        "/v1/people/$PLAYER_ID?hydrate=currentTeam" to "people.json",
    )

    public fun register(fetcher: SampleFetcher, root: File = SampleFetcher.repoRoot()): SampleFetcher =
        fetcher.routes(MlbProvider.DEFAULT_BASE_URL, SampleFetcher.samplesDir("baseball", "mlb", root), paths)

    /** Same routes, but [LIVE_GAME_ID] serves the mid-game replay instead of the final feed. */
    public fun registerLive(fetcher: SampleFetcher, root: File = SampleFetcher.repoRoot()): SampleFetcher =
        fetcher.routes(
            MlbProvider.DEFAULT_BASE_URL,
            SampleFetcher.samplesDir("baseball", "mlb", root),
            paths + ("/v1.1/game/$LIVE_GAME_ID/feed/live" to "feed-live.live.json"),
        )
}
