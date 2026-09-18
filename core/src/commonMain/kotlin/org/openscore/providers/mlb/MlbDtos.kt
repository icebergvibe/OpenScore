package org.openscore.providers.mlb

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/*
 * DTOs for statsapi.mlb.com — see apis/baseball/mlb/README.md. Only fields the provider
 * reads. The API writes several numbers as strings (season, ranks, games back, jersey
 * numbers, batting order); those stay strings here and are parsed in the mapper.
 */

/** `{id, fullName, link}` — how people appear everywhere outside the player maps. */
@Serializable
public data class MlbPersonRef(val id: Long, val fullName: String? = null)

@Serializable
public data class MlbTeamStats(val stats: List<MlbStatGroup> = emptyList())

@Serializable
public data class MlbStatGroup(val group: MlbStatName, val splits: List<MlbStatSplit> = emptyList())

@Serializable
public data class MlbStatName(val displayName: String)

@Serializable
public data class MlbStatSplit(
    val season: String,
    val team: MlbTeam? = null,
    val stat: Map<String, JsonPrimitive> = emptyMap(),
)

@Serializable
public data class MlbStatus(
    val abstractGameState: String = "",
    val codedGameState: String = "",
    val detailedState: String = "",
    val statusCode: String = "",
    val startTimeTBD: Boolean = false,
    val reason: String? = null,
)

@Serializable
public data class MlbNamed(val id: Int, val name: String? = null)

@Serializable
public data class MlbTeam(
    val id: Int,
    val name: String = "",
    val abbreviation: String? = null,
    val teamName: String? = null,
    val locationName: String? = null,
    val shortName: String? = null,
    val venue: MlbNamed? = null,
    val league: MlbNamed? = null,
    val division: MlbNamed? = null,
    val active: Boolean? = null,
)

@Serializable
public data class MlbTeamsResponse(val teams: List<MlbTeam> = emptyList())

// ---- schedule --------------------------------------------------------------------------

@Serializable
public data class MlbSchedule(val dates: List<MlbScheduleDate> = emptyList())

@Serializable
public data class MlbScheduleDate(val date: String, val games: List<MlbScheduleGame> = emptyList())

@Serializable
public data class MlbScheduleGame(
    val gamePk: Long,
    val gameType: String = "",
    val season: String? = null,
    val gameDate: String,
    val officialDate: String? = null,
    val status: MlbStatus = MlbStatus(),
    val teams: MlbScheduleTeams,
    val venue: MlbNamed? = null,
    val doubleHeader: String? = null,
    val gameNumber: Int? = null,
    val scheduledInnings: Int? = null,
    val linescore: MlbLinescore? = null,
    val decisions: MlbDecisions? = null,
    val rescheduleDate: String? = null,
)

@Serializable
public data class MlbDecisions(
    val winner: MlbPersonRef? = null,
    val loser: MlbPersonRef? = null,
    val save: MlbPersonRef? = null,
)

@Serializable
public data class MlbScheduleTeams(val home: MlbScheduleSide, val away: MlbScheduleSide)

@Serializable
public data class MlbScheduleSide(
    val team: MlbTeam,
    val score: Int? = null,
    val isWinner: Boolean? = null,
    val probablePitcher: MlbPersonRef? = null,
)

// ---- linescore --------------------------------------------------------------------------

@Serializable
public data class MlbLinescore(
    val currentInning: Int? = null,
    val inningState: String? = null,
    val isTopInning: Boolean? = null,
    val scheduledInnings: Int? = null,
    val innings: List<MlbInning> = emptyList(),
    val teams: MlbLinescoreTeams = MlbLinescoreTeams(),
    val defense: MlbDefense? = null,
    val offense: MlbOffense? = null,
    val balls: Int? = null,
    val strikes: Int? = null,
    val outs: Int? = null,
)

@Serializable
public data class MlbInning(val num: Int, val home: MlbInningSide = MlbInningSide(), val away: MlbInningSide = MlbInningSide())

/** `runs` is absent for a half-inning not (yet) played, e.g. the home 9th of a home win. */
@Serializable
public data class MlbInningSide(val runs: Int? = null, val hits: Int? = null, val errors: Int? = null)

@Serializable
public data class MlbLinescoreTeams(val home: MlbLinescoreTotals = MlbLinescoreTotals(), val away: MlbLinescoreTotals = MlbLinescoreTotals())

@Serializable
public data class MlbLinescoreTotals(val runs: Int? = null, val hits: Int? = null, val errors: Int? = null, val leftOnBase: Int? = null)

@Serializable
public data class MlbDefense(val pitcher: MlbPersonRef? = null, val team: MlbNamed? = null)

@Serializable
public data class MlbOffense(
    val batter: MlbPersonRef? = null,
    val onDeck: MlbPersonRef? = null,
    val first: MlbPersonRef? = null,
    val second: MlbPersonRef? = null,
    val third: MlbPersonRef? = null,
    val team: MlbNamed? = null,
)

// ---- live feed (v1.1/game/{pk}/feed/live) ------------------------------------------------

@Serializable
public data class MlbLiveFeed(
    /** `0` when the gamePk does not exist (the endpoint still answers 200). */
    val gamePk: Long = 0,
    /** Cursor used by `/diffPatch?startTimecode=` for an incremental next update. */
    val metaData: MlbMetaData = MlbMetaData(),
    val gameData: MlbGameData = MlbGameData(),
    val liveData: MlbLiveData = MlbLiveData(),
)

@Serializable
public data class MlbMetaData(val timeStamp: String? = null, val wait: Int? = null)

@Serializable
public data class MlbGameData(
    val game: MlbGameInfo = MlbGameInfo(),
    val datetime: MlbDatetime = MlbDatetime(),
    val status: MlbStatus = MlbStatus(),
    val teams: MlbFeedTeams? = null,
    /** Keyed `ID{personId}`. */
    val players: Map<String, MlbPerson> = emptyMap(),
    val venue: MlbNamed? = null,
)

@Serializable
public data class MlbGameInfo(val pk: Long = 0, val type: String = "", val season: String? = null, val doubleHeader: String? = null, val gameNumber: Int? = null)

@Serializable
public data class MlbDatetime(val dateTime: String? = null, val officialDate: String? = null)

@Serializable
public data class MlbFeedTeams(val home: MlbTeam, val away: MlbTeam)

@Serializable
public data class MlbLiveData(
    val plays: MlbPlays = MlbPlays(),
    val linescore: MlbLinescore = MlbLinescore(),
    val boxscore: MlbBoxscore? = null,
)

@Serializable
public data class MlbPlays(val allPlays: List<MlbPlay> = emptyList(), val scoringPlays: List<Int> = emptyList())

@Serializable
public data class MlbPlay(
    val result: MlbPlayResult = MlbPlayResult(),
    val about: MlbPlayAbout,
    val count: MlbCount = MlbCount(),
    val matchup: MlbMatchup = MlbMatchup(),
    val runners: List<MlbRunner> = emptyList(),
    val playEvents: List<MlbPlayEvent> = emptyList(),
    val atBatIndex: Int,
)

@Serializable
public data class MlbPlayResult(
    val type: String? = null,
    val event: String? = null,
    val eventType: String? = null,
    val description: String? = null,
    val rbi: Int? = null,
    val awayScore: Int? = null,
    val homeScore: Int? = null,
    val isOut: Boolean? = null,
)

@Serializable
public data class MlbPlayAbout(
    val atBatIndex: Int = 0,
    val halfInning: String = "",
    val isTopInning: Boolean = true,
    val inning: Int,
    val isComplete: Boolean = false,
    val isScoringPlay: Boolean = false,
)

@Serializable
public data class MlbCount(val balls: Int = 0, val strikes: Int = 0, val outs: Int = 0)

@Serializable
public data class MlbMatchup(val batter: MlbPersonRef? = null, val pitcher: MlbPersonRef? = null)

@Serializable
public data class MlbRunner(val movement: MlbRunnerMovement = MlbRunnerMovement(), val details: MlbRunnerDetails = MlbRunnerDetails())

@Serializable
public data class MlbRunnerMovement(val start: String? = null, val end: String? = null, val outBase: String? = null, val isOut: Boolean? = null)

@Serializable
public data class MlbRunnerDetails(val runner: MlbPersonRef? = null, val eventType: String? = null, val isScoringEvent: Boolean? = null)

@Serializable
public data class MlbPlayEvent(
    val type: String = "",
    val index: Int = 0,
    val details: MlbPlayEventDetails = MlbPlayEventDetails(),
    val player: MlbPersonRef? = null,
    val position: MlbPosition? = null,
    val hitData: MlbHitData? = null,
)

@Serializable
public data class MlbPlayEventDetails(
    val description: String? = null,
    val event: String? = null,
    val eventType: String? = null,
    val awayScore: Int? = null,
    val homeScore: Int? = null,
    val isScoringPlay: Boolean? = null,
    val isOut: Boolean? = null,
)

@Serializable
public data class MlbHitData(
    val launchSpeed: Double? = null,
    val launchAngle: Double? = null,
    val totalDistance: Double? = null,
    val trajectory: String? = null,
    val coordinates: MlbHitCoordinates? = null,
)

@Serializable
public data class MlbHitCoordinates(val coordX: Double? = null, val coordY: Double? = null)

@Serializable
public data class MlbPosition(val code: String? = null, val name: String? = null, val type: String? = null, val abbreviation: String? = null)

// ---- boxscore ----------------------------------------------------------------------------

@Serializable
public data class MlbBoxscore(val teams: MlbBoxscoreTeams? = null)

@Serializable
public data class MlbBoxscoreTeams(val home: MlbBoxscoreTeam, val away: MlbBoxscoreTeam)

@Serializable
public data class MlbBoxscoreTeam(
    val team: MlbTeam,
    /** Keyed `ID{personId}`. */
    val players: Map<String, MlbBoxscorePlayer> = emptyMap(),
    val batters: List<Long> = emptyList(),
    val pitchers: List<Long> = emptyList(),
    val bench: List<Long> = emptyList(),
    val bullpen: List<Long> = emptyList(),
    /** The nine current lineup slots, in order; empty before the game. */
    val battingOrder: List<Long> = emptyList(),
)

@Serializable
public data class MlbBoxscorePlayer(
    val person: MlbPersonRef,
    val jerseyNumber: String? = null,
    val position: MlbPosition? = null,
    /** `100`–`903`: hundreds = lineup slot, last two digits = substitution sequence. */
    val battingOrder: String? = null,
    val allPositions: List<MlbPosition> = emptyList(),
)

// ---- people / roster ---------------------------------------------------------------------

@Serializable
public data class MlbPerson(
    val id: Long,
    val fullName: String = "",
    val firstName: String? = null,
    val lastName: String? = null,
    val primaryNumber: String? = null,
    val birthDate: String? = null,
    val birthCity: String? = null,
    val birthStateProvince: String? = null,
    val birthCountry: String? = null,
    /** Imperial, e.g. `6' 7"`. */
    val height: String? = null,
    /** Pounds. */
    val weight: Int? = null,
    val active: Boolean? = null,
    val currentTeam: MlbNamed? = null,
    val primaryPosition: MlbPosition? = null,
    val batSide: MlbCode? = null,
    val pitchHand: MlbCode? = null,
)

@Serializable
public data class MlbCode(val code: String? = null, val description: String? = null)

@Serializable
public data class MlbPeopleResponse(val people: List<MlbPerson> = emptyList())

@Serializable
public data class MlbRoster(val teamId: Int? = null, val roster: List<MlbRosterEntry> = emptyList())

@Serializable
public data class MlbRosterEntry(
    val person: MlbPersonRef,
    val jerseyNumber: String? = null,
    val position: MlbPosition? = null,
    val status: MlbCode? = null,
)

// ---- standings ---------------------------------------------------------------------------

@Serializable
public data class MlbStandings(val records: List<MlbStandingsRecord> = emptyList())

@Serializable
public data class MlbStandingsRecord(
    val standingsType: String? = null,
    val league: MlbNamed? = null,
    val division: MlbNamed? = null,
    val teamRecords: List<MlbTeamRecord> = emptyList(),
)

@Serializable
public data class MlbTeamRecord(
    val team: MlbTeam,
    val season: String? = null,
    val streak: MlbStreak? = null,
    val divisionRank: String? = null,
    val leagueRank: String? = null,
    val wildCardRank: String? = null,
    val gamesPlayed: Int = 0,
    val gamesBack: String? = null,
    val wildCardGamesBack: String? = null,
    val wins: Int = 0,
    val losses: Int = 0,
    val winningPercentage: String? = null,
    val runsScored: Int? = null,
    val runsAllowed: Int? = null,
    val runDifferential: Int? = null,
    val divisionLeader: Boolean? = null,
    val clinched: Boolean? = null,
    val magicNumber: String? = null,
    val eliminationNumber: String? = null,
    val records: MlbSplitRecords? = null,
)

@Serializable
public data class MlbStreak(val streakCode: String? = null)

@Serializable
public data class MlbSplitRecords(val splitRecords: List<MlbSplitRecord> = emptyList())

@Serializable
public data class MlbSplitRecord(val type: String = "", val wins: Int = 0, val losses: Int = 0)
