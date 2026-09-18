package org.openscore.model.football

import org.openscore.model.EventDetails
import org.openscore.model.EventType
import org.openscore.model.PlayerRef

public enum class FootballEventType(override val key: String, override val isGoal: Boolean = false) : EventType {
    GOAL("goal", isGoal = true),
    /**
     * [org.openscore.model.GameEvent.team] is the side **credited** with the goal — the scorer's
     * opponents — so the event sits with the goal it adds to on a scoreboard; the scorer in
     * `players`/[FootballGoalDetails.scorer] plays for the other side. Feeds differ (Pulselive,
     * LaLiga and MLS attribute the event to the scorer's team), so every mapper normalises.
     */
    OWN_GOAL("own-goal", isGoal = true),
    PENALTY_GOAL("penalty-goal", isGoal = true),
    PENALTY_MISSED("penalty-missed"),
    /** A goal ruled out (VAR, offside …). */
    GOAL_DISALLOWED("goal-disallowed"),
    YELLOW_CARD("yellow-card"),
    SECOND_YELLOW("second-yellow"),
    RED_CARD("red-card"),
    SUBSTITUTION("substitution"),
    VAR("var"),
    PERIOD_START("period-start"),
    PERIOD_END("period-end"),
    GAME_END("game-end"),
    /** One kick of a penalty shoot-out; details are [org.openscore.model.ShootoutAttemptDetails]. */
    SHOOTOUT_ATTEMPT("shootout-attempt"),
    OTHER("other"),
}

public enum class GoalKind { OPEN_PLAY, PENALTY, OWN_GOAL, FREE_KICK, HEADER, OTHER }

public enum class VarDecision { CONFIRMED, OVERTURNED }

public data class FootballGoalDetails(
    val scorer: PlayerRef?,
    val assist: PlayerRef? = null,
    val kind: GoalKind = GoalKind.OPEN_PLAY,
    val varDecision: VarDecision? = null,
    /** Scorer's season total after this goal, when the feed says. */
    val scorerSeasonTotal: Int? = null,
) : EventDetails

public enum class CardKind { YELLOW, SECOND_YELLOW, RED }

public data class CardDetails(
    val player: PlayerRef?,
    val card: CardKind,
    /** League-native reason text or code, when given. */
    val reason: String? = null,
) : EventDetails

public data class SubstitutionDetails(
    val playerOn: PlayerRef?,
    val playerOff: PlayerRef?,
    /** `tactical`, `injury`, … when the feed says. */
    val reason: String? = null,
) : EventDetails

public data class PenaltyMissDetails(
    val player: PlayerRef?,
    val savedBy: PlayerRef? = null,
    /** `saved`, `missed`, `post` … when the feed says. */
    val outcome: String? = null,
) : EventDetails

public data class DisallowedGoalDetails(
    val player: PlayerRef?,
    val reason: String? = null,
) : EventDetails
