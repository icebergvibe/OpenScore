@file:Suppress("PropertyName")

package org.openscore.providers.premierleague

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/* DTOs for the Pulselive SDP API behind premierleague.com — see apis/football/premier-league/README.md. */

@Serializable
public data class PlPage<T>(val data: List<T> = emptyList())

@Serializable
public data class PlMatchTeam(
    val id: String,
    val name: String = "",
    val shortName: String? = null,
    val abbr: String? = null,
    val score: Int? = null,
    val halfTimeScore: Int? = null,
    val redCards: Int? = null,
)

@Serializable
public data class PlMatch(
    val matchId: String,
    val competitionId: String? = null,
    val matchWeek: Int? = null,
    val seasonId: String? = null,
    /** List items carry `season` instead of `seasonId`. */
    val season: String? = null,
    /** `2026-09-06 16:30:00` local time in [kickoffTimezoneString]. */
    val kickoff: String? = null,
    val kickoffTimezoneString: String? = null,
    /** PreMatch | FirstHalf | HalfTime | SecondHalf | … | FullTime */
    val period: String = "",
    /** Minutes, as a string. */
    val clock: String? = null,
    val resultType: String? = null,
    val ground: String? = null,
    val attendance: Int? = null,
    val homeTeam: PlMatchTeam,
    val awayTeam: PlMatchTeam,
)

@Serializable
public data class PlTimelineEvent(
    val periodId: String? = null,
    val minutes: Int = 0,
    val seconds: Int = 0,
    val eventType: String = "",
    val tag: String? = null,
    val teamId: String? = null,
    val playerId: String? = null,
    val isoTimestampUtc: String? = null,
)

@Serializable
public data class PlEvents(val homeTeam: PlEventsTeam? = null, val awayTeam: PlEventsTeam? = null)

@Serializable
public data class PlEventsTeam(
    val id: String,
    val goals: List<PlGoal> = emptyList(),
    val cards: List<PlCard> = emptyList(),
    val subs: List<PlSub> = emptyList(),
)

@Serializable
public data class PlGoal(val goalType: String? = null, val period: String? = null, val time: String? = null, val playerId: String? = null, val assistPlayerId: String? = null)

@Serializable
public data class PlCard(val type: String? = null, val period: String? = null, val time: String? = null, val playerId: String? = null)

@Serializable
public data class PlSub(val period: String? = null, val time: String? = null, val playerOnId: String? = null, val playerOffId: String? = null)

@Serializable
public data class PlLineups(
    @SerialName("home_team") val homeTeam: PlLineupTeam? = null,
    @SerialName("away_team") val awayTeam: PlLineupTeam? = null,
)

@Serializable
public data class PlLineupTeam(
    val teamId: String? = null,
    val players: List<PlLineupPlayer> = emptyList(),
    val formation: PlFormation? = null,
    val managers: List<PlManager> = emptyList(),
)

@Serializable
public data class PlLineupPlayer(
    val id: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val knownName: String? = null,
    val shirtNum: String? = null,
    val isCaptain: Boolean = false,
    /** Goalkeeper | Defender | Midfielder | Forward | Substitute */
    val position: String? = null,
    val subPosition: String? = null,
)

@Serializable
public data class PlFormation(val formation: String? = null, val lineup: List<List<String>> = emptyList(), val subs: List<String> = emptyList())

@Serializable
public data class PlManager(val id: String? = null, val firstName: String? = null, val lastName: String? = null)

@Serializable
/** Stats are floats except a few nested objects (`fastestPlayer`), hence [JsonElement]. */
public data class PlTeamStats(val side: String? = null, val teamId: String? = null, val stats: Map<String, JsonElement> = emptyMap())

@Serializable
public data class PlStandings(
    val matchweek: Int? = null,
    val live: Boolean = false,
    val season: PlNamed? = null,
    val tables: List<PlTable> = emptyList(),
)

@Serializable
public data class PlNamed(val id: String? = null, val name: String? = null)

@Serializable
public data class PlTable(val entries: List<PlTableEntry> = emptyList())

@Serializable
public data class PlTableEntry(val team: PlMatchTeam, val overall: PlRecord)

@Serializable
public data class PlRecord(
    val position: Int = 0,
    val startingPosition: Int? = null,
    val played: Int = 0,
    val won: Int = 0,
    val drawn: Int = 0,
    val lost: Int = 0,
    val goalsFor: Int = 0,
    val goalsAgainst: Int = 0,
    val points: Int = 0,
)

@Serializable
public data class PlTeam(
    val id: String,
    val name: String = "",
    val shortName: String? = null,
    val abbr: String? = null,
    val stadium: PlStadium? = null,
)

@Serializable
public data class PlStadium(val name: String? = null, val city: String? = null, val country: String? = null)

@Serializable
public data class PlSquad(val team: PlMatchTeam? = null, val players: List<PlSquadPlayer> = emptyList())

@Serializable
public data class PlSquadPlayer(
    /** A string in squads, `{competitionId, seasonId, playerId}` in playerinfo. */
    val id: JsonElement? = null,
    val name: PlName? = null,
    val shirtNum: Int? = null,
    val position: String? = null,
    val country: PlCountry? = null,
    val dates: PlDates? = null,
    val height: Int? = null,
    val weight: Int? = null,
    val preferredFoot: String? = null,
    val currentTeam: PlMatchTeam? = null,
)

@Serializable
public data class PlName(val first: String? = null, val last: String? = null, val display: String? = null)

@Serializable
public data class PlCountry(val isoCode: String? = null, val country: String? = null)

@Serializable
public data class PlDates(val birth: String? = null, val joinedClub: String? = null)
