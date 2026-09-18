@file:Suppress("PropertyName")

package org.openscore.providers.liiga

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * DTOs for liiga.fi/api/v2. Only fields the provider reads; see apis/hockey/liiga/README.md.
 */

@Serializable
public data class LiigaGamesByDate(
    val games: List<LiigaGame> = emptyList(),
    val previousGameDate: String? = null,
    val nextGameDate: String? = null,
)

@Serializable
public data class LiigaGameDetail(
    val game: LiigaGame? = null,
    val homeTeamPlayers: List<LiigaLineupPlayer> = emptyList(),
    val awayTeamPlayers: List<LiigaLineupPlayer> = emptyList(),
)

@Serializable
public data class LiigaGame(
    val id: Long,
    val season: Int,
    val serie: String? = null,
    val start: String,
    val started: Boolean = false,
    val ended: Boolean = false,
    /** ACTIVE_OR_NOT_STARTED | ENDED_DURING_REGULAR_GAME_TIME | ENDED_DURING_EXTENDED_GAME_TIME | ENDED_DURING_WINNING_SHOT_COMPETITION */
    val finishedType: String? = null,
    val currentPeriod: Int = 0,
    /** Seconds of game clock elapsed in total. */
    val gameTime: Int = 0,
    val periods: List<LiigaPeriod> = emptyList(),
    val homeTeam: LiigaGameTeam,
    val awayTeam: LiigaGameTeam,
    val iceRink: LiigaRink? = null,
    val winningShotCompetitionEvents: List<LiigaShootoutEvent> = emptyList(),
    val playOffPhase: Int = 0,
)

@Serializable
public data class LiigaPeriod(
    val index: Int,
    /** NORMAL | OVERTIME | WINNING_SHOT_COMPETITION */
    val category: String,
    val homeTeamGoals: Int = 0,
    val awayTeamGoals: Int = 0,
    val startTime: Int = 0,
    val endTime: Int = 0,
)

@Serializable
public data class LiigaRink(val id: Int? = null, val name: String? = null, val city: String? = null)

@Serializable
public data class LiigaLogos(val darkBg: String? = null, val lightBg: String? = null)

@Serializable
public data class LiigaGameTeam(
    /** `"626537494:sport"`. */
    val teamId: String,
    val teamName: String,
    val goals: Int = 0,
    val goalEvents: List<LiigaGoalEvent> = emptyList(),
    val penaltyEvents: List<LiigaPenaltyEvent> = emptyList(),
    val logos: LiigaLogos? = null,
)

@Serializable
public data class LiigaPlayerName(val playerId: Long, val firstName: String = "", val lastName: String = "")

@Serializable
public data class LiigaGoalEvent(
    val eventId: Int,
    val period: Int,
    val gameTime: Int,
    val scorerPlayerId: Long? = null,
    val scorerPlayer: LiigaPlayerName? = null,
    val assistantPlayerIds: List<Long> = emptyList(),
    val assistantPlayers: List<LiigaPlayerName> = emptyList(),
    val homeTeamScore: Int,
    val awayTeamScore: Int,
    /** Finnish codes: YV, YV2, AV, TM, IM, VT, VL, RL … */
    val goalTypes: List<String> = emptyList(),
    val winningGoal: Boolean = false,
    val goalsSoFarInSeason: Int? = null,
)

@Serializable
public data class LiigaPenaltyEvent(
    val eventId: Int,
    val period: Int,
    val gameTime: Int,
    val playerId: Long? = null,
    val suffererPlayerId: Long? = null,
    val penaltyMinutes: Int? = null,
    val penaltyFaultType: String? = null,
    val penaltyFaultName: String? = null,
    val penaltyBegintime: Int? = null,
    val penaltyEndtime: Int? = null,
)

@Serializable
public data class LiigaShootoutEvent(
    val shotNumber: Int,
    val shootingPlayerId: Long? = null,
    val blockingPlayerId: Long? = null,
    val shootingTeamId: String,
    val goalScored: Boolean = false,
    val winningGoal: Boolean = false,
)

@Serializable
public data class LiigaLineupPlayer(
    val id: Long,
    val teamId: String,
    val firstName: String = "",
    val lastName: String = "",
    val jersey: Int? = null,
    /** GOALIE, LEFT_DEFENSEMAN, CENTER, STRIKER … */
    val role: String? = null,
    /** MV, VP, OP, P, KH, VL, OL, H … */
    val roleCode: String? = null,
    val line: Int? = null,
    val captain: Boolean = false,
    val alternateCaptain: Boolean = false,
    val injured: Boolean = false,
    val dateOfBirth: String? = null,
    val placeOfBirth: String? = null,
    val countryOfBirth: String? = null,
    val nationality: String? = null,
    /** LEFT | RIGHT */
    val handedness: String? = null,
    val height: Int? = null,
    val weight: Int? = null,
    val pictureUrl: String? = null,
)

@Serializable
public data class LiigaShot(
    /** EvenStrengthShot | PowerplayShot | ShorthandedShot */
    val type: String? = null,
    /** GOAL | GOALIE_BLOCKED | PLAYER_BLOCKED | MISSED */
    val eventType: String,
    val period: Int,
    val gameTime: Int,
    /** Numeric team id only. */
    val shootingTeamId: Long,
    val shooterId: Long? = null,
    val blockerId: Long? = null,
    val shotX: Double? = null,
    val shotY: Double? = null,
)

@Serializable
public data class LiigaStandings(
    val season: List<LiigaStandingsRow> = emptyList(),
    val playoffs: List<LiigaStandingsRow> = emptyList(),
    val playoffsLines: List<Int> = emptyList(),
)

@Serializable
public data class LiigaStandingsRow(
    val internalId: Long? = null,
    val teamId: String,
    val teamName: String,
    val teamLogos: LiigaLogos? = null,
    val ranking: Int = 0,
    val games: Int = 0,
    /** Regulation wins only. */
    val wins: Int = 0,
    val overtimeWins: Int = 0,
    val losses: Int = 0,
    val overtimeLosses: Int = 0,
    /** Games decided after regulation (OT wins + OT losses). */
    val ties: Int = 0,
    val points: Int = 0,
    val goals: Int = 0,
    val goalsAgainst: Int = 0,
    val serie: String? = null,
)

@Serializable
public data class LiigaTeamsInfo(val teams: Map<String, LiigaTeamInfo> = emptyMap())

@Serializable
public data class LiigaTeamInfo(
    val id: String,
    val name: String,
    val short_name: String? = null,
    val slug: String? = null,
    val locality: String? = null,
    val logo: String? = null,
    val url: String? = null,
    val country: JsonElement? = null,
)

@Serializable
public data class LiigaPlayerInfo(
    val fihaId: Long,
    val firstName: String = "",
    val lastName: String = "",
    val dateOfBirth: String? = null,
    val birthLocality: LiigaLocality? = null,
    val nationality: LiigaCountry? = null,
    /** L | R here. */
    val handedness: String? = null,
    val height: Int? = null,
    val weight: Int? = null,
    val isRemoved: Boolean = false,
    val activeSeasons: List<Int> = emptyList(),
    val teams: Map<String, LiigaPlayerTeamSeason> = emptyMap(),
)

@Serializable
public data class LiigaCountry(val code: String? = null, val name: String? = null)

@Serializable
public data class LiigaLocality(val country: LiigaCountry? = null, val name: String? = null)

@Serializable
public data class LiigaPlayerTeamSeason(
    val season: Int,
    val jersey: Int? = null,
    val teamId: Long? = null,
    val slug: String? = null,
    val teamName: String? = null,
    val imageUrl: String? = null,
    val position: String? = null,
)
