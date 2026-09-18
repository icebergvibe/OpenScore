package org.openscore.model

import kotlinx.datetime.LocalDate
import kotlin.time.Duration
import kotlin.time.Instant

public enum class GameState {
    /** On the schedule, nothing has happened yet. */
    SCHEDULED,
    /** Lineups posted / warm-ups; the feed has switched to its live shape but play has not started. */
    PRE_GAME,
    LIVE,
    /** Between periods/halves. Only leagues whose feeds expose a break state produce this; others stay [LIVE]. */
    INTERMISSION,
    FINAL,
    POSTPONED,
    SUSPENDED,
    CANCELLED,
    UNKNOWN;

    /** True while the game is in progress, including breaks. */
    public val isLive: Boolean get() = this == LIVE || this == INTERMISSION
    public val isFinished: Boolean get() = this == FINAL
    /** True once play is under way or over: anything but a game still waiting for kick-off. */
    public val hasStarted: Boolean get() = isLive || isFinished
    /** True once no further live update can come (finished, cancelled or postponed); live polling stops here. */
    public val isTerminal: Boolean get() = this == FINAL || this == CANCELLED || this == POSTPONED
}

public data class Score(val home: Int, val away: Int)

/** A named pre-game matchup, such as baseball's probable pitchers or hockey's starting goalies. */
public data class GamePreview(
    val label: String,
    val home: PlayerRef? = null,
    val away: PlayerRef? = null,
)

/** A player credited with a result role supplied by the league (winner, save, player of match, …). */
public data class GameCredit(val role: String, val player: PlayerRef)

public enum class PeriodType {
    /** Regulation period / half / inning. */
    REGULATION,
    /** Any extra playing time: hockey OT, football extra time, extra innings. */
    OVERTIME,
    /** Shootout / penalty shootout. */
    SHOOTOUT,
    UNKNOWN,
}

/**
 * One segment of a game. [number] counts from 1 across all segments (NHL: 1–3 REG, 4 OT, 5 SO),
 * [label] is what a scoreboard shows (`1`, `OT`, `2OT`, `SO`, `1H`, `ET1`, `Top 9` …).
 */
public data class Period(
    val number: Int,
    val type: PeriodType,
    val label: String,
)

/**
 * A point in game time. Leagues count differently (NHL counts down, Liiga counts up,
 * football counts up in minutes) so both directions are optional; at least one is set
 * when a provider reports a clock at all.
 */
public data class GameTime(
    val period: Period,
    val elapsed: Duration? = null,
    val remaining: Duration? = null,
    /** Display form in the sport's convention (`12:34`, `67'`, `90'+4`), when the provider has one. */
    val label: String? = null,
)

/**
 * Live clock. Absent on leagues that publish no clock (CHL, KHL, MLB).
 * [running] is null when the feed does not say (Liiga).
 */
public data class Clock(
    val time: GameTime,
    val running: Boolean? = null,
) {
    val period: Period get() = time.period
}

/** How a finished game was decided. */
public enum class GameEnding { REGULATION, OVERTIME, SHOOTOUT }

/**
 * Sport-specific live state that a clock cannot express. Baseball is the first user
 * ([org.openscore.model.baseball.BaseballSituation]: inning half, outs, count, runners);
 * sports with a real clock leave [Game.situation] null.
 */
public interface GameSituation

public data class PeriodScore(
    val period: Period,
    val home: Int,
    val away: Int,
)

public data class Game(
    val leagueId: String,
    /** League-native game id as a string (`2026020001`, a UUID, …). */
    val id: String,
    val seasonId: String? = null,
    val stage: StageKind? = null,
    /** Competition name when the provider serves several (cup rounds, multi-tier feeds like Fogis). */
    val competition: String? = null,
    /** Scheduled start, UTC. */
    val startTime: Instant,
    /** Official calendar date in the league's schedule, when supplied by the provider. */
    val scheduleDate: LocalDate? = null,
    val venue: String? = null,
    val home: TeamRef,
    val away: TeamRef,
    val state: GameState,
    /** Current or final score. Null before the game and where the feed has none. */
    val score: Score? = null,
    val clock: Clock? = null,
    /** Sport-specific live state (baseball: inning half, outs, count, runners). Null where a clock says it all. */
    val situation: GameSituation? = null,
    /** Per-segment scores in [Period.number] order. Empty until play starts. */
    val periodScores: List<PeriodScore> = emptyList(),
    /** Set once [state] is [GameState.FINAL], if the feed says how it ended. */
    val ending: GameEnding? = null,
    /** Optional pre-game matchup information when the upstream publishes named participants. */
    val preview: GamePreview? = null,
    /** Result credits from the upstream, in its supplied role terminology. */
    val credits: List<GameCredit> = emptyList(),
    /**
     * Events, when the call that produced this game included them; `null` means "not loaded",
     * an empty list means "loaded, none yet".
     */
    val events: List<GameEvent>? = null,
    /**
     * Per-team match statistics keyed by a stat name (`possession`, `shots`, `shotsOnTarget`,
     * `corners`, `fouls`, `xg`, `offsides` … documented per provider). Values are strings so
     * percentages, counts and decimals share one shape.
     */
    val stats: Map<String, StatPair> = emptyMap(),
    /** The provider's raw status strings, for debugging and for states the enum cannot express. */
    val rawState: String? = null,
    /**
     * True while the league has fixed the day but not yet the kick-off time; [startTime] then
     * stands at the start of that day (in the league's zone) and should be shown as a date only.
     */
    val startTimeTbd: Boolean = false,
)

/** One statistic for both sides. */
public data class StatPair(val home: String, val away: String)
