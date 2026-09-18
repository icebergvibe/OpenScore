package org.openscore.providers.laliga

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
import org.openscore.model.Player
import org.openscore.model.PlayerRef
import org.openscore.model.Score
import org.openscore.model.StageKind
import org.openscore.model.StandingsGroup
import org.openscore.model.StandingsRow
import org.openscore.model.StandingsTable
import org.openscore.model.StatPair
import org.openscore.model.Team
import org.openscore.model.TeamRef
import org.openscore.model.football.CardDetails
import org.openscore.model.football.CardKind
import org.openscore.model.football.FootballEventType
import org.openscore.model.football.FootballGoalDetails
import org.openscore.model.football.GoalKind
import org.openscore.model.football.PenaltyMissDetails
import org.openscore.model.football.SubstitutionDetails
import org.openscore.model.football.footballFormation
import org.openscore.provider.Dates
import org.openscore.providers.football.FootballPeriods
import kotlin.math.floor
import kotlin.time.Duration
import kotlin.time.Instant

/** Pure functions from apim.laliga.com DTOs to the core model. Team ids are team slugs. */
public class LaLigaMapper(private val leagueId: String) {

    public fun teamRef(t: LlTeam?): TeamRef = TeamRef(
        leagueId = leagueId,
        id = t?.slug ?: t?.id?.toString() ?: "",
        name = t?.nickname ?: t?.name ?: t?.slug ?: "",
        abbreviation = t?.shortname,
        logoUrl = t?.shield?.url,
    )

    public fun team(t: LlTeam): Team = Team(
        ref = teamRef(t),
        commonName = t.nickname,
        placeName = t.venue?.city,
        arena = t.venue?.name,
        country = "ESP",
    )

    public fun gameState(status: String): GameState = when (status) {
        "PreMatch" -> GameState.SCHEDULED
        "FirstHalf", "SecondHalf", "ExtraFirstHalf", "ExtraSecondHalf", "ShootOut", "Live" -> GameState.LIVE
        "HalfTime", "ExtraHalfTime", "FullTime90" -> GameState.INTERMISSION
        "FullTime" -> GameState.FINAL
        "Postponed" -> GameState.POSTPONED
        "Canceled", "Cancelled" -> GameState.CANCELLED
        "Abandoned" -> GameState.SUSPENDED
        "" -> GameState.UNKNOWN
        else -> GameState.UNKNOWN
    }

    public fun currentPeriod(status: String): Period? = when (status) {
        "FirstHalf", "HalfTime" -> FootballPeriods.FIRST_HALF
        "SecondHalf", "FullTime90" -> FootballPeriods.SECOND_HALF
        "ExtraFirstHalf", "ExtraHalfTime" -> FootballPeriods.EXTRA_FIRST
        "ExtraSecondHalf" -> FootballPeriods.EXTRA_SECOND
        "ShootOut" -> FootballPeriods.PENALTIES
        else -> null
    }

    private fun periodKey(p: Period): String = when (p.number) { 1 -> "FirstHalf"; 2 -> "SecondHalf"; 3 -> "ExtraFirstHalf"; 4 -> "ExtraSecondHalf"; else -> "ShootOut" }

    private fun periodByName(name: String?, minute: Int?): Period = when (name) {
        "FirstHalf" -> FootballPeriods.FIRST_HALF
        "SecondHalf" -> FootballPeriods.SECOND_HALF
        "ExtraFirstHalf" -> FootballPeriods.EXTRA_FIRST
        "ExtraSecondHalf" -> FootballPeriods.EXTRA_SECOND
        "ShootOut" -> FootballPeriods.PENALTIES
        else -> FootballPeriods.periodForMinute(minute ?: 0)
    }

    public fun game(m: LlMatch, now: Instant, events: LlEvents? = null, stats: LlMatchStats? = null, seasonId: String? = m.subscription?.slug): Game {
        val state = gameState(m.status)
        val home = teamRef(m.home_team)
        val away = teamRef(m.away_team)
        val score = if (state != GameState.SCHEDULED && m.home_score != null && m.away_score != null) Score(m.home_score, m.away_score) else null
        val mapped = events?.let { events(it, m, home, away) }
        val period = currentPeriod(m.status)
        return Game(
            leagueId = leagueId,
            id = m.slug ?: m.id.toString(),
            seasonId = seasonId,
            stage = StageKind.REGULAR,
            startTime = Dates.instant(m.date ?: m.time ?: error("match ${m.id} has no date")),
            venue = m.venue?.name,
            home = home,
            away = away,
            state = state,
            score = score,
            clock = clock(m, state, period, now),
            periodScores = periodScores(mapped, period, state, score, home, away),
            ending = if (state.isFinished) (if (m.period_started.containsKey("ShootOut")) GameEnding.SHOOTOUT else if (m.period_started.containsKey("ExtraFirstHalf")) GameEnding.OVERTIME else GameEnding.REGULATION) else null,
            events = mapped,
            stats = stats?.let { stats(it, m) }.orEmpty(),
            rawState = m.status + (m.match_time?.let { "/$it'" } ?: ""),
        )
    }

    /** Second-precision `period_started` when present, else the whole-minute `match_time`. */
    private fun clock(m: LlMatch, state: GameState, period: Period?, now: Instant): Clock? {
        if (!state.isLive || period == null) return null
        val times = m.period_started[periodKey(period)]
        if (times?.start != null) {
            val until = times.stop?.let(Dates::instant) ?: now
            val elapsed = (until - Dates.instant(times.start)).let { if (it.isNegative()) Duration.ZERO else it }
            return Clock(FootballPeriods.timeInPeriod(period, elapsed), running = times.stop == null)
        }
        val minute = m.match_time ?: return Clock(GameTime(period), running = state == GameState.LIVE)
        return Clock(FootballPeriods.fromCumulative(period, minute), running = state == GameState.LIVE)
    }

    private fun periodScores(events: List<GameEvent>?, current: Period?, state: GameState, score: Score?, home: TeamRef, away: TeamRef): List<PeriodScore> {
        if (score == null || events == null) return emptyList()
        val last = current ?: if (state.isFinished) FootballPeriods.SECOND_HALF else return emptyList()
        val goals = events.filter { it.type.isGoal }
        return (1..last.number).map { n ->
            val p = when (n) { 1 -> FootballPeriods.FIRST_HALF; 2 -> FootballPeriods.SECOND_HALF; 3 -> FootballPeriods.EXTRA_FIRST; 4 -> FootballPeriods.EXTRA_SECOND; else -> FootballPeriods.PENALTIES }
            PeriodScore(p, goals.count { it.period.number == n && it.team?.id == home.id }, goals.count { it.period.number == n && it.team?.id == away.id })
        }
    }

    private fun stats(s: LlMatchStats, m: LlMatch): Map<String, StatPair> {
        val home = s.match_team_stats.firstOrNull { it.opta_team_id == m.home_team?.opta_id }?.stats ?: return emptyMap()
        val away = s.match_team_stats.firstOrNull { it.opta_team_id == m.away_team?.opta_id }?.stats ?: return emptyMap()
        fun num(e: kotlinx.serialization.json.JsonElement?): Double? = (e as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        fun fmt(v: Double) = if (v == floor(v)) v.toLong().toString() else v.toString()
        fun pair(key: String, default: Double? = null): StatPair? {
            val h = num(home[key]) ?: default ?: return null
            val a = num(away[key]) ?: default ?: return null
            return StatPair(fmt(h), fmt(a))
        }
        return listOfNotNull(
            pair("possession_percentage")?.let { "possession" to it },
            pair("total_scoring_att", 0.0)?.let { "shots" to it },
            pair("ontarget_scoring_att", 0.0)?.let { "shotsOnTarget" to it },
            pair("corner_taken", 0.0)?.let { "corners" to it },
            pair("fk_foul_lost", 0.0)?.let { "fouls" to it },
            pair("total_offside", 0.0)?.let { "offsides" to it },
            pair("total_pass", 0.0)?.let { "passes" to it },
            pair("total_yel_card", 0.0)?.let { "yellowCards" to it },
            pair("total_red_card", 0.0)?.let { "redCards" to it },
        ).toMap()
    }

    // ---- events --------------------------------------------------------------------------

    private fun ref(l: LlEventLineup?): PlayerRef? {
        val p = l?.person ?: return null
        val name = p.name ?: listOfNotNull(p.firstname, p.lastname).joinToString(" ")
        return PlayerRef(leagueId, p.slug ?: p.id?.toString() ?: name, name)
    }

    public fun events(e: LlEvents, m: LlMatch, home: TeamRef, away: TeamRef): List<GameEvent> {
        fun team(id: Int?): TeamRef? = when (id) { m.home_team?.id -> home; m.away_team?.id -> away; else -> null }
        val out = ArrayList<GameEvent>()
        var h = 0
        var a = 0
        val ordered = e.match_events.sortedWith(compareBy({ it.minute ?: 0 }, { it.second ?: 0 }, { it.id ?: 0 }))
        for ((i, ev) in ordered.withIndex()) {
            val period = periodByName(ev.period, ev.time ?: ev.minute)
            val time = if (ev.minute != null) FootballPeriods.fromCumulative(period, ev.minute, ev.second ?: 0, floorMinutes = true)
                else if (ev.time != null) FootballPeriods.fromCumulative(period, ev.time) else GameTime(period)
            val teamRef = team(ev.lineup?.team?.id)
            val player = ref(ev.lineup)
            val id = ev.id?.toString() ?: "event-$i"
            val kind = ev.match_event_kind
            val name = kind?.name?.lowercase().orEmpty()
            val collection = kind?.collection?.lowercase().orEmpty()
            val event: GameEvent = when {
                collection == "goal" -> {
                    val goalKind = when {
                        name.contains("own") -> GoalKind.OWN_GOAL
                        name.contains("penalty") -> GoalKind.PENALTY
                        else -> GoalKind.OPEN_PLAY
                    }
                    val type = when (goalKind) { GoalKind.OWN_GOAL -> FootballEventType.OWN_GOAL; GoalKind.PENALTY -> FootballEventType.PENALTY_GOAL; else -> FootballEventType.GOAL }
                    // The feed's `lineup.team` is the scorer's side; an own goal is credited to the other one
                    // (Athletic–Elche 2026-09-12: Chust's own goal carried Elche and the 1–1 belonged to Athletic).
                    val credited = if (goalKind == GoalKind.OWN_GOAL) when (teamRef?.id) { home.id -> away; away.id -> home; else -> null } else teamRef
                    if (credited?.id == home.id) h++ else if (credited?.id == away.id) a++
                    val assist = ref(ev.assist).takeIf { goalKind != GoalKind.OWN_GOAL }
                    GameEvent(
                        id = id, type = type, rawType = "goal:$name", time = time, team = credited, players = listOfNotNull(player, assist), score = Score(h, a),
                        description = (if (goalKind == GoalKind.OWN_GOAL) "Own goal — " else if (goalKind == GoalKind.PENALTY) "Penalty — " else "Goal — ") + (player?.name ?: "?") + (assist?.let { " · ${it.name}" } ?: ""),
                        details = FootballGoalDetails(player, assist, goalKind), sortOrder = i,
                    )
                }
                collection == "booking" -> {
                    val (type, card) = when {
                        name.contains("second") || name.contains("yellow/red") || name.contains("2nd") -> FootballEventType.SECOND_YELLOW to CardKind.SECOND_YELLOW
                        name.contains("red") -> FootballEventType.RED_CARD to CardKind.RED
                        else -> FootballEventType.YELLOW_CARD to CardKind.YELLOW
                    }
                    GameEvent(id = id, type = type, rawType = "booking:$name", time = time, team = teamRef, players = listOfNotNull(player), description = "${kind?.name} — ${player?.name ?: "?"}", details = CardDetails(player, card), sortOrder = i)
                }
                collection == "substitution" -> {
                    val off = ref(ev.lineup_off)
                    GameEvent(id = id, type = FootballEventType.SUBSTITUTION, rawType = "substitution:$name", time = time, team = teamRef, players = listOfNotNull(player, off), description = "Sub — ${player?.name ?: "?"} for ${off?.name ?: "?"}", details = SubstitutionDetails(player, off, kind?.name?.lowercase()), sortOrder = i)
                }
                name.contains("penalty") && (name.contains("miss") || name.contains("saved")) -> GameEvent(id = id, type = FootballEventType.PENALTY_MISSED, rawType = name, time = time, team = teamRef, players = listOfNotNull(player), details = PenaltyMissDetails(player), sortOrder = i)
                else -> GameEvent(id = id, type = FootballEventType.OTHER, rawType = "$collection:$name", time = time, team = teamRef, players = listOfNotNull(player), description = kind?.name, sortOrder = i)
            }
            out += event
        }
        // Period markers from the header's second-precision timestamps.
        for ((key, times) in m.period_started) {
            val period = periodByName(key, null)
            if (times.start != null) out += GameEvent(id = "$key-start", type = FootballEventType.PERIOD_START, rawType = "period", time = GameTime(period, Duration.ZERO, label = FootballPeriods.label(FootballPeriods.offsetMinutes(period))), description = "Start of ${period.label}", sortOrder = -1)
            if (times.start != null && times.stop != null) {
                val len = Dates.instant(times.stop) - Dates.instant(times.start)
                val isEnd = key == "SecondHalf" && m.status == "FullTime" && !m.period_started.containsKey("ExtraFirstHalf")
                out += GameEvent(id = "$key-end", type = if (isEnd) FootballEventType.GAME_END else FootballEventType.PERIOD_END, rawType = "period", time = GameTime(period, len), description = "End of ${period.label}", sortOrder = Int.MAX_VALUE)
            }
        }
        return out.sortedWith(compareBy({ it.period.number }, { it.time.elapsed?.inWholeSeconds ?: 0L }, { it.sortOrder ?: 0 }))
    }

    // ---- lineups -------------------------------------------------------------------------

    private fun ref(row: LlLineupRow): PlayerRef {
        val p = row.person
        val name = p?.name ?: listOfNotNull(p?.firstname, p?.lastname).joinToString(" ")
        return PlayerRef(leagueId, p?.slug ?: row.id?.toString() ?: name, name, row.shirt_number, if (row.position == 1) "GK" else null)
    }

    public fun lineups(gameId: String, l: LlLineups, m: LlMatch, home: TeamRef, away: TeamRef): List<Lineup> {
        fun side(team: TeamRef, rows: List<LlLineupRow>, formation: String?): Lineup? {
            val starters = rows.filter { it.status == "start" }.sortedBy { it.position }
            if (starters.isEmpty()) return null
            val bench = rows.filter { it.status == "sub" }.sortedBy { it.position }
            val coach = rows.firstOrNull { it.position == 0 }?.person?.name
            return Lineup(gameId, team, listOf(LineupGroup(LineupGroupKind.STARTERS, "Starting XI", starters.map(::ref)), LineupGroup(LineupGroupKind.BENCH, "Bench", bench.map(::ref))), coach, footballFormation(formation))
        }
        return listOfNotNull(side(home, l.home_team_lineups, m.home_formation), side(away, l.away_team_lineups, m.away_formation))
    }

    // ---- standings / squad / player -------------------------------------------------------

    public fun standings(s: LlStandings, seasonId: String?, label: String): StandingsTable {
        val rows = s.standings.sortedBy { it.position }.map { r ->
            StandingsRow(
                team = teamRef(r.team), rank = r.position, played = r.played, wins = r.won, losses = r.lost, draws = r.drawn, points = r.points,
                goalsFor = r.goals_for, goalsAgainst = r.goals_against, goalDifference = r.goals_for - r.goals_against,
                extra = buildMap { r.previous_position?.let { put("previousPosition", it.toString()) } },
            )
        }
        return StandingsTable(leagueId, seasonId, StageKind.REGULAR, listOf(StandingsGroup(label, rows)), grouping = "league")
    }

    private fun positionCode(name: String?): String? = when (name) { "Goalkeeper" -> "GK"; "Defender" -> "DF"; "Midfielder" -> "MF"; "Forward" -> "FW"; else -> name }

    public fun player(p: LlPerson, shirt: Int?, position: String?, teamId: String?): Player = Player(
        ref = PlayerRef(leagueId, p.slug ?: p.id?.toString() ?: p.name.orEmpty(), p.name ?: listOfNotNull(p.firstname, p.lastname).joinToString(" "), shirt, positionCode(position)),
        firstName = p.firstname,
        lastName = p.lastname,
        birthDate = Dates.localDateOrNull(p.date_of_birth),
        birthPlace = p.place_of_birth,
        nationality = p.country?.id,
        heightCm = p.height,
        weightKg = p.weight,
        teamId = teamId,
    )
}
