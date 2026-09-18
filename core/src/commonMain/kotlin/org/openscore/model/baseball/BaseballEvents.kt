package org.openscore.model.baseball

import org.openscore.model.EventDetails
import org.openscore.model.EventType
import org.openscore.model.GameSituation
import org.openscore.model.PlayerRef

/**
 * Baseball events come at two granularities: one per plate appearance (the play's result)
 * and, in between, base-running plays and substitutions. Pitches are not events; the
 * pitch count of a plate appearance is in [PlateAppearanceDetails.pitches].
 *
 * No baseball type is a "goal": runs score on many kinds of play, so scoring plays are
 * flagged by [PlateAppearanceDetails.scoringPlay] and carried by [org.openscore.model.GameEvent.score].
 */
public enum class BaseballEventType(override val key: String) : EventType {
    // plate-appearance results
    SINGLE("single"),
    DOUBLE("double"),
    TRIPLE("triple"),
    HOME_RUN("home-run"),
    WALK("walk"),
    INTENTIONAL_WALK("intentional-walk"),
    HIT_BY_PITCH("hit-by-pitch"),
    STRIKEOUT("strikeout"),
    /** Ground-out, fly-out, line-out, pop-out. */
    FIELD_OUT("field-out"),
    FORCE_OUT("force-out"),
    FIELDERS_CHOICE("fielders-choice"),
    DOUBLE_PLAY("double-play"),
    TRIPLE_PLAY("triple-play"),
    SACRIFICE_FLY("sacrifice-fly"),
    SACRIFICE_BUNT("sacrifice-bunt"),
    /** Batter reaches on a fielding error. */
    ERROR("error"),
    /** Catcher's, batter's or fan interference. */
    INTERFERENCE("interference"),
    // base running (between plate appearances)
    STOLEN_BASE("stolen-base"),
    CAUGHT_STEALING("caught-stealing"),
    PICKOFF("pickoff"),
    WILD_PITCH("wild-pitch"),
    PASSED_BALL("passed-ball"),
    BALK("balk"),
    /** A runner put out on the bases outside a plate appearance result. */
    RUNNER_OUT("runner-out"),
    // personnel
    SUBSTITUTION("substitution"),
    EJECTION("ejection"),
    OTHER("other"),
}

public enum class InningHalf {
    TOP,
    /** Break between the top and the bottom of an inning. */
    MIDDLE,
    BOTTOM,
    /** Break after the bottom half. */
    END,
}

/** Bases as the feeds name them: `1B`, `2B`, `3B`, and `score` for home plate. */
public data class RunnerMovement(
    val runner: PlayerRef?,
    /** Base the runner started from, or null for the batter. */
    val from: String?,
    /** Base reached (`1B`, `2B`, `3B`, `score`), or null when put out. */
    val to: String?,
    val out: Boolean,
) {
    val scored: Boolean get() = to == "score"
}

/** Statcast-style batted-ball measurements where the league tracks them. */
public data class BattedBall(
    val launchSpeedMph: Double? = null,
    val launchAngle: Double? = null,
    val distanceFt: Double? = null,
    /** `ground_ball`, `line_drive`, `fly_ball`, `popup`, `bunt_*` … */
    val trajectory: String? = null,
)

public data class PlateAppearanceDetails(
    val batter: PlayerRef?,
    val pitcher: PlayerRef?,
    val rbi: Int,
    /** True when the batter was put out (runners may still have advanced or scored). */
    val out: Boolean,
    /** Outs in the half-inning after this play. */
    val outsAfter: Int,
    val scoringPlay: Boolean,
    val pitches: Int,
    val runners: List<RunnerMovement> = emptyList(),
    val battedBall: BattedBall? = null,
) : EventDetails

/** Stolen base, caught stealing, pickoff, wild pitch, passed ball, balk, runner out. */
public data class BaseRunningDetails(
    val runner: PlayerRef?,
    val from: String? = null,
    val to: String? = null,
    val out: Boolean = false,
) : EventDetails

public data class BaseballSubstitutionDetails(
    val playerIn: PlayerRef?,
    /** Name of the player leaving, when the feed states it (MLB only says so in prose). */
    val replaces: String? = null,
    /** Position taken (`P`, `PH`, `PR`, `C` …). */
    val position: String? = null,
    /** `pitching`, `offensive`, `defensive`, `switch` … as the league classifies it. */
    val kind: String? = null,
) : EventDetails

/**
 * The state a baseball scoreboard shows while a game is live: which half of which inning,
 * outs, the count, who is on base and who is up. Present on [org.openscore.model.Game.situation]
 * while the game is live or between half-innings.
 */
public data class BaseballSituation(
    val inning: Int,
    val half: InningHalf,
    val outs: Int,
    val balls: Int,
    val strikes: Int,
    val onFirst: PlayerRef? = null,
    val onSecond: PlayerRef? = null,
    val onThird: PlayerRef? = null,
    val batter: PlayerRef? = null,
    val pitcher: PlayerRef? = null,
    val onDeck: PlayerRef? = null,
) : GameSituation {
    val runnersOn: Int get() = listOf(onFirst, onSecond, onThird).count { it != null }
}
