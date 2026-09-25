package org.openscore.testing

import org.openscore.providers.khl.KhlProvider
import java.io.File

/** URL → sample routing for the KHL samples captured on 2026-09-11 (stage 407 = Regular 2026/27). */
public object KhlSamples {
    public const val GAME_DATE: String = "2026-09-11" // Moscow day 1789074000–1789160400
    public const val PRE_GAME_ID: String = "3000171"
    public const val FINAL_GAME_ID: String = "3000051"
    public const val OT_GAME_ID: String = "2786578"
    public const val SHOOTOUT_GAME_ID: String = "2786714"
    /** The 2026-09-13 capture: Sibir 2-5 Avangard, recorded face-off to final. */
    public const val LIVE_GAME_ID: String = "3000195"
    public const val TEAM_ID: String = "40" // Ak Bars
    public const val PREVIOUS_STAGE: String = "370"

    public val paths: Map<String, String> = mapOf(
        "/events_v2.json?locale=en&order_direction=asc&q[start_at_gt_time_from_unixtime]=1789074000&q[start_at_lt_time_from_unixtime]=1789160400" to "events_v2.date-range.json",
        "/event_v2.json?id=$PRE_GAME_ID&locale=en" to "event_v2.pre.json",
        "/event_v2.json?id=$FINAL_GAME_ID&locale=en" to "event_v2.final.json",
        "/event_v2.json?id=$OT_GAME_ID&locale=en" to "event_v2.final-overtime.json",
        "/event_v2.json?id=$SHOOTOUT_GAME_ID&locale=en" to "event_v2.final-shootout.json",
        "/event_v2.json?id=$LIVE_GAME_ID&locale=en" to "event_v2.live.json",
        "/event_v2.json?id=1&locale=en" to "event_v2.not-found.json",
        "/data.json" to "data.json",
        "/tables_v2.json?locale=en&stage_id=407" to "tables_v2.json",
        "/tables_v2.json?locale=en&stage_id=$PREVIOUS_STAGE" to "tables_v2.json",
        "/teams_v2.json?locale=en" to "teams_v2.json",
    )

    public fun register(fetcher: SampleFetcher, root: File = SampleFetcher.repoRoot()): SampleFetcher =
        fetcher.routes(KhlProvider.DEFAULT_BASE_URL, SampleFetcher.samplesDir("hockey", "khl", root), paths)
}
