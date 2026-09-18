package org.openscore.model

/**
 * Marker for event-type enums. Each sport defines its own enum implementing this
 * (see [org.openscore.model.hockey.HockeyEventType]); apps that only need a generic
 * timeline can switch on [isGoal] / [key].
 */
public interface EventType {
    /** Stable, lower-kebab-case key (`goal`, `penalty`, `period-start` …). */
    public val key: String
    /** True for anything that changes the score in regulation/extra time (own goals and penalties included). */
    public val isGoal: Boolean get() = key == "goal"
}

/** Sport-specific payload of a [GameEvent]. Each sport contributes its own implementations. */
public interface EventDetails

/** One attempt in a shootout (hockey) or penalty shoot-out (football). */
public data class ShootoutAttemptDetails(
    val shooter: PlayerRef?,
    val goalie: PlayerRef?,
    val scored: Boolean,
    val shotType: String? = null,
) : EventDetails

/** Position on the playing surface in the league's own coordinate system; see the league README. */
public data class Coordinates(val x: Double, val y: Double)

public data class GameEvent(
    /** Unique within the game. */
    val id: String,
    val type: EventType,
    /** The provider's own type string, kept for events that map to a generic `OTHER`. */
    val rawType: String,
    val time: GameTime,
    /** Team the event is attributed to, if any. */
    val team: TeamRef? = null,
    /** Players involved, primary actor first (scorer, penalised player, shooter, faceoff winner …). */
    val players: List<PlayerRef> = emptyList(),
    /** Score after the event, when the feed provides it (typically only on goals). */
    val score: Score? = null,
    val coordinates: Coordinates? = null,
    val description: String? = null,
    val details: EventDetails? = null,
    /** Provider ordering key, useful when several events share the same game time. */
    val sortOrder: Int? = null,
) {
    val period: Period get() = time.period
}
