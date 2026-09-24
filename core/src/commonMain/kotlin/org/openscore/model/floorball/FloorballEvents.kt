package org.openscore.model.floorball

import org.openscore.model.EventType

/**
 * Floorball's event vocabulary. Three periods, a goal with assists, timed penalties and a
 * goalkeeper make it the same shape as hockey, with its own low-level rows: a feed reports
 * every shot, save, block, faceoff and line change, not only what a timeline shows.
 *
 * The keys deliberately match [org.openscore.model.hockey.HockeyEventType] where the two
 * sports mean the same thing, so a consumer that already renders a goal or a penalty renders
 * a floorball one without a second branch.
 */
public enum class FloorballEventType(override val key: String) : EventType {
    GOAL("goal"),
    PENALTY("penalty"),
    SHOT("shot"),
    MISSED_SHOT("missed-shot"),
    BLOCKED_SHOT("blocked-shot"),
    SAVE("save"),
    FACEOFF("faceoff"),
    /** A line change: floorball feeds record every one, including the twenty that register the squad. */
    LINE_CHANGE("line-change"),
    GOALIE_CHANGE("goalie-change"),
    TIMEOUT("timeout"),
    PERIOD_START("period-start"),
    PERIOD_END("period-end"),
    GAME_START("game-start"),
    GAME_END("game-end"),
    SHOOTOUT_ATTEMPT("shootout-attempt"),
    OTHER("other"),
}

/*
 * A floorball goal, penalty, shot or faceoff carries exactly the fields hockey's does
 * (scorer and assists, infraction and minutes, shooter and outcome), so the rink sports
 * share one set of details rather than two identical ones: a mapper here still reads as
 * floorball, and an app keeps one `when` over the event type.
 */

public typealias GoalDetails = org.openscore.model.hockey.GoalDetails
public typealias PenaltyDetails = org.openscore.model.hockey.PenaltyDetails
public typealias ShotDetails = org.openscore.model.hockey.ShotDetails
public typealias ShotOutcome = org.openscore.model.hockey.ShotOutcome
public typealias FaceoffDetails = org.openscore.model.hockey.FaceoffDetails
public typealias Strength = org.openscore.model.hockey.Strength
public typealias ShootoutAttemptDetails = org.openscore.model.ShootoutAttemptDetails
