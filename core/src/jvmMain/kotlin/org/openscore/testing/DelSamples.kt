package org.openscore.testing

import org.openscore.providers.del.DelProvider
import java.io.File

/** URL → sample routing for the DEL samples captured on 2026-09-19 (tournament 77 = 2026-27 regular season). */
public object DelSamples {
    /** German day 2026-09-18 (six finals) = UTC window 2026-09-17 22:00:00 to 2026-09-18 21:59:59. */
    public const val FINAL_DAY: String = "2026-09-18"
    /** German day 2026-09-20 (seven scheduled games, game day 2). */
    public const val SCHEDULED_DAY: String = "2026-09-20"
    public const val EMPTY_DAY: String = "2026-09-19"
    public const val FINAL_GAME_ID: String = "4389t77"
    public const val SHOOTOUT_GAME_ID: String = "4394t77"
    public const val PLAYOFF_OT_GAME_ID: String = "4384t76"
    public const val SCHEDULED_GAME_ID: String = "4396t77"
    public const val UNKNOWN_GAME_ID: String = "1t77"
    public const val TEAM_ID: String = "EBB"
    public const val PLAYER_ID: String = "2209"

    private const val Q = "?os=android&lastUpdate=0&requestName="

    public val paths: Map<String, String> = mapOf(
        "${Q}tournamentList" to "tournament-list.json",
        "${Q}games&dateFrom=2026-09-17%2022:00:00&dateTo=2026-09-18%2021:59:59" to "games.day.json",
        "${Q}games&dateFrom=2026-09-19%2022:00:00&dateTo=2026-09-20%2021:59:59" to "games.day.scheduled.json",
        "${Q}games&dateFrom=2026-09-18%2022:00:00&dateTo=2026-09-19%2021:59:59" to "games.day.empty.json",
        "${Q}games&dateFrom=2026-09-16%2022:00:00&dateTo=2026-09-19%2021:59:59" to "games.range.json",
        "${Q}games&tournamentId=77&gameNumber=4389" to "games.final.json",
        "${Q}games&tournamentId=77&gameNumber=4394" to "games.final-shootout.json",
        "${Q}games&tournamentId=77&gameNumber=4396" to "games.scheduled.json",
        "${Q}games&tournamentId=77&gameNumber=1" to "games.unknown.json",
        "${Q}games&tournamentId=76" to "games.playoffs.json",
        "${Q}games&tournamentId=76&gameNumber=4384" to "games.final-playoff-ot.json",
        "${Q}gameSituations&tournamentId=77&gameNumber=4389" to "game-situations.final.json",
        "${Q}gameSituations&tournamentId=77&gameNumber=4394" to "game-situations.final-shootout.json",
        "${Q}gameSituations&tournamentId=76&gameNumber=4384" to "game-situations.final-playoff-ot.json",
        "${Q}gameSituations&tournamentId=77&gameNumber=4396" to "game-situations.scheduled.json",
        "${Q}gameSituationsExtended&tournamentId=77&gameNumber=4389" to "game-situations-extended.final.json",
        "${Q}gameResults&tournamentId=77&gameNumber=4389" to "game-results.final.json",
        "${Q}gameResults&tournamentId=77&gameNumber=4394" to "game-results.final-shootout.json",
        "${Q}gameResults&tournamentId=76&gameNumber=4384" to "game-results.final-playoff-ot.json",
        "${Q}gameLineup&tournamentId=77&gameNumber=4389" to "game-lineup.final.json",
        "${Q}gameOfficials&tournamentId=77&gameNumber=4389" to "game-officials.final.json",
        "${Q}teamList&tournamentId=77" to "team-list.json",
        "${Q}teamStandings&tournamentId=77" to "team-standings.json",
        "${Q}teamStandings&tournamentId=75" to "team-standings.2025-26.json",
        "${Q}teamMembers&tournamentId=77&noc=EBB" to "team-members.team.json",
        "${Q}teamMembers&tournamentId=77&noc=STR" to "team-members.team.STR.json",
        "${Q}teamMembers&tournamentId=77&memberId=2209" to "team-members.player.json",
    )

    public fun register(fetcher: SampleFetcher, root: File = SampleFetcher.repoRoot()): SampleFetcher {
        // The playoff game's rosters and team list are not captured: names fall back to the feed's own.
        return fetcher.routes(DelProvider.DEFAULT_BASE_URL, SampleFetcher.samplesDir("hockey", "del", root), paths)
    }
}
