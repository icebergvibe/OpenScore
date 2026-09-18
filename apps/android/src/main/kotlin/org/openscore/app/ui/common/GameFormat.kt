package org.openscore.app.ui.common

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toLocalDateTime
import org.openscore.app.R
import org.openscore.model.Game
import org.openscore.model.GameEnding
import org.openscore.model.GameState
import org.openscore.model.League
import org.openscore.model.PeriodType
import org.openscore.model.Sport
import org.openscore.model.baseball.BaseballSituation
import org.openscore.model.baseball.InningHalf
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.Duration.Companion.minutes

/** How a status pill is coloured. */
enum class StatusTone { SCHEDULED, LIVE, BREAK, DONE, OFF }

data class StatusLabel(val text: String, val tone: StatusTone)

val Sport.displayName: String
    get() = when (this) {
        Sport.HOCKEY -> "Hockey"
        Sport.FOOTBALL -> "Football"
        Sport.BASEBALL -> "Baseball"
        Sport.MOTORSPORT -> "Motorsport"
    }

val Sport.iconRes: Int
    get() = when (this) {
        Sport.HOCKEY -> R.drawable.ic_sport_hockey
        Sport.FOOTBALL -> R.drawable.ic_sport_football
        Sport.BASEBALL -> R.drawable.ic_sport_baseball
        Sport.MOTORSPORT -> R.drawable.ic_sport_motorsport
    }

/** The ball for a goal / run, so a football never sits beside a hockey goal. */
val Sport.goalIcon: String
    get() = when (this) {
        Sport.HOCKEY -> "🏒"
        Sport.FOOTBALL -> "⚽"
        Sport.BASEBALL -> "⚾"
        Sport.MOTORSPORT -> "🏎️"
    }

/** Regional-indicator flag for an ISO 3166-1 alpha-2 code; `EU` renders as the EU flag. */
fun flagEmoji(country: String?): String {
    val code = country?.uppercase()?.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } } ?: return "🌐"
    return code.map { c -> String(Character.toChars(0x1F1E6 + (c - 'A'))) }.joinToString("")
}

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
private val dayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())

/** Kick-off in the phone's own zone. */
fun Game.startTimeLabel(zone: TimeZone = TimeZone.currentSystemDefault()): String = startTime.timeLabel(zone)

/** A wall-clock instant as `HH:mm` in [zone]. */
fun Instant.timeLabel(zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val local = toLocalDateTime(zone)
    return timeFormatter.format(java.time.LocalTime.of(local.hour, local.minute))
}

/** "Today" / "Yesterday" / "Tomorrow", otherwise "Sat, 23 Aug". */
fun dayHeading(date: LocalDate, today: LocalDate): String {
    val diff = date.toEpochDays() - today.toEpochDays()
    return when (diff) {
        0L -> "Today"
        -1L -> "Yesterday"
        1L -> "Tomorrow"
        else -> dayFormatter.format(date.toJavaLocalDate())
    }
}

private fun Duration.mmss(): String {
    val total = inWholeSeconds.coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/** What the clock says, in the sport's own convention: `67'`, `P2 12:34`, `Bot 7`. */
fun Game.clockLabel(sport: Sport): String? {
    val clock = clock ?: return null
    val time = clock.time
    time.label?.let { label ->
        return if (sport == Sport.HOCKEY) "${time.period.label} $label" else label
    }
    return when (sport) {
        Sport.FOOTBALL -> {
            val elapsed = time.elapsed ?: return time.period.label
            val offset = footballOffsetMinutes(time.period.number)
            val nominal = footballLength(time.period.number)
            val minute = elapsed.inWholeMinutes.toInt() + 1
            if (nominal != null && elapsed >= nominal) "${offset + nominal.inWholeMinutes}'+${(elapsed - nominal).inWholeMinutes + 1}" else "${offset + minute}'"
        }
        Sport.HOCKEY -> {
            val shown = time.remaining ?: time.elapsed ?: return time.period.label
            "${time.period.label} ${shown.mmss()}"
        }
        Sport.BASEBALL -> time.period.label
        Sport.MOTORSPORT -> time.period.label
    }
}

internal fun footballOffsetMinutes(period: Int): Int = when (period) { 1 -> 0; 2 -> 45; 3 -> 90; 4 -> 105; else -> 120 }
private fun footballLength(period: Int): Duration? = when (period) { 1, 2 -> 45.minutes; 3, 4 -> 15.minutes; else -> null }

/**
 * How far through the game the clock says we are, or null when there is nothing honest to
 * draw. Football measures against ninety minutes, hockey against sixty; anything in extra
 * time is simply full. Baseball has no clock, and a bar that guesses is worse than none.
 */
fun Game.progress(sport: Sport): Float? {
    if (!state.isLive) return null
    val time = clock?.time ?: return null
    if (time.period.type != PeriodType.REGULATION) return 1f
    return when (sport) {
        Sport.FOOTBALL -> {
            if (state == GameState.INTERMISSION) return 0.5f
            val elapsed = time.elapsed ?: return null
            ((footballOffsetMinutes(time.period.number) + elapsed.inWholeMinutes) / 90f).coerceIn(0f, 1f)
        }
        Sport.HOCKEY -> {
            val inPeriod = time.elapsed ?: time.remaining?.let { 20.minutes - it } ?: return null
            (((time.period.number - 1) * 20 + inPeriod.inWholeMinutes) / 60f).coerceIn(0f, 1f)
        }
        Sport.BASEBALL -> null
        Sport.MOTORSPORT -> null
    }
}

fun Game.statusLabel(sport: Sport): StatusLabel = when (state) {
    GameState.SCHEDULED -> StatusLabel(if (startTimeTbd) "TBD" else startTimeLabel(), StatusTone.SCHEDULED)
    GameState.PRE_GAME -> StatusLabel("Pre-game", StatusTone.BREAK)
    GameState.LIVE -> StatusLabel(clockLabel(sport) ?: "Live", StatusTone.LIVE)
    GameState.INTERMISSION -> StatusLabel(breakLabel(sport), StatusTone.BREAK)
    GameState.FINAL -> StatusLabel(finalLabel(sport), StatusTone.DONE)
    GameState.POSTPONED -> StatusLabel("Postponed", StatusTone.OFF)
    GameState.SUSPENDED -> StatusLabel("Suspended", StatusTone.OFF)
    GameState.CANCELLED -> StatusLabel("Cancelled", StatusTone.OFF)
    GameState.UNKNOWN -> StatusLabel(rawState ?: "—", StatusTone.OFF)
}

private fun Game.breakLabel(sport: Sport): String {
    val period = clock?.time?.period
    return when (sport) {
        Sport.FOOTBALL -> when (period?.number) { null, 1 -> "HT"; 3 -> "ET HT"; else -> "Break" }
        Sport.HOCKEY -> period?.let { "Int ${it.label}" } ?: "Intermission"
        Sport.BASEBALL -> (situation as? BaseballSituation)?.let {
            when (it.half) { InningHalf.MIDDLE -> "Mid ${it.inning}"; InningHalf.END -> "End ${it.inning}"; else -> "Break" }
        } ?: "Break"
        Sport.MOTORSPORT -> "Break"
    }
}

private fun Game.finalLabel(sport: Sport): String = when (sport) {
    Sport.FOOTBALL -> when (ending) { GameEnding.OVERTIME -> "AET"; GameEnding.SHOOTOUT -> "Pens"; else -> "FT" }
    Sport.HOCKEY -> when (ending) { GameEnding.OVERTIME -> "Final/OT"; GameEnding.SHOOTOUT -> "Final/SO"; else -> "Final" }
    Sport.BASEBALL -> if ((periodScores.size) > 9) "Final/${periodScores.size}" else "Final"
    Sport.MOTORSPORT -> "Final"
}

/** `2 - 1`, or `vs` before anything has happened. */
fun Game.scoreLabel(): String {
    val score = score
    return if (score == null || state == GameState.SCHEDULED) "vs" else "${score.home} - ${score.away}"
}

/** The half-time score, once a football match is past its first half. */
fun Game.halfTimeLabel(sport: Sport): String? {
    if (sport != Sport.FOOTBALL) return null
    if (state == GameState.SCHEDULED || state == GameState.PRE_GAME) return null
    val current = clock?.time?.period?.number
    val inFirstHalf = state == GameState.LIVE && (current == null || current <= 1)
    if (inFirstHalf) return null
    val first = periodScores.firstOrNull { it.period.number == 1 } ?: return null
    return "HT ${first.home}-${first.away}"
}

/** What a group of games on the timeline is called: the league's name, or its id while the league is unknown. */
fun groupTitle(league: League?, leagueId: String): String = league?.name ?: leagueId

/** The competition under a league's name, unless it only repeats it (`Allsvenskan 2026` under Allsvenskan). */
fun groupSubtitle(league: League?, competition: String?): String? =
    if (competition == null || league == null || competition.startsWith(league.name)) null else competition
