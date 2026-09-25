package org.openscore.app.data

import org.openscore.cache.CachedDayListingProvider
import org.openscore.model.Game
import org.openscore.model.GameState
import org.openscore.model.combat.FightSituation
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * A listing is asked again a little before kick-off, so the first minute is not missed. Taken
 * from the core's day cache rather than repeated here: the two decide the same thing from either
 * side, and the cache serving a stored day the poll thinks is due would freeze a score.
 */
private val KICKOFF_LEAD = CachedDayListingProvider.KICKOFF_LEAD

/**
 * How long after kick-off a game the listing still calls scheduled is worth asking about. Past
 * this it is not going to start — postponed without the feed saying so, or a league whose day
 * listing lags — and a request a minute all day buys nothing.
 */
private val KICKOFF_GRACE = 3.hours

/**
 * A fight's start time is its card segment's, and the main event walks out hours after the
 * segment opens; a fight not yet under way is waiting, not lost, for this long.
 */
private val WALKOUT_GRACE = 6.hours

/**
 * Whether a held game is worth asking its league's day listing about again: in play, or on the
 * doorstep of kick-off, where that listing is what turns a scheduled row into a live one. The
 * feed and the team page share this rule so a game goes live at the same moment on both.
 * A kick-off the league has not fixed yet (a TBD time standing at midnight) is never due.
 */
fun Game.wantsScorePoll(now: Instant): Boolean = when {
    state.isLive -> true
    state != GameState.SCHEDULED && state != GameState.PRE_GAME -> false
    startTimeTbd -> false
    else -> startTime <= now + KICKOFF_LEAD && startTime > now - (if (situation is FightSituation) WALKOUT_GRACE else KICKOFF_GRACE)
}
