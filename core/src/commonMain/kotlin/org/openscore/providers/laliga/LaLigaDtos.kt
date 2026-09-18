@file:Suppress("PropertyName")

package org.openscore.providers.laliga

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/* DTOs for apim.laliga.com (public-service + webview) — see apis/football/la-liga/README.md. */

@Serializable
public data class LlSubscriptions(val subscriptions: List<LlSubscription> = emptyList())

@Serializable
public data class LlSubscriptionWrapper(val subscription: LlSubscription)

@Serializable
public data class LlSubscription(
    val id: Int? = null,
    val name: String? = null,
    val slug: String,
    val season: String? = null,
    val year: Int? = null,
    val current_gameweek: LlGameweek? = null,
)

@Serializable
public data class LlGameweek(val id: Int? = null, val week: Int? = null, val name: String? = null, val date: String? = null)

@Serializable
public data class LlCalendar(val calendars: List<LlCalendarDay> = emptyList())

@Serializable
public data class LlCalendarDay(val date: String? = null, val calendar_gameweeks: List<LlCalendarGameweek> = emptyList())

@Serializable
public data class LlCalendarGameweek(val competition: LlSlugged? = null, val gameweek: LlGameweek? = null, val has_games: Boolean = false)

@Serializable
public data class LlSlugged(val id: Int? = null, val slug: String? = null, val name: String? = null)

@Serializable
public data class LlShield(val url: String? = null)

@Serializable
public data class LlTeam(
    val id: Int? = null,
    val slug: String? = null,
    val name: String? = null,
    val nickname: String? = null,
    val shortname: String? = null,
    val color: String? = null,
    val opta_id: String? = null,
    val shield: LlShield? = null,
    val venue: LlVenue? = null,
)

@Serializable
public data class LlVenue(val name: String? = null, val city: String? = null, val timezone: String? = null)

@Serializable
public data class LlMatches(val total: Int? = null, val matches: List<LlMatch> = emptyList())

@Serializable
public data class LlMatchWrapper(val match: LlMatch)

@Serializable
public data class LlMatch(
    val id: Int,
    val slug: String? = null,
    val name: String? = null,
    /** UTC ISO with `+00:00`. */
    val date: String? = null,
    val time: String? = null,
    /** PreMatch | FirstHalf | HalfTime | SecondHalf | … | FullTime | Abandoned | Postponed | Canceled */
    val status: String = "",
    val home_score: Int? = null,
    val away_score: Int? = null,
    val home_team: LlTeam? = null,
    val away_team: LlTeam? = null,
    val home_formation: String? = null,
    val away_formation: String? = null,
    val gameweek: LlGameweek? = null,
    val venue: LlVenue? = null,
    val opta_id: String? = null,
    /** Whole minutes, keeps counting past 90. Header only. */
    val match_time: Int? = null,
    val attempt: Int? = null,
    /** Webview header only. */
    val period_started: Map<String, LlPeriodTimes> = emptyMap(),
    val subscription: LlSubscription? = null,
    val season: JsonElement? = null,
)

@Serializable
public data class LlPeriodTimes(val start: String? = null, val stop: String? = null)

@Serializable
public data class LlEvents(val match_events: List<LlEvent> = emptyList())

@Serializable
public data class LlEvent(
    val id: Long? = null,
    val match_event_kind: LlEventKind? = null,
    val lineup: LlEventLineup? = null,
    val lineup_off: LlEventLineup? = null,
    val assist: LlEventLineup? = null,
    /** Conventional minute. */
    val time: Int? = null,
    /** Elapsed match minute (second half continues from 45). */
    val minute: Int? = null,
    val second: Int? = null,
    val period: String? = null,
    val date_source: String? = null,
)

@Serializable
public data class LlEventKind(val id: Int? = null, val name: String? = null, val collection: String? = null)

@Serializable
public data class LlEventLineup(val team: LlIdOnly? = null, val person: LlPerson? = null)

@Serializable
public data class LlIdOnly(val id: Int? = null)

@Serializable
public data class LlPerson(
    val id: Int? = null,
    val name: String? = null,
    val nickname: String? = null,
    val firstname: String? = null,
    val lastname: String? = null,
    val slug: String? = null,
    val date_of_birth: String? = null,
    val place_of_birth: String? = null,
    val weight: Int? = null,
    val height: Int? = null,
    val country: LlCountry? = null,
    val squad: LlSquadInfo? = null,
    val team: LlTeam? = null,
)

@Serializable
public data class LlCountry(val id: String? = null)

@Serializable
public data class LlSquadInfo(val shirt_number: Int? = null, val position: LlPosition? = null)

@Serializable
public data class LlPosition(val id: Int? = null, val name: String? = null)

@Serializable
public data class LlLineups(val home_team_lineups: List<LlLineupRow> = emptyList(), val away_team_lineups: List<LlLineupRow> = emptyList())

@Serializable
public data class LlLineupRow(
    val id: Long? = null,
    /** 0 coach, 1–11 starters, 12+ bench. */
    val position: Int = 0,
    /** start | sub */
    val status: String? = null,
    val shirt_number: Int? = null,
    val captain: Boolean = false,
    val person: LlPerson? = null,
)

@Serializable
public data class LlMatchStats(val match_team_stats: List<LlTeamStats> = emptyList())

@Serializable
public data class LlTeamStats(val opta_team_id: String? = null, val stats: Map<String, JsonElement?> = emptyMap())

@Serializable
public data class LlStandings(val total: Int? = null, val standings: List<LlStandingRow> = emptyList())

@Serializable
public data class LlStandingRow(
    val played: Int = 0,
    val points: Int = 0,
    val won: Int = 0,
    val drawn: Int = 0,
    val lost: Int = 0,
    val goals_for: Int = 0,
    val goals_against: Int = 0,
    val goal_difference: String? = null,
    val position: Int = 0,
    val previous_position: Int? = null,
    val team: LlTeam,
)

@Serializable
public data class LlTeamWrapper(val team: LlTeam)

@Serializable
public data class LlSquads(val total: Int? = null, val squads: List<LlSquadRow> = emptyList())

@Serializable
public data class LlSquadRow(
    val id: Long? = null,
    val shirt_number: Int? = null,
    val position: LlPosition? = null,
    val person: LlPerson? = null,
    val opta_id: String? = null,
    val loan: Boolean = false,
)

@Serializable
public data class LlPlayerWrapper(val player: LlPerson)
