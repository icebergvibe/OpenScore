package org.openscore.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** The sports OpenScore knows about. Adding one here should be a rare event. */
public enum class Sport { HOCKEY, FLOORBALL, FOOTBALL, BASEBALL, MOTORSPORT, MMA }

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
    /**
     * The zone this league keeps its calendar in: the one
     * [org.openscore.provider.LeagueProvider.gamesOn] takes its date in, and the one a game's
     * day has to be worked out in to ask for that game again. The NHL files an eight o'clock
     * game on the US Eastern date it starts on, whatever date that is where the reader is
     * sitting, and a reader asking their own date for it would be asking for the wrong day.
     *
     * Deliberately required: a league given the wrong zone here, or quietly defaulted to one,
     * shows its games under a day its own provider would never return.
     */
    val zone: TimeZone,
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
