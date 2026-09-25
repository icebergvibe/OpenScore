package org.openscore.providers.khl

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
import org.openscore.model.PeriodType
import org.openscore.model.PlayerRef
import org.openscore.model.Score
import org.openscore.model.StageKind
import org.openscore.model.StandingsGroup
import org.openscore.model.StandingsRow
import org.openscore.model.StandingsTable
import org.openscore.model.Team
import org.openscore.model.TeamRef
import org.openscore.model.hockey.GoalDetails
import org.openscore.model.hockey.HockeyEventType
import org.openscore.model.hockey.PenaltyDetails
import org.openscore.model.hockey.ShootoutAttemptDetails
import org.openscore.model.hockey.Strength
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Pure functions from KHL (webcaster.pro) DTOs to the core model. */
public object KhlMapper {

    private const val LEAGUE_ID = "khl"

    public fun teamRef(t: KhlTeam): TeamRef = TeamRef(LEAGUE_ID, t.id.toString(), t.name, null, t.image)

    public fun team(t: KhlTeam): Team = Team(
        ref = teamRef(t),
        placeName = t.location,
        conference = t.conference,
        division = t.division,
    )

    public fun period(n: Int): Period = when {
        n <= 3 -> Period(n, PeriodType.REGULATION, n.toString())
        n == 4 -> Period(4, PeriodType.OVERTIME, "OT")
        else -> Period(5, PeriodType.SHOOTOUT, "SO")
    }

    private fun periodLength(p: Period, stage: StageKind): Duration? = when (p.type) {
        PeriodType.REGULATION -> 20.minutes
        PeriodType.OVERTIME -> if (stage == StageKind.PLAYOFF) 20.minutes else 5.minutes
        else -> null
    }

    /** Game seconds → period time; the period comes from the feed (null = shootout). */
    private fun time(gameSeconds: Int, rawPeriod: Int?, stage: StageKind): GameTime {
        val period = period(rawPeriod ?: 5)
        if (period.type == PeriodType.SHOOTOUT) return GameTime(period)
        val start = if (period.type == PeriodType.OVERTIME) 3600 else (period.number - 1) * 1200
        val elapsed = (gameSeconds - start).coerceAtLeast(0).seconds
        val len = periodLength(period, stage)
        return GameTime(period, elapsed, if (len != null && elapsed <= len) len - elapsed else null)
    }

    public fun stage(e: KhlEvent): StageKind = when {
        e.stage_type == "playoff" || e.stage_name?.startsWith("Playoff", true) == true -> StageKind.PLAYOFF
        else -> StageKind.REGULAR
    }

    /** `"5:1"` → home 5, away 1. */
    public fun score(text: String?): Score? {
        val parts = text?.split(':') ?: return null
        if (parts.size != 2) return null
        val h = parts[0].trim().toIntOrNull() ?: return null
        val a = parts[1].trim().toIntOrNull() ?: return null
        return Score(h, a)
    }

    public fun ending(s: KhlScores?): GameEnding = when {
        s?.bullitt != null -> GameEnding.SHOOTOUT
        s?.overtime != null -> GameEnding.OVERTIME
        else -> GameEnding.REGULATION
    }

    /**
     * `game_state_key` is the flag (`in_progress` observed 2026-09-13; anything other than
     * not_yet_started/finished is live). During a live game the newest `state` text event
     * saying "End of …" means an intermission.
     */
    public fun gameState(e: KhlEvent): GameState = when (e.game_state_key) {
        "not_yet_started" -> GameState.SCHEDULED
        "finished" -> GameState.FINAL
        "" -> GameState.UNKNOWN
        else -> if (isBreakText(latestStateText(e))) GameState.INTERMISSION else GameState.LIVE
    }

    private fun latestStateText(e: KhlEvent): String? = e.text_events.firstOrNull { it.type == "state" }?.text

    private fun isBreakText(text: String?): Boolean =
        text != null && text.startsWith("End of", ignoreCase = true) && !text.contains("game", true)

    /**
     * The period an intermission follows, from the same `End of …` text that makes it an
     * intermission: `End of 1 period` → 1, `End of overtime` → 4.
     *
     * The feed's own `period` is **10** during every break, which is not a period number at all
     * (a finished game reads -1). Passing it through [period] made it period 5, and period 5 is
     * the shootout, so all three intermissions of the 2026-09-13 capture rendered as `SO` on a
     * game that never went past regulation. A break belongs to the period that just ended.
     */
    private fun breakPeriod(e: KhlEvent): Int? {
        val text = latestStateText(e)?.takeIf(::isBreakText) ?: return null
        if (text.contains("overtime", true)) return 4
        return Regex("""\d+""").find(text)?.value?.toIntOrNull()
    }

    /** The period to show: during a break the one that just ended, otherwise the feed's own. */
    private fun currentPeriod(e: KhlEvent, state: GameState): Int? =
        if (state == GameState.INTERMISSION) breakPeriod(e) else e.period?.takeIf { it >= 1 }

    public fun periodScores(s: KhlScores?): List<PeriodScore> {
        if (s == null) return emptyList()
        val out = ArrayList<PeriodScore>()
        listOf(s.first_period, s.second_period, s.third_period).forEachIndexed { i, text ->
            score(text)?.let { out += PeriodScore(period(i + 1), it.home, it.away) }
        }
        score(s.overtime)?.let { out += PeriodScore(period(4), it.home, it.away) }
        score(s.bullitt)?.let { out += PeriodScore(period(5), it.home, it.away) }
        return out
    }

    public fun game(e: KhlEvent, withEvents: Boolean): Game {
        val state = gameState(e)
        val stage = stage(e)
        return Game(
            leagueId = LEAGUE_ID,
            id = e.id.toString(),
            seasonId = e.stage_id?.toString(),
            stage = stage,
            startTime = Instant.fromEpochMilliseconds(e.start_at),
            venue = e.arena?.name ?: e.location,
            home = teamRef(e.team_a),
            away = teamRef(e.team_b),
            state = state,
            score = if (state == GameState.SCHEDULED) null else score(e.score),
            // The feed has the current period but no clock; elapsed/remaining stay null.
            clock = currentPeriod(e, state)?.takeIf { state.isLive }
                ?.let { Clock(GameTime(period(it)), running = if (state == GameState.INTERMISSION) false else null) },
            periodScores = periodScores(e.scores),
            ending = if (state.isFinished) ending(e.scores) else null,
            events = if (withEvents) events(e, stage) else null,
            rawState = listOfNotNull(e.game_state_key, e.period?.let { "period=$it" }).joinToString("/"),
        )
    }

    // ---- events --------------------------------------------------------------------------

    private class Roster(e: KhlEvent) {
        private val byTeamAndNumber: Map<Pair<Int, Int>, KhlRosterPlayer> =
            (e.team_a.players.map { e.team_a.id to it } + e.team_b.players.map { e.team_b.id to it })
                .filter { (_, p) -> p.shirt_number != null }
                .associate { (teamId, p) -> (teamId to p.shirt_number!!) to p }
        private val byTeamAndName: Map<Pair<Int, String>, KhlRosterPlayer> =
            (e.team_a.players.map { e.team_a.id to it } + e.team_b.players.map { e.team_b.id to it })
                .associate { (teamId, p) -> (teamId to p.name) to p }

        fun ref(teamId: Int?, number: Int?, name: String): PlayerRef {
            val p = (if (teamId != null && number != null) byTeamAndNumber[teamId to number] else null)
                ?: (if (teamId != null) byTeamAndName[teamId to name] else null)
            return if (p != null) playerRef(p) else PlayerRef(LEAGUE_ID, "${teamId ?: "?"}-${number ?: name}", name, number)
        }
    }

    public fun playerRef(p: KhlRosterPlayer): PlayerRef = PlayerRef(
        leagueId = LEAGUE_ID,
        id = p.id.toString(),
        name = p.name,
        jerseyNumber = p.shirt_number,
        position = when (p.role_key) { "goaltender" -> "G"; "defensemen" -> "D"; "forward" -> "F"; else -> p.role_key },
        headshotUrl = p.image,
    )

    public fun strength(abbr: String?): Strength? = when (abbr) {
        "ES", "EV" -> Strength.EV
        "PP", "PP1", "PP2" -> Strength.PP
        "SH", "SH1", "SH2" -> Strength.SH
        "EN" -> Strength.EN
        "PS" -> Strength.PS
        else -> null
    }

    public fun events(e: KhlEvent, stage: StageKind): List<GameEvent> {
        val roster = Roster(e)
        val home = teamRef(e.team_a)
        val away = teamRef(e.team_b)
        fun team(id: Int?): TeamRef? = when (id) { e.team_a.id -> home; e.team_b.id -> away; else -> null }
        fun teamByName(name: String?): TeamRef? = when (name) { e.team_a.name -> home; e.team_b.name -> away; else -> null }
        val out = ArrayList<GameEvent>()
        var seq = 0

        for (g in e.goals) {
            if (g.period == null || g.status_abbr == "SO") continue // the deciding shootout goal; attempts come from `bullet` texts
            seq++
            val teamId = g.author?.team_id
            val scorer = g.author?.let { roster.ref(teamId, it.shirt_number, it.name) }
            val assists = g.assistants.map { roster.ref(teamId, it.shirt_number, it.name) }
            val strength = strength(g.status_abbr)
            out += GameEvent(
                id = "goal-${g.time}-${seq}", type = HockeyEventType.GOAL, rawType = "goal" + (g.status_abbr?.let { ":$it" } ?: ""),
                time = time(g.time, g.period, stage), team = team(teamId),
                players = listOfNotNull(scorer) + assists, score = score(g.score),
                description = buildString {
                    append("Goal — ").append(scorer?.name ?: "?")
                    if (assists.isNotEmpty()) append(" · ").append(assists.joinToString { it.name })
                    strength?.takeIf { it != Strength.EV }?.let { append(" [").append(it).append(']') }
                },
                details = GoalDetails(scorer = scorer, assists = assists, strength = strength, emptyNet = strength == Strength.EN),
                sortOrder = g.time,
            )
        }
        for (v in e.violations) {
            seq++
            val teamId = v.violator?.team_id
            val player = v.violator?.let { roster.ref(teamId, it.shirt_number, it.name) }
            out += GameEvent(
                id = "penalty-${v.time}-$seq", type = HockeyEventType.PENALTY, rawType = "violation",
                time = time(v.time, v.period, stage), team = team(teamId), players = listOfNotNull(player),
                description = listOfNotNull(player?.name ?: "Team penalty", v.penalty_time?.let { "$it min" }, v.penalty_reason).joinToString(" — "),
                details = PenaltyDetails(player = player, minutes = v.penalty_time, infraction = v.penalty_reason),
                sortOrder = v.time,
            )
        }
        // text_events are newest first; `seconds`/`time_s` are broadcast/wall time, not game time.
        for (t in e.text_events.asReversed()) {
            seq++
            when (t.type) {
                "state" -> {
                    val text = t.text
                    val period = t.period?.let(::period)
                    val len = period?.let { periodLength(it, stage) }
                    when {
                        text.startsWith("End of the game", true) -> {
                            val last = period ?: lastPeriod(e)
                            val lastLen = periodLength(last, stage)
                            out += GameEvent(
                                id = "state-$seq", type = HockeyEventType.GAME_END, rawType = "state", description = text,
                                time = GameTime(last, lastLen, if (lastLen != null) Duration.ZERO else null), sortOrder = Int.MAX_VALUE,
                            )
                        }
                        period != null && text.startsWith("Start", true) -> out += GameEvent(
                            id = "state-$seq", type = HockeyEventType.PERIOD_START, rawType = "state", description = text,
                            time = GameTime(period, Duration.ZERO, len), sortOrder = -1,
                        )
                        period != null && text.startsWith("End", true) -> out += GameEvent(
                            id = "state-$seq", type = HockeyEventType.PERIOD_END, rawType = "state", description = text,
                            time = GameTime(period, len, if (len != null) Duration.ZERO else null), sortOrder = Int.MAX_VALUE - 1,
                        )
                    }
                }
                "bullet" -> {
                    val attempt = parseBullet(t.text)
                    val shooterTeam = teamByName(attempt?.shooterTeam)
                    val shooter = attempt?.let { roster.ref(shooterTeam?.id?.toIntOrNull(), it.shooterNumber, it.shooter) }
                    val goalie = attempt?.let { roster.ref(teamByName(it.goalieTeam)?.id?.toIntOrNull(), it.goalieNumber, it.goalie) }
                    out += GameEvent(
                        id = "so-$seq", type = HockeyEventType.SHOOTOUT_ATTEMPT, rawType = "bullet",
                        time = GameTime(period(5)), team = shooterTeam, players = listOfNotNull(shooter, goalie),
                        score = score(t.score), description = t.text,
                        details = ShootoutAttemptDetails(shooter, goalie, scored = attempt?.scored == true),
                        sortOrder = seq,
                    )
                }
                "replace" -> out += GameEvent(
                    id = "replace-$seq", type = HockeyEventType.GOALIE_CHANGE, rawType = "replace",
                    time = GameTime(t.period?.let(::period) ?: lastPeriod(e)), description = t.text, sortOrder = seq,
                )
            }
        }
        return out.sortedWith(compareBy({ it.period.number }, { it.time.elapsed?.inWholeSeconds ?: 0L }, { it.sortOrder ?: 0 }))
    }

    private fun lastPeriod(e: KhlEvent): Period = when {
        e.scores?.bullitt != null -> period(5)
        e.scores?.overtime != null -> period(4)
        else -> period(3)
    }

    public class Bullet(
        val shooterNumber: Int?, val shooter: String, val shooterTeam: String,
        val goalieNumber: Int?, val goalie: String, val goalieTeam: String,
        val scored: Boolean,
    )

    /** `"Shootout by 20.Lockhart Lucas (Spartak) vs 31.Ozolin Yaroslav (Neftekhimik). Missed."` */
    public fun parseBullet(text: String): Bullet? {
        val m = BULLET.find(text) ?: return null
        val (n1, name1, team1, n2, name2, team2, result) = m.destructured
        return Bullet(n1.toIntOrNull(), name1.trim(), team1.trim(), n2.toIntOrNull(), name2.trim(), team2.trim(), result.trim().equals("Goal", true))
    }

    private val BULLET = Regex("""Shootout by (\d+)\.([^()]+)\(([^)]+)\) vs (\d+)\.([^()]+)\(([^)]+)\)\.\s*(\w+)""")

    // ---- lineups -------------------------------------------------------------------------

    public fun lineups(e: KhlEvent): List<Lineup> {
        fun side(t: KhlTeam): Lineup {
            val groups = ArrayList<LineupGroup>()
            if (t.start_fives.isNotEmpty()) groups += LineupGroup(LineupGroupKind.STARTERS, "Starting six", t.start_fives.map(::playerRef))
            groups += LineupGroup(LineupGroupKind.FORWARDS, "Forwards", t.players.filter { it.role_key == "forward" }.map(::playerRef))
            groups += LineupGroup(LineupGroupKind.DEFENSE, "Defense", t.players.filter { it.role_key == "defensemen" }.map(::playerRef))
            groups += LineupGroup(LineupGroupKind.GOALIES, "Goalies", t.players.filter { it.role_key == "goaltender" }.map(::playerRef))
            return Lineup(e.id.toString(), teamRef(t), groups)
        }
        if (e.team_a.players.isEmpty() && e.team_b.players.isEmpty()) return emptyList()
        return listOf(side(e.team_a), side(e.team_b))
    }

    // ---- standings -----------------------------------------------------------------------

    public fun standings(stage: KhlStageTables, seasonId: String): StandingsTable {
        val rows = stage.regular ?: emptyList()
        fun int(s: String) = s.trim().toIntOrNull() ?: 0
        val mapped = rows.map { r ->
            val wins = int(r.w) + int(r.otw) + int(r.sow)
            val otherLosses = int(r.otl) + int(r.sol)
            StandingsRow(
                team = TeamRef(LEAGUE_ID, r.id.toString(), r.name, null, r.image),
                rank = 0,
                played = int(r.gp),
                wins = wins,
                losses = int(r.l),
                otherLosses = otherLosses,
                points = int(r.pts),
                goalsFor = int(r.gf),
                goalsAgainst = int(r.ga),
                goalDifference = int(r.gf) - int(r.ga),
                extra = buildMap {
                    put("regulationWins", r.w)
                    put("overtimeWins", r.otw)
                    put("shootoutWins", r.sow)
                    r.conference?.let { put("conference", it) }
                },
            ) to r
        }
        val groups = mapped
            .groupBy { (_, r) -> r.division ?: r.conference ?: "League" }
            .entries
            .sortedWith(compareBy({ it.value.first().second.conference_key ?: "" }, { it.key }))
            .map { (label, members) ->
                StandingsGroup(label, members.map { it.first }.sortedWith(compareByDescending<StandingsRow> { it.points }.thenBy { it.played }).mapIndexed { i, row -> row.copy(rank = i + 1) })
            }
        return StandingsTable(LEAGUE_ID, seasonId, if (stage.type == "playoff") StageKind.PLAYOFF else StageKind.REGULAR, groups, grouping = if (rows.any { it.division != null }) "division" else "league")
    }
}
