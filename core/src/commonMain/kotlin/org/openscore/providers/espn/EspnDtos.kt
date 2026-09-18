package org.openscore.providers.espn

import kotlinx.serialization.Serializable

/** `site.web.api.espn.com/apis/common/v3/sports/soccer/{league}/teams/{id}/roster?season=` — only the fields read. */
@Serializable
public data class EspnRoster(
    val team: EspnRosterTeam? = null,
    val season: EspnRosterSeason? = null,
    val coach: List<EspnCoach> = emptyList(),
    val positionGroups: List<EspnPositionGroup> = emptyList(),
)

@Serializable
public data class EspnRosterTeam(val id: String? = null, val displayName: String? = null, val abbreviation: String? = null)

@Serializable
public data class EspnRosterSeason(val year: Int? = null, val name: String? = null)

@Serializable
public data class EspnCoach(val id: String? = null, val firstName: String? = null, val lastName: String? = null)

@Serializable
public data class EspnPositionGroup(val position: String? = null, val athletes: List<EspnAthlete> = emptyList())

@Serializable
public data class EspnAthlete(
    val id: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val displayName: String? = null,
    val fullName: String? = null,
    val jersey: String? = null,
    /** `G`, `D`, `M`, `F`. */
    val position: EspnPosition? = null,
    /** `"1995-09-15T07:00Z"` — the date part is the birthday. */
    val displayDOB: String? = null,
    /** Inches. */
    val height: Double? = null,
    /** Pounds. */
    val weight: Double? = null,
    val citizenship: String? = null,
    val birthPlace: EspnBirthPlace? = null,
    val headshot: EspnHeadshot? = null,
)

@Serializable
public data class EspnPosition(val abbreviation: String? = null, val name: String? = null)

@Serializable
public data class EspnBirthPlace(val country: String? = null)

@Serializable
public data class EspnHeadshot(val href: String? = null)
