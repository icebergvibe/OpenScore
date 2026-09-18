@file:Suppress("PropertyName")

package org.openscore.providers.nhl

import kotlinx.serialization.Serializable

/*
 * DTOs for api-web.nhle.com/v1. Only the fields the provider reads are declared; unknown
 * keys are ignored. Shapes come from apis/hockey/nhl/samples and every sample there is
 * parsed in NhlDtoTest.
 */

/** Localised string: `{"default": "Toronto", "fr": "Toronto"}`. */
@Serializable
public data class NhlLocalized(val default: String = "")

@Serializable
public data class NhlPeriodDescriptor(
    val number: Int,
    /** `REG`, `OT`, `SO`. */
    val periodType: String,
    val maxRegulationPeriods: Int? = null,
)

@Serializable
public data class NhlClock(
    /** `mm:ss`. */
    val timeRemaining: String? = null,
    val secondsRemaining: Int? = null,
    val running: Boolean = false,
    val inIntermission: Boolean = false,
)

@Serializable
public data class NhlGameOutcome(
    /** `REG`, `OT`, `SO`. */
    val lastPeriodType: String,
)

/** Team block as it appears on score/schedule/gamecenter game objects. */
@Serializable
public data class NhlGameTeam(
    val id: Int,
    val abbrev: String,
    /** Full name on `/score`, absent on gamecenter (which has commonName + placeName). */
    val name: NhlLocalized? = null,
    val commonName: NhlLocalized? = null,
    val placeName: NhlLocalized? = null,
    val score: Int? = null,
    val sog: Int? = null,
    val logo: String? = null,
    val darkLogo: String? = null,
)

// ---- GET /score/{date} -------------------------------------------------------------

@Serializable
public data class NhlScoreResponse(
    val prevDate: String? = null,
    val currentDate: String,
    val nextDate: String? = null,
    val games: List<NhlScoreGame> = emptyList(),
)

@Serializable
public data class NhlScoreGame(
    val id: Long,
    val season: Int,
    val gameType: Int,
    val gameDate: String,
    val venue: NhlLocalized? = null,
    val startTimeUTC: String,
    val gameState: String,
    val gameScheduleState: String = "OK",
    val awayTeam: NhlGameTeam,
    val homeTeam: NhlGameTeam,
    val clock: NhlClock? = null,
    val period: Int? = null,
    val periodDescriptor: NhlPeriodDescriptor? = null,
    val gameOutcome: NhlGameOutcome? = null,
    val goals: List<NhlScoreGoal> = emptyList(),
)

@Serializable
public data class NhlScoreGoal(
    val period: Int,
    val periodDescriptor: NhlPeriodDescriptor,
    val timeInPeriod: String,
    val playerId: Long,
    val name: NhlLocalized? = null,
    val teamAbbrev: String,
    val awayScore: Int,
    val homeScore: Int,
    /** `ev`, `pp`, `sh`. */
    val strength: String? = null,
    val goalModifier: String? = null,
)

// ---- GET /gamecenter/{id}/play-by-play ---------------------------------------------

@Serializable
public data class NhlPlayByPlay(
    val id: Long,
    val season: Int,
    val gameType: Int,
    val gameDate: String,
    val venue: NhlLocalized? = null,
    val venueLocation: NhlLocalized? = null,
    val startTimeUTC: String,
    val gameState: String,
    val gameScheduleState: String = "OK",
    val periodDescriptor: NhlPeriodDescriptor? = null,
    val awayTeam: NhlGameTeam,
    val homeTeam: NhlGameTeam,
    val clock: NhlClock? = null,
    val gameOutcome: NhlGameOutcome? = null,
    val regPeriods: Int? = null,
    val rosterSpots: List<NhlRosterSpot> = emptyList(),
    val plays: List<NhlPlay> = emptyList(),
)

@Serializable
public data class NhlRosterSpot(
    val teamId: Int,
    val playerId: Long,
    val firstName: NhlLocalized,
    val lastName: NhlLocalized,
    val sweaterNumber: Int? = null,
    val positionCode: String? = null,
    val headshot: String? = null,
)

@Serializable
public data class NhlPlay(
    val eventId: Int,
    val periodDescriptor: NhlPeriodDescriptor,
    val timeInPeriod: String,
    val timeRemaining: String,
    /** Four digits: away goalie, away skaters, home skaters, home goalie (`1551`). */
    val situationCode: String? = null,
    val typeCode: Int,
    val typeDescKey: String,
    val sortOrder: Int,
    val details: NhlPlayDetails? = null,
)

/** Union of every `details` shape; which fields are set depends on `typeDescKey`. */
@Serializable
public data class NhlPlayDetails(
    val eventOwnerTeamId: Int? = null,
    val xCoord: Double? = null,
    val yCoord: Double? = null,
    val zoneCode: String? = null,
    val reason: String? = null,
    val shotType: String? = null,
    // goal
    val scoringPlayerId: Long? = null,
    val scoringPlayerTotal: Int? = null,
    val assist1PlayerId: Long? = null,
    val assist2PlayerId: Long? = null,
    val goalieInNetId: Long? = null,
    val awayScore: Int? = null,
    val homeScore: Int? = null,
    // shots
    val shootingPlayerId: Long? = null,
    val blockingPlayerId: Long? = null,
    val awaySOG: Int? = null,
    val homeSOG: Int? = null,
    // penalty
    val typeCode: String? = null,
    val descKey: String? = null,
    val duration: Int? = null,
    val committedByPlayerId: Long? = null,
    val drawnByPlayerId: Long? = null,
    val servedByPlayerId: Long? = null,
    // faceoff / hit / possession
    val winningPlayerId: Long? = null,
    val losingPlayerId: Long? = null,
    val hittingPlayerId: Long? = null,
    val hitteePlayerId: Long? = null,
    val playerId: Long? = null,
)

// ---- GET /gamecenter/{id}/boxscore -------------------------------------------------

@Serializable
public data class NhlBoxscore(
    val id: Long,
    val gameState: String,
    val awayTeam: NhlGameTeam,
    val homeTeam: NhlGameTeam,
    val playerByGameStats: NhlPlayerByGameStats? = null,
)

@Serializable
public data class NhlPlayerByGameStats(
    val awayTeam: NhlBoxscoreTeam,
    val homeTeam: NhlBoxscoreTeam,
)

@Serializable
public data class NhlBoxscoreTeam(
    val forwards: List<NhlBoxscorePlayer> = emptyList(),
    val defense: List<NhlBoxscorePlayer> = emptyList(),
    val goalies: List<NhlBoxscorePlayer> = emptyList(),
)

@Serializable
public data class NhlBoxscorePlayer(
    val playerId: Long,
    val sweaterNumber: Int? = null,
    /** Abbreviated: `J. Eichel`. */
    val name: NhlLocalized,
    val position: String? = null,
    val starter: Boolean? = null,
)

// ---- GET /standings/{date} ---------------------------------------------------------

@Serializable
public data class NhlStandingsResponse(
    val wildCardIndicator: Boolean = false,
    val standingsDateTimeUtc: String? = null,
    val standings: List<NhlStandingsRow> = emptyList(),
)

@Serializable
public data class NhlStandingsRow(
    val seasonId: Int,
    val gameTypeId: Int? = null,
    val date: String? = null,
    val teamName: NhlLocalized,
    val teamCommonName: NhlLocalized? = null,
    val teamAbbrev: NhlLocalized,
    val placeName: NhlLocalized? = null,
    val teamLogo: String? = null,
    val teamLogoDark: String? = null,
    val conferenceName: String? = null,
    val conferenceAbbrev: String? = null,
    val divisionName: String? = null,
    val divisionAbbrev: String? = null,
    val conferenceSequence: Int? = null,
    val divisionSequence: Int? = null,
    val leagueSequence: Int? = null,
    val wildcardSequence: Int? = null,
    val clinchIndicator: String? = null,
    val gamesPlayed: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val otLosses: Int = 0,
    val ties: Int = 0,
    val points: Int = 0,
    val goalFor: Int = 0,
    val goalAgainst: Int = 0,
    val goalDifferential: Int = 0,
    val regulationWins: Int? = null,
    val regulationPlusOtWins: Int? = null,
    val shootoutWins: Int? = null,
    val shootoutLosses: Int? = null,
    val streakCode: String? = null,
    val streakCount: Int? = null,
    val homeWins: Int? = null,
    val homeLosses: Int? = null,
    val homeOtLosses: Int? = null,
    val roadWins: Int? = null,
    val roadLosses: Int? = null,
    val roadOtLosses: Int? = null,
    val l10Wins: Int? = null,
    val l10Losses: Int? = null,
    val l10OtLosses: Int? = null,
)

// ---- GET /roster/{team}/current ----------------------------------------------------

@Serializable
public data class NhlRoster(
    val forwards: List<NhlRosterPlayer> = emptyList(),
    val defensemen: List<NhlRosterPlayer> = emptyList(),
    val goalies: List<NhlRosterPlayer> = emptyList(),
)

@Serializable
public data class NhlRosterPlayer(
    val id: Long,
    val headshot: String? = null,
    val firstName: NhlLocalized,
    val lastName: NhlLocalized,
    val sweaterNumber: Int? = null,
    val positionCode: String? = null,
    val shootsCatches: String? = null,
    val heightInCentimeters: Int? = null,
    val weightInKilograms: Int? = null,
    val birthDate: String? = null,
    val birthCity: NhlLocalized? = null,
    val birthStateProvince: NhlLocalized? = null,
    val birthCountry: String? = null,
)

// ---- GET /player/{id}/landing ------------------------------------------------------

@Serializable
public data class NhlPlayerLanding(
    val playerId: Long,
    val isActive: Boolean? = null,
    val currentTeamId: Int? = null,
    val currentTeamAbbrev: String? = null,
    val firstName: NhlLocalized,
    val lastName: NhlLocalized,
    val sweaterNumber: Int? = null,
    val position: String? = null,
    val headshot: String? = null,
    val heightInCentimeters: Int? = null,
    val weightInKilograms: Int? = null,
    val birthDate: String? = null,
    val birthCity: NhlLocalized? = null,
    val birthStateProvince: NhlLocalized? = null,
    val birthCountry: String? = null,
    val shootsCatches: String? = null,
)

// ---- GET /standings-season -----------------------------------------------------------

@Serializable
public data class NhlStandingsSeasons(
    val currentDate: String? = null,
    val seasons: List<NhlStandingsSeason> = emptyList(),
)

@Serializable
public data class NhlStandingsSeason(
    val id: Int,
    val standingsStart: String,
    val standingsEnd: String,
)
