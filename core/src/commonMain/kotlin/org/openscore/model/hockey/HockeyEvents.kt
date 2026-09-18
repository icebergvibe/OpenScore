package org.openscore.model.hockey

import org.openscore.model.EventDetails
import org.openscore.model.EventType
import org.openscore.model.PlayerRef

public enum class HockeyEventType(override val key: String) : EventType {
    GOAL("goal"),
    PENALTY("penalty"),
    DELAYED_PENALTY("delayed-penalty"),
    SHOT("shot"),
    MISSED_SHOT("missed-shot"),
    BLOCKED_SHOT("blocked-shot"),
    HIT("hit"),
    FACEOFF("faceoff"),
    GIVEAWAY("giveaway"),
    TAKEAWAY("takeaway"),
    STOPPAGE("stoppage"),
    PERIOD_START("period-start"),
    PERIOD_END("period-end"),
    GAME_END("game-end"),
    GOALIE_CHANGE("goalie-change"),
    SHOOTOUT_ATTEMPT("shootout-attempt"),
    OTHER("other"),
}

/** Manpower situation for a goal, where the league provides it. */
public enum class Strength {
    /** Even strength. */
    EV,
    /** Power play. */
    PP,
    /** Short-handed. */
    SH,
    /** Empty net (opposing goalie pulled). */
    EN,
    /** Penalty shot. */
    PS,
}

public data class GoalDetails(
    val scorer: PlayerRef?,
    val assists: List<PlayerRef> = emptyList(),
    val strength: Strength? = null,
    val emptyNet: Boolean? = null,
    val shotType: String? = null,
    val goalie: PlayerRef? = null,
    /** Scorer's season total after this goal, when the feed says. */
    val scorerSeasonTotal: Int? = null,
) : EventDetails

public data class PenaltyDetails(
    val player: PlayerRef?,
    val drawnBy: PlayerRef? = null,
    val servedBy: PlayerRef? = null,
    val minutes: Int? = null,
    /** League-native infraction key/name (`hooking`, `Tripping` …). */
    val infraction: String? = null,
    /** League-native severity (`MIN`, `MAJ`, `MIS`, `GAME` …). */
    val severity: String? = null,
) : EventDetails

public enum class ShotOutcome { ON_GOAL, MISSED, BLOCKED }

public data class ShotDetails(
    val shooter: PlayerRef?,
    val outcome: ShotOutcome,
    val goalie: PlayerRef? = null,
    val blockedBy: PlayerRef? = null,
    val shotType: String? = null,
    /** Why a missed shot missed (`wide-right`, `over-net` …). */
    val reason: String? = null,
) : EventDetails

public data class FaceoffDetails(
    val winner: PlayerRef?,
    val loser: PlayerRef?,
) : EventDetails

public data class HitDetails(
    val hitter: PlayerRef?,
    val hittee: PlayerRef?,
) : EventDetails

public data class StoppageDetails(val reason: String?) : EventDetails

public typealias ShootoutAttemptDetails = org.openscore.model.ShootoutAttemptDetails
