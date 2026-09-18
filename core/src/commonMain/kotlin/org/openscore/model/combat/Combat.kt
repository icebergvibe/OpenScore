package org.openscore.model.combat

import org.openscore.model.EventType
import org.openscore.model.GameSituation
import org.openscore.model.TeamRef

/**
 * Combat sports (MMA first). A fight is a [org.openscore.model.Game] between two fighters —
 * the red corner as `home`, the blue corner as `away` — whose rounds are its periods. There
 * is no running score: what a scoreboard shows instead lives in [FightSituation].
 */
public enum class CombatEventType(override val key: String) : EventType {
    KNOCKDOWN("knockdown"),
    TAKEDOWN("takedown"),
    TAKEDOWN_ATTEMPT("takedown-attempt"),
    SUBMISSION_ATTEMPT("submission-attempt"),
    /** The bottom fighter comes out on top. */
    REVERSAL("reversal"),
    ROUND_START("period-start"),
    ROUND_END("period-end"),
    /** The referee stops the clock (foul, doctor); [org.openscore.model.GameEvent.description] says why. */
    PAUSE("pause"),
    RESUME("resume"),
    WALKOUT("walkout"),
    FIGHT_START("game-start"),
    FIGHT_END("game-end"),
    /** A winner named — unofficially by the tracker, then officially. */
    RESULT("result"),
    OTHER("other"),
}

public enum class FightMethod { KO_TKO, SUBMISSION, DECISION, NO_CONTEST, OVERTURNED, OTHER }

public enum class FightOutcome { WIN, LOSS, DRAW, NO_CONTEST }

/** One judge's card for the whole fight, from the home (red) corner's side. */
public data class Scorecard(
    val judge: String,
    val home: Int,
    val away: Int,
)

/** How a fight ended and who won — MMA's result line (`Van def. Pantoja by KO/TKO, R2 3:14`). */
public data class FightResult(
    /** Null for a draw or a no contest. */
    val winner: TeamRef?,
    val method: FightMethod,
    /** The feed's own wording (`Decision - Split`, `TKO - Doctor's Stoppage`). */
    val methodLabel: String,
    val round: Int?,
    /** Time into [round] when it ended, `m:ss`; a decision ends at the full round. */
    val time: String?,
    /** What finished it: the submission, or the strike, target and position. */
    val detail: String? = null,
    val notes: String? = null,
    val homeOutcome: FightOutcome? = null,
    val awayOutcome: FightOutcome? = null,
    val scorecards: List<Scorecard> = emptyList(),
    val fightOfTheNight: Boolean = false,
)

/**
 * What a fight is, before, during and after: the bout's format and, once decided, its result.
 * Present on every fight; the clock (round and time) is the ordinary [org.openscore.model.Clock].
 */
public data class FightSituation(
    val scheduledRounds: Int,
    /** Round lengths in minutes, first round first; empty when the feed gives only a count. */
    val roundMinutes: List<Int> = emptyList(),
    /** `Flyweight`, `Women's Bantamweight`, `Catch Weight (130 lb)`. */
    val weightClass: String? = null,
    /** `UFC Flyweight Title` when a belt is on the line. */
    val title: String? = null,
    /** Where the bout sits on the card (`Main`, `Prelims1`), as the feed names it. */
    val cardSegment: String? = null,
    /** 1 for the main event; higher numbers fight earlier. */
    val cardPosition: Int? = null,
    val result: FightResult? = null,
) : GameSituation
