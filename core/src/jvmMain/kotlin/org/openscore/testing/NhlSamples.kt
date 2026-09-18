package org.openscore.testing

import org.openscore.providers.nhl.NhlProvider
import java.io.File

/** URL → sample routing for the NHL samples captured on 2026-09-11. */
public object NhlSamples {
    public const val OPENING_NIGHT: String = "2026-09-29"
    public const val FINAL_DATE: String = "2026-06-14"
    public const val NO_GAMES_DATE: String = "2026-09-11"
    public const val PRE_GAME_ID: String = "2026020001"
    public const val FINAL_GAME_ID: String = "2025030416"
    public const val SHOOTOUT_GAME_ID: String = "2025020444"

    public val paths: Map<String, String> = mapOf(
        "/score/$OPENING_NIGHT" to "score.json",
        "/score/$FINAL_DATE" to "score.final.json",
        "/score/$NO_GAMES_DATE" to "score.no-games.json",
        "/gamecenter/$PRE_GAME_ID/play-by-play" to "gamecenter-play-by-play.pre.json",
        "/gamecenter/$FINAL_GAME_ID/play-by-play" to "gamecenter-play-by-play.final.json",
        "/gamecenter/$SHOOTOUT_GAME_ID/play-by-play" to "gamecenter-play-by-play.final-shootout.json",
        "/gamecenter/$PRE_GAME_ID/boxscore" to "gamecenter-boxscore.pre.json",
        "/gamecenter/$FINAL_GAME_ID/boxscore" to "gamecenter-boxscore.final.json",
        "/standings/now" to "standings.json",
        "/standings-season" to "standings-season.json",
        "/standings/2026-04-17" to "standings.json",
        "/roster/TOR/current" to "roster-current.json",
        "/player/8478403/landing" to "player-landing.json",
    )

    public fun register(fetcher: SampleFetcher, root: File = SampleFetcher.repoRoot()): SampleFetcher =
        fetcher.routes(NhlProvider.DEFAULT_BASE_URL, SampleFetcher.samplesDir("hockey", "nhl", root), paths)
}
