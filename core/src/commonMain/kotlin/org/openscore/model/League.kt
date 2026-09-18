package org.openscore.model

import kotlinx.datetime.LocalDate

/** The sports OpenScore knows about. Adding one here should be a rare event. */
public enum class Sport { HOCKEY, FOOTBALL, BASEBALL, MOTORSPORT, MMA }

/**
 * A competition served by one [org.openscore.provider.LeagueProvider].
 *
 * [id] is a stable slug (`nhl`, `shl`, `liiga`, …) and is the first half of every
 * (league, id) key in the model. Never assume ids from different leagues are related.
 */
public data class League(
    val id: String,
    val sport: Sport,
    val name: String,
    /** ISO 3166-1 alpha-2 where it makes sense, or a free-form region like "EU". */
    val country: String?,
    val websiteUrl: String? = null,
)

public enum class StageKind { PRESEASON, REGULAR, PLAYOFF, OTHER }

public data class Stage(
    val id: String,
    val kind: StageKind,
    val label: String,
)

public data class Season(
    val leagueId: String,
    /** League-native season id, e.g. `20262027` for the NHL. */
    val id: String,
    /** Human label, e.g. `2026–27`. */
    val label: String,
    val start: LocalDate? = null,
    val end: LocalDate? = null,
    val stages: List<Stage> = emptyList(),
    val current: Boolean = false,
)
