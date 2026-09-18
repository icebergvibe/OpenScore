package org.openscore.testing

import org.openscore.providers.ufc.UfcProvider
import java.io.File

/**
 * URL → sample routing for the UFC samples captured on 2026-09-18. The routed ids form a dense
 * run [FIRST_EVENT_ID]..[LAST_EVENT_ID] followed by empties, so a provider seeded inside it can
 * locate the frontier the way it does live.
 */
public object UfcSamples {
    public const val UPCOMING_EVENT_ID: Int = 1335
    public const val UPCOMING_DATE: String = "2026-09-19"
    public const val TITLE_FIGHT_ID: Int = 13017
    public const val FINAL_EVENT_ID: Int = 1320
    public const val FINAL_DATE: String = "2026-07-18"
    public const val MAIN_EVENT_FIGHT_ID: Int = 12919
    public const val SUBMISSION_FIGHT_ID: Int = 12874
    public const val DRAW_EVENT_ID: Int = 1302
    public const val DRAW_FIGHT_ID: Int = 12742
    public const val NO_CONTEST_EVENT_ID: Int = 1312
    public const val NO_CONTEST_FIGHT_ID: Int = 12730
    public const val DWCS_EVENT_ID: Int = 1334
    public const val CANCELED_EVENT_ID: Int = 1303
    public const val NO_CARD_EVENT_ID: Int = 1342

    /** The samples are routed at their real ids; the ids in between are served as an announced card without fights, so the run is dense. */
    public const val FIRST_EVENT_ID: Int = 1296
    public const val LAST_EVENT_ID: Int = 1342

    public val paths: Map<String, String> = buildMap {
        for (id in FIRST_EVENT_ID..LAST_EVENT_ID) put("/event/live/$id.json", "event.live.upcoming.no-card.json")
        put("/event/live/$UPCOMING_EVENT_ID.json", "event.live.upcoming.json")
        put("/event/live/$FINAL_EVENT_ID.json", "event.live.final.json")
        put("/event/live/$DRAW_EVENT_ID.json", "event.live.final.draw.json")
        put("/event/live/$NO_CONTEST_EVENT_ID.json", "event.live.final.no-contest.json")
        put("/event/live/$DWCS_EVENT_ID.json", "event.live.final.dwcs.json")
        put("/event/live/$CANCELED_EVENT_ID.json", "event.live.canceled.json")
        put("/event/live/$NO_CARD_EVENT_ID.json", "event.live.upcoming.no-card.json")
        for (id in LAST_EVENT_ID + 1..LAST_EVENT_ID + 80) put("/event/live/$id.json", "event.live.unknown.json")
        put("/event/live/999999.json", "event.live.unknown.json")
        put("/fight/live/$TITLE_FIGHT_ID.json", "fight.live.upcoming.json")
        put("/fight/live/$MAIN_EVENT_FIGHT_ID.json", "fight.live.final.json")
        put("/fight/live/9999999.json", "fight.live.unknown.json")
    }

    public fun register(fetcher: SampleFetcher, root: File = SampleFetcher.repoRoot()): SampleFetcher =
        fetcher.routes(UfcProvider.DEFAULT_BASE_URL, SampleFetcher.samplesDir("mma", "ufc", root), paths)
}
