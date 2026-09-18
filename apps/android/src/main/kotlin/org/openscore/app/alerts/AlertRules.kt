package org.openscore.app.alerts

import org.openscore.app.ui.common.footballOffsetMinutes
import org.openscore.app.ui.common.goalIcon
import org.openscore.model.Game
import org.openscore.model.GameEnding
import org.openscore.model.GameEvent
import org.openscore.model.GameState
import org.openscore.model.Score
import org.openscore.model.Sport
import org.openscore.model.football.CardDetails
import org.openscore.model.football.FootballGoalDetails
import org.openscore.model.football.GoalKind
import org.openscore.model.hockey.GoalDetails
import org.openscore.model.hockey.PenaltyDetails
import org.openscore.model.hockey.Strength
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One notification to show, worked out by the rules and posted by [Notifications]. */
data class Post(
    val kind: AlertKind,
    /** Stable across polls, so the same thing is never shown twice and a repost replaces itself. */
    val id: String,
    val title: String,
    val text: String,
    /** A busy match posts several; grouped ones fold under the fixture in the shade. */
    val grouped: Boolean = false,
)

/** What one look at a due row decided: the row to put back (null when there is nothing left to watch), and what to show. */
data class Outcome(val next: PendingAlert?, val posts: List<Post> = emptyList(), val log: String? = null)

/**
 * The decisions behind the notifications, kept free of Android so they can be tested on the
 * JVM: what a followed game turns into when the schedule is built, and what a due row does
 * when the listing is re-read.
 *
 * Everything but the kick-off reminder reads the core's day listing, which carries state and
 * score for every game including finished ones, so a start, a goal, a break and a result are
 * all answered by the one request per league a poll makes. Cards are the exception — bookings
 * live only in a provider's events — and so is the scorer's name, which is looked up once per
 * goal rather than per poll.
 */
object AlertRules {

    /** How often a running game is looked at. A floor: a phone in Doze is woken less often whatever is asked. */
    const val LIVE_POLL_MINUTES = 3

    /** After this long a game is assumed lost rather than checked forever. */
    const val GIVE_UP_MS = 8L * 60 * 60 * 1000

    /** Bounds on how soon a game that is still running is looked at again for its result. */
    private const val MIN_RETRY_MINUTES = 3
    private const val MAX_RETRY_MINUTES = 30
    private const val DEFAULT_RETRY_MINUTES = 10

    /** Stoppage plus the lag between the final whistle and the feed saying so. */
    private const val STOPPAGE_ALLOWANCE_MINUTES = 4

    /** The events a card watch announces: football's cards, and the penalties hockey books a player with instead. */
    private val CARD_KEYS = setOf("yellow-card", "second-yellow", "red-card", "penalty")

    /** Kick-off to the final whistle, generously; the result check is first due this long after kick-off. */
    fun typicalDurationMinutes(sport: Sport): Int = when (sport) {
        Sport.FOOTBALL -> 115
        Sport.HOCKEY -> 170
        Sport.BASEBALL -> 200
        Sport.MOTORSPORT -> 180
    }

    /** The rows a followed game on [date]'s listing turns into under [settings]; none for a game already over. */
    fun alertsFor(game: Game, sport: Sport, competition: String, date: String, settings: AlertSettings, now: Long): List<PendingAlert> {
        if (game.state.isTerminal) return emptyList()
        val kickoff = game.startTime.toEpochMilliseconds()
        val base = PendingAlert(
            leagueId = game.leagueId,
            gameId = game.id,
            date = date,
            kind = AlertKind.KICKOFF,
            dueAt = kickoff - settings.leadMinutes * 60_000L,
            kickoffAt = kickoff,
            home = game.home.name,
            away = game.away.name,
            competition = competition,
        )
        return buildList {
            if (settings.kickoff && base.dueAt > now) add(base)
            // Queued only for a game that has not kicked off, which is what makes the first reading
            // of "live" a genuine start rather than a watch that began mid-match.
            if (settings.started && kickoff > now) add(base.copy(kind = AlertKind.STARTED, dueAt = kickoff))
            if (settings.results) add(base.copy(kind = AlertKind.RESULT, dueAt = kickoff + typicalDurationMinutes(sport) * 60_000L))
            // Due at kick-off, or straight away for a match already under way — the first poll of one
            // of those records what it finds without announcing it.
            if (settings.incidents) add(base.copy(kind = AlertKind.LIVE, dueAt = maxOf(kickoff, now)))
            if (settings.breaks) add(base.copy(kind = AlertKind.BREAK, dueAt = maxOf(kickoff, now)))
        }
    }

    /**
     * Whether a watch on a game has anything left to wait for. [game] comes from the listing, so a
     * null one is a game the league no longer lists — before kick-off that means not yet and
     * afterwards it means gone, and only the clock tells those apart.
     */
    fun watchIsOver(alert: PendingAlert, sport: Sport, game: Game?, now: Long): String? = when {
        game != null && game.state.isTerminal -> game.state.name.lowercase()
        game == null && now > alert.kickoffAt + typicalDurationMinutes(sport) * 60_000L -> "not listed"
        now - alert.kickoffAt > GIVE_UP_MS -> "given up on"
        else -> null
    }

    /** The outcome of a watch whose game is over or lost, or null while there is still something to wait for. */
    private inline fun ended(alert: PendingAlert, sport: Sport, game: Game?, now: Long, posts: () -> List<Post> = { emptyList() }): Outcome? =
        watchIsOver(alert, sport, game, now)?.let { reason ->
            Outcome(null, posts() + listOfNotNull(game?.let { calledOffPost(alert, it) }), log = "${alert.kind} watch on ${alert.title} ends: $reason")
        }

    /**
     * A game the league has called off. Every watch on the game shares the one id, so whichever
     * kind reads the listing first says it and the rest find it already delivered; the rebuild
     * posts the same thing when it finds a queued reminder for a game that is no longer happening.
     */
    fun calledOffPost(alert: PendingAlert, game: Game): Post? {
        val what = when (game.state) {
            GameState.POSTPONED -> "Postponed"
            GameState.CANCELLED -> "Cancelled"
            else -> return null
        }
        return Post(AlertKind.KICKOFF, "${alert.key}|CALLED_OFF", scoredTitle(alert, game.score), "$what · ${alert.competition}")
    }

    private fun PendingAlert.later(now: Long): PendingAlert = copy(dueAt = now + LIVE_POLL_MINUTES * 60_000L)

    private fun PendingAlert.hasStarted(game: Game?): Boolean =
        game != null && game.state != GameState.SCHEDULED && game.state != GameState.PRE_GAME && game.state != GameState.UNKNOWN

    private val Score.key: String get() = "$home-$away"

    /**
     * Waits for a game to actually begin, and says so once. The kick-off reminder fires on the
     * time the game was due to start; this fires on the feed saying it has, which is the
     * difference that matters for a delayed one. Stops polling the moment it has said so.
     */
    fun pollStart(alert: PendingAlert, sport: Sport, game: Game?, now: Long): Outcome {
        ended(alert, sport, game, now)?.let { return it }
        if (!alert.hasStarted(game)) return Outcome(alert.later(now))
        return Outcome(null, listOf(Post(AlertKind.STARTED, alert.id, alert.title, "Under way · ${alert.competition}")))
    }

    /**
     * Announces the lulls: half time, and the end of a period. One notification per break, so a
     * hockey game's two intermissions read as two, and the row stays until the game is over.
     * Only leagues whose feed exposes a break state produce [GameState.INTERMISSION]; the rest
     * simply never fire this.
     */
    fun pollBreak(alert: PendingAlert, sport: Sport, game: Game?, now: Long): Outcome {
        ended(alert, sport, game, now)?.let { return it }
        val later = alert.later(now)
        if (game == null || !alert.hasStarted(game)) return Outcome(later)
        val breakId = game.takeIf { it.state == GameState.INTERMISSION }?.let { "${alert.id}|${breakKey(it)}" }
        if (!alert.seeded) {
            // The first look at a match that may already be paused: a break in progress is recorded
            // without being shown, so switching this on during half time does not announce that half time.
            return Outcome(later.copy(seeded = true, seen = alert.seen + listOfNotNull(breakId)), log = breakId?.let { "seeded ${alert.title} at $it" })
        }
        if (breakId == null || breakId in alert.seen) return Outcome(later)
        val post = Post(AlertKind.BREAK, breakId, scoredTitle(alert, game.score), "${breakHeadline(game, sport)} · ${alert.competition}", grouped = true)
        return Outcome(later.copy(seen = alert.seen + breakId), listOf(post))
    }

    /** What tells one intermission from the next: the period the clock is on, or failing that how many have been scored. */
    private fun breakKey(game: Game): String = game.clock?.period?.number?.toString() ?: "p${game.periodScores.size}"

    fun breakHeadline(game: Game, sport: Sport): String {
        val period = game.clock?.period?.number
        return when (sport) {
            // During a football break the clock names the half just finished — every football
            // mapper builds it so, through FootballPeriods — which is what tells half time from
            // the pause before extra time. Said as what has been played rather than what comes
            // next, since a feed may show the same break for a moment before calling full time.
            Sport.FOOTBALL -> when (period) {
                null, 1 -> "Half time"
                2 -> "End of 90 minutes"
                3 -> "Half time in extra time"
                else -> "End of extra time"
            }
            // Which period the clock names during an intermission differs by feed (the one just
            // finished, or the next), so the count of periods scored is the safer number.
            Sport.HOCKEY -> game.periodScores.size.takeIf { it > 0 }?.let { "End of period $it" } ?: "Intermission"
            Sport.BASEBALL -> "Break"
            Sport.MOTORSPORT -> "Break"
        }
    }

    /**
     * Looks at one game that should be running and announces whatever is new: a goal is a score
     * that moved, a card is an event not seen before. Returns the row to put back with what it
     * has now seen, or nothing when the game is over.
     *
     * [events] is null when they were not read this poll — not asked for, or the read failed.
     * A card watch that could have had them and did not goes back unseeded, because passing a
     * failed read off as "nothing happened" would swallow the first half.
     */
    fun pollLive(alert: PendingAlert, sport: Sport, game: Game?, events: List<GameEvent>?, settings: AlertSettings, canEvents: Boolean, now: Long): Outcome {
        ended(alert, sport, game, now) {
            // A goal in the last minute lands in the same poll as the final whistle, and it is still a goal.
            val finalScore = game?.score
            if (game != null && game.state.isFinished && settings.goals && alert.seeded && finalScore != null && alert.seenScore != null && alert.seenScore != finalScore.key) {
                goalPosts(alert, sport, finalScore, alert.seenScore, events)
            } else emptyList()
        }?.let { return it }
        val later = alert.later(now)
        if (game == null || !alert.hasStarted(game)) return Outcome(later)
        val score = game.score ?: return Outcome(later)
        val cards = events?.filter { it.type.key in CARD_KEYS }

        if (!alert.seeded) {
            if (settings.cards && canEvents && cards == null) return Outcome(later)
            return Outcome(
                later.copy(seeded = true, seenScore = score.key, seen = alert.seen + cards.orEmpty().map { it.id }),
                log = "seeded ${alert.title} at ${score.key} with ${cards.orEmpty().size} card(s)",
            )
        }

        val posts = mutableListOf<Post>()
        if (settings.goals && alert.seenScore != null && alert.seenScore != score.key) {
            posts += goalPosts(alert, sport, score, alert.seenScore, events)
        }
        val newCards = if (settings.cards) cards.orEmpty().filterNot { it.id in alert.seen } else emptyList()
        newCards.forEach { event ->
            posts += Post(AlertKind.LIVE, "${alert.id}|${event.id}", scoredTitle(alert, score), listOf(cardHeadline(event), eventDetail(event, sport), alert.competition).filter { it.isNotBlank() }.joinToString(" · "), grouped = true)
        }
        return Outcome(later.copy(seenScore = score.key, seen = alert.seen + newCards.map { it.id }), posts)
    }

    /**
     * Whether this poll should ask the provider for the game's events: always when cards are
     * watched, and once per goal — a score that moved since the last look — so the scorer can be
     * named without paying a request every three minutes for the length of the match.
     */
    fun needsEvents(alert: PendingAlert, game: Game?, settings: AlertSettings, canEvents: Boolean): Boolean {
        if (!canEvents || game == null || !alert.hasStarted(game)) return false
        if (settings.cards) return true
        val score = game.score ?: return false
        return settings.goals && alert.seeded && alert.seenScore != null && alert.seenScore != score.key
    }

    private fun goalPosts(alert: PendingAlert, sport: Sport, score: Score, seenScore: String, events: List<GameEvent>?): List<Post> {
        val (seenHome, seenAway) = seenScore.split("-").map { it.toIntOrNull() ?: 0 }
        val title = scoredTitle(alert, score)
        val homeGained = score.home - seenHome
        val awayGained = score.away - seenAway
        val gained = homeGained + awayGained
        if (gained <= 0) {
            // A score that went down is a goal taken away (VAR, a correction), not a goal.
            val what = if (sport == Sport.BASEBALL) "Score corrected" else "Goal overturned"
            return listOf(Post(AlertKind.LIVE, "${alert.id}|GOAL|${score.key}|down", title, "🚫 $what · ${alert.competition}", grouped = true))
        }
        if (sport == Sport.BASEBALL) {
            // Runs come in bunches and no event is "the run", so each side that scored gets one
            // line with the count rather than a notification per run.
            return listOf(Triple("home", alert.home, homeGained), Triple("away", alert.away, awayGained)).filter { it.third > 0 }.map { (key, side, runs) ->
                val what = if (runs == 1) "Run" else "$runs runs"
                Post(AlertKind.LIVE, "${alert.id}|RUNS|${score.key}|$key", title, "${sport.goalIcon} $what for $side · ${alert.competition}", grouped = true)
            }
        }
        // The last [gained] goals in the events are the new ones; the feed may lag the score, in
        // which case the side that scored is all there is to say, the home side's goals first.
        val goals = events.orEmpty().filter { it.type.isGoal }.sortedWith(compareBy({ it.sortOrder ?: Int.MAX_VALUE }, { it.time.period.number })).takeLast(gained)
        return (0 until gained).map { i ->
            val event = goals.getOrNull(goals.size - gained + i)
            val side = event?.team?.name ?: if (i < homeGained) alert.home else alert.away
            val detail = event?.let { eventDetail(it, sport) }.orEmpty()
            Post(
                kind = AlertKind.LIVE,
                id = "${alert.id}|GOAL|${score.key}|$i",
                title = title,
                text = listOf("${sport.goalIcon} Goal for $side", detail, alert.competition).filter { it.isNotBlank() }.joinToString(" · "),
                grouped = true,
            )
        }
    }

    /** `🟨 Yellow card for Arsenal`; a hockey penalty goes to the team whose player sits. */
    private fun cardHeadline(event: GameEvent): String {
        val team = event.team?.name
        return when (event.type.key) {
            "yellow-card" -> "🟨 Yellow card" + team?.let { " for $it" }.orEmpty()
            "second-yellow" -> "🟨🟥 Second yellow" + team?.let { " for $it" }.orEmpty()
            "red-card" -> "🟥 Red card" + team?.let { " for $it" }.orEmpty()
            else -> "⏱ Penalty" + team?.let { " to $it" }.orEmpty()
        }
    }

    /**
     * Who, how and when: `Haaland 67'`, `Gabriel (og) 45'`, `Saka (pen) 71'`, `Matthews (PP) 12:34`.
     * The how only when it changes the reading of the goal — an own goal is scored by the other
     * side's player, so without the mark the name reads as the wrong team's.
     */
    private fun eventDetail(event: GameEvent, sport: Sport): String {
        val details = event.details
        val who = when (details) {
            is FootballGoalDetails -> details.scorer?.name
            is GoalDetails -> details.scorer?.name
            is CardDetails -> details.player?.name
            is PenaltyDetails -> details.player?.name
            else -> null
        } ?: event.players.firstOrNull()?.name
        val how = when {
            event.type.key == "own-goal" || (details is FootballGoalDetails && details.kind == GoalKind.OWN_GOAL) -> "og"
            event.type.key == "penalty-goal" || (details is FootballGoalDetails && details.kind == GoalKind.PENALTY) -> "pen"
            details is GoalDetails -> when {
                details.strength == Strength.PP -> "PP"
                details.strength == Strength.SH -> "SH"
                details.strength == Strength.EN || details.emptyNet == true -> "EN"
                else -> null
            }
            else -> null
        }?.let { "($it)" }
        val time = event.time.label ?: when (sport) {
            Sport.FOOTBALL -> event.time.elapsed?.let { "${footballOffsetMinutes(event.time.period.number) + it.inWholeMinutes}'" }
            Sport.HOCKEY -> (event.time.elapsed ?: event.time.remaining)?.let { "%d:%02d".format(it.inWholeSeconds / 60, it.inWholeSeconds % 60) }
            Sport.BASEBALL -> event.time.period.label
            Sport.MOTORSPORT -> event.time.period.label
        }
        return listOfNotNull(who, how, time).joinToString(" ")
    }

    /** Full time, once the listing says so; otherwise when to look again, or nothing when the game is lost. */
    fun pollResult(alert: PendingAlert, sport: Sport, game: Game?, now: Long): Outcome = when {
        game != null && game.state.isFinished -> Outcome(
            null,
            listOf(Post(AlertKind.RESULT, alert.id, scoredTitle(alert, game.score), "${finalHeadline(game, sport)} · ${alert.competition}")),
        )
        game != null && (game.state == GameState.CANCELLED || game.state == GameState.POSTPONED) ->
            Outcome(null, listOfNotNull(calledOffPost(alert, game)), log = "dropping ${alert.title}: ${game.state}")
        now - alert.kickoffAt > GIVE_UP_MS -> Outcome(null, log = "giving up on ${alert.title}")
        // Still in progress, or the listing could not be read: look again, as soon as the clock suggests is worth it.
        else -> retryMinutes(game, sport).let { Outcome(alert.copy(dueAt = now + it * 60_000L), log = "${alert.title} not finished (${game?.state}); re-checking in ${it}m") }
    }

    fun finalHeadline(game: Game, sport: Sport): String = when (sport) {
        Sport.FOOTBALL -> when (game.ending) { GameEnding.OVERTIME -> "After extra time"; GameEnding.SHOOTOUT -> "After penalties"; else -> "Full time" }
        Sport.HOCKEY -> when (game.ending) { GameEnding.OVERTIME -> "Final, overtime"; GameEnding.SHOOTOUT -> "Final, shootout"; else -> "Final" }
        Sport.BASEBALL -> if (game.periodScores.size > 9) "Final, ${game.periodScores.size} innings" else "Final"
        Sport.MOTORSPORT -> "Final"
    }

    /**
     * How long to leave a game that is still running. Football reports the minute and hockey the
     * period, so the remaining time can be read off them; everything else falls back to a fixed gap.
     */
    fun retryMinutes(game: Game?, sport: Sport): Int {
        if (game == null || !game.state.isLive) return DEFAULT_RETRY_MINUTES
        val time = game.clock?.time
        val minutes = when (sport) {
            Sport.FOOTBALL -> {
                if (game.state == GameState.INTERMISSION) 45 + STOPPAGE_ALLOWANCE_MINUTES
                else time?.let { t -> t.elapsed?.let { (90 - footballOffsetMinutes(t.period.number) - it.inWholeMinutes.toInt()).coerceAtLeast(0) + STOPPAGE_ALLOWANCE_MINUTES } }
                    ?: DEFAULT_RETRY_MINUTES
            }
            // A twenty-minute period takes about thirty-five on the wall, plus an intermission each.
            Sport.HOCKEY -> time?.let { (3 - it.period.number).coerceAtLeast(0) * 35 + (it.remaining?.inWholeMinutes?.toInt()?.let { m -> m * 3 / 2 } ?: 10) } ?: DEFAULT_RETRY_MINUTES
            Sport.BASEBALL -> DEFAULT_RETRY_MINUTES
            Sport.MOTORSPORT -> DEFAULT_RETRY_MINUTES
        }
        return minutes.coerceIn(MIN_RETRY_MINUTES, MAX_RETRY_MINUTES)
    }

    /** `Arsenal 2 - 1 Chelsea` once there is a score, else `Arsenal v Chelsea`. */
    fun scoredTitle(alert: PendingAlert, score: Score?): String =
        if (score != null) "${alert.home} ${score.home} - ${score.away} ${alert.away}" else alert.title

    fun kickoffPost(alert: PendingAlert): Post {
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(alert.kickoffAt))
        return Post(AlertKind.KICKOFF, alert.id, alert.title, "Starts at $clock · ${alert.competition}")
    }
}
