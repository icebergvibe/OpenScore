@file:Suppress("PropertyName")

package org.openscore.providers.bundesliga

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * DTOs for the Firebase Realtime Database behind bundesliga.com — see
 * apis/football/bundesliga/README.md. Nodes are id-keyed objects, not arrays.
 */

@Serializable
public data class BlConfig(val season: BlConfigSeason? = null, val matchday: BlConfigMatchday? = null)

@Serializable
public data class BlConfigSeason(val dflDatalibrarySeasonId: String, val name: String? = null)

@Serializable
public data class BlConfigMatchday(val dflDatalibraryMatchdayId: String? = null, val matchdayNumber: Int? = null)

@Serializable
public data class BlTeam(
    val dflDatalibraryClubId: String,
    val nameFull: String = "",
    val nameShort: String? = null,
    val threeLetterCode: String? = null,
    val logoUrl: String? = null,
)

@Serializable
public data class BlTeams(val home: BlTeam, val away: BlTeam)

@Serializable
public data class BlSideScore(val live: Int? = null, val halftime: Int? = null, val fulltime: Int? = null)

@Serializable
public data class BlScore(val home: BlSideScore? = null, val away: BlSideScore? = null)

@Serializable
public data class BlMinute(val minute: Int = 0, val injuryTime: Int = 0)

/** Basic match object (`matches/{id}`), also the base of the ticker match. */
@Serializable
public data class BlMatch(
    val matchId: String,
    val dflDatalibraryCompetitionId: String? = null,
    val dflDatalibrarySeasonId: String? = null,
    val dflDatalibraryMatchdayId: String? = null,
    val matchday: Int? = null,
    val matchType: String? = null,
    /** `2026-09-11T18:30:00+0000` (UTC). */
    val plannedKickOff: String,
    val matchDateFixed: Boolean = true,
    val kickOff: String? = null,
    /** PRE_MATCH | FIRST_HALF | HALF | SECOND_HALF | FINAL_WHISTLE */
    val matchStatus: String = "",
    val minuteOfPlay: BlMinute? = null,
    val score: BlScore? = null,
    val teams: BlTeams,
    // ticker-only
    val referee: BlReferee? = null,
    val stadiumName: String? = null,
    val liveBlogEntries: Map<String, BlEntry> = emptyMap(),
)

@Serializable
public data class BlReferee(val displayName: String? = null, val firstName: String? = null, val lastName: String? = null)

@Serializable
public data class BlPerson(val dflDatalibraryObjectId: String? = null, val name: String = "", val imageUrl: String? = null, val shirtNumber: Int? = null, val position: String? = null)

@Serializable
public data class BlEntry(
    val entryType: String = "",
    val entryDate: String? = null,
    val matchSection: String? = null,
    val order: Long = 0,
    val playtime: BlMinute? = null,
    /** home | away | none */
    val side: String? = null,
    val hidden: Boolean = false,
    val detail: BlDetail? = null,
)

/** Union of every ticker `detail` shape. */
@Serializable
public data class BlDetail(
    val score: BlPlainScore? = null,
    val scorer: BlPerson? = null,
    val assist: BlPerson? = null,
    val penalty: Boolean? = null,
    val ownGoal: Boolean? = null,
    val xG: Double? = null,
    val person: BlPerson? = null,
    val `in`: BlPerson? = null,
    val out: BlPerson? = null,
    val review: String? = null,
    val situation: String? = null,
    val decision: String? = null,
    val headline: String? = null,
    val text: String? = null,
    val formation: String? = null,
)

@Serializable
public data class BlPlainScore(val home: Int = 0, val away: Int = 0)

// ---- lineup ----------------------------------------------------------------------------

@Serializable
public data class BlLineup(val home: BlLineupSide? = null, val away: BlLineupSide? = null)

@Serializable
public data class BlLineupSide(
    val startingEleven: BlPersons? = null,
    val bench: BlPersons? = null,
    val coaches: BlPersons? = null,
)

@Serializable
public data class BlPersons(val tacticalFormationName: String? = null, val persons: List<BlLineupPerson> = emptyList())

@Serializable
public data class BlLineupPerson(
    val dflDatalibraryObjectId: String? = null,
    val name: String = "",
    val tacticalName: String? = null,
    val shirtNumber: Int? = null,
    /** GOALKEEPER | DEFENSE | MIDFIELD | ATTACK | HEADCOACH */
    val role: String? = null,
    val imageUrl: String? = null,
)

// ---- stats -----------------------------------------------------------------------------

@Serializable
public data class BlStat(val homeValue: Double? = null, val awayValue: Double? = null)

@Serializable
public data class BlStats(
    val ballPossessionRatio: BlStat? = null,
    val cornerKicks: BlStat? = null,
    val distanceCovered: BlStat? = null,
    val fouls: BlStat? = null,
    val offsides: BlStat? = null,
    val passAccuracy: BlStat? = null,
    val passes: BlStat? = null,
    val shotsOffTarget: BlStat? = null,
    val shotsOnTarget: BlStat? = null,
    val sprints: BlStat? = null,
    val tacklesWon: BlStat? = null,
    val XGoals: BlStat? = null,
)

// ---- table -----------------------------------------------------------------------------

@Serializable
public data class BlTable(
    val season: BlNamed? = null,
    val matchday: BlNamed? = null,
    val entries: List<BlTableEntry> = emptyList(),
)

@Serializable
public data class BlNamed(val id: String? = null, val name: String? = null)

@Serializable
public data class BlTableEntry(
    val rank: Int = 0,
    val tendency: String? = null,
    val club: BlTableClub,
    val gamesPlayed: Int = 0,
    val wins: Int = 0,
    val draws: Int = 0,
    val losses: Int = 0,
    val goalsScored: Int = 0,
    val goalsAgainst: Int = 0,
    val goalDifference: Int = 0,
    val points: Int = 0,
    val qualification: String? = null,
)

@Serializable
public data class BlTableClub(
    val id: String? = null,
    val dflDatalibraryClubId: String? = null,
    val nameFull: String = "",
    val nameShort: String? = null,
    val threeLetterCode: String? = null,
    val logoUrl: String? = null,
)

/** Marker for arbitrary nodes (e.g. shallow listings). */
public typealias BlNode = JsonElement
