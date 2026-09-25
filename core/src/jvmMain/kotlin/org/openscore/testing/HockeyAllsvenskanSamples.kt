package org.openscore.testing

import java.io.File
import org.openscore.providers.sportality.HockeyAllsvenskanProvider

public object HockeyAllsvenskanSamples {
    public const val PRE_GAME_ID: String = "20260918-aik-modo"
    public const val FINAL_GAME_ID: String = "20250919-ssk-bik"
    /**
     * The StatNet keys the play-by-play route wants. Only AIK v MoDo is reachable through the
     * captured season page, so it is the game the play-by-play tests go through - with its
     * finished document, which is the one that has all three periods, penalties and shot
     * outcomes in it. Its `/api/game` sample is the same evening's first period: both are that
     * game's real data, from two moments of it, which is what a live path sees anyway.
     */
    public const val GAME_NUMBER: String = "23401"
    public const val SCHEDULED: String = "2026-09-18T17:00:00Z"
    /** AIK v MoDo in the first period on 2026-09-18, the game the lineup page was captured for. */
    public const val LIVE_GAME_ID: String = PRE_GAME_ID
    public const val PAGE: String = "${HockeyAllsvenskanProvider.DEFAULT_BASE_URL}/pages/matcher?_rsc=openscore"
    public const val VIEW: String = "${HockeyAllsvenskanProvider.DEFAULT_BASE_URL}/games/$LIVE_GAME_ID/view?_rsc=openscore"
    public const val TABLE: String = "${HockeyAllsvenskanProvider.DEFAULT_BASE_URL}/api/league-standings-all-time"
    public const val CURRENT_SEASON: Int = 2026
    public const val PAST_SEASON: Int = 2025
    /** The profile the player page was captured for. */
    public const val PLAYER_SLUG: String = "patrik-zackrisson"
    public const val PLAYER: String = "${HockeyAllsvenskanProvider.DEFAULT_BASE_URL}/players/$PLAYER_SLUG?_rsc=openscore"

    /** Leksand: the club the leaderboard samples were captured for. */
    public const val TEAM_ID: String = "LIF"
    /** The CMS spelling of [TEAM_ID]. Leksand's two codes happen to be the same. */
    public const val TEAM_LABEL: String = "LIF"
    /** MoDo, whose two codes are *not* the same: the StatNet id is `MODO`, the CMS name `MoDo`. */
    public const val TWO_CODE_TEAM_ID: String = "MODO"
    public const val TWO_CODE_TEAM_LABEL: String = "MoDo"
    public const val SQUAD: String = "${HockeyAllsvenskanProvider.DEFAULT_BASE_URL}/api/all-players"
    public const val PLAYER_LEADERBOARD: String = "${HockeyAllsvenskanProvider.DEFAULT_BASE_URL}/api/player-leaderboard"
    public const val TEAM_LEADERBOARD: String = "${HockeyAllsvenskanProvider.DEFAULT_BASE_URL}/api/team-leaderboard"
    public const val PLAY_BY_PLAY: String = "${HockeyAllsvenskanProvider.DEFAULT_BASE_URL}/api/play-by-play"

    public fun register(fetcher: SampleFetcher, root: File = SampleFetcher.repoRoot()): SampleFetcher {
        val dir = SampleFetcher.samplesDir("hockey", "hockeyallsvenskan", root)
        return fetcher
            .route("${HockeyAllsvenskanProvider.DEFAULT_BASE_URL}/pages/matcher?_rsc=openscore", File(dir, "matcher.rsc.txt"), "text/x-component")
            .route("${HockeyAllsvenskanProvider.DEFAULT_BASE_URL}/api/game?slug=$FINAL_GAME_ID", File(dir, "game.new.final.json"))
            .route("${HockeyAllsvenskanProvider.DEFAULT_BASE_URL}/api/game?slug=$LIVE_GAME_ID", File(dir, "game.new.live.json"))
            .route("${HockeyAllsvenskanProvider.DEFAULT_BASE_URL}/games/$LIVE_GAME_ID/view?_rsc=openscore", File(dir, "game-view.lineups.rsc.txt"), "text/x-component")
            .route(PLAYER, File(dir, "player-profile.rsc.txt"), "text/x-component")
            // The POST routes. The body is part of the key, because it is what picks the answer.
            .postRoute(SQUAD, squadBody(TEAM_LABEL), File(dir, "all-players.team.json"))
            .postRoute(SQUAD, squadBody(TWO_CODE_TEAM_LABEL), File(dir, "all-players.team-modo.json"))
            // The wrong code is a 200 with no players, never an error, so it is routed as such.
            .postRoute(SQUAD, squadBody(TWO_CODE_TEAM_ID), File(dir, "all-players.unknown-team.json"))
            .postRoute(PLAYER_LEADERBOARD, playerLeaderboardBody(TEAM_ID, "TP", false), File(dir, "player-leaderboard.team.json"))
            .postRoute(PLAYER_LEADERBOARD, playerLeaderboardBody(TEAM_ID, "SVSPerc", true), File(dir, "player-leaderboard.goalkeepers.json"))
            .postRoute(TABLE, standingsBody(CURRENT_SEASON), File(dir, "league-standings-all-time.json"))
            .postRoute(TABLE, standingsBody(PAST_SEASON), File(dir, "league-standings-all-time.past-season.json"))
            .postRoute(TEAM_LEADERBOARD, teamLeaderboardBody("PPPerc"), File(dir, "team-leaderboard.pp.json"))
            .postRoute(TEAM_LEADERBOARD, teamLeaderboardBody("PKPerc"), File(dir, "team-leaderboard.pk.json"))
            .postRoute(TEAM_LEADERBOARD, teamLeaderboardBody("SOG"), File(dir, "team-leaderboard.sog.json"))
            .postRoute(TEAM_LEADERBOARD, teamLeaderboardBody("SVSPerc"), File(dir, "team-leaderboard.svs.json"))
            .postRoute(PLAY_BY_PLAY, pollPayload(GAME_NUMBER, "AIK", "MODO", SCHEDULED), File(dir, "play-by-play.new.final.json"))
    }

    public fun standingsBody(season: Int): String =
        """{"league":"HA","phase":"HA","season":$season,"location":"all"}"""

    public fun squadBody(teamShortName: String): String =
        """{"scope":"team","orderBy":"name","teamShortName":"$teamShortName","leagueShortName":"HA","searchQuery":"","page":1,"pageSize":60}"""

    public fun playerLeaderboardBody(team: String, metric: String, goalkeeper: Boolean): String =
        """{"league":"HA","team":"$team","phase":"HA","metric":"$metric","isGoalkeeper":$goalkeeper,"page":1,"pageSize":60}"""

    public fun teamLeaderboardBody(metric: String): String =
        """{"league":"HA","phase":"HA","metric":"$metric","page":1,"pageSize":20}"""

    public fun pollPayload(number: String, home: String, away: String, scheduled: String): String =
        """{"statNetGameNumber":"$number","homeStatNetId":"$home","awayStatNetId":"$away","scheduledDateTime":"$scheduled"}"""
}
