@file:Suppress("PropertyName")

package org.openscore.providers.sportality

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * DTOs for the Sportality platform behind shl.se, and behind SSL floorball through
 * `SslProvider`. See apis/hockey/shl/README.md. hockeyallsvenskan.se left the platform for its
 * own site in 2026-09 and has its own shapes in HockeyAllsvenskanDtos.kt.
 */

// ---- /api/sports-v2/season-series-game-types-filter -----------------------------------

@Serializable
public data class SptFilter(
    val ssgtUuid: String,
    val defaultSsgtFilter: SptDefaultFilter,
    val season: List<SptNamed> = emptyList(),
    val series: List<SptNamed> = emptyList(),
)

@Serializable
public data class SptDefaultFilter(val season: String, val series: String, val gameType: String)

@Serializable
public data class SptNamed(val uuid: String, val code: String? = null)

// ---- /api/gameday/gameheader ----------------------------------------------------------

@Serializable
public data class SptHeaderGame(
    val uuid: String,
    val startDateTime: String,
    val date: String,
    val played: Boolean = false,
    val overtime: Boolean = false,
    val shootout: Boolean = false,
    val ssgtUuid: String? = null,
    val seriesCode: String? = null,
    val venue: String? = null,
    val homeTeam: SptHeaderTeam,
    val awayTeam: SptHeaderTeam,
    val roundNumber: Int? = null,
)

@Serializable
public data class SptHeaderTeam(
    val name: String,
    /** Display code (`SKE`, `ÖRE`). */
    val code: String,
    val result: Int? = null,
    val logo: String? = null,
)

// ---- /api/sports-v2/game-schedule ----------------------------------------------------

@Serializable
public data class SptSchedule(val gameInfo: List<SptScheduleGame> = emptyList(), val ssgtUuid: String? = null)

@Serializable
public data class SptScheduleGame(
    val uuid: String,
    val rawStartDateTime: String,
    /** pre-game | live | post-game | canceled */
    val state: String? = null,
    val overtime: Boolean = false,
    val shootout: Boolean = false,
    val ssgtUuid: String? = null,
    val homeTeamInfo: SptScheduleTeam,
    val awayTeamInfo: SptScheduleTeam,
    val venueInfo: SptVenue? = null,
    val seriesInfo: SptSeriesInfo? = null,
)

@Serializable
public data class SptScheduleTeam(
    val uuid: String,
    val code: String? = null,
    val names: SptTeamNames? = null,
    /** Int once played, the string `"N/A"` before. */
    val score: JsonElement? = null,
    val icon: String? = null,
)

@Serializable
public data class SptVenue(val uuid: String? = null, val name: String? = null)

@Serializable
public data class SptSeriesInfo(val uuid: String? = null, val code: String? = null, val displayName: String? = null)

@Serializable
public data class SptTeamNames(
    val code: String? = null,
    val short: String? = null,
    val long: String? = null,
    val full: String? = null,
)

// ---- /api/sports-v2/game-info/{uuid} --------------------------------------------------

@Serializable
public data class SptGameInfoResponse(
    val gameInfo: SptGameInfo,
    val homeTeam: SptGameInfoTeam,
    val awayTeam: SptGameInfoTeam,
    val ssgtUuid: String? = null,
    val seriesUuid: String? = null,
)

@Serializable
public data class SptGameInfo(
    /** Empty string for unknown ids. */
    val gameUuid: String = "",
    val extId: String? = null,
    val startDateTime: String = "",
    val arenaName: String? = null,
    /** pre_game | post_game - stays `pre_game` while the game is on (2026-09-19). */
    val state: String? = null,
    val overtime: Boolean = false,
    val shootout: Boolean = false,
    val seriesCode: String? = null,
    val seriesName: String? = null,
    val roundNumber: Int? = null,
)

@Serializable
public data class SptGameInfoTeam(
    val names: SptTeamNames = SptTeamNames(),
    val uuid: String = "",
    val instanceId: String? = null,
    val icon: String? = null,
    /** Int once played, `""` before. */
    val score: JsonElement? = null,
)

// ---- /api/gameday/game-overview/{uuid} ------------------------------------------------

@Serializable
public data class SptOverview(
    val gameUuid: String,
    val homeGoals: Int = 0,
    val awayGoals: Int = 0,
    /** NotStarted | Ongoing | PeriodBreak | GameEnded - see [SportalityMapper.overviewState] for what `Ongoing` covers. */
    val state: String,
    /** `{}` until the first record of the game; then the game time of the latest record. */
    val time: SptOverviewTime? = null,
)

@Serializable
public data class SptOverviewTime(val period: Int = 0, val periodTime: String? = null)

// ---- /api/gameday/play-by-play/{uuid} -------------------------------------------------

/**
 * Union of all event shapes; `type` decides which fields are meaningful. Types seen: `period`,
 * `goal`, `shot`, `penalty`, `goalkeeper`, `timeout`, `shootout-penalty-shot` and the editorial
 * `insight` (Swedish title/description, same `eventId` as the goal it describes). Rows carry a
 * `revision` and may be `deleted`; `eventUuid` is the only id unique across types.
 */
@Serializable
public data class SptEvent(
    val type: String,
    val eventId: Int? = null,
    val eventUuid: String? = null,
    val deleted: Boolean = false,
    val period: Int = 0,
    /** `MM:SS` elapsed in the period. */
    val time: String? = null,
    val gameState: String? = null,
    val eventTeam: SptEventTeam? = null,
    val player: SptEventPlayer? = null,
    val assists: Map<String, SptEventPlayer> = emptyMap(),
    val locationX: Double? = null,
    val locationY: Double? = null,
    val homeGoals: Int? = null,
    val awayGoals: Int? = null,
    /** EQ, PP1, PP2, SH1, SH2, EN, PS */
    val goalStatus: String? = null,
    val isPenaltyShot: Boolean = false,
    val isEmptyNetGoal: Boolean = false,
    val offence: String? = null,
    val variant: SptPenaltyVariant? = null,
    val isEntering: Boolean? = null,
    // period records
    val started: Boolean? = null,
    val finished: Boolean? = null,
    // shootout-penalty-shot
    val isGoal: Boolean? = null,
    val shootoutIndex: Int? = null,
    val shootoutScore: SptShootoutScore? = null,
)

@Serializable
public data class SptEventTeam(
    val teamId: String? = null,
    /** home | away */
    val place: String? = null,
    val teamCode: String? = null,
    val teamName: String? = null,
)

@Serializable
public data class SptEventPlayer(
    /** Statnet numeric id as a string — not the athlete UUID. */
    val playerId: String,
    val firstName: String = "",
    val familyName: String = "",
    val jerseyToday: String? = null,
    val statistics: List<SptKeyValue> = emptyList(),
)

@Serializable
public data class SptKeyValue(val key: String, val value: String)

@Serializable
public data class SptPenaltyVariant(
    val shortName: String? = null,
    val description: String? = null,
    val minorTime: String? = null,
    val doubleMinorTime: String? = null,
    val majorTime: String? = null,
    val benchTime: String? = null,
    val misconductTime: String? = null,
)

@Serializable
public data class SptShootoutScore(val homeGoals: Int = 0, val awayGoals: Int = 0)

// ---- /api/gameday/boxscore/{uuid} -----------------------------------------------------

@Serializable
public data class SptBoxscore(
    val players: SptSides<Map<String, SptBoxPlayer>> = SptSides(),
    val goalkeepers: SptSides<Map<String, SptBoxPlayer>> = SptSides(),
    val stats: SptSides<List<SptBoxRow>> = SptSides(),
    val gkStats: SptSides<List<SptBoxRow>> = SptSides(),
)

@Serializable
public data class SptSides<T>(val homeTeamValue: T? = null, val awayTeamValue: T? = null)

@Serializable
public data class SptBoxPlayer(val fullName: String = "", val firstName: String = "", val lastName: String = "")

@Serializable
public data class SptBoxRow(
    @SerialName("NR") val nr: Int? = null,
    @SerialName("POS") val pos: String? = null,
    @SerialName("LINE") val line: Int? = null,
    val info: SptBoxInfo,
)

@Serializable
public data class SptBoxInfo(val playerId: Long, val teamId: String? = null, val team: String? = null)

// ---- /api/statistics-v2/league-standings ----------------------------------------------

@Serializable
public data class SptStandings(
    val leagueStandings: List<SptStandingsRow> = emptyList(),
    val groupings: List<SptGrouping> = emptyList(),
)

@Serializable
public data class SptGrouping(val description: String, val first: Int, val last: Int)

@Serializable
public data class SptStandingsRow(
    @SerialName("Rank") val rank: Int = 0,
    @SerialName("GP") val gp: Int = 0,
    /** Regulation wins. */
    @SerialName("W") val w: Int = 0,
    @SerialName("OTW") val otw: Int = 0,
    @SerialName("L") val l: Int = 0,
    @SerialName("OTL") val otl: Int = 0,
    @SerialName("G") val g: Int = 0,
    @SerialName("GA") val ga: Int = 0,
    @SerialName("Diff") val diff: Int = 0,
    @SerialName("Points") val points: Int = 0,
    val info: SptStandingsInfo,
)

@Serializable
public data class SptStandingsInfo(val teamId: String? = null, val teamInfo: SptStandingsTeamInfo)

@Serializable
public data class SptStandingsTeamInfo(
    val teamUuid: String,
    val teamMedia: String? = null,
    val teamNames: SptTeamNames = SptTeamNames(),
)

// ---- /api/sports-v2/all-teams/{ssgt} --------------------------------------------------

@Serializable
public data class SptTeam(
    val uuid: String,
    /** Statnet code (`SAIK`). */
    val teamCode: String? = null,
    val ownerInstanceId: String? = null,
    val teamNames: SptTeamNames = SptTeamNames(),
    val logo: String? = null,
    val icon: String? = null,
)

// ---- /api/sports-v2/athletes/by-team-uuid/{uuid} --------------------------------------

@Serializable
public data class SptAthleteGroup(
    val position: String? = null,
    /** GK | D | F */
    val positionCode: String? = null,
    val players: List<SptAthlete> = emptyList(),
)

@Serializable
public data class SptAthlete(
    val uuid: String,
    val firstName: String = "",
    val lastName: String = "",
    val fullName: String? = null,
    val nationality: String? = null,
    val jerseyNumber: Int? = null,
    val renderedLatestPortrait: SptMedia? = null,
)

@Serializable
public data class SptMedia(val url: String? = null)

// ---- /api/statistics-v2/athlete/profile-page ------------------------------------------

@Serializable
public data class SptProfilePage(
    val uuid: String,
    val fullName: String? = null,
    val firstName: String = "",
    val lastName: String = "",
    val birthDate: String? = null,
    val nationality: String? = null,
    val height: SptMeasure? = null,
    val weight: SptMeasure? = null,
    val jerseyNumber: Int? = null,
    val position: String? = null,
    val positionCode: String? = null,
    /** `L`, `R` or `-`. */
    val shoots: String? = null,
    val team: SptProfileTeam? = null,
    val isInSquad: Boolean? = null,
    val media: SptMedia? = null,
)

@Serializable
public data class SptMeasure(val value: Double? = null, val format: String? = null)

@Serializable
public data class SptProfileTeam(val uuid: String? = null, val name: String? = null, val code: String? = null, val media: String? = null)

// ---- game-broadcaster.s8y.se/live/game?gameUuid= (SSE) ----------------------------------

/**
 * One `data:` object of the live stream. Exactly one key is set and names the message kind;
 * `teamStatistics` and `playerStatistics` (about forty per event) are not decoded.
 */
@Serializable
public data class SptStreamMessage(
    val liveState: SptLiveState? = null,
    /** A play-by-play row in the REST shape, `period` records included; revisions are re-sent. */
    val liveEvent: SptEvent? = null,
    /** `{period, periodTime}` of the latest record, sent alongside events only. */
    val gameTime: SptOverviewTime? = null,
)

/** Heartbeat every ~20 s (`updated: false`) and one message per transition (`updated: true`). */
@Serializable
public data class SptLiveState(
    /** unknown | ongoing | intermission | overtime | decided */
    val liveState: String,
    val updated: Boolean = false,
    val previousLiveState: String? = null,
    /** `GameEnded` alongside `decided`. */
    val gameState: String? = null,
)
