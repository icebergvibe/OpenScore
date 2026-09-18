package org.openscore.providers.ufc

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.openscore.model.Clock
import org.openscore.model.Game
import org.openscore.model.GameEvent
import org.openscore.model.GameState
import org.openscore.model.GameTime
import org.openscore.model.Period
import org.openscore.model.PeriodType
import org.openscore.model.PlayerRef
import org.openscore.model.StatPair
import org.openscore.model.TeamRef
import org.openscore.model.combat.CombatEventType
import org.openscore.model.combat.FightMethod
import org.openscore.model.combat.FightOutcome
import org.openscore.model.combat.FightResult
import org.openscore.model.combat.FightSituation
import org.openscore.model.combat.Scorecard
import org.openscore.provider.Dates
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * UFC card document → core model. A fight is a [Game]: red corner home, blue corner away,
 * rounds as periods, no score; the bout and its result are the [FightSituation]. The game id
 * is `{eventId}-{fightId}`, because only the event document carries the tracked timeline.
 */
internal object UfcMapper {

    const val LEAGUE_ID: String = "ufc"
    /** The feed has no seasons; every game carries this id so a season store can file the snapshot. */
    const val SEASON_ID: String = "cards"

    fun gameId(eventId: Int, fightId: Int): String = "$eventId-$fightId"
    fun eventIdOf(gameId: String): Int? = gameId.substringBefore('-').toIntOrNull()
    fun fightIdOf(gameId: String): Int? = gameId.substringAfter('-', "").toIntOrNull()

    /** Every fight on the card, main event first as the feed orders it; [eventId] is the id the document was read as. */
    fun card(e: UfcEvent, eventId: Int, withEvents: Boolean): List<Game> =
        e.FightCard.map { game(e, eventId, it, withEvents) }

    /** The card's calendar date where it takes place — the date UFC itself puts on the event. */
    fun localDate(e: UfcEvent): LocalDate? {
        val start = Dates.instantOrNull(e.StartTime) ?: return null
        return start.toLocalDateTime(zone(e.TimeZone)).date
    }

    private fun zone(text: String?): TimeZone =
        text?.let { runCatching { TimeZone.of(it) }.getOrNull() } ?: TimeZone.UTC

    fun game(e: UfcEvent, eventId: Int, f: UfcFight, withEvents: Boolean): Game {
        val eventStart = Dates.instantOrNull(e.StartTime)
        val start = Dates.instantOrNull(f.CardSegmentStartTime) ?: eventStart
            ?: Instant.fromEpochSeconds(0)
        val date = localDate(e)
        val (home, away) = corners(f)
        val rounds = roundMinutes(f.RuleSet)
        val state = state(e, f)
        val result = result(f, home, away)
        return Game(
            leagueId = LEAGUE_ID,
            id = gameId(eventId, f.FightId),
            seasonId = SEASON_ID,
            competition = e.Name,
            startTime = start,
            scheduleDate = date,
            venue = listOfNotNull(e.Location?.Venue, e.Location?.City).joinToString(", ").ifBlank { null },
            home = home,
            away = away,
            state = state,
            clock = if (state.isLive) clock(e, f, rounds) else null,
            situation = FightSituation(
                scheduledRounds = f.RuleSet?.PossibleRounds ?: rounds.size,
                roundMinutes = rounds,
                weightClass = weightClass(f.WeightClass),
                title = f.Accolades.firstOrNull { it.Type == "Belt" }?.Name ?: f.Accolades.firstOrNull()?.Name,
                cardSegment = f.CardSegment,
                cardPosition = f.FightOrder,
                result = result,
            ),
            events = if (withEvents) events(f, home, away, rounds) else null,
            rawState = listOfNotNull(e.Status, f.Status).joinToString("/"),
        )
    }

    /** Red corner first; a card without corner labels keeps the feed's order. */
    private fun corners(f: UfcFight): Pair<TeamRef, TeamRef> {
        val red = f.Fighters.firstOrNull { it.Corner == "Red" } ?: f.Fighters.getOrNull(0)
        val blue = f.Fighters.firstOrNull { it.Corner == "Blue" && it !== red } ?: f.Fighters.firstOrNull { it !== red }
        return teamRef(red, "Red corner") to teamRef(blue, "Blue corner")
    }

    private fun teamRef(x: UfcFighter?, fallback: String): TeamRef {
        if (x == null) return TeamRef(LEAGUE_ID, "tbd", fallback, clubId = null)
        return TeamRef(LEAGUE_ID, x.FighterId.toString(), fullName(x.Name) ?: fallback, abbreviation = x.Name?.LastName, clubId = null)
    }

    private fun playerRef(x: UfcFighter): PlayerRef = PlayerRef(LEAGUE_ID, x.FighterId.toString(), fullName(x.Name) ?: x.FighterId.toString())

    private fun fullName(n: UfcName?): String? =
        listOfNotNull(n?.FirstName?.trim(), n?.LastName?.trim()).filter { it.isNotEmpty() }.joinToString(" ").ifBlank { null }

    private fun weightClass(w: UfcWeightClass?): String? {
        w ?: return null
        val catch = w.CatchWeight?.let { " (${formatLb(it)} lb)" } ?: ""
        return w.Description?.let { it + catch }
    }

    private fun formatLb(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    /** `3 Rnd (5-5-5)` → [5, 5, 5]; a count without lengths is five-minute rounds. */
    fun roundMinutes(r: UfcRuleSet?): List<Int> {
        val listed = r?.Description?.substringAfter('(', "")?.substringBefore(')')
            ?.split('-')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
        if (listed.isNotEmpty()) return listed
        return List(r?.PossibleRounds ?: 3) { 5 }
    }

    private fun isOvertimeRound(r: UfcRuleSet?, round: Int): Boolean =
        r?.Description?.contains("OT") == true && round == r.PossibleRounds

    private fun period(round: Int, rules: UfcRuleSet?): Period = when {
        round <= 0 -> Period(0, PeriodType.UNKNOWN, "Pre-fight")
        isOvertimeRound(rules, round) -> Period(round, PeriodType.OVERTIME, "OT")
        else -> Period(round, PeriodType.REGULATION, "R$round")
    }

    // ---- state -----------------------------------------------------------------------------

    private val ROUND_ACTIONS = setOf("round_start", "round_end", "round_pause", "round_unpause")
    private val OVER_ACTIONS = setOf("fight_over", "fight_complete", "results")

    fun state(e: UfcEvent, f: UfcFight): GameState {
        if (e.Status.equals("Canceled", ignoreCase = true) || e.Status.equals("Cancelled", ignoreCase = true)) return GameState.CANCELLED
        val actions = f.FightNightTracking
        val started = actions.any { it.Type == "round_start" }
        return when (f.Status) {
            "Final" -> GameState.FINAL
            "Canceled", "Cancelled" -> GameState.CANCELLED
            // "Over": the fight has ended but the result is not official; keep polling until it is.
            "Live", "Over" -> GameState.LIVE
            "Upcoming", null -> when {
                started || actions.any { it.Type in OVER_ACTIONS } -> GameState.LIVE
                actions.isNotEmpty() || (e.LiveFightId != null && e.LiveFightId == f.FightId) -> GameState.PRE_GAME
                else -> GameState.SCHEDULED
            }
            // The in-progress statuses are read off ufc.com's script, not yet observed: a fight
            // under a status this mapper does not know is still live once a round has started.
            else -> if (started || actions.any { it.Type in OVER_ACTIONS }) GameState.LIVE else GameState.UNKNOWN
        }
    }

    /**
     * Round and running flag from the tracked round actions; the event's live pointer supplies
     * the round (and, when the feed sends it, the elapsed time) for the fight in the cage.
     */
    private fun clock(e: UfcEvent, f: UfcFight, rounds: List<Int>): Clock? {
        val pointed = e.LiveFightId != null && e.LiveFightId == f.FightId
        val last = f.FightNightTracking.lastOrNull { it.Type in ROUND_ACTIONS }
        val round = (if (pointed) e.LiveRoundNumber else null) ?: last?.RoundNumber ?: return null
        val running = when (last?.Type) {
            "round_start", "round_unpause" -> true
            "round_end", "round_pause" -> false
            else -> null
        }
        val length = (rounds.getOrNull(round - 1) ?: 5).minutes
        val elapsed = if (pointed) parseClock(e.LiveRoundElapsedTime) else null
        val remaining = elapsed?.let { (length - it).coerceAtLeast(Duration.ZERO) }
        return Clock(GameTime(period(round, f.RuleSet), elapsed, remaining, label = remaining?.let(::mmss)), running)
    }

    /** `m:ss`, or a bare number of seconds; null for anything else. */
    fun parseClock(text: String?): Duration? {
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        t.toIntOrNull()?.let { return it.seconds }
        val parts = t.split(':')
        if (parts.size != 2) return null
        val m = parts[0].toIntOrNull() ?: return null
        val s = parts[1].toIntOrNull() ?: return null
        return m.minutes + s.seconds
    }

    private fun mmss(d: Duration): String {
        val total = d.inWholeSeconds.coerceAtLeast(0)
        val s = total % 60
        return "${total / 60}:${if (s < 10) "0$s" else s}"
    }

    // ---- result ----------------------------------------------------------------------------

    private fun result(f: UfcFight, home: TeamRef, away: TeamRef): FightResult? {
        val r = f.Result
        val method = r?.Method
        if (method == null && f.Status != "Final") return null
        val winner = f.Fighters.firstOrNull { it.Outcome?.OutcomeId == 1 }
        return FightResult(
            winner = winner?.let { if (it.FighterId.toString() == away.id) away else home },
            method = method(method),
            methodLabel = method ?: "Final",
            round = r?.EndingRound,
            time = r?.EndingTime,
            detail = detail(r),
            notes = r?.EndingNotes,
            homeOutcome = outcome(f.Fighters.firstOrNull { it.FighterId.toString() == home.id }),
            awayOutcome = outcome(f.Fighters.firstOrNull { it.FighterId.toString() == away.id }),
            scorecards = r?.FightScores.orEmpty().mapNotNull { j ->
                val h = j.Fighters.firstOrNull { it.FighterId.toString() == home.id }?.Score
                val a = j.Fighters.firstOrNull { it.FighterId.toString() == away.id }?.Score
                if (h == null || a == null) null
                else Scorecard(listOfNotNull(j.JudgeFirstName, j.JudgeLastName).joinToString(" "), h, a)
            },
            homeBonuses = bonuses(f.Fighters.firstOrNull { it.FighterId.toString() == home.id }),
            awayBonuses = bonuses(f.Fighters.firstOrNull { it.FighterId.toString() == away.id }),
            fightOfTheNight = r?.FightOfTheNight ?: false,
        )
    }

    private fun bonuses(x: UfcFighter?): List<String> = listOfNotNull(
        "Performance of the Night".takeIf { x?.PerformanceOfTheNight == true },
        "KO of the Night".takeIf { x?.KOOfTheNight == true },
        "Submission of the Night".takeIf { x?.SubmissionOfTheNight == true },
    )

    fun method(label: String?): FightMethod = when {
        label == null -> FightMethod.OTHER
        label.startsWith("KO") || label.startsWith("TKO") -> FightMethod.KO_TKO
        label.startsWith("Submission") -> FightMethod.SUBMISSION
        label.startsWith("Decision") -> FightMethod.DECISION
        label == "Could Not Continue" -> FightMethod.NO_CONTEST
        label == "Overturned" -> FightMethod.OVERTURNED
        else -> FightMethod.OTHER
    }

    private fun outcome(x: UfcFighter?): FightOutcome? = when (x?.Outcome?.OutcomeId) {
        1 -> FightOutcome.WIN
        2 -> FightOutcome.LOSS
        3 -> FightOutcome.DRAW
        4 -> FightOutcome.NO_CONTEST
        else -> null
    }

    /** `Rear Naked Choke` or `Punches to the head`; the position it happened from is left to the feed. */
    private fun detail(r: UfcResult?): String? {
        r ?: return null
        r.EndingSubmission?.let { return it }
        val strike = r.EndingStrike ?: return null
        val target = r.EndingTarget?.let { " to the ${it.lowercase()}" } ?: ""
        return strike + target
    }

    // ---- events ----------------------------------------------------------------------------

    /** Actions the timeline does not need: the broadcast beats and the bookkeeping after the result. */
    private val SKIPPED_ACTIONS = setOf("tale_of_the_tape", "staredown", "results", "fight_complete")

    fun events(f: UfcFight, home: TeamRef, away: TeamRef, rounds: List<Int>): List<GameEvent> {
        val fighters = f.Fighters.associateBy { it.FighterId }
        val out = ArrayList<GameEvent>()
        var round = 0
        f.FightNightTracking.forEachIndexed { index, a ->
            if (a.Type in SKIPPED_ACTIONS) return@forEachIndexed
            a.RoundNumber?.let { round = it }
            val fighter = a.FighterId?.let(fighters::get)
            val team = fighter?.let { if (it.FighterId.toString() == away.id) away else home }
            val players = listOfNotNull(fighter?.let(::playerRef))
            // The reason follows the pause as its own action at the same time; fold it in.
            if (a.Type.startsWith("pause_reason_")) {
                val reason = pauseReason(a.Type)
                val last = out.lastOrNull()
                if (last != null && last.type == CombatEventType.PAUSE && last.description == null) {
                    out[out.lastIndex] = last.copy(description = reason, team = team, players = players)
                } else {
                    out += event(a, index, CombatEventType.PAUSE, round, rounds, f.RuleSet, team, players, reason)
                }
                return@forEachIndexed
            }
            val (type, description) = when (a.Type) {
                "knockdown" -> CombatEventType.KNOCKDOWN to null
                "takedown" -> CombatEventType.TAKEDOWN to null
                "takedown_attempt" -> CombatEventType.TAKEDOWN_ATTEMPT to null
                "submission_attempt" -> CombatEventType.SUBMISSION_ATTEMPT to null
                "reversal" -> CombatEventType.REVERSAL to null
                "round_start" -> CombatEventType.ROUND_START to null
                "round_end" -> CombatEventType.ROUND_END to null
                "round_pause" -> CombatEventType.PAUSE to null
                "round_unpause" -> CombatEventType.RESUME to null
                "walkout" -> CombatEventType.WALKOUT to null
                "fight_open" -> CombatEventType.FIGHT_START to null
                "fight_over" -> CombatEventType.FIGHT_END to null
                "unofficial_winner_kotko" -> CombatEventType.RESULT to "Wins by KO/TKO"
                "unofficial_winner_submission" -> CombatEventType.RESULT to "Wins by submission"
                "unofficial_winner_decision" -> CombatEventType.RESULT to "Wins by decision"
                else -> CombatEventType.OTHER to a.Type.replace('_', ' ')
            }
            out += event(a, index, type, round, rounds, f.RuleSet, team, players, description)
        }
        return out
    }

    private fun pauseReason(type: String): String? = when (type.removePrefix("pause_reason_")) {
        "low_blow" -> "Low blow"
        "eye_poke" -> "Eye poke"
        "doctor" -> "Doctor check"
        "generic" -> null
        else -> type.removePrefix("pause_reason_").replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private fun event(
        a: UfcAction, index: Int, type: CombatEventType, round: Int, rounds: List<Int>, rules: UfcRuleSet?,
        team: TeamRef?, players: List<PlayerRef>, description: String?,
    ): GameEvent {
        val remaining = parseClock(a.RoundTime)
        val length = (rounds.getOrNull(round - 1) ?: 5).minutes
        val elapsed = remaining?.let { (length - it).coerceAtLeast(Duration.ZERO) }
        return GameEvent(
            id = a.ActionId.toString(),
            type = type,
            rawType = a.Type,
            time = GameTime(
                period = period(round, rules),
                elapsed = elapsed,
                remaining = remaining,
                // Stated as results are (`R1 4:26`), not as the broadcast clock counts.
                label = elapsed?.let(::mmss),
            ),
            team = team,
            players = players,
            description = description,
            sortOrder = index,
        )
    }

    // ---- stats -----------------------------------------------------------------------------

    /** The fight route's totals as home/away pairs; keys are documented in the league README. */
    fun stats(d: UfcFightDetail, home: TeamRef, away: TeamRef): Map<String, StatPair> {
        val h = d.FightStats.firstOrNull { it.FighterId.toString() == home.id } ?: return emptyMap()
        val a = d.FightStats.firstOrNull { it.FighterId.toString() == away.id } ?: return emptyMap()
        val out = LinkedHashMap<String, StatPair>()
        fun put(key: String, hv: Any?, av: Any?) {
            if (hv != null && av != null) out[key] = StatPair(hv.toString(), av.toString())
        }
        put("sigStrikesLanded", h.SigStrikesLanded, a.SigStrikesLanded)
        put("sigStrikesAttempted", h.SigStrikesAttempted, a.SigStrikesAttempted)
        put("sigStrikeAccuracy", h.SigStrikesAccuracy?.let { percent(it) }, a.SigStrikesAccuracy?.let { percent(it) })
        put("totalStrikesLanded", h.TotalStrikesLanded, a.TotalStrikesLanded)
        put("totalStrikesAttempted", h.TotalStrikesAttempted, a.TotalStrikesAttempted)
        put("knockdowns", h.Knockdowns, a.Knockdowns)
        put("takedownsLanded", h.TakedownsLanded, a.TakedownsLanded)
        put("takedownsAttempted", h.TakedownsAttempted, a.TakedownsAttempted)
        put("submissionAttempts", h.SubmissionsAttempted, a.SubmissionsAttempted)
        put("reversals", h.Reversals, a.Reversals)
        put("controlTime", h.ControlTime, a.ControlTime)
        put("sigHeadStrikesLanded", h.SigHeadStrikesLanded, a.SigHeadStrikesLanded)
        put("sigBodyStrikesLanded", h.SigBodyStrikesLanded, a.SigBodyStrikesLanded)
        put("sigLegStrikesLanded", h.SigLegStrikesLanded, a.SigLegStrikesLanded)
        put("sigDistanceStrikesLanded", h.SigDistanceStrikesLanded, a.SigDistanceStrikesLanded)
        put("sigClinchStrikesLanded", h.SigClinchStrikesLanded, a.SigClinchStrikesLanded)
        put("sigGroundStrikesLanded", h.SigGroundStrikesLanded, a.SigGroundStrikesLanded)
        return out
    }

    private fun percent(v: Double): String {
        val tenths = (v * 10).toLong()
        return "${tenths / 10}.${tenths % 10}%"
    }
}
