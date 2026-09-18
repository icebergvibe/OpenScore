@file:Suppress("PropertyName")

package org.openscore.providers.ligue1

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * DTOs for ma-api.ligue1.fr (MPG-built API behind ligue1.com) — see
 * apis/football/ligue-1/README.md. Only fields the provider reads.
 */

@Serializable
public data class L1Assets(val logo: L1Sizes? = null, val facePictures: L1Sizes? = null)

@Serializable
public data class L1Sizes(val small: String? = null, val medium: String? = null, val large: String? = null)

@Serializable
public data class L1ClubIdentity(
    val id: String,
    val shortId: Long? = null,
    val name: String = "",
    val shortName: String? = null,
    val officialName: String? = null,
    val trigram: String? = null,
    val primaryColor: String? = null,
    val assets: L1Assets? = null,
    val isInFrenchChampionship: Boolean? = null,
)

// ---- match summaries -----------------------------------------------------------------

@Serializable
public data class L1SummarySide(
    val clubId: String,
    val clubIdentity: L1ClubIdentity? = null,
    val score: Int? = null,
    val shootOutScore: Int? = null,
)

@Serializable
public data class L1MatchSummary(
    val matchId: String,
    val championshipId: Int,
    val gameWeekNumber: Int? = null,
    val date: String,
    val period: String = "",
    val isLive: Boolean = false,
    val matchTime: String? = null,
    val home: L1SummarySide,
    val away: L1SummarySide,
    val unknownMatch: Boolean = false,
)

@Serializable
public data class L1DailyCalendars(val results: L1DailyResults = L1DailyResults())

@Serializable
public data class L1DailyResults(
    /** date → championshipId → gameWeek → { matchesIds } */
    val byDate: Map<String, Map<String, Map<String, L1MatchIds>>> = emptyMap(),
    val matches: Map<String, L1MatchSummary> = emptyMap(),
)

@Serializable
public data class L1MatchIds(val matchesIds: List<String> = emptyList())

@Serializable
public data class L1GameWeekMatches(val matches: List<L1MatchSummary> = emptyList(), val currentMatches: List<L1MatchSummary> = emptyList())

// ---- match resource --------------------------------------------------------------------

@Serializable
public data class L1Match(
    val id: String,
    val championshipId: Int,
    val season: Int? = null,
    val gameWeekNumber: Int? = null,
    val date: String,
    val period: String = "",
    val matchTime: String? = null,
    val periodsDates: L1PeriodDates? = null,
    val home: L1MatchSide,
    val away: L1MatchSide,
    val stadium: L1Stadium? = null,
    val officials: List<L1Official> = emptyList(),
    val timeline: List<JsonElement> = emptyList(),
)

@Serializable
public data class L1PeriodDates(
    val firstHalfStartedAt: String? = null,
    val firstHalfEndedAt: String? = null,
    val secondHalfStartedAt: String? = null,
    val secondHalfEndedAt: String? = null,
    val extraFirstHalfStartedAt: String? = null,
    val extraFirstHalfEndedAt: String? = null,
    val extraSecondHalfStartedAt: String? = null,
    val extraSecondHalfEndedAt: String? = null,
)

@Serializable
public data class L1Stadium(val id: String? = null, val name: String? = null, val attendance: Int? = null)

@Serializable
public data class L1Official(val id: Long? = null, val name: String = "", val type: Int? = null)

@Serializable
public data class L1Manager(val id: Long? = null, val firstName: String? = null, val lastName: String? = null, val knownName: String? = null)

@Serializable
public data class L1MatchSide(
    val clubId: String,
    val clubIdentity: L1ClubIdentity? = null,
    val score: Int? = null,
    val shootOutScore: Int? = null,
    val manager: L1Manager? = null,
    val formation: String? = null,
    val playersIds: List<String> = emptyList(),
    val players: Map<String, L1MatchPlayer> = emptyMap(),
    val goals: List<L1Goal> = emptyList(),
    val canceledGoals: List<L1Goal> = emptyList(),
    val missedPenalties: List<L1Goal> = emptyList(),
    val substitutions: List<L1Substitution> = emptyList(),
    val bookings: List<L1Booking> = emptyList(),
    val penaltyShots: List<L1PenaltyShot> = emptyList(),
    val stats: L1TeamStats? = null,
)

@Serializable
public data class L1Goal(
    val eventId: String? = null,
    /** goal | own | penalty */
    val type: String? = null,
    val scorerId: String? = null,
    val playerId: String? = null,
    val assistProviderId: String? = null,
    val side: String? = null,
    val time: String? = null,
    /** Epoch milliseconds UTC. */
    val timestamp: Long? = null,
    /** 0 none, 1 accepted, 2 rejected. */
    val varDecision: Int? = null,
    val reason: String? = null,
)

@Serializable
public data class L1Booking(
    val eventId: String? = null,
    val playerId: String? = null,
    /** yellow | secondYellow | straightRed | red */
    val type: String? = null,
    val reason: String? = null,
    val side: String? = null,
    val time: String? = null,
    val timestamp: Long? = null,
)

@Serializable
public data class L1Substitution(
    val eventId: String? = null,
    val subOffId: String? = null,
    val subOnId: String? = null,
    val reason: String? = null,
    val side: String? = null,
    val time: String? = null,
    val timestamp: Long? = null,
)

/** Shoot-out kick; shape not yet observed — every field optional. */
@Serializable
public data class L1PenaltyShot(
    val eventId: String? = null,
    val playerId: String? = null,
    val scorerId: String? = null,
    val type: String? = null,
    val scored: Boolean? = null,
    val side: String? = null,
    val timestamp: Long? = null,
)

@Serializable
public data class L1MatchPlayer(
    val id: String,
    val playerIdentity: L1PlayerIdentity? = null,
    /** 1 GK, 2 DF, 3 MF, 4 FW */
    val position: Int? = null,
    val formationPlace: Int? = null,
    val shirtNumber: Int? = null,
    val startedMatch: Boolean? = null,
    val sub: Int? = null,
    val playedMatch: Boolean? = null,
)

@Serializable
public data class L1PlayerIdentity(
    val id: String,
    val shortOptaId: Long? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val birthDate: String? = null,
    val countryName: String? = null,
    val countryShortCode: String? = null,
    val preferredFoot: String? = null,
    val jerseyNumber: Int? = null,
    val assets: L1Assets? = null,
)

@Serializable
public data class L1TeamStats(
    val possessionPercentage: Double? = null,
    val passes: Int? = null,
    val bigChances: Int? = null,
    val expectedGoals: Double? = null,
    val scoringAttempts: Int? = null,
    val ontargetScoringAttempts: Int? = null,
    val blockedScoringAttempts: Int? = null,
    val offsides: Int? = null,
    val corners: Int? = null,
    val fouls: Int? = null,
    val bookingsData: L1Bookings? = null,
)

@Serializable
public data class L1Bookings(val yellow: Int? = null, val red: Int? = null)

// ---- standings / clubs / players -----------------------------------------------------

@Serializable
public data class L1Standings(val season: Int? = null, val standings: Map<String, L1StandingsRow> = emptyMap())

@Serializable
public data class L1StandingsRow(
    val rank: Int,
    val clubId: String,
    val clubIdentity: L1ClubIdentity? = null,
    val points: Int = 0,
    val played: Int = 0,
    val wins: Int = 0,
    val draws: Int = 0,
    val losses: Int = 0,
    val forGoals: Int = 0,
    val againstGoals: Int = 0,
    val goalsDifference: Int = 0,
    val rankDelta: Int? = null,
    val seasonResults: List<L1SeasonResult> = emptyList(),
)

@Serializable
public data class L1SeasonResult(val resultLetter: String? = null)

@Serializable
public data class L1Club(
    val id: String,
    val name: String = "",
    val shortName: String? = null,
    val officialName: String? = null,
    val acronym: String? = null,
    val stadiumId: String? = null,
    val websiteLink: String? = null,
)

@Serializable
public data class L1ClubSummary(
    val squad: L1Squad? = null,
    val manager: L1Manager? = null,
    val stadium: L1Stadium? = null,
    /** The club's fixtures this season, in the daily-calendar summary shape. */
    val matches: Map<String, L1MatchSummary> = emptyMap(),
)

@Serializable
public data class L1Squad(
    val goalkeepersIds: List<String> = emptyList(),
    val defendersIds: List<String> = emptyList(),
    val midfieldersIds: List<String> = emptyList(),
    val strikersIds: List<String> = emptyList(),
    val players: Map<String, L1PlayerIdentity> = emptyMap(),
)

@Serializable
public data class L1Player(
    val id: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val birthDate: String? = null,
    val countryName: String? = null,
    val countryShortCode: String? = null,
    val preferredFoot: String? = null,
    val championships: Map<String, L1PlayerChampionship> = emptyMap(),
)

@Serializable
public data class L1PlayerChampionship(
    val championshipId: Int? = null,
    val championshipClubId: String? = null,
    val jerseyNumber: Int? = null,
    val position: Int? = null,
    val active: Int? = null,
    val assets: L1Assets? = null,
)
