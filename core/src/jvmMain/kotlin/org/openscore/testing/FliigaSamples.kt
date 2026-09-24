package org.openscore.testing

import kotlinx.serialization.builtins.ListSerializer
import org.openscore.net.OpenScoreJson
import org.openscore.providers.fliiga.FlStandingsRow
import org.openscore.providers.fliiga.FliigaProvider
import java.io.File

/**
 * URL → sample routing for the F-Liiga Men samples captured on 2026-09-23: the 2026-2027
 * season's match records (three pages of `ottelut`), the table and both leaderboards, and the
 * 3–4 overtime final `929774` as both a compact card and a full match document.
 *
 * The team-records URL depends on the order the table lists its twelve WordPress ids in, so it
 * is built from the captured table rather than written out.
 */
public object FliigaSamples {
    /** Two finals: `929785` at 14:00Z and `929774` at 15:00Z. */
    public const val FINAL_DAY: String = "2026-09-20"
    /** Three fixtures still to come when the records were captured. */
    public const val SCHEDULED_DAY: String = "2026-09-25"
    public const val SEASON_ID: String = "2026-2027"
    /** OLS 3–4 O2-Jyväskylä after overtime: 426 event rows, seven goals and one penalty. */
    public const val FINAL_GAME_ID: String = "929774"
    public const val OTHER_FINAL_GAME_ID: String = "929785"
    /** SPV–Jymy as it looked before the throw-off: lineups posted, no result. */
    public const val SCHEDULED_GAME_ID: String = "929763"
    /** OLS, by the TorneoPal club id the provider uses as its team id. */
    public const val TEAM_ID: String = "393"
    public const val PLAYER_ID: String = "47983"

    private const val AJAX = "/wp-admin/admin-ajax.php?action="

    private fun scoreboard(action: String) = "$AJAX$action&season=$SEASON_ID&phase=&series=miehet&lang=en"

    private fun seasonPage(page: Int) =
        "/wp-json/wp/v2/ottelut?per_page=${FliigaProvider.PAGE_SIZE}&page=$page&orderby=id&order=desc&_fields=${FliigaProvider.MATCH_FIELDS}"

    public val paths: Map<String, String> = buildMap {
        for (page in 1..3) put(seasonPage(page), "matches.page$page.json")
        put(scoreboard("fliiga_scoreboard_standings"), "standings.json")
        put(scoreboard("fliiga_scoreboard_points"), "player-points.json")
        put(scoreboard("fliiga_scoreboard_goalkeepers"), "goalkeepers.json")
        put("${AJAX}match_teams&match_id=$FINAL_GAME_ID", "match-detail.final.json")
        put("${AJAX}match_teams&match_id=$SCHEDULED_GAME_ID", "match-detail.pre.json")
        put("${AJAX}match_live_data&lang=en&match_id=$FINAL_GAME_ID", "match-summary.final.json")
        put("${AJAX}match_live_data&lang=en&match_id=$OTHER_FINAL_GAME_ID", "match-summary.final-krp.json")
        put("${AJAX}match_live_data&lang=en&match_id=$SCHEDULED_GAME_ID", "match-summary.pre.json")
    }

    public fun register(fetcher: SampleFetcher, root: File = SampleFetcher.repoRoot()): SampleFetcher {
        val dir = SampleFetcher.samplesDir("floorball", "f-liiga", root)
        fetcher.routes(FliigaProvider.DEFAULT_BASE_URL, dir, paths)
        val table = OpenScoreJson.decodeFromString(ListSerializer(FlStandingsRow.serializer()), File(dir, "standings.json").readText())
        val ids = table.mapNotNull { it.wpId }.distinct()
        fetcher.route(
            FliigaProvider.DEFAULT_BASE_URL +
                "/wp-json/wp/v2/joukkueet?include=${ids.joinToString(",")}&per_page=${ids.size}&orderby=include&_fields=${FliigaProvider.TEAM_FIELDS}",
            File(dir, "teams.json"),
        )
        return fetcher
    }
}
