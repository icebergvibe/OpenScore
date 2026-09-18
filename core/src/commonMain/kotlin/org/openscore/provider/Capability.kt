package org.openscore.provider

/**
 * What a [LeagueProvider] can actually deliver. Apps must check these instead of catching
 * [UnsupportedCapabilityException]; the set is fixed per league and documented in the
 * league README under "Core model mapping".
 */
public enum class Capability {
    GAMES_BY_DATE,
    GAME,
    EVENTS,
    LINEUPS,
    STANDINGS,
    TEAM,
    TEAM_SCHEDULE,
    TEAM_STATS,
    ROSTER,
    PLAYER,
    /** [LeagueProvider.live] delivers updates (by polling unless [LIVE_PUSH] is also set). */
    LIVE_UPDATES,
    /** Updates arrive by server push (SSE/MQTT) rather than polling. */
    LIVE_PUSH,
    /** Games carry a [org.openscore.model.Clock] while live. */
    CLOCK,
    /** The clock says whether it is running. */
    CLOCK_RUNNING_FLAG,
    /** Intermissions are reported as [org.openscore.model.GameState.INTERMISSION]. */
    INTERMISSION_STATE,
    PERIOD_SCORES,
    EVENT_COORDINATES,
    /** Lineups have real line/pairing structure, not just positional groups. */
    LINE_GROUPS,
}

public class UnsupportedCapabilityException(
    public val leagueId: String,
    public val capability: Capability,
) : UnsupportedOperationException("League '$leagueId' does not support $capability")
