@file:Suppress("PropertyName")

package org.openscore.providers.khl

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * DTOs for khl.api.webcaster.pro/api/khl_mobile — see apis/hockey/khl/README.md.
 * Video fields are deliberately omitted.
 */

@Serializable
public data class KhlError(val code: Int? = null, val message: String? = null)

@Serializable
public data class KhlErrorResponse(val error: KhlError? = null)

@Serializable
public data class KhlEventWrapper(val event: KhlEvent)

@Serializable
public data class KhlTeam(
    val id: Int,
    val khl_id: Int? = null,
    val name: String = "",
    val location: String? = null,
    val image: String? = null,
    val division: String? = null,
    val division_key: String? = null,
    val conference: String? = null,
    val conference_key: String? = null,
    // detail-only
    val shots: Int? = null,
    val start_fives: List<KhlRosterPlayer> = emptyList(),
    val players: List<KhlRosterPlayer> = emptyList(),
)

@Serializable
public data class KhlTeamWrapper(val team: KhlTeam)

@Serializable
public data class KhlRosterPlayer(
    val id: Int,
    val khl_id: Int? = null,
    val shirt_number: Int? = null,
    val name: String = "",
    /** forward | defensemen | goaltender */
    val role_key: String? = null,
    val image: String? = null,
)

@Serializable
public data class KhlScores(
    val first_period: String? = null,
    val second_period: String? = null,
    val third_period: String? = null,
    val overtime: String? = null,
    val bullitt: String? = null,
)

@Serializable
public data class KhlArena(val id: Int? = null, val name: String? = null, val city: String? = null)

/** Game summary (`events_v2`) and detail (`event_v2`) share this shape; detail fills the extra lists. */
@Serializable
public data class KhlEvent(
    val id: Long,
    val khl_id: Long? = null,
    val stage_id: Int? = null,
    val stage_name: String? = null,
    val season: String? = null,
    /** regular | playoff */
    val stage_type: String? = null,
    /** not_yet_started | finished (| in_progress) */
    val game_state_key: String = "",
    /** null before, -1 when finished, 1–5 live. */
    val period: Int? = null,
    val name: String? = null,
    val location: String? = null,
    /** Milliseconds. */
    val start_at: Long,
    val team_a: KhlTeam,
    val team_b: KhlTeam,
    /** `"5:1"` */
    val score: String? = null,
    val scores: KhlScores? = null,
    val arena: KhlArena? = null,
    val goals: List<KhlGoal> = emptyList(),
    val violations: List<KhlViolation> = emptyList(),
    val text_events: List<KhlTextEvent> = emptyList(),
)

@Serializable
public data class KhlPerson(
    val shirt_number: Int? = null,
    val name: String = "",
    val team_id: Int? = null,
)

@Serializable
public data class KhlGoal(
    /** Seconds from game start. */
    val time: Int,
    /** Score after the goal, `"2:1"`. */
    val score: String? = null,
    /** null for shootout goals. */
    val period: Int? = null,
    val status: String? = null,
    /** ES | PP | SH | EN | PS | SO … */
    val status_abbr: String? = null,
    val author: KhlPerson? = null,
    val assistants: List<KhlPerson> = emptyList(),
)

@Serializable
public data class KhlViolation(
    val time: Int,
    val period: Int? = null,
    val penalty_time: Int? = null,
    val penalty_reason: String? = null,
    val violator: KhlPerson? = null,
)

@Serializable
public data class KhlTextEvent(
    val seconds: Int? = null,
    /** state | goal | violation | replace | bullet | info */
    val type: String,
    val period: Int? = null,
    val text: String = "",
    val score: String? = null,
)

// ---- data.json ------------------------------------------------------------------------

@Serializable
public data class KhlData(
    val current_stage_id: Int? = null,
    val stages_v2: List<KhlStage> = emptyList(),
    val teams: List<KhlTeam> = emptyList(),
)

@Serializable
public data class KhlStage(
    val id: Int,
    val title: String? = null,
    /** regular | playoff */
    val type: String? = null,
    val season: String? = null,
)

// ---- tables_v2.json --------------------------------------------------------------------

@Serializable
public data class KhlSeasonTables(val season: String? = null, val stages: List<KhlStageTables> = emptyList())

@Serializable
public data class KhlStageTables(
    val id: Int,
    val title: String? = null,
    val type: String? = null,
    val regular: List<KhlTableRow>? = null,
    /** Playoff "tables" are bracket pairings, a different shape; not mapped. */
    val playoff: JsonElement? = null,
)

/** All counts are strings in the feed. */
@Serializable
public data class KhlTableRow(
    val id: Int,
    val name: String = "",
    val location: String? = null,
    val image: String? = null,
    val division: String? = null,
    val division_key: String? = null,
    val conference: String? = null,
    val conference_key: String? = null,
    val gp: String = "0",
    val w: String = "0",
    val otw: String = "0",
    val sow: String = "0",
    val sol: String = "0",
    val otl: String = "0",
    val l: String = "0",
    val pts: String = "0",
    val gf: String = "0",
    val ga: String = "0",
)
