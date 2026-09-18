@file:Suppress("PropertyName")

package org.openscore.providers.malta

import kotlinx.serialization.Serializable

/* DTOs for api.mfa.com.mt (COMET-backed MFA match centre) — see apis/football/malta-premier/README.md. */

@Serializable
public data class MtTeam(val id: Int, val name: String = "", val image: String? = null, val isLive: Boolean = false)

@Serializable
public data class MtVenue(val id: Int? = null, val name: String? = null)

@Serializable
public data class MtReferee(val id: String? = null, val name: String? = null, val role: String? = null)

@Serializable
public data class MtMatch(
    val id: Long,
    val homeTeam: MtTeam,
    val awayTeam: MtTeam,
    /** UTC with seven fractional digits. */
    val startDate: String,
    val referees: List<MtReferee> = emptyList(),
    val venue: MtVenue? = null,
    /** SCHEDULED | LIVE | PLAYED | … */
    val status: String = "",
    val isLive: Boolean = false,
    /** `12'`, `45+2'`, `HT`, `FT` — absent before kick-off. */
    val matchTime: String? = null,
    val isLineupAvailable: Boolean = false,
    val competitionId: String? = null,
    val competitionTypeId: Int? = null,
    val competitionName: String? = null,
)

@Serializable
public data class MtResult(
    val id: Long,
    val status: String = "",
    val isLive: Boolean = false,
    val matchTime: String? = null,
    val homeScore: String? = null,
    val awayScore: String? = null,
    val homePenalties: String? = null,
    val awayPenalties: String? = null,
    val events: List<MtEvent> = emptyList(),
)

@Serializable
public data class MtEvent(
    val id: Long? = null,
    /** GOAL | PENALTY | OWN_GOAL | MISSED_PENALTY | YELLOW | RED | SECOND_YELLOW | SUBSTITUTION … */
    val type: String = "",
    /** HOME | AWAY */
    val team: String? = null,
    val time: String? = null,
    val playerName: String? = null,
    val playerId: Long? = null,
    val playerNameTwo: String? = null,
    val playerIdTwo: Long? = null,
)

@Serializable
public data class MtLineup(val homeTeam: MtLineupTeam? = null, val awayTeam: MtLineupTeam? = null)

@Serializable
public data class MtLineupTeam(val coach: String? = null, val players: List<MtLineupPlayer> = emptyList())

@Serializable
public data class MtLineupPlayer(
    val id: Long,
    val name: String = "",
    val number: String? = null,
    val profilePhoto: String? = null,
    val startingLineup: Boolean = false,
    val isCaptain: Boolean = false,
    val isGoalkeeper: Boolean = false,
)

@Serializable
public data class MtStandings(val competitionTypeId: Int? = null, val competitionTypeName: String? = null, val gameWeek: Int? = null, val phases: List<MtPhase> = emptyList())

@Serializable
public data class MtPhase(val groups: List<MtGroup> = emptyList())

@Serializable
public data class MtGroup(val id: String? = null, val competitionName: String? = null, val data: List<MtRow> = emptyList())

@Serializable
public data class MtRow(
    val position: Int = 0,
    val club: MtTeam,
    val matchesPlayed: Int = 0,
    val wins: Int = 0,
    val draws: Int = 0,
    val losses: Int = 0,
    val goalDifference: Int = 0,
    val points: Int = 0,
)

@Serializable
public data class MtTeams(val competitionTypeId: Int? = null, val data: List<MtTeam> = emptyList())

@Serializable
public data class MtSquadPlayer(val id: Long, val name: String = "", val number: String? = null, val isGoalKeeper: Int = 0, val profilePhoto: String? = null)

@Serializable
public data class MtPlayer(
    val id: Long,
    val playerName: String = "",
    val profilePhoto: String? = null,
    val team: MtTeam? = null,
    val details: List<MtDetail> = emptyList(),
)

@Serializable
public data class MtDetail(val id: Int? = null, val title: String = "", val value: String? = null)
