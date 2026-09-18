package org.openscore.testing

import org.openscore.providers.uefa.UefaHosts
import java.io.File

/** URL → sample routing for the UEFA club-competition samples captured on 2026-09-13 (season 2027). */
public object UefaSamples {
    public val HOSTS: UefaHosts = UefaHosts()
    public const val DAY: String = "2026-09-08" // UCL league phase MD1, all finished
    public const val PRE_DAY: String = "2026-09-16" // UEL MD1, all upcoming
    public const val UECL_DAY: String = "2026-10-15" // UECL MD1
    public const val EMPTY_DAY: String = "2026-09-13"
    public const val FINAL_MATCH_ID: String = "2049553" // Real Madrid 2–1 Inter
    public const val PRE_MATCH_ID: String = "2050063" // Omonia–Celta (UEL)
    public const val TWO_LEGS_MATCH_ID: String = "2048635" // TNS–Sabah, second leg
    public const val EXTRA_TIME_MATCH_ID: String = "2047770" // Juventus 3–2 Galatasaray aet (2025/26)
    public const val PENALTIES_MATCH_ID: String = "2047742" // Paris 1–1 Arsenal, 4–3 pens (2026 final)
    public const val OWN_GOAL_MATCH_ID: String = "2048624" // L. Red Imps 3–1 Inter Escaldes
    public const val TEAM_ID: String = "50051" // Real Madrid
    public const val PLAYER_ID: String = "250076574" // Mbappé
    public const val SEASON: String = "2027"
    /** Wall clock for the tests; no live sample yet, so it only matters for `currentSeason()`. */
    public const val NOW: String = "2026-09-13T12:00:00Z"

    private const val EVENTS = "/events?filter=MAIN&order=ASC&limit=500&offset=0"

    public val matchPaths: Map<String, String> = mapOf(
        "/matches?competitionId=1&fromDate=$DAY&toDate=$DAY&limit=100&offset=0&order=ASC" to "matches.day.json",
        "/matches?competitionId=14&fromDate=$PRE_DAY&toDate=$PRE_DAY&limit=100&offset=0&order=ASC" to "matches.day-pre.json",
        "/matches?competitionId=2019&fromDate=$UECL_DAY&toDate=$UECL_DAY&limit=100&offset=0&order=ASC" to "matches.day-uecl.json",
        "/matches?competitionId=1&fromDate=$EMPTY_DAY&toDate=$EMPTY_DAY&limit=100&offset=0&order=ASC" to "matches.empty.json",
        "/matches?competitionId=14&fromDate=$EMPTY_DAY&toDate=$EMPTY_DAY&limit=100&offset=0&order=ASC" to "matches.empty.json",
        "/matches?competitionId=2019&fromDate=$EMPTY_DAY&toDate=$EMPTY_DAY&limit=100&offset=0&order=ASC" to "matches.empty.json",
        "/matches/$FINAL_MATCH_ID" to "match.final.json",
        "/matches/$PRE_MATCH_ID" to "match.pre.json",
        "/matches/$TWO_LEGS_MATCH_ID" to "match.final-two-legs.json",
        "/matches/$EXTRA_TIME_MATCH_ID" to "match.final-extra-time.json",
        "/matches/$PENALTIES_MATCH_ID" to "match.final-penalties.json",
        "/matches/$FINAL_MATCH_ID$EVENTS" to "match-events.main.final.json",
        "/matches/$PRE_MATCH_ID$EVENTS" to "match-events.main.pre.json",
        "/matches/$PENALTIES_MATCH_ID$EVENTS" to "match-events.main.final-penalties.json",
        "/matches/$OWN_GOAL_MATCH_ID$EVENTS" to "match-events.main.own-goal.json",
        "/matches/$FINAL_MATCH_ID/lineups" to "match-lineups.final.json",
        "/matches/$PRE_MATCH_ID/lineups" to "match-lineups.pre.json",
        "/livescore" to "livescore.json",
    )

    public val compPaths: Map<String, String> = mapOf(
        "/competitions?competitionIds=1,14,2019" to "competitions.json",
        "/teams?teamIds=$TEAM_ID" to "teams.by-id.json",
        "/players?playerIds=$PLAYER_ID" to "players.by-id.json",
    )

    public val standingsPaths: Map<String, String> = mapOf(
        "/standings?competitionId=1&seasonYear=$SEASON&phase=TOURNAMENT" to "standings.json",
        "/standings?competitionId=14&seasonYear=$SEASON&phase=TOURNAMENT" to "standings.uel.json",
        "/standings?competitionId=1&seasonYear=2026&phase=TOURNAMENT" to "standings.previous-season.json",
    )

    public val statsPaths: Map<String, String> = mapOf(
        "/team-statistics/$FINAL_MATCH_ID" to "team-statistics.final.json",
        "/team-statistics/$PRE_MATCH_ID" to "team-statistics.pre.json",
    )

    public fun register(fetcher: SampleFetcher, root: File = SampleFetcher.repoRoot()): SampleFetcher {
        val dir = SampleFetcher.samplesDir("football", "uefa", root)
        fetcher.routes(HOSTS.match, dir, matchPaths)
        fetcher.routes(HOSTS.comp, dir, compPaths)
        fetcher.routes(HOSTS.standings, dir, standingsPaths)
        fetcher.routes(HOSTS.stats, dir, statsPaths)
        // The extra-time / two-leg / own-goal matches have no events or stats sample: serve "nothing" so game() still maps.
        fetcher.route(HOSTS.match + "/matches/$EXTRA_TIME_MATCH_ID$EVENTS", File(dir, "match-events.main.pre.json"))
        fetcher.route(HOSTS.match + "/matches/$TWO_LEGS_MATCH_ID$EVENTS", File(dir, "match-events.main.pre.json"))
        fetcher.route(HOSTS.stats + "/team-statistics/$EXTRA_TIME_MATCH_ID", File(dir, "team-statistics.pre.json"))
        fetcher.route(HOSTS.stats + "/team-statistics/$TWO_LEGS_MATCH_ID", File(dir, "team-statistics.pre.json"))
        fetcher.route(HOSTS.stats + "/team-statistics/$PENALTIES_MATCH_ID", File(dir, "team-statistics.pre.json"))
        return fetcher
    }
}
