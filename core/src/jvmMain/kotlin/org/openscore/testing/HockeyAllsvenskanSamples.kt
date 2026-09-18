package org.openscore.testing

import java.io.File
import org.openscore.providers.sportality.HockeyAllsvenskanProvider

public object HockeyAllsvenskanSamples {
    public const val PRE_GAME_ID: String = "20260918-aik-modo"
    public const val FINAL_GAME_ID: String = "20250919-ssk-bik"

    public fun register(fetcher: SampleFetcher, root: File = SampleFetcher.repoRoot()): SampleFetcher {
        val dir = SampleFetcher.samplesDir("hockey", "hockeyallsvenskan", root)
        return fetcher
            .route("${HockeyAllsvenskanProvider.DEFAULT_BASE_URL}/pages/matcher?_rsc=openscore", File(dir, "matcher.rsc.txt"), "text/x-component")
            .route("${HockeyAllsvenskanProvider.DEFAULT_BASE_URL}/api/game?slug=$FINAL_GAME_ID", File(dir, "game.new.final.json"))
    }
}
