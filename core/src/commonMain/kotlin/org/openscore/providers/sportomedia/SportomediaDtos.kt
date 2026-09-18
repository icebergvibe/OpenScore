@file:Suppress("PropertyName")

package org.openscore.providers.sportomedia

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/* DTOs for the GraphQL API at gql.sportomedia.se (allsvenskan.se) — see apis/football/allsvenskan/README.md. */

@Serializable
public data class GqlResponse<T>(val data: T? = null, val errors: List<GqlError> = emptyList())

@Serializable
public data class GqlError(val message: String? = null, val path: List<JsonElement> = emptyList())

@Serializable
public data class SmMatchesForLeagueData(val matchesForLeague: SmMatches? = null)

@Serializable
public data class SmMatches(val matches: List<SmMatch> = emptyList())

@Serializable
public data class SmMatchData(val match: SmMatchWrapper? = null)

@Serializable
public data class SmMatchWrapper(val match: SmMatch? = null)

@Serializable
public data class SmMatch(
    val id: Long,
    val startDate: String,
    val homeTeamName: String = "",
    val visitingTeamName: String = "",
    val homeTeamAbbrv: String = "",
    val visitingTeamAbbrv: String = "",
    val homeTeamScore: Int = 0,
    val visitingTeamScore: Int = 0,
    /** UPCOMING | ONGOING | INTERRUPTED | POSTPONED | FINISHED */
    val status: String = "",
    val extendedStatus: String? = null,
    /** PERIOD_FIRST_HALF … PERIOD_PENALTIES, null outside play. */
    val period: String? = null,
    val round: Int? = null,
    val configLeagueName: String? = null,
    val leagueName: String? = null,
    val matchMinute: Int = 0,
    val matchMinuteWithStoppageTime: String? = null,
    val homeTeamLogo: String? = null,
    val visitingTeamLogo: String? = null,
    val arenaName: String? = null,
    val spectators: Int? = null,
    val referees: List<String> = emptyList(),
    val matchEvents: List<SmEvent> = emptyList(),
)

@Serializable
public data class SmEvent(
    val type: String = "",
    val typeString: String? = null,
    /** Seconds from kick-off of the match (second half starts at 2700). */
    val gameTime: Int? = null,
    val period: String? = null,
    val description: String? = null,
    val teamName: String? = null,
    val homeTeamScore: Int? = null,
    val visitingTeamScore: Int? = null,
    val homeTeamPeriodScore: Int? = null,
    val visitingTeamPeriodScore: Int? = null,
    val byHomeTeam: Boolean? = null,
    val source: String? = null,
    val key: String? = null,
    val minuteWithStoppageTime: String? = null,
    val playerName: String? = null,
    val inPlayerName: String? = null,
    val outPlayerName: String? = null,
    val assistPlayerName: String? = null,
    val assistPlayerId: Long? = null,
)

@Serializable
public data class SmLineupsData(val lineups: SmLineups? = null)

@Serializable
public data class SmLineups(val homeTeam: SmLineupTeam? = null, val visitingTeam: SmLineupTeam? = null)

@Serializable
public data class SmLineupTeam(
    val abbrv: String? = null,
    /** `4-2-3-1` or `fallback` when unpublished. */
    val formation: String? = null,
    val starting: List<SmLineupPlayer> = emptyList(),
    val substitutes: List<SmLineupPlayer> = emptyList(),
)

@Serializable
public data class SmLineupPlayer(
    val id: Long? = null,
    val displayName: String? = null,
    val givenName: String? = null,
    val surName: String? = null,
    val shirtNumber: Int? = null,
    val image: String? = null,
    /** Formation slot `"1"`–`"11"`, `"Sub"`. */
    val position: String? = null,
    val positionText: String? = null,
)

@Serializable
public data class SmMatchStatsData(val matchStats: SmMatchStats? = null)

@Serializable
public data class SmMatchStats(val id: Long? = null, val totalStats: SmPeriodStats? = null)

@Serializable
public data class SmPeriodStats(
    val homeTeamPossesion: Int? = null,
    val visitingTeamPossesion: Int? = null,
    val homeTeamShots: Int? = null,
    val visitingTeamShots: Int? = null,
    val homeTeamShotsOnTarget: Int? = null,
    val visitingTeamShotsOnTarget: Int? = null,
    val homeTeamCorners: Int? = null,
    val visitingTeamCorners: Int? = null,
    val homeTeamOffsides: Int? = null,
    val visitingTeamOffsides: Int? = null,
    val homeTeamYellowCards: Int? = null,
    val visitingTeamYellowCards: Int? = null,
    val homeTeamRedCards: Int? = null,
    val visitingTeamRedCards: Int? = null,
    val homeTeamDistance: Int? = null,
    val visitingTeamDistance: Int? = null,
)

@Serializable
public data class SmStandingsData(val standingsForLeague: SmStandings? = null)

@Serializable
public data class SmStandings(val type: String? = null, val standings: List<SmStandingRow> = emptyList())

@Serializable
public data class SmStandingRow(
    val teamAbbrv: String = "",
    val teamName: String = "",
    val position: Int = 0,
    val previousPosition: Int? = null,
    val teamId: Long? = null,
    val logoImageUrl: String? = null,
    val borderType: String? = null,
    val stats: List<SmNamedValue> = emptyList(),
    val form: List<SmFormMatch> = emptyList(),
)

@Serializable
public data class SmNamedValue(val name: String = "", val value: String? = null)

@Serializable
public data class SmFormMatch(val matchResult: String? = null)

@Serializable
public data class SmTeamsData(val teamsForLeague: SmTeams? = null)

@Serializable
public data class SmTeams(val teams: List<SmTeam> = emptyList())

@Serializable
public data class SmTeamData(val team: SmTeam? = null)

@Serializable
public data class SmTeam(
    val abbrv: String,
    val name: String? = null,
    val displayName: String? = null,
    val fogisId: Long? = null,
    val logoImageUrl: String? = null,
    val arena: SmArena? = null,
)

@Serializable
public data class SmArena(val name: String? = null, val spectators: Int? = null)

@Serializable
public data class SmSquadData(val squad: SmSquad? = null)

@Serializable
public data class SmSquad(
    val goalkeepers: List<SmPlayer> = emptyList(),
    val defenders: List<SmPlayer> = emptyList(),
    val midfields: List<SmPlayer> = emptyList(),
    val forwards: List<SmPlayer> = emptyList(),
)

@Serializable
public data class SmPlayerData(val player: SmPlayer? = null)

@Serializable
public data class SmPlayer(
    val id: Long? = null,
    val fogisId: Long? = null,
    val givenName: String? = null,
    val surName: String? = null,
    val displayName: String? = null,
    val shirtNumber: Int? = null,
    val position: String? = null,
    val nationality: String? = null,
    val birthDate: String? = null,
    val height: Int? = null,
    val weight: Int? = null,
    val image: String? = null,
    val isGoalkeeper: Boolean? = null,
    val teamAbbrv: String? = null,
)
