package org.openscore.clubs

import org.openscore.model.Sport

/**
 * One club as OpenScore knows it, across every league it plays in.
 *
 * [id] is an OpenScore-owned slug (`manchester-united`, `hammarby`, `frolunda`) that never
 * changes once published: apps key favourites and notifications on it. It is lowercase ASCII
 * with hyphens; readability is a courtesy, nothing is derived from it.
 *
 * [ids] maps an id namespace to the club's native id there. A namespace is normally a league
 * id (`premier-league`, `shl`); leagues that share one team-id space share a namespace
 * (`ucl` covers `uel` and `uecl`, `allsvenskan` covers `superettan`), see [Clubs.namespace].
 */
public data class Club(
    val id: String,
    val name: String,
    val sport: Sport,
    /** FIFA/IOC-style trigram (`ENG`, `SWE`, `GER`), the convention every feed uses. */
    val country: String?,
    val ids: Map<String, String>,
)
