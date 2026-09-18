package org.openscore.app.ui.detail

import androidx.compose.ui.graphics.Color
import org.openscore.app.ui.common.goalIcon
import org.openscore.app.ui.theme.ScoreColors
import org.openscore.model.GameEvent
import org.openscore.model.ShootoutAttemptDetails
import org.openscore.model.Sport
import org.openscore.model.baseball.PlateAppearanceDetails
import org.openscore.model.football.CardDetails
import org.openscore.model.football.FootballGoalDetails
import org.openscore.model.football.SubstitutionDetails
import org.openscore.model.hockey.GoalDetails
import org.openscore.model.hockey.PenaltyDetails
import org.openscore.model.hockey.Strength

/** The events a timeline shows: what changed the score, who was booked or sent off, who came on. */
private val KEY_EVENT_KEYS = setOf(
    "penalty", "yellow-card", "second-yellow", "red-card", "substitution", "penalty-missed",
    "goal-disallowed", "var", "shootout-attempt", "home-run", "ejection",
    // A fight: what moved it, and the stoppages and the result.
    "knockdown", "takedown", "submission-attempt", "reversal", "pause", "result",
)

fun GameEvent.isKeyEvent(): Boolean =
    type.isGoal || type.key in KEY_EVENT_KEYS || (details as? PlateAppearanceDetails)?.scoringPlay == true

/** A mark, a colour, the name it belongs to and a quieter second line. [colors] is the theme's [ScoreColors], since this is not composable. */
data class EventLine(val icon: String, val color: Color?, val actor: String, val detail: String?, val time: String)

fun GameEvent.present(sport: Sport, onSurface: Color, colors: ScoreColors): EventLine {
    val d = details
    val first = players.firstOrNull()?.name
    val icon: String
    var color: Color? = null
    var actor: String = first ?: description ?: rawType
    var detail: String? = null

    when (type.key) {
        "goal", "own-goal", "penalty-goal", "home-run" -> {
            icon = when (type.key) { "penalty-goal" -> "${sport.goalIcon}🎯"; "own-goal" -> "${sport.goalIcon} OG"; else -> sport.goalIcon }
            color = if (type.key == "own-goal") colors.red else colors.goal
            when (d) {
                is GoalDetails -> {
                    actor = d.scorer?.name ?: actor
                    val assists = d.assists.joinToString(", ") { it.name }
                    val strength = d.strength?.takeIf { it != Strength.EV }?.name
                    detail = listOfNotNull(assists.takeIf { it.isNotEmpty() }, strength).joinToString(" · ").ifEmpty { null }
                }
                is FootballGoalDetails -> {
                    actor = d.scorer?.name ?: actor
                    detail = d.assist?.name
                }
                is PlateAppearanceDetails -> {
                    actor = d.batter?.name ?: actor
                    detail = description?.takeIf { it.length < 120 }
                }
            }
        }
        "penalty-missed" -> { icon = "${sport.goalIcon}❌"; color = colors.red }
        "goal-disallowed" -> { icon = "🚫"; color = onSurface; detail = description }
        "yellow-card" -> { icon = "🟨"; color = colors.yellow; detail = (d as? CardDetails)?.reason }
        "second-yellow" -> { icon = "🟨🟥"; color = colors.red; detail = (d as? CardDetails)?.reason }
        "red-card" -> { icon = "🟥"; color = colors.red; detail = (d as? CardDetails)?.reason }
        "substitution" -> {
            icon = "🔁"
            color = onSurface
            (d as? SubstitutionDetails)?.let {
                actor = it.playerOn?.name ?: actor
                detail = it.playerOff?.name?.let { off -> "↓ $off" }
            }
        }
        "penalty" -> {
            icon = "⏱"
            color = colors.yellow
            (d as? PenaltyDetails)?.let {
                actor = it.player?.name ?: actor
                detail = listOfNotNull(it.infraction, it.minutes?.let { m -> "$m min" }).joinToString(" · ").ifEmpty { null }
            }
        }
        "shootout-attempt" -> {
            val a = d as? ShootoutAttemptDetails
            icon = if (a?.scored == true) "✅" else "❌"
            color = if (a?.scored == true) colors.goal else onSurface
            actor = a?.shooter?.name ?: actor
        }
        "var" -> { icon = "📺"; color = onSurface; detail = description }
        "knockdown" -> { icon = "💥"; color = colors.goal }
        "takedown" -> { icon = "🤼"; color = onSurface }
        "submission-attempt" -> { icon = "🔒"; color = colors.yellow }
        "reversal" -> { icon = "🔄"; color = onSurface }
        "pause" -> { icon = "⏸"; color = colors.yellow; detail = description; if (first == null) actor = "Pause" }
        "result" -> { icon = "🏆"; color = colors.goal; detail = description }
        else -> {
            icon = if ((d as? PlateAppearanceDetails)?.scoringPlay == true) sport.goalIcon else ""
            color = if (icon.isNotEmpty()) colors.goal else onSurface
            (d as? PlateAppearanceDetails)?.let { actor = it.batter?.name ?: actor; detail = description?.takeIf { s -> s.length < 120 } }
        }
    }
    return EventLine(icon, color, actor, detail, timeLabel(sport))
}

private fun GameEvent.timeLabel(sport: Sport): String {
    time.label?.let { return if (sport == Sport.MMA) "${time.period.label} $it" else it }
    return when (sport) {
        Sport.FOOTBALL -> time.elapsed?.let { "${(footballOffset(time.period.number) + it.inWholeMinutes)}'" } ?: time.period.label
        Sport.HOCKEY -> {
            val t = time.elapsed ?: time.remaining ?: return time.period.label
            val s = t.inWholeSeconds
            "%d:%02d".format(s / 60, s % 60)
        }
        Sport.BASEBALL -> time.period.label
        Sport.MOTORSPORT -> time.period.label
        Sport.MMA -> time.period.label
    }
}

private fun footballOffset(period: Int): Int = when (period) { 1 -> 0; 2 -> 45; 3 -> 90; 4 -> 105; else -> 120 }

/** `shotsOnTarget` → `Shots on target`, `xg` → `xG`, `distanceKm` → `Distance (km)`. */
fun statLabel(key: String): String = when (key) {
    "xg" -> "xG"
    "distanceKm" -> "Distance (km)"
    "distanceM" -> "Distance (m)"
    else -> key.replace(Regex("([a-z])([A-Z])")) { "${it.groupValues[1]} ${it.groupValues[2].lowercase()}" }
        .replaceFirstChar { it.uppercase() }
}

private val PERCENT_KEYS = setOf("possession", "passAccuracy")

/**
 * The value as the row shows it: a percentage gets its sign once, and a measurement the feed
 * hands over to three decimals (`104.431` km) is shown to one, so a value never wraps its column.
 */
fun statValue(key: String, value: String): String {
    val trimmed = value.trim()
    if (key in PERCENT_KEYS) return if (trimmed.endsWith('%')) trimmed else "$trimmed%"
    val decimals = trimmed.substringAfter('.', "").length
    if (decimals > 2) trimmed.toDoubleOrNull()?.let { return "%.1f".format(java.util.Locale.ROOT, it) }
    return trimmed
}

/** Numeric share of the home side in a two-sided stat, or null when the values are not numbers. */
fun statShare(home: String, away: String): Float? {
    val h = home.trim().removeSuffix("%").toDoubleOrNull() ?: return null
    val a = away.trim().removeSuffix("%").toDoubleOrNull() ?: return null
    val total = h + a
    return if (total <= 0.0) 0.5f else (h / total).toFloat()
}
