package org.openscore.app.ui.feed

import org.openscore.model.Sport

/** The three feeds the bottom bar switches between; Settings is not a feed. */
enum class FeedKind { GAMES, LIVE, FAVORITES }

/**
 * What a feed shows, resolved down to the leagues that will be asked for each day. Two specs
 * that fetch the same leagues share held days, so flipping between All games and Live costs
 * nothing extra.
 */
data class FeedSpec(
    val kind: FeedKind,
    /** The rail's sport, or null for Favorites, which spans every sport. */
    val sport: Sport?,
    val leagueIds: List<String>,
) {
    /** Held days are keyed on what was fetched, not on how it is filtered. */
    val fetchKey: List<String> get() = leagueIds
}
