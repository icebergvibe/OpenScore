package org.openscore.providers.sportality

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * DTOs for hockeyallsvenskan.se (Next.js over Strapi, with StatNet behind the game data) - see
 * apis/hockey/hockeyallsvenskan/README.md. The JSON routes, the props inside the pages' React
 * Server Components payloads, and the bodies of the POST routes. Internal: nothing outside
 * [HockeyAllsvenskanProvider] and [HockeyAllsvenskanMapper] needs these shapes, and every value
 * the site sends as a string stays a string here.
 */

@Serializable
internal data class HaGamesResponse(val data: List<HaGame> = emptyList())

@Serializable
internal data class HaGame(
    val season: String? = null,
    val slug: String,
    val scheduledDateTime: String,
    val venue: String? = null,
    val currentPeriod: String? = null,
    val homeScore: String? = null,
    val awayScore: String? = null,
    val endedDateTime: String? = null,
    val decidedIn: String? = null,
    val homeScoreP1: String? = null,
    val awayScoreP1: String? = null,
    val homeScoreP2: String? = null,
    val awayScoreP2: String? = null,
    val homeScoreP3: String? = null,
    val awayScoreP3: String? = null,
    val homeOtScore: String? = null,
    val awayOtScore: String? = null,
    val homeSoScore: String? = null,
    val awaySoScore: String? = null,
    val gameType: String? = null,
    val playOffGame: String? = null,
    val playOffGameLevel: String? = null,
    val playedDateTime: String? = null,
    val isCompleted: Boolean? = null,
    /** What the play-by-play route calls this game; the core has no field for it. */
    val statNetGameNumber: String? = null,
    val homeStatNetId: String,
    val awayStatNetId: String,
    val homeTeam: HaTeam? = null,
    val awayTeam: HaTeam? = null,
)

@Serializable
internal data class HaTeam(
    val name: String,
    val shortName: String? = null,
    val teamArena: String? = null,
    val logo: HaLogo? = null,
)

@Serializable internal data class HaLogo(val url: String? = null)

/** One row of the game page's lineup component: a dressed player, or an official (`isReferee` / `isLinePerson`). */
@Serializable
internal data class HaLineupEntry(
    val documentId: String? = null,
    val firstName: String? = null,
    val familyName: String? = null,
    /** GK | LD | RD | LW | CE | RW; null for officials. */
    val positionToday: String? = null,
    /** Forward line or defence pair, `"1"`-`"4"`; the goalies' order. */
    val line: String? = null,
    val jerseyToday: String? = null,
    /** The starting goalie. */
    val isStarting: Boolean = false,
    val playerStatNetId: String? = null,
    val isReferee: Boolean = false,
    val isLinePerson: Boolean = false,
    /** The player's profile, when the CMS has one (null for a few call-ups). */
    val player: HaLineupPlayer? = null,
)

@Serializable
internal data class HaLineupPlayer(
    val statNetId: String? = null,
    val jerseyNumber: String? = null,
    /**
     * The key [HockeyAllsvenskanProvider.player] takes. Not read yet: lineup and play-by-play
     * refs use the StatNet id, the only id the play-by-play has, and which of the two a player
     * ref should carry is still open.
     */
    val slug: String? = null,
    val headshots: HaHeadshots? = null,
)

@Serializable
internal data class HaHeadshots(val small: String? = null, val medium: String? = null)

/**
 * `POST /api/league-standings-all-time`, which is where the table lives since
 * `/pages/tabell` stopped server-rendering it. `season` is a year; `phase` and `location` are
 * accepted and only `location` changes anything (`all`, `home`, `away`).
 */
@Serializable
internal data class HaStandingsRequest(
    val league: String = "HA",
    val phase: String = "HA",
    val season: Int,
    val location: String = "all",
)

@Serializable
internal data class HaStandingsResponse(val standings: List<HaStandingsRow> = emptyList())

/**
 * One table row. Every value is a string, the ranks included. There is no StatNet id on it any
 * more and the two percentage columns have been empty in every season observed.
 */
@Serializable
internal data class HaStandingsRow(
    val rank: String? = null,
    /** Display spelling (`ÖIK`, `MORA`, `NVIF`), not the StatNet id (`OSIK`, `MIK`, `NYB`). */
    val teamCode: String? = null,
    @SerialName("games_played") val gamesPlayed: String? = null,
    /** Regulation wins; a win past regulation is in the overtime or shoot-out column. */
    val wins: String? = null,
    val losses: String? = null,
    @SerialName("overtime_wins") val overtimeWins: String? = null,
    @SerialName("overtime_losses") val overtimeLosses: String? = null,
    @SerialName("shootouts_wins") val shootoutWins: String? = null,
    @SerialName("shootouts_losses") val shootoutLosses: String? = null,
    val goals: String? = null,
    @SerialName("goals_against") val goalsAgainst: String? = null,
    @SerialName("goal_difference") val goalDifference: String? = null,
    @SerialName("total_points") val totalPoints: String? = null,
    @SerialName("power_play_perc") val powerPlayPerc: String? = null,
    @SerialName("penalty_kill_perc") val penaltyKillPerc: String? = null,
    /** The CMS short name (`MoDo`), which is how the site's squad route spells this club. */
    val statNetClubLabel: String? = null,
)

/** The player page's profile component (`team.player-profile-<block>-<instance>`). */
@Serializable
internal data class HaPlayerProps(
    val playerData: HaPlayerData? = null,
    val careerStats: HaCareerStats? = null,
)

@Serializable
internal data class HaPlayerData(
    val firstName: String? = null,
    val familyName: String? = null,
    val slug: String? = null,
    val statNetId: String? = null,
    /** The club's StatNet id, so a profile says which side the player is on. */
    val teamStatNetId: String? = null,
    val jerseyNumber: String? = null,
    val positionCode: String? = null,
    val birthDate: String? = null,
    val country: String? = null,
    val height: String? = null,
    val weight: String? = null,
    val shoots: String? = null,
    val headshots: HaHeadshots? = null,
)

/** Only the identity half of the career block is mapped; the stat lines are not in the model. */
@Serializable
internal data class HaCareerStats(@SerialName("player_info") val playerInfo: HaPlayerInfo? = null)

@Serializable
internal data class HaPlayerInfo(
    @SerialName("Birthdate") val birthdate: String? = null,
    @SerialName("Nationality") val nationality: String? = null,
    @SerialName("Height") val height: Int? = null,
    @SerialName("Weight") val weight: Int? = null,
    @SerialName("Position") val position: String? = null,
)

// ---- The three POST routes. Requests are serialized, not hand-built, so a field never goes
// ---- missing silently: `scheduledDateTime` absent gets a 400, a misspelled club a 200 with nothing.

/** `POST /api/all-players`. [teamShortName] is the CMS spelling (`MoDo`), not the StatNet id. */
@Serializable
internal data class HaSquadRequest(
    val scope: String = "team",
    val orderBy: String = "name",
    val teamShortName: String,
    val leagueShortName: String = "HA",
    val searchQuery: String = "",
    val page: Int = 1,
    val pageSize: Int = 60,
)

@Serializable
internal data class HaSquadResponse(val players: List<HaSquadPlayer> = emptyList())

@Serializable
internal data class HaSquadPlayer(
    val firstName: String? = null,
    val familyName: String? = null,
    /** How `player()` is keyed; the StatNet id is not on this row at all. */
    val slug: String? = null,
    val positionCode: String? = null,
    val jerseyNumber: String? = null,
    val birthDate: String? = null,
    val country: String? = null,
    val height: String? = null,
    val weight: String? = null,
    val shoots: String? = null,
    val headshots: HaHeadshots? = null,
)

/** `POST /api/team-leaderboard`: the 14 clubs by one metric. */
@Serializable
internal data class HaTeamLeaderRequest(
    val league: String = "HA",
    val phase: String = "HA",
    val metric: String,
    val page: Int = 1,
    val pageSize: Int = 20,
)

@Serializable
internal data class HaTeamLeaderboard(val teams: List<HaTeamLeaderRow> = emptyList())

@Serializable
internal data class HaTeamLeaderRow(
    val teamID: String? = null,
    val metricValue: String? = null,
)

/** `POST /api/player-leaderboard`. [team] is the StatNet id, and omitting [phase] empties it. */
@Serializable
internal data class HaPlayerLeaderRequest(
    val league: String = "HA",
    val team: String,
    val phase: String = "HA",
    val metric: String,
    val isGoalkeeper: Boolean = false,
    val page: Int = 1,
    val pageSize: Int = 60,
)

@Serializable
internal data class HaPlayerLeaderboard(val players: List<HaPlayerLeaderRow> = emptyList())

@Serializable
internal data class HaPlayerLeaderRow(
    val rank: Int? = null,
    /** The assembled display name; the parts are beside it. */
    val player: String? = null,
    val firstName: String? = null,
    val familyName: String? = null,
    val metricValue: String? = null,
)

/**
 * `POST /api/play-by-play`. The site calls this the game page's `pollPayload`; it is null there
 * outside the poll window, so the fields are taken from the season snapshot instead.
 * [scheduledDateTime] is required despite the README once saying only the three ids were: without
 * it the proxy answers `400`, and `playedDateTime` (which it does not need) makes no difference.
 */
@Serializable
internal data class HaPollPayload(
    val statNetGameNumber: String,
    val homeStatNetId: String,
    val awayStatNetId: String,
    val scheduledDateTime: String,
)

/**
 * Only the periods are read. Beside them the document has `game_info` (`game_finished`,
 * `decided_in`), which the game document already says, and each period its `StartTime`,
 * `EndTime` and a `Finisehd` flag (upstream's spelling), which no event needs.
 */
@Serializable
internal data class HaPlayByPlay(
    @SerialName("game_events") val gameEvents: List<HaPbpPeriod> = emptyList(),
)

@Serializable
internal data class HaPbpPeriod(
    @SerialName("Period") val period: String? = null,
    @SerialName("period_label") val periodLabel: String? = null,
    @SerialName("Events") val events: List<HaPbpEvent> = emptyList(),
)

/**
 * One event. Observed `type`: `Goal`, `Penalty`, `Shot`, `GoalkeeperEvent`, `Timeout`; the
 * site's own filters also name `Save`, `BlockedShot`, `Sent off`, `Expulsion` and
 * `ShootoutPenaltyShot`, which no captured game has produced yet. May be incomplete.
 */
@Serializable
internal data class HaPbpEvent(
    val type: String? = null,
    /** Seconds elapsed inside the period, as a string, counted from 0 again each period. */
    val time: String? = null,
    /**
     * Doubles as the manpower on a goal (`EQ`), the infraction on a penalty
     * (`2 min, Tripping`) and the outcome on a shot (`save`, `outside`, `covered by player`,
     * `frame hit`, `on target`). May be incomplete.
     */
    val eventDescription: String? = null,
    /** `1-0`, home first, only on goals. */
    val runningScore: String? = null,
    val scorerSeasonGoals: String? = null,
    /** A string, not a boolean. */
    val isPenaltyShot: String? = null,
    @SerialName("Assist1") val assist1: HaPbpAssist? = null,
    @SerialName("Assist2") val assist2: HaPbpAssist? = null,
    /** Absent on a team penalty, which nobody serves. */
    val player: HaPbpPlayer? = null,
    val team: HaPbpTeam? = null,
    /** Which way a goalie went, on a `GoalkeeperEvent`. */
    val isEntering: Boolean? = null,
)

@Serializable
internal data class HaPbpAssist(
    val name: String? = null,
    val jerseyToday: String? = null,
)

@Serializable
internal data class HaPbpPlayer(
    val statNetId: String? = null,
    val firstName: String? = null,
    val familyName: String? = null,
    val jerseyNumber: String? = null,
)

@Serializable
internal data class HaPbpTeam(
    /** The id the core keys teams by; the name and display code beside it are not needed. */
    val statNetId: String? = null,
)
