package org.openscore.testing

import java.io.File
import org.openscore.providers.sportality.HockeyAllsvenskanProvider

public object HockeyAllsvenskanSamples {
    public const val PRE_GAME_ID: String = "20260918-aik-modo"
    public const val FINAL_GAME_ID: String = "20250919-ssk-bik"
    /** AIK v MoDo in the first period on 2026-09-18, the game the lineup page was captured for. */
    public const val LIVE_GAME_ID: String = PRE_GAME_ID
    public const val PAGE: String = "${HockeyAllsvenskanProvider.DEFAULT_BASE_URL}/pages/matcher?_rsc=openscore"
    public const val VIEW: String = "${HockeyAllsvenskanProvider.DEFAULT_BASE_URL}/games/$LIVE_GAME_ID/view?_rsc=openscore"

    public fun register(fetcher: SampleFetcher, root: File = SampleFetcher.repoRoot()): SampleFetcher {
        val dir = SampleFetcher.samplesDir("hockey", "hockeyallsvenskan", root)
        return fetcher
            .route("${HockeyAllsvenskanProvider.DEFAULT_BASE_URL}/pages/matcher?_rsc=openscore", File(dir, "matcher.rsc.txt"), "text/x-component")
            .route("${HockeyAllsvenskanProvider.DEFAULT_BASE_URL}/api/game?slug=$FINAL_GAME_ID", File(dir, "game.new.final.json"))
            .route("${HockeyAllsvenskanProvider.DEFAULT_BASE_URL}/api/game?slug=$LIVE_GAME_ID", File(dir, "game.new.live.json"))
            .route("${HockeyAllsvenskanProvider.DEFAULT_BASE_URL}/games/$LIVE_GAME_ID/view?_rsc=openscore", File(dir, "game-view.lineups.rsc.txt"), "text/x-component")
    }
}
