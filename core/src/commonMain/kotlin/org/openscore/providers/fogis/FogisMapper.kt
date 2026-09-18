package org.openscore.providers.fogis

import org.openscore.model.Clock
import org.openscore.model.Game
import org.openscore.model.GameEnding
import org.openscore.model.GameEvent
import org.openscore.model.GameState
import org.openscore.model.GameTime
import org.openscore.model.Lineup
import org.openscore.model.LineupGroup
import org.openscore.model.LineupGroupKind
import org.openscore.model.Period
import org.openscore.model.PeriodScore
import org.openscore.model.PlayerRef
import org.openscore.model.Score
import org.openscore.model.ShootoutAttemptDetails
import org.openscore.model.StageKind
import org.openscore.model.StatPair
import org.openscore.model.TeamRef
import org.openscore.model.football.CardDetails
import org.openscore.model.football.CardKind
import org.openscore.model.football.FootballEventType
import org.openscore.model.football.footballFormation
import org.openscore.model.football.FootballGoalDetails
import org.openscore.model.football.GoalKind
import org.openscore.model.football.PenaltyMissDetails
import org.openscore.model.football.SubstitutionDetails
import org.openscore.net.XmlNode
import org.openscore.providers.football.FootballPeriods
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Pure functions from the Fogis livescore XML to the core model. */
public class FogisMapper(private val leagueId: String) {

    public fun teamRef(t: XmlNode): TeamRef = TeamRef(
        leagueId = leagueId,
        id = t["id"].orEmpty(),
        name = t["long-name"] ?: t["name"] ?: t["short-name"].orEmpty(),
        abbreviation = null,
        logoUrl = t["teamImageUrl"],
    )

    /** The `schedule-{tournament}` files name states rather than numbering them; same vocabulary as `<status desc>`. */
    public fun gameState(statusDesc: String?): GameState = when (statusDesc) {
        "NOT_STARTED", "RESCHEDULED" -> GameState.SCHEDULED
        "HALFTIME" -> GameState.INTERMISSION
        "FINISHED" -> GameState.FINAL
        "POSTPONED" -> GameState.POSTPONED
        "CANCELED", "CANCELLED" -> GameState.CANCELLED
        null -> GameState.UNKNOWN
        else -> if (statusDesc.endsWith("_IN_PROGRESS") || statusDesc == "PENALTIES") GameState.LIVE else GameState.UNKNOWN
    }

    /**
     * One `<game>` of a tournament schedule: fixture, state and final score only — no events,
     * clock or period scores, which a live game's own resource supplies.
     */
    public fun scheduledGame(g: XmlNode, tournamentName: String?): Game {
        val state = gameState(g["status"])
        val home = g.child("home-team")?.let(::teamRef) ?: TeamRef(leagueId, "", "?")
        val away = g.child("away-team")?.let(::teamRef) ?: TeamRef(leagueId, "", "?")
        val score = if (state == GameState.SCHEDULED || state == GameState.POSTPONED) null
            else g.int("home-score")?.let { h -> g.int("away-score")?.let { a -> Score(h, a) } }
        return Game(
            leagueId = leagueId,
            id = g["id"].orEmpty(),
            seasonId = g["date"]?.take(4),
            stage = if (tournamentName?.contains("cupen", true) == true) StageKind.OTHER else StageKind.REGULAR,
            competition = tournamentName,
            startTime = FogisProvider.localInstant(g["date"].orEmpty(), g["start"] ?: "00:00:00"),
            venue = g["stadium"]?.trim()?.ifBlank { null },
            home = home,
            away = away,
            state = state,
            score = score,
            rawState = g["status"],
        )
    }

    public fun gameState(statusId: Int?): GameState = when (statusId) {
        7, 12 -> GameState.SCHEDULED
        1, 2, 8, 9, 10, 11, 13, 80, 81, 82, 83, 84 -> GameState.LIVE
        3 -> GameState.INTERMISSION
        6 -> GameState.FINAL
        4 -> GameState.POSTPONED
        5 -> GameState.CANCELLED
        null -> GameState.UNKNOWN
        else -> GameState.UNKNOWN
    }

    public fun phasePeriod(phase: Int?): Period = when (phase) {
        1 -> FootballPeriods.FIRST_HALF
        2 -> FootballPeriods.SECOND_HALF
        3 -> FootballPeriods.EXTRA_FIRST
        4 -> FootballPeriods.EXTRA_SECOND
        5 -> FootballPeriods.PENALTIES
        else -> FootballPeriods.FIRST_HALF
    }

    private fun statusPeriod(statusId: Int?): Period? = when (statusId) {
        1, 3 -> FootballPeriods.FIRST_HALF
        2 -> FootballPeriods.SECOND_HALF
        8 -> FootballPeriods.EXTRA_FIRST
        9 -> FootballPeriods.EXTRA_SECOND
        11 -> FootballPeriods.PENALTIES
        else -> null
    }

    /** `mm:ss` (cumulative match clock) → duration. */
    public fun clockDuration(text: String?): Duration? {
        val parts = text?.split(':') ?: return null
        if (parts.size != 2) return null
        return (parts[0].toIntOrNull() ?: return null).minutes + (parts[1].toIntOrNull() ?: return null).seconds
    }

    /**
     * @param events the `<events>` node next to the game. The day overview carries a partial
     *   list (half starts and goals), enough for the clock and period scores but not a timeline:
     *   pass [includeEvents] = false there so [Game.events] stays "not loaded".
     */
    public fun game(g: XmlNode, events: XmlNode?, now: Instant, reportType: String?, includeEvents: Boolean = true): Game {
        val teams = g.child("teams")?.childrenNamed("team").orEmpty()
        val home = teams.firstOrNull { it.bool("home-team") == true }?.let(::teamRef) ?: TeamRef(leagueId, "", "?")
        val away = teams.firstOrNull { it.bool("home-team") == false }?.let(::teamRef) ?: TeamRef(leagueId, "", "?")
        val statusId = g.child("status")?.int("id")
        val state = gameState(statusId)
        val start = FogisProvider.localInstant(g["date"].orEmpty(), g["start"] ?: "00:00:00")
        val eventNodes = events?.childrenNamed("event").orEmpty().asReversed() // newest first in the feed
        val mapped = if (events != null && includeEvents) events(eventNodes, home, away, state) else null
        val scoreNode = g.child("score")
        val shootout = eventNodes.any { it.int("phase") == 5 }
        val rawHome = scoreNode?.int("home-team")
        val rawAway = scoreNode?.int("away-team")
        // The top-level score includes shootout goals; the regulation/ET score is the last non-shootout HALFENDED.
        val regulation = if (shootout) eventNodes.lastOrNull { it["type"] == "HALFENDED" && (it.int("phase") ?: 0) < 5 }?.let { Score(it.int("home-score") ?: 0, it.int("away-score") ?: 0) } else null
        val score = when {
            state == GameState.SCHEDULED -> null
            regulation != null -> regulation
            rawHome != null && rawAway != null -> Score(rawHome, rawAway)
            else -> null
        }
        val tournament = g.child("tournament")
        return Game(
            leagueId = leagueId,
            id = g["id"].orEmpty(),
            seasonId = g["date"]?.take(4),
            stage = if (tournament?.get("name")?.contains("cupen", true) == true) StageKind.OTHER else StageKind.REGULAR,
            competition = tournament?.get("name"),
            startTime = start,
            venue = g.child("stadium")?.get("name")?.trim()?.ifBlank { null },
            home = home,
            away = away,
            state = state,
            score = score,
            clock = clock(state, statusId, eventNodes, g["date"], now),
            periodScores = periodScores(state, scoreNode, eventNodes, shootout, statusPeriod(statusId)),
            ending = if (state.isFinished) (if (shootout) GameEnding.SHOOTOUT else if (eventNodes.any { (it.int("phase") ?: 0) in 3..4 }) GameEnding.OVERTIME else GameEnding.REGULATION) else null,
            events = mapped,
            stats = g.child("stats")?.let(::stats).orEmpty(),
            rawState = listOfNotNull(g.child("status")?.get("desc"), reportType?.let { "report=$it" }).joinToString("/"),
        )
    }

    /** No clock field: the latest HALFSTARTED's wall-clock plus elapsed real time. */
    private fun clock(state: GameState, statusId: Int?, events: List<XmlNode>, date: String?, now: Instant): Clock? {
        if (!state.isLive) return null
        val period = statusPeriod(statusId) ?: FootballPeriods.FIRST_HALF
        if (state == GameState.INTERMISSION) return Clock(FootballPeriods.timeAtMinute(period, FootballPeriods.offsetMinutes(period) + (FootballPeriods.length(period)?.inWholeMinutes?.toInt() ?: 45)), running = false)
        val started = events.lastOrNull { it["type"] == "HALFSTARTED" && phasePeriod(it.int("phase")) == period }
        val dayTime = started?.get("day-time")
        if (date != null && dayTime != null) {
            val elapsed = (now - FogisProvider.localInstant(date, dayTime)).let { if (it.isNegative()) Duration.ZERO else it }
            return Clock(FootballPeriods.timeInPeriod(period, elapsed), running = true)
        }
        return Clock(GameTime(period), running = true)
    }

    private fun periodScores(state: GameState, scoreNode: XmlNode?, events: List<XmlNode>, shootout: Boolean, period: Period?): List<PeriodScore> {
        if (state == GameState.SCHEDULED || scoreNode == null) return emptyList()
        val out = ArrayList<PeriodScore>()
        var prevH = 0
        var prevA = 0
        val ended = events.filter { it["type"] == "HALFENDED" && (it.int("phase") ?: 0) in 1..4 }.sortedBy { it.int("phase") ?: 0 }
        if (ended.isNotEmpty()) {
            for (e in ended) {
                val h = e.int("home-score") ?: prevH
                val a = e.int("away-score") ?: prevA
                out += PeriodScore(phasePeriod(e.int("phase")), h - prevH, a - prevA)
                prevH = h; prevA = a
            }
        } else if (state.isFinished || (state.isLive && period == FootballPeriods.SECOND_HALF)) {
            // No period markers (basic reporting, or the day overview which carries none): the
            // half-time attributes give the first half, the running total the rest.
            val ht = Score(scoreNode.int("home-team-half-time") ?: 0, scoreNode.int("away-team-half-time") ?: 0)
            val total = Score(scoreNode.int("home-team") ?: 0, scoreNode.int("away-team") ?: 0)
            out += PeriodScore(FootballPeriods.FIRST_HALF, ht.home, ht.away)
            out += PeriodScore(FootballPeriods.SECOND_HALF, total.home - ht.home, total.away - ht.away)
            return out
        }
        if (shootout) {
            val last = events.lastOrNull { it.int("phase") == 5 && (it["type"] == "pg" || it["type"] == "pm") }
            if (last != null) out += PeriodScore(FootballPeriods.PENALTIES, (last.int("home-score") ?: 0) - prevH, (last.int("away-score") ?: 0) - prevA)
        }
        return out
    }

    private fun stats(s: XmlNode): Map<String, StatPair> {
        fun pair(key: String): StatPair? {
            val h = s["home-$key"] ?: return null
            val a = s["away-$key"] ?: return null
            return StatPair(h, a)
        }
        return listOfNotNull(
            pair("finishes")?.let { "shots" to it },
            pair("shots-on-goal")?.let { "shotsOnTarget" to it },
            pair("corners")?.let { "corners" to it },
            pair("offsides")?.let { "offsides" to it },
            pair("yellow-cards")?.let { "yellowCards" to it },
            pair("red-cards")?.let { "redCards" to it },
            pair("freekicks-awarded")?.let { "freeKicks" to it },
        ).toMap()
    }

    // ---- events --------------------------------------------------------------------------

    private fun participant(p: XmlNode?): PlayerRef? {
        if (p == null || p["id"] == "0" || p["id"].isNullOrBlank()) return null
        val name = listOfNotNull(p["given-name"]?.ifBlank { null }, p["surname"]?.ifBlank { null }).joinToString(" ")
        return PlayerRef(leagueId, p["id"]!!, name.ifBlank { "#${p["id"]}" }, p.int("number")?.takeIf { it >= 0 }, headshotUrl = p["player-guid"]?.takeIf { it.isNotBlank() }?.let { "https://staticcdn.svenskfotboll.se/img/players/$it.jpg" })
    }

    public fun events(nodes: List<XmlNode>, home: TeamRef, away: TeamRef, state: GameState): List<GameEvent> {
        val out = ArrayList<GameEvent>()
        for ((i, e) in nodes.withIndex()) {
            val period = phasePeriod(e.int("phase"))
            val total = clockDuration(e["game-time"])
            val elapsed = total?.let { (it - FootballPeriods.offsetMinutes(period).minutes).let { d -> if (d.isNegative()) Duration.ZERO else d } }
            val label = e["game-minute-for-web"]?.let { FootballPeriods.parseMinute(it) }?.let { (m, s) -> FootballPeriods.label(m, s) }
            val time = GameTime(period, elapsed, label = label)
            val team = when (e.bool("home-team")) { true -> home; false -> away; null -> null }
            val score = if (e.int("home-score") != null && e.int("away-score") != null) Score(e.int("home-score")!!, e.int("away-score")!!) else null
            val parts = e.child("participants")?.childrenNamed("participant").orEmpty()
            val id = e["id"] ?: "event-$i"
            val typeId = e.int("event-type-id")
            val type = e["type"].orEmpty()
            val inShootout = period.type == org.openscore.model.PeriodType.SHOOTOUT
            val event: GameEvent? = when {
                type == "HALFSTARTED" -> GameEvent(id = id, type = FootballEventType.PERIOD_START, rawType = type, time = GameTime(period, Duration.ZERO, label = FootballPeriods.label(FootballPeriods.offsetMinutes(period))), score = score, description = "Start of ${period.label}", sortOrder = i)
                type == "HALFENDED" -> {
                    val isLast = state.isFinished && nodes.drop(i + 1).none { it["type"] == "HALFSTARTED" }
                    GameEvent(id = id, type = if (isLast) FootballEventType.GAME_END else FootballEventType.PERIOD_END, rawType = type, time = time, score = score, description = "End of ${period.label}", sortOrder = i)
                }
                type == "pg" || type == "pm" -> {
                    val shooter = participant(parts.firstOrNull())
                    GameEvent(id = id, type = FootballEventType.SHOOTOUT_ATTEMPT, rawType = "$type:$typeId", time = GameTime(period), team = team, players = listOfNotNull(shooter), score = score,
                        description = (if (type == "pg") "Penalty scored — " else "Penalty missed — ") + (shooter?.name ?: "?"), details = ShootoutAttemptDetails(shooter, null, scored = type == "pg"), sortOrder = i)
                }
                type == "G" || type == "g" || typeId == 6 || typeId == 14 || typeId == 39 -> {
                    val scorer = participant(parts.firstOrNull { it["type"] == "S" } ?: parts.firstOrNull())
                    val assist = participant(parts.firstOrNull { it.int("event-type-id") == 11 })
                    val goalType = e.int("goal-type-ix")
                    val kind = when {
                        goalType == 4 -> GoalKind.OWN_GOAL
                        type == "g" || typeId == 14 || goalType == 5 || goalType == 12 -> GoalKind.PENALTY
                        goalType == 2 -> GoalKind.FREE_KICK
                        goalType == 11 || goalType == 16 -> GoalKind.HEADER
                        else -> GoalKind.OPEN_PLAY
                    }
                    val ftype = when (kind) { GoalKind.OWN_GOAL -> FootballEventType.OWN_GOAL; GoalKind.PENALTY -> FootballEventType.PENALTY_GOAL; else -> FootballEventType.GOAL }
                    GameEvent(id = id, type = ftype, rawType = "$type:$typeId", time = time, team = team, players = listOfNotNull(scorer, assist), score = score,
                        // An own goal can come with no participant at all (Sirius–Degerfors 2026-09-14: `id="0"`, empty names).
                        description = (if (kind == GoalKind.PENALTY) "Penalty" else if (kind == GoalKind.OWN_GOAL) "Own goal" else "Goal") + (scorer?.let { " — ${it.name}" } ?: "") + (assist?.let { " · ${it.name}" } ?: ""),
                        details = FootballGoalDetails(scorer, assist, kind), sortOrder = i)
                }
                type == "P" || typeId == 20 || typeId in setOf(2, 8, 9) -> {
                    val p = parts.firstOrNull()
                    val player = participant(p)
                    val (ftype, card) = when (p?.get("type")) {
                        "R" -> FootballEventType.RED_CARD to CardKind.RED
                        "YR" -> FootballEventType.SECOND_YELLOW to CardKind.SECOND_YELLOW
                        else -> if (typeId in setOf(2, 8, 9)) FootballEventType.RED_CARD to CardKind.RED else FootballEventType.YELLOW_CARD to CardKind.YELLOW
                    }
                    GameEvent(id = id, type = ftype, rawType = "$type:$typeId", time = time, team = team, players = listOfNotNull(player), score = score,
                        description = "${p?.get("type-desc") ?: card.name} — ${player?.name ?: "?"}", details = CardDetails(player, card), sortOrder = i)
                }
                type == "S" || typeId == 16 -> {
                    val on = participant(parts.firstOrNull { it["type"] == "I" })
                    val off = participant(parts.firstOrNull { it["type"] == "O" })
                    GameEvent(id = id, type = FootballEventType.SUBSTITUTION, rawType = "$type:$typeId", time = time, team = team, players = listOfNotNull(on, off), score = score,
                        description = "Sub — ${on?.name ?: "?"} for ${off?.name ?: "?"}", details = SubstitutionDetails(on, off), sortOrder = i)
                }
                typeId in setOf(18, 19, 26) -> {
                    val player = participant(parts.firstOrNull())
                    GameEvent(id = id, type = FootballEventType.PENALTY_MISSED, rawType = "$type:$typeId", time = time, team = team, players = listOfNotNull(player), score = score, description = e["type-desc"], details = PenaltyMissDetails(player), sortOrder = i)
                }
                inShootout -> null
                else -> GameEvent(id = id, type = FootballEventType.OTHER, rawType = "$type:$typeId", time = time, team = team, players = listOfNotNull(participant(parts.firstOrNull())), score = score, description = e["type-desc"], sortOrder = i)
            }
            if (event != null) out += event
        }
        return out
    }

    // ---- lineups -------------------------------------------------------------------------

    private fun player(p: XmlNode): PlayerRef = PlayerRef(
        leagueId, p["id"].orEmpty(),
        listOfNotNull(p["given-name"]?.ifBlank { null }, p["surname"]?.ifBlank { null }).joinToString(" ").ifBlank { "#${p["id"]}" },
        p.int("number")?.takeIf { it >= 0 },
        when (p.int("formation-group")) { 1 -> "GK"; 2 -> "DF"; 3 -> "MF"; 4 -> "FW"; else -> if (p.bool("is-goalkeeper") == true) "GK" else null },
        p["image"],
    )

    public fun lineups(gameId: String, root: XmlNode, info: XmlNode): List<Lineup> {
        val infoTeams = info.child("teams")?.childrenNamed("team").orEmpty()
        return root.child("teams")?.childrenNamed("team").orEmpty().mapNotNull { t ->
            val players = t.child("lineup")?.childrenNamed("player").orEmpty()
            if (players.isEmpty()) return@mapNotNull null
            val ref = infoTeams.firstOrNull { it["id"] == t["id"] }?.let(::teamRef) ?: teamRef(t)
            Lineup(
                gameId, ref,
                listOf(
                    LineupGroup(LineupGroupKind.STARTERS, "Starting XI", players.filter { it["position"] != "Sub" }.sortedBy { it.int("position") ?: 99 }.map(::player)),
                    LineupGroup(LineupGroupKind.BENCH, "Bench", players.filter { it["position"] == "Sub" }.map(::player)),
                ),
                headCoach = t["coach-name"],
                formation = footballFormation(t["formation-desc"]?.takeIf { it != "0" }),
            )
        }
    }
}
