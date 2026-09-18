package org.openscore.model

import org.openscore.clubs.Clubs

/** Enough to draw a team in a list: id, name, short name, logo. */
public data class TeamRef(
    val leagueId: String,
    /** League-native team id. For the NHL this is the abbreviation (`TOR`). */
    val id: String,
    /** Full display name, e.g. `Toronto Maple Leafs`. */
    val name: String,
    /** Short code shown in scoreboards, e.g. `TOR`. May equal [id]. */
    val abbreviation: String? = null,
    val logoUrl: String? = null,
    /**
     * OpenScore's own stable club id (`manchester-united`), the same across every league the
     * club plays in; null when the club is not in [Clubs]. Filled from the crosswalk by
     * default, so key favourites on `clubId ?: "$leagueId/$id"`.
     */
    val clubId: String? = Clubs.clubId(leagueId, id),
)

public data class Team(
    val ref: TeamRef,
    /** Place/city part of the name, e.g. `Toronto`. */
    val placeName: String? = null,
    /** Nickname part of the name, e.g. `Maple Leafs`. */
    val commonName: String? = null,
    val arena: String? = null,
    /** Top-level grouping (conference, or a league's single "group"). */
    val conference: String? = null,
    /** Second-level grouping (division, group stage group …). */
    val division: String? = null,
    val logoDarkUrl: String? = null,
    /** ISO 3166-1 alpha-2 where known; useful in multi-nation competitions (CHL). */
    val country: String? = null,
) {
    val leagueId: String get() = ref.leagueId
    val id: String get() = ref.id
    val name: String get() = ref.name
}
