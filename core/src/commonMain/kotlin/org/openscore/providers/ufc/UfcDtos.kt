@file:Suppress("PropertyName")

package org.openscore.providers.ufc

import kotlinx.serialization.Serializable

/*
 * DTOs for the UFC live-stats API on d29dxerjsp82wz.cloudfront.net — see apis/mma/ufc/README.md.
 * Both routes wrap one object; an unknown id answers 200 with that object empty, so every
 * field of the detail types is optional.
 */

@Serializable
public data class UfcEventResponse(val LiveEventDetail: UfcEvent = UfcEvent())

@Serializable
public data class UfcFightResponse(val LiveFightDetail: UfcFightDetail = UfcFightDetail())

@Serializable
public data class UfcEvent(
    val EventId: Int? = null,
    val Name: String? = null,
    /** UTC, minute precision (`2026-09-19T21:30Z`). */
    val StartTime: String? = null,
    /** The venue's offset as text (`GMT-07:00`). */
    val TimeZone: String? = null,
    /** Upcoming | Final | Canceled (Live expected). */
    val Status: String? = null,
    val LiveEventId: Int? = null,
    val LiveFightId: Int? = null,
    val LiveRoundNumber: Int? = null,
    val LiveRoundElapsedTime: String? = null,
    val Organization: UfcOrganization? = null,
    val Location: UfcLocation? = null,
    val FightCard: List<UfcFight> = emptyList(),
) {
    val isEmpty: Boolean get() = EventId == null
}

@Serializable
public data class UfcOrganization(val OrganizationId: Int? = null, val Name: String? = null)

@Serializable
public data class UfcLocation(
    val City: String? = null,
    val State: String? = null,
    val Country: String? = null,
    val TriCode: String? = null,
    val VenueId: Int? = null,
    val Venue: String? = null,
)

@Serializable
public data class UfcFight(
    val FightId: Int,
    /** 1 = main event; the highest number fights first. */
    val FightOrder: Int? = null,
    /** Upcoming | Final; Live and Over per ufc.com's script. */
    val Status: String? = null,
    val CardSegment: String? = null,
    val CardSegmentStartTime: String? = null,
    val CardSegmentBroadcaster: String? = null,
    val Fighters: List<UfcFighter> = emptyList(),
    val Result: UfcResult? = null,
    val WeightClass: UfcWeightClass? = null,
    val Accolades: List<UfcAccolade> = emptyList(),
    val Referee: UfcReferee? = null,
    val RuleSet: UfcRuleSet? = null,
    val FightNightTracking: List<UfcAction> = emptyList(),
)

/** The fight route: the card's fight plus statistics. */
@Serializable
public data class UfcFightDetail(
    val Event: UfcEvent? = null,
    val FightId: Int? = null,
    val Status: String? = null,
    val OfficialStats: Boolean? = null,
    val Fighters: List<UfcFighter> = emptyList(),
    val Result: UfcResult? = null,
    val FightStats: List<UfcFightStats> = emptyList(),
    val RoundStats: List<UfcRoundStats> = emptyList(),
)

@Serializable
public data class UfcFighter(
    val FighterId: Int,
    val MMAId: Int? = null,
    val Name: UfcName? = null,
    val Born: UfcPlace? = null,
    val FightingOutOf: UfcPlace? = null,
    val Record: UfcRecord? = null,
    val DOB: String? = null,
    val Age: Int? = null,
    val Stance: String? = null,
    val Weight: Double? = null,
    val Height: Double? = null,
    val Reach: Double? = null,
    val UFCLink: String? = null,
    /** Red | Blue */
    val Corner: String? = null,
    val WeighIn: Double? = null,
    val Outcome: UfcOutcome? = null,
    val KOOfTheNight: Boolean = false,
    val SubmissionOfTheNight: Boolean = false,
    val PerformanceOfTheNight: Boolean = false,
)

@Serializable
public data class UfcName(val FirstName: String? = null, val LastName: String? = null, val NickName: String? = null)

@Serializable
public data class UfcPlace(val City: String? = null, val State: String? = null, val Country: String? = null, val TriCode: String? = null)

@Serializable
public data class UfcRecord(val Wins: Int = 0, val Losses: Int = 0, val Draws: Int = 0, val NoContests: Int = 0)

/** 1 Win, 2 Loss, 3 Draw, 4 No Contest; both null before the result. */
@Serializable
public data class UfcOutcome(val OutcomeId: Int? = null, val Outcome: String? = null)

@Serializable
public data class UfcResult(
    val Method: String? = null,
    val EndingRound: Int? = null,
    /** Time into the round, `m:ss`. */
    val EndingTime: String? = null,
    val EndingStrike: String? = null,
    val EndingTarget: String? = null,
    val EndingPosition: String? = null,
    val EndingSubmission: String? = null,
    val EndingNotes: String? = null,
    val FightOfTheNight: Boolean = false,
    val FightScores: List<UfcJudgeScore> = emptyList(),
    /** Fight route only. */
    val RoundScores: List<UfcJudgeRounds> = emptyList(),
)

@Serializable
public data class UfcJudgeScore(
    val JudgeId: Int? = null,
    val JudgeFirstName: String? = null,
    val JudgeLastName: String? = null,
    val Fighters: List<UfcFighterScore> = emptyList(),
)

@Serializable
public data class UfcFighterScore(val FighterId: Int, val Score: Int)

@Serializable
public data class UfcJudgeRounds(
    val JudgeId: Int? = null,
    val JudgeFirstName: String? = null,
    val JudgeLastName: String? = null,
    val Rounds: List<UfcRoundScore> = emptyList(),
)

@Serializable
public data class UfcRoundScore(val RoundNumber: Int, val Fighters: List<UfcFighterScore> = emptyList())

@Serializable
public data class UfcWeightClass(
    val WeightClassId: Int? = null,
    /** Pounds, set only for a catch-weight bout. */
    val CatchWeight: Double? = null,
    val Weight: String? = null,
    val Description: String? = null,
    val Abbreviation: String? = null,
)

@Serializable
public data class UfcAccolade(val Type: String? = null, val Name: String? = null)

@Serializable
public data class UfcReferee(val RefereeId: Int? = null, val FirstName: String? = null, val LastName: String? = null)

@Serializable
public data class UfcRuleSet(val PossibleRounds: Int? = null, val Description: String? = null)

/** One tracked action; `RoundTime` counts down from the round length. */
@Serializable
public data class UfcAction(
    val ActionId: Int,
    val FighterId: Int? = null,
    val Type: String,
    val RoundNumber: Int? = null,
    val RoundTime: String? = null,
    val Timestamp: String? = null,
)

/** Per-fighter totals; the same counters appear per round in [UfcRoundStats]. Only the mapped ones are declared. */
@Serializable
public data class UfcFightStats(
    val FighterId: Int,
    val Knockdowns: Int? = null,
    val TotalStrikesAttempted: Int? = null,
    val TotalStrikesLanded: Int? = null,
    val SigStrikesAttempted: Int? = null,
    val SigStrikesLanded: Int? = null,
    val SigStrikesAccuracy: Double? = null,
    val SigHeadStrikesLanded: Int? = null,
    val SigBodyStrikesLanded: Int? = null,
    val SigLegStrikesLanded: Int? = null,
    val SigDistanceStrikesLanded: Int? = null,
    val SigClinchStrikesLanded: Int? = null,
    val SigGroundStrikesLanded: Int? = null,
    val TakedownsAttempted: Int? = null,
    val TakedownsLanded: Int? = null,
    val TakedownsAccuracy: Double? = null,
    val Reversals: Int? = null,
    val SubmissionsAttempted: Int? = null,
    val ControlTime: String? = null,
)

@Serializable
public data class UfcRoundStats(val FighterId: Int, val Rounds: List<UfcRoundCounters> = emptyList())

@Serializable
public data class UfcRoundCounters(
    val RoundNumber: Int,
    val Knockdowns: Int? = null,
    val SigStrikesAttempted: Int? = null,
    val SigStrikesLanded: Int? = null,
    val TakedownsAttempted: Int? = null,
    val TakedownsLanded: Int? = null,
    val ControlTime: String? = null,
)
