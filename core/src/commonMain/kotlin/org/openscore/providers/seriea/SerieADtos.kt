@file:Suppress("PropertyName")

package org.openscore.providers.seriea

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/* DTOs for the Deltatre SDP API behind legaseriea.it — see apis/football/serie-a/README.md. */

@Serializable
public data class SaSeasons(val seasons: List<SaSeason> = emptyList())

@Serializable
public data class SaSeason(val seasonId: String, val seasonName: String? = null)

@Serializable
public data class SaMatchdays(val matchdays: List<SaMatchday> = emptyList())

@Serializable
public data class SaMatchday(
    val matchSetId: String,
    val name: String? = null,
    val startDateUtc: String? = null,
    val endDateUtc: String? = null,
    /** Fixture | Playing | Played */
    val matchdayStatus: String? = null,
)

@Serializable
public data class SaImagery(val teamLogo: String? = null, val teamLogoLight: String? = null)

@Serializable
public data class SaTeam(
    val teamId: String? = null,
    val shortName: String? = null,
    val officialName: String? = null,
    val acronymName: String? = null,
    val mediaName: String? = null,
    val countryCode: String? = null,
    val imagery: SaImagery? = null,
    val scores: List<SaScorer> = emptyList(),
    val tacticalFormation: String? = null,
    val fielded: List<SaPlayer> = emptyList(),
    val benched: List<SaPlayer> = emptyList(),
    val staff: List<SaStaff> = emptyList(),
    // standings
    val qualification: JsonElement? = null,
    val stats: List<SaStat> = emptyList(),
)

@Serializable
public data class SaScorer(val playerId: String? = null, val displayName: String? = null, val events: List<SaScorerEvent> = emptyList())

@Serializable
public data class SaScorerEvent(val type: String? = null, val time: Int? = null, val additionalTime: Int? = null, val relatedPlayerId: String? = null, val phase: String? = null)

@Serializable
public data class SaMatches(val matches: List<SaMatch> = emptyList())

/** Match object (list items and the `header`). */
@Serializable
public data class SaMatch(
    val matchId: String? = null,
    val seasonId: String? = null,
    /** UPCOMING | LIVE | FINISHED | POSTPONED | … */
    val status: String? = null,
    val providerStatus: String? = null,
    /** PRE_MATCH | FIRST_HALF | HALF_TIME_BREAK | SECOND_HALF | END_SECOND_HALF | FULL_TIME … */
    val phase: String? = null,
    val matchDateUtc: String? = null,
    val isUnknownKickOffTime: Boolean = false,
    val homeScorePush: Int? = null,
    val awayScorePush: Int? = null,
    val providerHomeScore: Int? = null,
    val providerAwayScore: Int? = null,
    val providerPenaltyScoreHome: Int? = null,
    val providerPenaltyScoreAway: Int? = null,
    val winReason: String? = null,
    val home: SaTeam? = null,
    val away: SaTeam? = null,
    val stadiumName: String? = null,
    val cityName: String? = null,
    val matchSet: SaMatchday? = null,
    /** Minute as a string (`"90"`) or number. */
    val time: JsonElement? = null,
    val additionalTime: JsonElement? = null,
)

@Serializable
public data class SaSummary(
    val matchId: String? = null,
    val status: String? = null,
    val phase: String? = null,
    val events: List<SaEvent> = emptyList(),
)

@Serializable
public data class SaEvent(
    val type: String = "",
    val label: String? = null,
    val description: String? = null,
    val eventId: String? = null,
    val timeStamp: String? = null,
    val homeScorePush: Int? = null,
    val awayScorePush: Int? = null,
    val phase: String? = null,
    val home: SaEventSide? = null,
    val away: SaEventSide? = null,
)

@Serializable
public data class SaEventSide(
    val time: Int? = null,
    val additionalTime: Int? = null,
    val phase: String? = null,
    val player: SaEventPlayer? = null,
)

@Serializable
public data class SaEventPlayer(
    val playerId: String? = null,
    val bibNumber: String? = null,
    val role: Int? = null,
    val shortName: String? = null,
    val displayName: String? = null,
    val assistPlayerId: String? = null,
    val assistShortName: String? = null,
    val relatedPlayerId: String? = null,
    val relatedShortName: String? = null,
)

@Serializable
public data class SaLineups(val matchId: String? = null, val home: SaTeam? = null, val away: SaTeam? = null)

@Serializable
public data class SaPlayer(
    val playerId: String,
    val bibNumber: String? = null,
    val role: Int? = null,
    val roleLabel: String? = null,
    val mediaFirstName: String? = null,
    val mediaLastName: String? = null,
    val shortName: String? = null,
    val displayName: String? = null,
    val nationality: String? = null,
    val nationalityIsoCode: String? = null,
    val isCaptain: Boolean? = null,
    val dateOfBirth: String? = null,
    val height: String? = null,
    val weight: String? = null,
)

@Serializable
public data class SaStaff(val staffId: String? = null, val role: Int? = null, val roleLabel: String? = null, val displayName: String? = null, val shortName: String? = null)

@Serializable
public data class SaTeamStats(val stats: List<SaStat> = emptyList())

@Serializable
public data class SaStat(val statsId: String = "", val statsValue: JsonElement? = null, val statsValueHome: JsonElement? = null, val statsValueAway: JsonElement? = null)

@Serializable
public data class SaStandings(val teams: List<SaTeam> = emptyList())

@Serializable
public data class SaRoster(val team: SaTeam? = null, val players: List<SaPlayer> = emptyList())
