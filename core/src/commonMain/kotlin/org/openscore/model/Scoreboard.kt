package org.openscore.model

/** One score-by-period column, with terminology normalized for the sport. */
public data class ScoreboardPeriod(val label: String, val score: PeriodScore)

/**
 * A consumer-ready score-by-period view. This deliberately lives in core so apps and feed
 * clients do not need league-specific rules for halves, overtime, or shootouts.
 */
public data class ScoreboardPresentation(
    val periods: List<ScoreboardPeriod>,
    val totalLabel: String,
    /** Explains why the match total can differ from a shootout column. */
    val note: String? = null,
)

public fun Game.scoreboardPresentation(sport: Sport): ScoreboardPresentation = ScoreboardPresentation(
    periods = periodScores.map { ScoreboardPeriod(it.period.scoreboardLabel(sport), it) },
    totalLabel = if (sport == Sport.BASEBALL) "R" else "T",
    note = when (ending) {
        GameEnding.SHOOTOUT -> when (sport) {
            Sport.FOOTBALL -> "Penalty shootout shown separately from the match score."
            Sport.HOCKEY -> "Shootout winner shown separately."
            else -> null
        }
        else -> null
    },
)

private fun Period.scoreboardLabel(sport: Sport): String = when (sport) {
    Sport.FOOTBALL -> when (type) {
        PeriodType.REGULATION -> when (number) { 1 -> "1H"; 2 -> "2H"; else -> label }
        PeriodType.OVERTIME -> when (number) { 3 -> "ET1"; 4 -> "ET2"; else -> "${number - 2}ET" }
        PeriodType.SHOOTOUT -> "PENS"
        PeriodType.UNKNOWN -> label
    }
    Sport.HOCKEY -> when (type) {
        PeriodType.REGULATION -> number.toString()
        PeriodType.OVERTIME -> if (number == 4) "OT" else "${number - 3}OT"
        PeriodType.SHOOTOUT -> "SO"
        PeriodType.UNKNOWN -> label
    }
    Sport.BASEBALL, Sport.MOTORSPORT, Sport.MMA -> label
}
