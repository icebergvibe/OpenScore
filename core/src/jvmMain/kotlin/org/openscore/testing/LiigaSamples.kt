package org.openscore.testing

import org.openscore.providers.liiga.LiigaProvider
import java.io.File

/** URL → sample routing for the Liiga samples captured on 2026-09-11 (season 2027). */
public object LiigaSamples {
    public const val GAME_DATE: String = "2026-09-01"
    public const val EMPTY_DATE: String = "2026-09-11"
    public const val PRE_GAME_ID: String = "2701298"
    public const val FINAL_GAME_ID: String = "2701274"
    public const val SHOOTOUT_GAME_ID: String = "2701280"
    public const val TEAM_ID: String = "495643563" // Kärpät
    public const val ROSTER_TEAM_ID: String = "238306801" // Jokerit
    public const val PLAYER_ID: String = "60860218"

    public val paths: Map<String, String> = mapOf(
        "/games?tournament=all&date=$GAME_DATE" to "games-by-date.json",
        "/games?tournament=all&date=$EMPTY_DATE" to "games-by-date.empty.json",
        "/games/2027/$PRE_GAME_ID" to "game.pre.json",
        "/games/2027/$FINAL_GAME_ID" to "game.final.json",
        "/games/2027/$SHOOTOUT_GAME_ID" to "game.final-shootout.json",
        "/shotmap/2027/$PRE_GAME_ID" to "shotmap.pre.json",
        "/shotmap/2027/$FINAL_GAME_ID" to "shotmap.final.json",
        "/shotmap/2027/$SHOOTOUT_GAME_ID" to "shotmap.final-shootout.json",
        "/standings?season=2027" to "standings.json",
        "/standings?season=2026" to "standings.playoffs.json",
        "/teams/info" to "teams-info.json",
        "/players/info?tournament=runkosarja&fromSeason=2027&toSeason=2027" to "players-info.json",
        "/players/info/$PLAYER_ID" to "player-info.json",
    )

    public fun register(fetcher: SampleFetcher, root: File = SampleFetcher.repoRoot()): SampleFetcher =
        fetcher.routes(LiigaProvider.DEFAULT_BASE_URL, SampleFetcher.samplesDir("hockey", "liiga", root), paths)
}
