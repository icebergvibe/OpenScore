package org.openscore.providers.uefa

import kotlinx.serialization.Serializable

/*
 * DTOs for the uefa.com micro-services (match / comp / standings / matchstats .uefa.com)
 * — see apis/football/uefa/README.md. Only the fields the provider reads; every id is a
 * string of digits. `translations` blocks are skipped except where a name lives only there.
 */

/** Nine-language name maps; the provider reads `EN`. */
@Serializable
public data class UefaTranslated(val EN: String? = null)

@Serializable
public data class UefaTeam(
    val id: String,
    val internationalName: String? = null,
    val teamCode: String? = null,
    val countryCode: String? = null,
    val logoUrl: String? = null,
    val mediumLogoUrl: String? = null,
    val bigLogoUrl: String? = null,
    val isPlaceHolder: Boolean = false,
    val translations: UefaTeamTranslations? = null,
)

@Serializable
public data class UefaTeamTranslations(
    val displayName: UefaTranslated? = null,
    val displayOfficialName: UefaTranslated? = null,
    val shortName: UefaTranslated? = null,
)

/**
 * A player, or — when a coach is booked — the staff wrapper `{ imageUrl, role, person: {…} }`
 * that events put in `primaryActor.person`; [flat] unwraps the latter.
 */
@Serializable
public data class UefaPerson(
    val id: String? = null,
    val person: UefaPerson? = null,
    val role: String? = null,
    val internationalName: String? = null,
    val clubId: String? = null,
    val clubJerseyNumber: String? = null,
    val clubShirtName: String? = null,
    val nationalTeamId: String? = null,
    val fieldPosition: String? = null,
    val detailedFieldPosition: String? = null,
    val countryCode: String? = null,
    val countryOfBirthCode: String? = null,
    val birthDate: String? = null,
    val height: Int? = null,
    val weight: Int? = null,
    val imageUrl: String? = null,
    val translations: UefaPersonTranslations? = null,
) {
    /** The person itself, unwrapped from a staff wrapper; `id` is never null on the result. */
    public val flat: UefaPerson get() = person?.let { it.copy(imageUrl = it.imageUrl ?: imageUrl, role = it.role ?: role) } ?: this
}

@Serializable
public data class UefaPersonTranslations(
    val name: UefaTranslated? = null,
    val firstName: UefaTranslated? = null,
    val lastName: UefaTranslated? = null,
)

@Serializable
public data class UefaCompetition(
    val id: String,
    val code: String? = null,
    val metaData: UefaMetaData? = null,
)

@Serializable
public data class UefaMetaData(
    val name: String? = null,
    val type: String? = null,
    val groupName: String? = null,
    val groupShortName: String? = null,
    /** Nations League only: the tier a group sits in, `League A` … `League D`. */
    val leagueName: String? = null,
    val leagueShortName: String? = null,
)

// ---- matches -----------------------------------------------------------------------------

@Serializable
public data class UefaScorePair(val home: Int = 0, val away: Int = 0)

@Serializable
public data class UefaScore(
    val regular: UefaScorePair? = null,
    val total: UefaScorePair? = null,
    val penalty: UefaScorePair? = null,
    val aggregate: UefaScorePair? = null,
)

@Serializable
public data class UefaKickOff(val date: String? = null, val dateTime: String, val utcOffsetInHours: Int? = null)

@Serializable
public data class UefaRound(
    val id: String? = null,
    val metaData: UefaMetaData? = null,
    val mode: String? = null,
    val modeDetail: String? = null,
    val phase: String? = null,
    val status: String? = null,
    val orderInCompetition: Int? = null,
)

@Serializable
public data class UefaMatchday(val id: String? = null, val name: String? = null, val longName: String? = null, val type: String? = null, val sequenceNumber: String? = null)

@Serializable
public data class UefaGroup(
    val id: String? = null,
    val metaData: UefaMetaData? = null,
    val order: Int? = null,
    val teams: List<String> = emptyList(),
    val teamsQualifiedNumber: Int? = null,
    /** Nations League only: the tier the group belongs to; absent in every club competition. */
    val league: UefaGroupLeague? = null,
)

/** The Nations League tier a group sits in (`League A`, order 1, down to `League D`, order 4). */
@Serializable
public data class UefaGroupLeague(val id: String? = null, val metaData: UefaMetaData? = null, val order: Int? = null)

@Serializable
public data class UefaStadium(
    val id: String? = null,
    val countryCode: String? = null,
    val capacity: Int? = null,
    val translations: UefaStadiumTranslations? = null,
)

@Serializable
public data class UefaStadiumTranslations(val name: UefaTranslated? = null, val officialName: UefaTranslated? = null)

@Serializable
public data class UefaWinnerSide(val reason: String? = null, val team: UefaTeam? = null)

@Serializable
public data class UefaWinner(val match: UefaWinnerSide? = null, val aggregate: UefaWinnerSide? = null)

/** `time` on events and on `playerEvents`: conventional minute in progress + second within it. */
@Serializable
public data class UefaEventTime(val minute: Int? = null, val second: Int? = null, val injuryMinute: Int? = null)

@Serializable
public data class UefaPlayerEvent(
    val id: String? = null,
    val goalType: String? = null,
    val penaltyType: String? = null,
    val phase: String? = null,
    val time: UefaEventTime? = null,
    val teamId: String? = null,
    val player: UefaPerson? = null,
)

@Serializable
public data class UefaPlayerEvents(
    val scorers: List<UefaPlayerEvent> = emptyList(),
    val redCards: List<UefaPlayerEvent> = emptyList(),
    val penaltyScorers: List<UefaPlayerEvent> = emptyList(),
    val penaltiesMissed: List<UefaPlayerEvent> = emptyList(),
)

/** `minute` on a live match object (shape from the community typings; not yet observed). */
@Serializable
public data class UefaMinute(val normal: Int? = null, val injury: Int? = null)

@Serializable
public data class UefaMatch(
    val id: String,
    val seasonYear: String? = null,
    val competition: UefaCompetition? = null,
    val competitionPhase: String? = null,
    val round: UefaRound? = null,
    val matchday: UefaMatchday? = null,
    val group: UefaGroup? = null,
    val type: String? = null,
    val kickOffTime: UefaKickOff,
    val status: String = "",
    val phase: String? = null,
    val minute: UefaMinute? = null,
    val lineupStatus: String? = null,
    val homeTeam: UefaTeam,
    val awayTeam: UefaTeam,
    val stadium: UefaStadium? = null,
    val matchAttendance: Int? = null,
    val fullTimeAt: String? = null,
    val score: UefaScore? = null,
    val winner: UefaWinner? = null,
    val playerEvents: UefaPlayerEvents? = null,
)

/** One `/livescore` entry: the site's change detector. */
@Serializable
public data class UefaLivescore(
    val id: String,
    val status: String? = null,
    val lineupStatus: String? = null,
    val hash: String? = null,
    val phase: String? = null,
    val minute: UefaMinute? = null,
    val score: UefaScore? = null,
)

// ---- events ------------------------------------------------------------------------------

@Serializable
public data class UefaCoordinate(val x: Double? = null, val y: Double? = null)

@Serializable
public data class UefaFieldPosition(val coordinate: UefaCoordinate? = null, val distance: Int? = null)

@Serializable
public data class UefaActor(val type: String? = null, val person: UefaPerson? = null, val team: UefaTeam? = null)

@Serializable
public data class UefaEvent(
    val id: String,
    val matchId: String? = null,
    val type: String,
    val subType: String? = null,
    val detail: String? = null,
    val phase: String? = null,
    val time: UefaEventTime? = null,
    val timestamp: String? = null,
    val primaryActor: UefaActor? = null,
    val secondaryActor: UefaActor? = null,
    val bodyPart: String? = null,
    val fieldPosition: UefaFieldPosition? = null,
    val totalScore: UefaScorePair? = null,
    val injuryTimeMinutesAdded: Int? = null,
    val freeText: String? = null,
)

// ---- lineups -----------------------------------------------------------------------------

@Serializable
public data class UefaLineupEntry(
    val player: UefaPerson,
    val jerseyNumber: Int? = null,
    val type: String? = null,
    val isBooked: Boolean = false,
    val fieldCoordinate: UefaCoordinate? = null,
)

@Serializable
public data class UefaCoach(val person: UefaPerson? = null, val role: String? = null)

@Serializable
public data class UefaLineupSide(
    val team: UefaTeam? = null,
    val field: List<UefaLineupEntry> = emptyList(),
    val bench: List<UefaLineupEntry> = emptyList(),
    val coaches: List<UefaCoach> = emptyList(),
    val shirtColor: String? = null,
)

@Serializable
public data class UefaLineups(
    val matchId: String? = null,
    val lineupStatus: String? = null,
    val homeTeam: UefaLineupSide? = null,
    val awayTeam: UefaLineupSide? = null,
)

// ---- statistics --------------------------------------------------------------------------

@Serializable
public data class UefaStatistic(val name: String, val value: String? = null)

@Serializable
public data class UefaTeamStatistics(val teamId: String, val statistics: List<UefaStatistic> = emptyList())

// ---- standings ---------------------------------------------------------------------------

@Serializable
public data class UefaStandingsItem(
    val rank: Int,
    val teamId: String? = null,
    val team: UefaTeam? = null,
    val played: Int = 0,
    val won: Int = 0,
    val drawn: Int = 0,
    val lost: Int = 0,
    val points: Int = 0,
    val goalsFor: Int = 0,
    val goalsAgainst: Int = 0,
    val goalDifference: Int = 0,
    val rankingCoefficient: Double? = null,
    val isLive: Boolean = false,
    val isTied: Boolean = false,
)

@Serializable
public data class UefaStandings(
    val group: UefaGroup? = null,
    val round: UefaRound? = null,
    val status: String? = null,
    val items: List<UefaStandingsItem> = emptyList(),
)

@Serializable
public data class UefaError(val error: UefaErrorBody? = null)

@Serializable
public data class UefaErrorBody(val message: String? = null, val status: Int? = null, val title: String? = null)
