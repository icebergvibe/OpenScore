package org.openscore.testing

import org.openscore.providers.sportality.ShlProvider
import java.io.File

/** URL → sample routing for the SHL Sportality samples. */
public class SportalitySamples(
    public val baseUrl: String,
    public val sampleDir: String,
    public val filterSeries: String,
    public val ssgtCurrent: String,
    public val ssgtPrevious: String,
    public val seasonUuid: String,
    public val seriesUuid: String,
    public val gameTypeUuid: String,
    public val preGameId: String,
    /** A game captured under way (2026-09-19, second period): `game-info`, `game-overview` and `play-by-play` in their live shapes. */
    public val liveGameId: String,
    public val finalGameId: String,
    public val shootoutGameId: String,
    public val teamId: String,
    public val playerId: String,
    /** A date inside the captured `gameheader` window with league games. */
    public val headerDate: String,
) {
    public val paths: Map<String, String> = mapOf(
        "/sports-v2/season-series-game-types-filter?series=$filterSeries" to "season-series-game-types-filter.json",
        "/gameday/gameheader" to "gameheader.json",
        "/sports-v2/all-teams/$ssgtCurrent" to "all-teams.json",
        "/sports-v2/game-schedule?seasonUuid=$seasonUuid&seriesUuid=$seriesUuid&gameTypeUuid=$gameTypeUuid&gamePlace=all&played=all" to "game-schedule.json",
        "/sports-v2/game-info/$preGameId" to "game-info.pre.json",
        "/sports-v2/game-info/$liveGameId" to "game-info.live.json",
        "/gameday/game-overview/$liveGameId" to "game-overview.live.json",
        "/gameday/play-by-play/$liveGameId" to "play-by-play.live.json",
        "/sports-v2/game-info/$finalGameId" to "game-info.final.json",
        "/gameday/game-overview/$finalGameId" to "game-overview.final.json",
        "/gameday/play-by-play/$finalGameId" to "play-by-play.final.json",
        "/gameday/play-by-play/$shootoutGameId" to "play-by-play.final-shootout.json",
        "/gameday/boxscore/$finalGameId" to "boxscore.final.json",
        "/statistics-v2/league-standings?ssgtUuid=$ssgtCurrent" to "league-standings.empty.json",
        "/statistics-v2/league-standings?ssgtUuid=$ssgtPrevious" to "league-standings.json",
        "/sports-v2/athletes/by-team-uuid/$teamId" to "athletes-by-team.json",
        "/statistics-v2/athlete/profile-page?playerUuid=$playerId&masterSiteInstanceId=" to "athlete-profile-page.json",
    )

    public fun register(fetcher: SampleFetcher, root: File = SampleFetcher.repoRoot()): SampleFetcher {
        val dir = SampleFetcher.samplesDir("hockey", sampleDir, root)
        fetcher.routes(baseUrl, dir, paths)
        // Only SHL captured the "unknown id → 200 with empty fields" shape; elsewhere an unrouted path 404s, which also maps to not-found.
        File(dir, "game-info.not-found.json").takeIf { it.isFile }?.let { fetcher.route("$baseUrl/sports-v2/game-info/does-not-exist", it) }
        for (path in listOf("/gameday/game-overview/$preGameId", "/gameday/play-by-play/$preGameId", "/gameday/boxscore/$preGameId")) {
            fetcher.emptyRoute(baseUrl + path)
        }
        return fetcher
    }

    public companion object {
        public val SHL: SportalitySamples = SportalitySamples(
            baseUrl = ShlProvider.DEFAULT_BASE_URL, sampleDir = "shl", filterSeries = "shl",
            ssgtCurrent = "qa98unlbd6", ssgtPrevious = "iuzqg7dqk9",
            seasonUuid = "ndcf81nlb3", seriesUuid = "qQ9-bb0bzEWUk", gameTypeUuid = "qQ9-af37Ti40B",
            preGameId = "p2qoh7wot5", liveGameId = "d0ucyo36vb", finalGameId = "bdhvuc5tex", shootoutGameId = "zfvdpfi2hk",
            teamId = "50e6-50e6DYeWM", playerId = "acnem5beey", headerDate = "2026-09-19",
        )
    }
}
