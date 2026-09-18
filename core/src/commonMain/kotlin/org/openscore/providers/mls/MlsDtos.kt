@file:Suppress("PropertyName")

package org.openscore.providers.mls

import kotlinx.serialization.Serializable

@Serializable public data class MlsSeasons(val seasons: List<MlsSeason> = emptyList())
@Serializable public data class MlsSeason(val season_id: String, val season: Int? = null)
@Serializable public data class MlsSchedule(val schedule: List<MlsScheduleMatch> = emptyList())
@Serializable public data class MlsScheduleMatch(
    val match_id: String, val season_id: String? = null, val competition_id: String? = null, val competition_name: String? = null,
    /** False on placeholder fixtures the club listing carries for internal test competitions. */
    val match_scheduled: Boolean? = null,
    val planned_kickoff_time: String? = null, val stadium_name: String? = null,
    val home_team_id: String? = null, val home_team_name: String? = null, val home_team_three_letter_code: String? = null,
    val away_team_id: String? = null, val away_team_name: String? = null, val away_team_three_letter_code: String? = null,
    val home_team_goals: Int? = null, val away_team_goals: Int? = null, val match_status: String? = null,
)
@Serializable public data class MlsMatch(
    val match_information: MlsMatchInformation? = null, val environment: MlsEnvironment? = null,
    val home: MlsMatchTeam? = null, val away: MlsMatchTeam? = null,
)
@Serializable public data class MlsMatchInformation(
    val match_id: String? = null, val season_id: String? = null, val competition_name: String? = null,
    val planned_kickoff_time: String? = null, val match_status: String? = null, val minute_of_play: String? = null,
    val home_team_goals: Int? = null, val away_team_goals: Int? = null,
)
@Serializable public data class MlsEnvironment(val stadium_name: String? = null)
@Serializable public data class MlsMatchTeam(
    val team_id: String? = null, val team_name: String? = null, val team_three_letter_code: String? = null,
    val latest_line_up: String? = null, val players: List<MlsLineupPlayer> = emptyList(), val trainer_staff: List<MlsStaff> = emptyList(),
)
@Serializable public data class MlsLineupPlayer(
    val person_id: String? = null, val first_name: String? = null, val last_name: String? = null,
    val short_name: String? = null, val shirt_number: Int? = null, val playing_position: String? = null, val starting: String? = null,
)
@Serializable public data class MlsStaff(val role: String? = null, val short_name: String? = null)
@Serializable public data class MlsEvents(
    val events: List<MlsEvent> = emptyList(),
    val next_page_token: String? = null,
)
@Serializable public data class MlsEvent(val type: String = "", val sub_type: String? = null, val event: MlsEventData? = null)
@Serializable public data class MlsEventData(
    val event_id: Long? = null, val game_section: String? = null, val minute_of_play: String? = null, val result: String? = null,
    val team_id: String? = null, val team_name: String? = null, val team_three_letter_code: String? = null,
    val player_id: String? = null, val player_first_name: String? = null, val player_last_name: String? = null,
    val assist_player_id: String? = null, val assist_player_first_name: String? = null, val assist_player_last_name: String? = null,
    val player_in_id: String? = null, val player_in_first_name: String? = null, val player_in_last_name: String? = null,
    val player_out_id: String? = null, val player_out_first_name: String? = null, val player_out_last_name: String? = null,
    val card_color: String? = null,
)
@Serializable public data class MlsMatchStats(val match_statistics_list: List<MlsMatchStatistics> = emptyList())
@Serializable public data class MlsMatchStatistics(val match_statistics: MlsStatistics? = null)
@Serializable public data class MlsStatistics(val team_statistics: List<MlsTeamStatistics> = emptyList())
@Serializable public data class MlsTeamStatistics(
    val team_role: String? = null, val possession_ratio: Double? = null, val shots_at_goal_sum: Int? = null,
    val shots_at_goal_successfull: Int? = null, val corner_kicks_sum: Int? = null, val fouls_sum: Int? = null,
    val offsides: Int? = null, val cards_yellow: Int? = null, val cards_red: Int? = null, val xG: Double? = null,
)
@Serializable public data class MlsClub(
    val club_id: String? = null, val club_name: String? = null, val three_letter_code: String? = null, val short_name: String? = null,
    val city: String? = null, val country: String? = null, val founded: String? = null, val stadium_name: String? = null,
)
@Serializable public data class MlsStandings(val tables: List<MlsTable> = emptyList())
@Serializable public data class MlsTable(val season_id: String? = null, val competition: String? = null, val entries: List<MlsStandingEntry> = emptyList())
@Serializable public data class MlsStandingEntry(
    val position: Int? = null, val team_id: String? = null, val team: String? = null, val team_three_letter_code: String? = null,
    val games_played: Int? = null, val wins: Int? = null, val draws: Int? = null, val losses: Int? = null,
    val goals_scored: Int? = null, val goals_against: Int? = null, val goals_difference: Int? = null, val points: Int? = null,
)
@Serializable public data class MlsRoster(val players: List<MlsRosterPlayer> = emptyList())
@Serializable public data class MlsRosterPlayer(
    val player_id: String? = null, val name: String? = null, val first_name: String? = null, val last_name: String? = null,
    val birth_date: String? = null, val nationality_english: String? = null, val shirt_number: Int? = null,
    val playing_position_english: String? = null, val club_id: String? = null,
)
@Serializable public data class MlsMetadataMatch(
    val sportecId: String? = null, val matchDate: String? = null, val home: MlsMetadataTeam? = null,
    val away: MlsMetadataTeam? = null, val venue: MlsMetadataVenue? = null, val season: MlsMetadataSeason? = null,
    val competition: MlsMetadataCompetition? = null,
)
@Serializable public data class MlsMetadataTeam(
    val sportecId: String? = null, val fullName: String? = null, val abbreviation: String? = null, val logoColorUrl: String? = null,
)
@Serializable public data class MlsMetadataVenue(val name: String? = null)
@Serializable public data class MlsMetadataSeason(val sportecId: String? = null)
@Serializable public data class MlsMetadataCompetition(val name: String? = null)
