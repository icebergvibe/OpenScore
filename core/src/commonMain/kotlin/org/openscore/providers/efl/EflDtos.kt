@file:Suppress("PropertyName")

package org.openscore.providers.efl

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/*
 * DTOs for EFL Digital's Gamechanger `multi-club-matches` v2 API behind efl.com - see
 * apis/football/efl/README.md. JSON:API-flavoured: every route answers `data` (a row or a list
 * of `{type, id, attributes}`) plus `meta`; errors are `{"errors": [{title, detail, status}]}`.
 */

@Serializable
public data class EflList<T>(val data: List<EflRow<T>> = emptyList(), val meta: EflMeta? = null)

@Serializable
public data class EflDocument<T>(val data: EflRow<T>)

@Serializable
public data class EflRow<T>(val type: String? = null, val id: String, val attributes: T)

@Serializable
public data class EflMeta(val totalCount: Int? = null, val count: Int? = null, val snapshotDate: String? = null)

/** A `/matches` list row: names and crests only, the team ids live in the crest file name (`t43.png`). */
@Serializable
public data class EflMatchRow(
    /** `2026-09-17 18:30:00`, UTC without a marker. */
    val kickOffDateUTC: String? = null,
    /** `null`, or what is still to be confirmed (`"DATE_AND_TIME"` observed) - see [isTbc]. */
    val TBC: JsonPrimitive? = null,
    val homeTeam: EflRowTeam = EflRowTeam(),
    val awayTeam: EflRowTeam = EflRowTeam(),
    val matchMinutes: Int? = null,
    /** PreMatch | FirstHalf | HalfTime | SecondHalf | … | FullTime | Postponed */
    val matchPeriod: String? = null,
    val formattedMatchTime: String? = null,
    /** NormalResult | PenaltyShootout | Aggregate | Postponed */
    val resultType: String? = null,
    val postponementReason: String? = null,
    val competitionID: Int? = null,
)

/** A kick-off is fixed unless `TBC` says otherwise; the flag has been seen as a string, so anything but null/false counts. */
public fun isTbc(tbc: JsonPrimitive?): Boolean = tbc != null && tbc !is JsonNull && tbc.content.isNotEmpty() && tbc.content != "false"

@Serializable
public data class EflRowTeam(
    val crest: String? = null,
    val name: String? = null,
    val officialName: String? = null,
    val shortName: String? = null,
    val initials: String? = null,
    val score: Int? = null,
    val halfScore: Int? = null,
    val penaltyScore: Int? = null,
)

/** `/matches/{id}`: header, officials, kits, lineups and events in one document. */
@Serializable
public data class EflMatch(
    val matchID: String? = null,
    val competitionID: Int? = null,
    val seasonID: Int? = null,
    val kickOffUTC: String? = null,
    val TBC: JsonPrimitive? = null,
    val groupName: String? = null,
    /** Round label: league round, cup round, `47`-`49` for the play-offs. */
    val matchDay: String? = null,
    /** Regular | Cup Short | Cup | 2nd Leg */
    val matchType: String? = null,
    /** Set for wins inside 90 minutes only. */
    val matchWinnerID: String? = null,
    val period: String? = null,
    val postponementReason: String? = null,
    val venue: String? = null,
    val venueCity: String? = null,
    val refereeName: String? = null,
    val homeTeamID: String? = null,
    val awayTeamID: String? = null,
    val matchDetails: EflMatchDetails? = null,
    /** May hold more than the two sides (play-off final placeholders): pick by [homeTeamID]/[awayTeamID]. */
    val matchTeams: List<EflMatchTeam> = emptyList(),
    val homeTeam: EflTeam? = null,
    val awayTeam: EflTeam? = null,
)

@Serializable
public data class EflMatchDetails(
    val attendance: Int? = null,
    val matchTime: Int? = null,
    val period: String? = null,
    val refereeName: String? = null,
    val resultType: String? = null,
    val matchWinner: EflTeam? = null,
    val formattedMatchTime: String? = null,
)

@Serializable
public data class EflMatchTeam(
    val teamID: String? = null,
    val formation: String? = null,
    /** Final score including extra time. */
    val score: Int? = null,
    val halfScore: Int? = null,
    /** Only filled when extra time was played. */
    val ninetyScore: Int? = null,
    val extraScore: Int? = null,
    val penaltyScore: Int? = null,
    val team: EflTeam? = null,
    /** `{}` before the lineups are published, `{Start[11], Sub[…]}` after. */
    val players: EflPlayers? = null,
    val events: EflEvents? = null,
)

@Serializable
public data class EflPlayers(
    @SerialName("Start") val start: List<EflMatchPlayer> = emptyList(),
    @SerialName("Sub") val sub: List<EflMatchPlayer> = emptyList(),
)

@Serializable
public data class EflMatchPlayer(
    val playerID: String? = null,
    /** 1-11 for starters, 0 on the bench. */
    val formationPlace: Int? = null,
    /** Goalkeeper | Defender | Midfielder | Striker | Substitute */
    val playerPosition: String? = null,
    /** The bench's real position (Forward rather than Striker here). */
    val playerSubPosition: String? = null,
    val shirtNumber: Int? = null,
    val playerStatus: String? = null,
    val playerName: EflPlayerName? = null,
)

@Serializable
public data class EflPlayerName(
    val playerID: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val knownName: String? = null,
    val customKnownName: String? = null,
)

@Serializable
public data class EflEvents(
    val goals: List<EflEvent> = emptyList(),
    val bookings: List<EflEvent> = emptyList(),
    val subs: List<EflEvent> = emptyList(),
    val shootout: List<EflEvent> = emptyList(),
    val `var`: List<EflEvent> = emptyList(),
)

/** One event; exactly one of the four payloads is set. */
@Serializable
public data class EflEvent(
    val eventID: String? = null,
    val teamID: String? = null,
    /** `2026-09-17 18:58:25.000000`, UTC. */
    val eventTimestamp: String? = null,
    /** Display minute (29 for 28:17); counts on from the final minute in a shoot-out. */
    val eventTime: Int? = null,
    val eventMinute: Int? = null,
    val eventSecond: Int? = null,
    /** FirstHalf | SecondHalf | ExtraFirstHalf | ExtraSecondHalf on goals and cards, `"1"`/`"2"`/`"4"` on substitutions, null in a shoot-out. */
    val eventPeriod: String? = null,
    val formattedEventTime: String? = null,
    val goalEvents: EflGoalEvent? = null,
    val bookingEvents: EflBookingEvent? = null,
    val substitutionEvents: EflSubstitutionEvent? = null,
    val shootoutEvents: EflShootoutEvent? = null,
)

/** The nested `player` objects are a stale join (wrong match, team and shirt); only the ids are used. */
@Serializable
public data class EflGoalEvent(val playerID: String? = null, /** Goal | Penalty | Own */ val goalType: String? = null)

@Serializable
public data class EflBookingEvent(
    val playerID: String? = null,
    /** Yellow | Red */
    val card: String? = null,
    /** Yellow | SecondYellow | Red */
    val cardType: String? = null,
    val reason: String? = null,
)

@Serializable
public data class EflSubstitutionEvent(val subOnID: String? = null, val subOffID: String? = null, val reason: String? = null)

@Serializable
public data class EflShootoutEvent(val playerID: String? = null, /** Scored | Missed | Saved */ val outcome: String? = null)

/** The team object as it appears in the match document and the table. */
@Serializable
public data class EflTeam(
    val teamID: String? = null,
    val teamName: String? = null,
    val teamOfficialName: String? = null,
    val teamNameInitials: String? = null,
    val teamShortName: String? = null,
    val crestURL: String? = null,
    val stadiumName: String? = null,
    val teamURL: String? = null,
)

/** `/teams` row attributes: the team plus the competition it was listed for. */
@Serializable
public data class EflTeamEntry(val teams: EflTeam? = null, val competitions: EflCompetition? = null)

@Serializable
public data class EflCompetition(val competitionID: Int? = null, val competitionName: String? = null, val competitionCode: String? = null)

/** `/league-tables` row attributes; rows arrive in table order. */
@Serializable
public data class EflTableRow(
    val teamName: String? = null,
    val teamNameInitials: String? = null,
    val teamShortName: String? = null,
    val crestURL: String? = null,
    val startDayPosition: Int? = null,
    val position: Int = 0,
    val won: Int = 0,
    val drawn: Int = 0,
    val lost: Int = 0,
    val goalsFor: Int = 0,
    val goalsAgainst: Int = 0,
    val goalDifference: Int = 0,
    val points: Int = 0,
    val played: Int = 0,
    /** Last five results, newest first: `W,W,W,W,D`. */
    val form: String? = null,
)

/** `/stats/match/{id}` row attributes: two rows, home first; `data` is empty before kick-off. */
@Serializable
public data class EflMatchStats(
    val teamID: String? = null,
    val possession: Double? = null,
    val shots: Int? = null,
    val shotsOnTarget: Int? = null,
    val corners: Int? = null,
    val fouls: Int? = null,
)
