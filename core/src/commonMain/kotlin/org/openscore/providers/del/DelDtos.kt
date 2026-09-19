package org.openscore.providers.del

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/*
 * DTOs for the DEL app backend, `del-services.appticore.com/2026/query.php` - see
 * apis/hockey/del/README.md. One URL, `requestName=` selects the dataset; every dataset is a
 * bare array except `gameTime`, `scoreboard` and the error envelope. App-only fields
 * (guesses, notifications, cheers, highlight links) are deliberately omitted.
 */

@Serializable
public data class DelError(val message: String? = null)

@Serializable
public data class DelErrorResponse(val error: DelError? = null)

/** `tournamentList`: the regular season (`category` 11) and, once created, the playoffs (12). */
@Serializable
public data class DelTournament(
    val tournamentID: Int,
    val name: String = "",
    val displayName: String = "",
    val category: Int? = null,
    val categoryName: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
)

/** One `games` row: the schedule, the scoreboard and the single-game read share this shape. */
@Serializable
public data class DelGame(
    /** Unix seconds, UTC. */
    val dateTime: Long,
    val gameNumber: Int,
    val tournamentID: Int,
    /** `"{gameNumber}t{tournamentID}"`, the core game id. */
    val uniqueID: String = "",
    /** Game day 1-52 in the regular season; 9 = pre-playoffs, 3/2/1 = QF/SF/final. */
    val tournamentPhase: Int? = null,
    /** `TAG` (regular season), `PR`, `QF`, `SF`, `GMG` (final). */
    val gamePhase: String? = null,
    val venueName: String? = null,
    val homeTeam: String,
    val guestTeam: String,
    /** `"1:0|2:0|1:2|-:-|-:-"`: P1|P2|P3|OT|GWS. */
    val scoreByPeriod: String? = null,
    val progressCode: String? = null,
    /** Repeats [progressCode], except `OT` / `GWS` on finals decided after regulation. */
    val progressCodeName: String? = null,
    /** 0 scheduled, 1-99 in play, 100 finished. */
    val progressPerc: Int = 0,
    val homeTeamScore: Int = 0,
    val guestTeamScore: Int = 0,
    /** Elapsed game seconds as a string; null on every scheduled and finished row seen. */
    val gameTime: String? = null,
    val spectators: Int? = null,
    val playoff: Int = 0,
    /** Game number within a playoff series. */
    val seriesNumber: Int = 0,
    val seriesHomeTeamScore: Int = 0,
    val seriesGuestTeamScore: Int = 0,
    val playoffReverseTeam: Int = 0,
    val deleted: Int = 0,
)

/** One `gameSituations` / `gameSituationsExtended` row. */
@Serializable
public data class DelSituation(
    val uniqueID: Long,
    val noc: String = "",
    /** `1` | `2` | `3` | `OT` | `GWS`. */
    val period: String = "",
    /** `mm:ss` elapsed in the game, not the period. */
    val time: String = "",
    val playerId: Long? = null,
    /** Family name only. */
    val playerName: String = "",
    val jerseyNumber: Int? = null,
    /** `G` goal | `P` penalty | `I` info | `S` shot. */
    val type: String = "",
    val actionCode1: String = "",
    val actionCode2: String = "",
    val actionCode3: String = "",
    val x: Double? = null,
    val y: Double? = null,
    /** Score after a goal; absent on other rows. */
    val homeScore: Int? = null,
    val guestScore: Int? = null,
)

/** One `gameResults` row; the statistics are filled on the `TOT` row only. */
@Serializable
public data class DelPeriodResult(
    val period: String = "",
    val homeScore: Int = 0,
    val guestScore: Int = 0,
    val homeSog: Int = 0,
    val guestSog: Int = 0,
    val homeShotAttempts: Int = 0,
    val guestShotAttempts: Int = 0,
    val homeSsg: Int = 0,
    val guestSsg: Int = 0,
    val homePim: Int = 0,
    val guestPim: Int = 0,
    val homeBully: Int = 0,
    val guestBully: Int = 0,
    val homePpCount: Int = 0,
    val guestPpCount: Int = 0,
    val homePpg: Int = 0,
    val guestPpg: Int = 0,
    val homeTpp: String? = null,
    val guestTpp: String? = null,
)

/** One `gameLineup` slot: a home and a guest player side by side. */
@Serializable
public data class DelLineupSlot(
    /** 0 = goalkeepers, 1-4 = lines. */
    val lineNumber: Int = 0,
    /** Goalkeepers 1-2; lines 1-2 defenders, 3-5 forwards. */
    val linePosition: Int = 0,
    val homePlayerMemberId: Long = 0,
    /** `"Family Given"`; empty on an unused slot. */
    val homePlayerName: String = "",
    val homePlayerNumber: Int = 0,
    val homePlayerSmallImageUrl: String? = null,
    val guestPlayerMemberId: Long = 0,
    val guestPlayerName: String = "",
    val guestPlayerNumber: Int = 0,
    val guestPlayerSmallImageUrl: String? = null,
)

@Serializable
public data class DelOfficial(
    val officialFamilyName: String = "",
    val officialGivenName: String = "",
    /** `Ref1` | `Ref2` | `Lin1` | `Lin2`. */
    val officialPosition: String = "",
)

/** One `teamList` row. No crest, city or arena. */
@Serializable
public data class DelTeam(
    val noc: String,
    val name: String = "",
)

@Serializable
public data class DelStandingsRow(
    val noc: String,
    val rank: Int = 0,
    val gamesPlayed: Int = 0,
    /** Regulation wins / losses; overtime and shoot-out results are [otw] / [otl]. */
    val gamesWon: Int = 0,
    val gamesLost: Int = 0,
    val otw: Int = 0,
    val otl: Int = 0,
    val goalsFor: Int = 0,
    val goalsAgainst: Int = 0,
    val points: Int = 0,
    val pointsPerGame: Double? = null,
)

/** One `teamMembers` row: a player, or the head coach (`position` `HED_COA`). */
@Serializable
public data class DelMember(
    val memberID: Long,
    val noc: String = "",
    val familyName: String = "",
    val givenName: String = "",
    val jerseyNumber: Int? = null,
    /** `GK` | `D` | `F` | `HED_COA`. */
    val position: String? = null,
    val shoots: String? = null,
    /** ISO `Z` string in `teamMembers`, epoch milliseconds in `statistics`. */
    val birthday: JsonPrimitive? = null,
    val birthCountry: String? = null,
    val nationality: String? = null,
    /** `"1.91m"`. */
    val height: String? = null,
    /** `"95kg"`. */
    val weight: String? = null,
    val captain: String? = null,
    val imageUrl: String? = null,
    val smallImageUrl: String? = null,
)
