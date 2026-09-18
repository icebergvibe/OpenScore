package org.openscore.app.data

import org.openscore.model.Game
import org.openscore.model.GameState
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** A listing is asked again a little before kick-off, so the first minute is not missed. */
private val KICKOFF_LEAD = 5.minutes

/**
 * How long after kick-off a game the listing still calls scheduled is worth asking about. Past
 * this it is not going to start — postponed without the feed saying so, or a league whose day
 * listing lags — and a request a minute all day buys nothing.
 */
private val KICKOFF_GRACE = 3.hours

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
    else -> startTime <= now + KICKOFF_LEAD && startTime > now - KICKOFF_GRACE
}
