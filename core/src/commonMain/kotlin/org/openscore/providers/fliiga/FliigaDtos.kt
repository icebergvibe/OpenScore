package org.openscore.providers.fliiga

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * DTOs for F-Liiga's key-less proxy over TorneoPal - see apis/floorball/f-liiga/README.md.
 * Two families: WordPress REST records (`ottelut` matches, `joukkueet` teams), which carry
 * the id bridge in their ACF `meta`, and the site's own `admin-ajax.php` actions, which
 * carry the match documents, the table and the player leaderboards. The AJAX routes answer
 * `Content-Type: text/html` with a JSON body, so the parser is chosen by endpoint.
 */

// ---- /wp-json/wp/v2/ottelut -----------------------------------------------------------

/** One match post. `_fields` trims this to the id bridge, so the SEO meta never arrives. */
@Serializable
public data class FlWpMatch(
    val id: Int = 0,
    val slug: String? = null,
    val modified: String? = null,
    val meta: FlWpMatchMeta = FlWpMatchMeta(),
)

@Serializable
public data class FlWpMatchMeta(
    @SerialName("_torneopal_id") val torneopalId: String? = null,
    /** ISO timestamp carrying the Helsinki offset. */
    @SerialName("_ottelu_aika") val startTime: String? = null,
    @SerialName("_kotijoukkue_torneopal_id") val homeSeasonTeamId: String? = null,
    @SerialName("_kotijoukkue_wp_id") val homeWpId: Int? = null,
    @SerialName("_vierasjoukkue_torneopal_id") val awaySeasonTeamId: String? = null,
    @SerialName("_vierasjoukkue_wp_id") val awayWpId: Int? = null,
    /** Written after the match; zero while it is unplayed, and for hours after a late finish. */
    @SerialName("_kotimaalit") val homeGoals: Int? = null,
    @SerialName("_vierasmaalit") val awayGoals: Int? = null,
    /** `2026-2027`: also the `season` parameter of every scoreboard action. */
    @SerialName("_kausi") val season: String? = null,
    /** `Miehet` / `Naiset`. */
    @SerialName("_sarja") val series: String? = null,
    @SerialName("_ryhma_nimi") val group: String? = null,
    @SerialName("_kierros_nimi") val round: String? = null,
    @SerialName("_yleisomaara") val attendance: Int? = null,
)

// ---- /wp-json/wp/v2/joukkueet ----------------------------------------------------------

/** A season team, and the only route that ties its TorneoPal season id to the stable club id. */
@Serializable
public data class FlWpTeam(
    val id: Int = 0,
    val slug: String? = null,
    val link: String? = null,
    val title: FlRendered? = null,
    val meta: FlWpTeamMeta = FlWpTeamMeta(),
)

@Serializable
public data class FlRendered(val rendered: String = "")

@Serializable
public data class FlWpTeamMeta(
    /** The season-team id the match documents use. */
    @SerialName("_torneopal_id") val seasonTeamId: String? = null,
    /** The club id that survives a season change; this is OpenScore's team id. */
    @SerialName("_liittyva_seura_torneopal_id") val clubId: String? = null,
    @SerialName("_liittyva_seura_wp_id") val clubWpId: Int? = null,
)

// ---- admin-ajax.php?action=match_live_data ---------------------------------------------

/** The compact card the site polls every 15 s: state, score and the venue, nothing heavier. */
@Serializable
public data class FlSummary(
    @SerialName("torneopal_id") val id: String = "",
    @SerialName("match_date") val date: String? = null,
    @SerialName("match_datetime") val startTime: String? = null,
    /** Minutes played, while a match is under way. */
    @SerialName("live_minutes") val liveMinutes: Int? = null,
    /** Fixture | Live | Break | Penalties | Played. */
    val status: String? = null,
    @SerialName("round_name") val round: String? = null,
    @SerialName("venue_name") val venue: String? = null,
    val attendance: Int? = null,
    /** An integer once there is a score, the empty string before - the site's own guard. */
    @SerialName("home_goals") val homeGoals: JsonElement? = null,
    @SerialName("away_goals") val awayGoals: JsonElement? = null,
    @SerialName("home_team") val homeTeam: FlNamedTeam? = null,
    @SerialName("away_team") val awayTeam: FlNamedTeam? = null,
    @SerialName("winner_team") val winnerTeam: FlNamedTeam? = null,
)

@Serializable
public data class FlNamedTeam(@SerialName("torneopal_id") val id: String = "", val name: String = "")

// ---- admin-ajax.php?action=match_teams -------------------------------------------------

/** Despite the action name this is the whole match: identity, result, lineups and events. */
@Serializable
public data class FlMatch(
    @SerialName("torneopal_id") val id: String = "",
    @SerialName("wp_post_id") val wpId: Int? = null,
    @SerialName("wp_link") val link: String? = null,
    val status: String? = null,
    @SerialName("match_date") val date: String? = null,
    @SerialName("match_datetime") val startTime: String? = null,
    @SerialName("group_name") val groupName: String? = null,
    @SerialName("round_name") val roundName: String? = null,
    val venue: String? = null,
    val attendance: Int? = null,
    @SerialName("live_minutes") val liveMinutes: Int? = null,
    val referees: List<String> = emptyList(),
    val season: FlSeason? = null,
    @SerialName("home_team") val homeTeam: FlMatchTeam? = null,
    @SerialName("away_team") val awayTeam: FlMatchTeam? = null,
    val result: FlResult? = null,
    val lineups: FlLineups? = null,
    val events: List<FlEvent> = emptyList(),
)

@Serializable
public data class FlSeason(val name: String? = null, @SerialName("torneopal_id") val id: String? = null)

@Serializable
public data class FlMatchTeam(
    /** The season-team id, not the club id. */
    @SerialName("torneopal_id") val id: String = "",
    val name: String = "",
    @SerialName("logo_url") val logo: FlLogo? = null,
    @SerialName("logo_url_dark_bg") val logoDark: FlLogo? = null,
    @SerialName("wp_link") val link: String? = null,
)

@Serializable
public data class FlLogo(val source: String? = null, @SerialName("wp_link") val wpLink: String? = null)

@Serializable
public data class FlResult(
    @SerialName("home_goals") val homeGoals: Int? = null,
    @SerialName("away_goals") val awayGoals: Int? = null,
    /** `p1`, `p2`, `p3` and `overtime`; null before the first period ends. */
    @SerialName("period_scores") val periodScores: Map<String, FlSide>? = null,
)

@Serializable
public data class FlSide(val home: Int = 0, val away: Int = 0)

@Serializable
public data class FlLineups(val home: FlLineupSide? = null, val away: FlLineupSide? = null)

@Serializable
public data class FlLineupSide(
    val players: List<FlLineupPlayer> = emptyList(),
    val coaches: List<FlCoach> = emptyList(),
)

@Serializable
public data class FlLineupPlayer(
    @SerialName("torneopal_id") val id: String = "",
    val name: String = "",
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("shirt_number") val shirtNumber: String? = null,
    /** `MV/1`, `VP/2`, `KH/1` …: position code and line number, separated by a slash. */
    val position: String? = null,
    /** `C`, `A` or empty. */
    val captain: String? = null,
    val start: Boolean = false,
    val goals: Int = 0,
    val assists: Int = 0,
    val points: Int = 0,
    @SerialName("penalty_minutes") val penaltyMinutes: Int = 0,
    val plus: Int = 0,
    val minus: Int = 0,
    val shots: Int = 0,
    val blocks: Int = 0,
    val saves: Int = 0,
    val conceded: Int = 0,
    val fouls: Int = 0,
    val warnings: Int = 0,
)

@Serializable
public data class FlCoach(val name: String = "", val role: String? = null)

/**
 * One row of the low-level feed. [type] classifies the row (`GOAL`, `PENALTY`, `EVENT`) and
 * [code] names it in Finnish (`maali`, `2min`, `laukaus` …); rows that belong to another row
 * (an assist, a save, a block, a plus/minus) point at it with [connectedEventId].
 */
@Serializable
public data class FlEvent(
    @SerialName("torneopal_event_id") val id: String = "",
    /** Cumulative match time as the scoreboard shows it (`45:30`). */
    val time: String? = null,
    /** Elapsed seconds inside [period]. */
    @SerialName("time_sec") val timeSec: Int? = null,
    /** `1`–`3`, `4` for overtime, and pseudo-periods for the match's own start and end rows. */
    val period: String? = null,
    @SerialName("period_display_name") val periodName: String? = null,
    /** GOAL | PENALTY | EVENT. */
    val type: String? = null,
    @SerialName("is_main_event") val mainEvent: Boolean = false,
    @SerialName("event_code") val code: String = "",
    @SerialName("code_en") val codeEn: String? = null,
    @SerialName("description_fi") val descriptionFi: String? = null,
    /** The penalty's infraction code (`MRK`), or a goal's running score. */
    @SerialName("description_raw") val descriptionRaw: String? = null,
    /** The penalty's infraction in full (`Mailarike`). */
    @SerialName("event_description_fi") val eventDescriptionFi: String? = null,
    @SerialName("team_name") val teamName: String? = null,
    /** `koti` (home) | `vieras` (away). */
    @SerialName("home_or_away") val side: String? = null,
    @SerialName("player_id") val playerId: String? = null,
    @SerialName("player_name") val playerName: String? = null,
    @SerialName("shirt_number") val shirtNumber: String? = null,
    @SerialName("player_2_id") val player2Id: String? = null,
    @SerialName("player_2_name") val player2Name: String? = null,
    /** `y,x` in TorneoPal rink units on shot rows; a faceoff spot writes `y,x/zone` instead. */
    val location: String? = null,
    /** Where in the goal a shot went, as `y,x` of the goal frame. */
    val placement: String? = null,
    @SerialName("score_after") val scoreAfter: FlSide? = null,
    @SerialName("penalty_shootout_score_after") val shootoutScoreAfter: FlSide? = null,
    val note: String? = null,
    /** `0` when the row stands alone. */
    @SerialName("connected_event_id") val connectedEventId: String? = null,
)

// ---- admin-ajax.php?action=fliiga_scoreboard_standings ----------------------------------

@Serializable
public data class FlStandingsRow(
    /** The season, `sb2026`; the row's own team ids are below. */
    @SerialName("torneopal_id") val seasonId: String? = null,
    @SerialName("wp_post_id") val wpId: Int? = null,
    val club: FlNamedTeam? = null,
    @SerialName("club_name") val clubName: String? = null,
    @SerialName("home_venue_name") val venue: String? = null,
    val logos: FlLogos? = null,
    @SerialName("season_name") val seasonName: String? = null,
    @SerialName("group_name") val groupName: String? = null,
    val standing: Int = 0,
    val games: Int = 0,
    /** Regulation wins, worth three points. */
    val wins: Int = 0,
    /** Regulation losses. */
    val loses: Int = 0,
    val tied: Int = 0,
    @SerialName("tied_won") val tiedWon: Int = 0,
    @SerialName("overtime_wins") val overtimeWins: Int = 0,
    @SerialName("overtime_losses") val overtimeLosses: Int = 0,
    val points: Int = 0,
    @SerialName("goals_for") val goalsFor: Int = 0,
    @SerialName("goals_against") val goalsAgainst: Int = 0,
    @SerialName("goals_diff") val goalsDiff: Int = 0,
    val shutouts: Int = 0,
    @SerialName("points_per_game") val pointsPerGame: Double? = null,
)

@Serializable
public data class FlLogos(val default: FlLogoPair? = null, @SerialName("dark_bg") val darkBg: FlLogoPair? = null)

@Serializable
public data class FlLogoPair(val source: String? = null, val wp: String? = null)

// ---- admin-ajax.php?action=fliiga_scoreboard_points | _goalkeepers ----------------------

/** A leaderboard row: the squad member plus every season total the site keeps for them. */
@Serializable
public data class FlPlayerRow(
    @SerialName("torneopal_id") val id: String = "",
    @SerialName("wp_post_id") val wpId: Int? = null,
    @SerialName("wp_link") val link: String? = null,
    @SerialName("first_name") val firstName: String = "",
    @SerialName("last_name") val lastName: String = "",
    @SerialName("birth_date") val birthDate: String? = null,
    @SerialName("shirt_number") val shirtNumber: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val gender: String? = null,
    /** `Kenttäpelaaja` (outfield) on the points board, `Maalivahti` on the goalkeepers' one. */
    val role: String? = null,
    @SerialName("team_name") val teamName: String? = null,
    @SerialName("games_played") val gamesPlayed: Int = 0,
    val points: Int = 0,
    val goals: Int = 0,
    val assists: Int = 0,
    val shots: Int = 0,
    @SerialName("plus_minus") val plusMinus: Int = 0,
    val blocks: Int = 0,
    val saves: Int = 0,
    val conceded: Int = 0,
    @SerialName("save_percentage") val savePercentage: String? = null,
    @SerialName("penalty_minutes") val penaltyMinutes: Int = 0,
)
