package org.openscore.testing

import org.openscore.providers.fogis.FogisProvider
import java.io.File

/** URL → sample routing for the Fogis livescore XML samples captured on 2026-09-12. */
public object FogisSamples {
    public const val DAY: String = "2026-09-12"
    public const val PRE_ID: String = "6529989" // AIK–Västerås SK
    public const val FINAL_ID: String = "6536131" // GIF Sundsvall 0–3 Örebro
    public const val SHOOTOUT_ID: String = "6827326" // Athletic Eskilstuna–IK Oddevold, 4–4, 6–7 pens
    /** overview.live.xml: 49 games at 12:38Z on 2026-09-13, nine in play. */
    public const val LIVE_DAY: String = "2026-09-13"
    public const val LIVE_ID: String = "6529991" // Hammarby–IF Brommapojkarna, 1–1 in the 29th minute
    public const val ALLSVENSKAN_2026: String = "133348"
    public const val SUPERETTAN_2026: String = "133340"
    public const val CUP_MEN_R1_2: String = "137810"

    public fun register(fetcher: SampleFetcher, root: File = SampleFetcher.repoRoot()): SampleFetcher {
        val dir = SampleFetcher.samplesDir("football", "fogis-livescore", root)
        val base = FogisProvider.DEFAULT_BASE_URL
        fun r(file: String, sample: String) = fetcher.route(base + file, File(dir, sample), contentType = "application/xml; charset=utf-8")
        r("overview-1-20260912.xml", "overview.today.xml")
        r("overview-1-20260913.xml", "overview.live.xml")
        r("tournaments-1.xml", "tournaments.xml")
        r("overview-1-20251201.xml", "overview.past-403.xml")
        r("overview-1-20261231.xml", "overview.future-empty.xml")
        r("game-info-$PRE_ID.xml", "game-info.pre.xml")
        r("lineup-$PRE_ID.xml", "lineup.pre.xml")
        r("game-info-$FINAL_ID.xml", "game-info.final.xml")
        r("lineup-$FINAL_ID.xml", "lineup.final.xml")
        r("game-info-$SHOOTOUT_ID.xml", "game-info.final.cup-shootout.xml")
        r("game-info-1.xml", "game-info.unknown-id.xml")
        return fetcher
    }
}
