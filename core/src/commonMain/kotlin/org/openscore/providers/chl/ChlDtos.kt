@file:Suppress("PropertyName")

package org.openscore.providers.chl

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * DTOs for the Corebine S3 files behind chl.hockey — see apis/hockey/chl/README.md.
 * Every file is `{ _type, data, errors[] }`; entities carry `_type` and `_entityId`.
 */

@Serializable
public data class ChlResponse<T>(val data: T? = null, val errors: List<JsonElement> = emptyList())

@Serializable
public data class ChlNamed(val name: String? = null, val shortName: String? = null)

@Serializable
public data class ChlCountry(val name: String? = null, val code: String? = null)

@Serializable
public data class ChlStage(val group: ChlOrdered? = null, val round: ChlOrdered? = null)

@Serializable
public data class ChlOrdered(val order: Int? = null, val name: String? = null)

@Serializable
public data class ChlTeamRef(
    @SerialName("_entityId") val entityId: String,
    val externalId: String? = null,
    val name: String = "",
    val shortName: String? = null,
    val country: ChlCountry? = null,
)

@Serializable
public data class ChlTeams(val home: ChlTeamRef, val away: ChlTeamRef)

@Serializable
public data class ChlScores(val home: Int = 0, val away: Int = 0)

@Serializable
public data class ChlVenue(@SerialName("_entityId") val entityId: String? = null, val name: String? = null)

/** Match object shared by `live-events`, `schedule-*` and the `scoreboard` file. */
@Serializable
public data class ChlMatch(
    @SerialName("_entityId") val entityId: String,
    val externalId: String? = null,
    val startDate: String,
    val startDateNotConfirmed: Boolean = false,
    /** not-started | finished (live value expected: in-progress) */
    val status: String = "",
    val venue: ChlVenue? = null,
    val stage: ChlStage? = null,
    val teams: ChlTeams,
    /** BG, F, F/OT, F/SO. Absent on unplayed games in `live-events`. */
    val state: ChlNamed? = null,
    val audience: Int? = null,
    val results: ChlResults? = null,
)

@Serializable
public data class ChlResults(
    val scores: ChlScores? = null,
    val periods: List<ChlPeriod> = emptyList(),
)

@Serializable
public data class ChlPeriod(
    /** 1st Period, 2nd Period, 3rd Period, Overtime, Shootout */
    val name: String = "",
    val shortName: String? = null,
    /** not-started | finished (| in-progress) */
    val status: String = "",
    val scores: ChlScores? = null,
    val actions: List<ChlAction> = emptyList(),
)

@Serializable
public data class ChlAction(
    val actionType: String,
    val externalId: String? = null,
    /** home | away */
    val teamType: String? = null,
    val time: ChlTimestamp? = null,
    val message: ChlMessage? = null,
    /** Scorer first, then assists. */
    val players: List<ChlPlayer> = emptyList(),
    val actions: List<ChlSubAction> = emptyList(),
)

@Serializable
public data class ChlSubAction(val actionType: String)

@Serializable
public data class ChlTimestamp(
    /** Seconds from the start of the game (period 2 starts at 1200). */
    val regularTime: Int = 0,
)

@Serializable
public data class ChlMessage(val title: String = "", val description: String = "")

@Serializable
public data class ChlPosition(
    val name: String? = null,
    /** GK | DE | FW */
    val shortName: String? = null,
    /** First Line … Fourth Line, Goalkeepers */
    val category: String? = null,
    val categoryIndex: Int? = null,
    /** 11–12 goalies, 21–22 defence pair, 31–33 forward line. */
    val index: Int? = null,
)

@Serializable
public data class ChlPlayer(
    @SerialName("_entityId") val entityId: String,
    val externalId: String? = null,
    val firstName: String = "",
    val lastName: String = "",
    val number: Int? = null,
    val nationality: ChlCountry? = null,
    val position: ChlPosition? = null,
    val info: ChlInfo? = null,
    val team: ChlTeamRef? = null,
)

@Serializable
public data class ChlInfo(val properties: List<ChlProperty> = emptyList())

/** Generic property: value is an int, a string, a date string or a country object depending on `_type`. */
@Serializable
public data class ChlProperty(
    val name: String = "",
    val shortName: String? = null,
    val value: JsonElement? = null,
    val shortValue: String? = null,
    val units: String? = null,
)

// ---- lineups -------------------------------------------------------------------------

@Serializable
public data class ChlLineups(
    @SerialName("_entityId") val entityId: String,
    val status: String = "",
    val teams: ChlLineupTeams,
    val officials: List<ChlPerson> = emptyList(),
)

@Serializable
public data class ChlLineupTeams(val home: ChlLineupTeam, val away: ChlLineupTeam)

@Serializable
public data class ChlLineupTeam(
    @SerialName("_entityId") val entityId: String,
    val externalId: String? = null,
    val name: String = "",
    val shortName: String? = null,
    val athletes: List<ChlPlayer> = emptyList(),
    val staff: List<ChlPerson> = emptyList(),
)

@Serializable
public data class ChlPerson(
    val firstName: String = "",
    val lastName: String = "",
    val number: Int? = null,
    val position: ChlPosition? = null,
)

// ---- standings -------------------------------------------------------------------------

@Serializable
public data class ChlSeries(
    val status: String? = null,
    val stage: ChlStage? = null,
    val teams: List<ChlStandingsTeam> = emptyList(),
)

@Serializable
public data class ChlStandingsTeam(
    @SerialName("_entityId") val entityId: String,
    val externalId: String? = null,
    val name: String = "",
    val shortName: String? = null,
    val stats: ChlTeamStats,
)

@Serializable
public data class ChlTeamStats(
    val place: Int = 0,
    val points: Int = 0,
    val isLive: Boolean = false,
    val matches: ChlMatchesStats? = null,
    val goals: ChlGoalsStats? = null,
)

@Serializable
public data class ChlMatchesStats(
    val played: ChlTotal? = null,
    val won: ChlWonLost? = null,
    val lost: ChlWonLost? = null,
    val drawn: Int = 0,
)

@Serializable
public data class ChlTotal(val total: Int = 0)

@Serializable
public data class ChlWonLost(val total: Int = 0, val overtimes: Int = 0, val shootouts: Int = 0)

@Serializable
public data class ChlGoalsStats(val scored: ChlTotal? = null, val conceded: ChlTotal? = null)

// ---- teams / roster ------------------------------------------------------------------

@Serializable
public data class ChlTeamWithAthletes(
    @SerialName("_entityId") val entityId: String,
    val externalId: String? = null,
    val name: String = "",
    val shortName: String? = null,
    val country: ChlCountry? = null,
    val athletes: List<ChlPlayer> = emptyList(),
)
