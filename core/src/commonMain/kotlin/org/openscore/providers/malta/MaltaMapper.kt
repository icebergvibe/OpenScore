package org.openscore.providers.malta

import kotlinx.datetime.LocalDate
import org.openscore.model.Clock
import org.openscore.model.Game
import org.openscore.model.GameEnding
import org.openscore.model.GameEvent
import org.openscore.model.GameState
import org.openscore.model.GameTime
import org.openscore.model.Lineup
import org.openscore.model.LineupGroup
import org.openscore.model.LineupGroupKind
import org.openscore.model.PeriodScore
import org.openscore.model.Player
import org.openscore.model.PlayerNames
import org.openscore.model.PlayerRef
import org.openscore.model.Score
import org.openscore.model.StageKind
import org.openscore.model.StandingsGroup
import org.openscore.model.StandingsRow
import org.openscore.model.StandingsTable
import org.openscore.model.Team
import org.openscore.model.TeamRef
import org.openscore.model.football.CardDetails
import org.openscore.model.football.CardKind
import org.openscore.model.football.FootballEventType
import org.openscore.model.football.FootballGoalDetails
import org.openscore.model.football.GoalKind
import org.openscore.model.football.PenaltyMissDetails
import org.openscore.model.football.SubstitutionDetails
import org.openscore.providers.football.FootballPeriods
import kotlin.time.Instant

/** Pure functions from the MFA match-centre DTOs to the core model. */
public class MaltaMapper(private val leagueId: String) {

    public fun teamRef(t: MtTeam): TeamRef = TeamRef(leagueId, t.id.toString(), t.name, null, t.image)

    public fun team(t: MtTeam): Team = Team(ref = teamRef(t), country = "MLT")

    /** `12'`, `45+2'`, `HT`, `FT` → state + clock. */
    public fun gameState(status: String, isLive: Boolean, matchTime: String?): GameState = when {
        status == "PLAYED" -> GameState.FINAL
        status == "POSTPONED" -> GameState.POSTPONED
        status == "CANCELLED" || status == "CANCELED" -> GameState.CANCELLED
        status == "ABANDONED" -> GameState.SUSPENDED
        isLive || status == "LIVE" -> if (matchTime?.uppercase() == "HT") GameState.INTERMISSION else GameState.LIVE
        status == "SCHEDULED" -> GameState.SCHEDULED
        status.isEmpty() -> GameState.UNKNOWN
        else -> GameState.UNKNOWN
    }

    private fun clock(state: GameState, matchTime: String?): Clock? {
        if (!state.isLive) return null
        if (matchTime?.uppercase() == "HT") return Clock(FootballPeriods.timeAtMinute(FootballPeriods.FIRST_HALF, 45), running = false)
        val (minute, stoppage) = FootballPeriods.parseMinute(matchTime) ?: return Clock(GameTime(FootballPeriods.FIRST_HALF), running = true)
        return Clock(FootballPeriods.timeAtMinute(FootballPeriods.periodForMinute(minute), minute, stoppage), running = true)
    }

    public fun game(m: MtMatch, result: MtResult?, withEvents: Boolean): Game {
        val status = result?.status ?: m.status
        val isLive = result?.isLive ?: m.isLive
        val matchTime = result?.matchTime ?: m.matchTime
        val state = gameState(status, isLive, matchTime)
        val home = teamRef(m.homeTeam)
        val away = teamRef(m.awayTeam)
        val score = if (state != GameState.SCHEDULED && result != null) Score(result.homeScore?.toIntOrNull() ?: 0, result.awayScore?.toIntOrNull() ?: 0) else null
        val events = if (withEvents && result != null) events(result, home, away, state) else null
        val pens = result?.let { (it.homePenalties?.toIntOrNull() ?: 0) + (it.awayPenalties?.toIntOrNull() ?: 0) } ?: 0
        return Game(
            leagueId = leagueId,
            id = m.id.toString(),
            seasonId = null,
            stage = StageKind.REGULAR,
            startTime = Instant.parse(m.startDate),
            venue = m.venue?.name,
            home = home,
            away = away,
            state = state,
            score = score,
            clock = clock(state, matchTime),
            periodScores = periodScores(events, state, score, matchTime, home, away),
            ending = if (state.isFinished) (if (pens > 0) GameEnding.SHOOTOUT else GameEnding.REGULATION) else null,
            events = events,
            rawState = listOfNotNull(status, matchTime).joinToString("/"),
        )
    }

    private fun periodScores(events: List<GameEvent>?, state: GameState, score: Score?, matchTime: String?, home: TeamRef, away: TeamRef): List<PeriodScore> {
        if (events == null || score == null) return emptyList()
        val goals = events.filter { it.type.isGoal }
        val minute = FootballPeriods.parseMinute(matchTime)?.first
        val periods = if (state.isFinished || matchTime?.uppercase() == "HT" || (minute != null && minute > 45)) {
            if (matchTime?.uppercase() == "HT") 1 else 2
        } else 1
        return (1..periods).map { n ->
            val p = if (n == 1) FootballPeriods.FIRST_HALF else FootballPeriods.SECOND_HALF
            PeriodScore(p, goals.count { it.period.number == n && it.team?.id == home.id }, goals.count { it.period.number == n && it.team?.id == away.id })
        }
    }

    // ---- events --------------------------------------------------------------------------

    private fun name(value: String): String = PlayerNames.display(value, leadingCapsAreFamilyName = true)

    private fun ref(id: Long?, name: String?): PlayerRef? = if (id == null && name == null) null else PlayerRef(leagueId, id?.toString() ?: name!!, name?.let(::name) ?: "#$id")

    /** Events carry only a minute label; the score is recomputed and the period inferred from the minute. */
    public fun events(r: MtResult, home: TeamRef, away: TeamRef, state: GameState): List<GameEvent> {
        val out = ArrayList<GameEvent>()
        var h = 0
        var a = 0
        for ((i, e) in r.events.withIndex()) {
            val (minute, stoppage) = FootballPeriods.parseMinute(e.time) ?: (0 to null)
            val period = FootballPeriods.periodForMinute(minute)
            val time = FootballPeriods.timeAtMinute(period, minute, stoppage)
            val team = when (e.team) { "HOME" -> home; "AWAY" -> away; else -> null }
            val player = ref(e.playerId, e.playerName)
            val id = e.id?.toString() ?: "event-$i"
            val event = when (e.type.uppercase()) {
                "GOAL", "PENALTY", "OWN_GOAL", "OWN GOAL" -> {
                    val kind = when (e.type.uppercase()) { "PENALTY" -> GoalKind.PENALTY; "OWN_GOAL", "OWN GOAL" -> GoalKind.OWN_GOAL; else -> GoalKind.OPEN_PLAY }
                    val type = when (kind) { GoalKind.PENALTY -> FootballEventType.PENALTY_GOAL; GoalKind.OWN_GOAL -> FootballEventType.OWN_GOAL; else -> FootballEventType.GOAL }
                    // An own goal is credited to the other side.
                    val scoringTeam = if (kind == GoalKind.OWN_GOAL) (if (team?.id == home.id) away else if (team?.id == away.id) home else null) else team
                    if (scoringTeam?.id == home.id) h++ else if (scoringTeam?.id == away.id) a++
                    GameEvent(id = id, type = type, rawType = e.type, time = time, team = scoringTeam, players = listOfNotNull(player), score = Score(h, a),
                        description = (if (kind == GoalKind.PENALTY) "Penalty — " else if (kind == GoalKind.OWN_GOAL) "Own goal — " else "Goal — ") + (player?.name ?: "?"),
                        details = FootballGoalDetails(player, null, kind), sortOrder = i)
                }
                "MISSED_PENALTY", "MISSED PENALTY", "PENALTY_MISSED" -> GameEvent(id = id, type = FootballEventType.PENALTY_MISSED, rawType = e.type, time = time, team = team, players = listOfNotNull(player), description = "Penalty missed — ${player?.name ?: "?"}", details = PenaltyMissDetails(player), sortOrder = i)
                "YELLOW", "YELLOW_CARD" -> GameEvent(id = id, type = FootballEventType.YELLOW_CARD, rawType = e.type, time = time, team = team, players = listOfNotNull(player), description = "Yellow — ${player?.name ?: "?"}", details = CardDetails(player, CardKind.YELLOW), sortOrder = i)
                "RED", "RED_CARD" -> GameEvent(id = id, type = FootballEventType.RED_CARD, rawType = e.type, time = time, team = team, players = listOfNotNull(player), description = "Red — ${player?.name ?: "?"}", details = CardDetails(player, CardKind.RED), sortOrder = i)
                "SECOND_YELLOW", "YELLOW_RED" -> GameEvent(id = id, type = FootballEventType.SECOND_YELLOW, rawType = e.type, time = time, team = team, players = listOfNotNull(player), description = "Second yellow — ${player?.name ?: "?"}", details = CardDetails(player, CardKind.SECOND_YELLOW), sortOrder = i)
                "SUBSTITUTION" -> {
                    val off = ref(e.playerIdTwo, e.playerNameTwo)
                    GameEvent(id = id, type = FootballEventType.SUBSTITUTION, rawType = e.type, time = time, team = team, players = listOfNotNull(player, off), description = "Sub — ${player?.name ?: "?"} for ${off?.name ?: "?"}", details = SubstitutionDetails(player, off), sortOrder = i)
                }
                else -> GameEvent(id = id, type = FootballEventType.OTHER, rawType = e.type, time = time, team = team, players = listOfNotNull(player), sortOrder = i)
            }
            out += event
        }
        if (state.isFinished) out += GameEvent(id = "full-time", type = FootballEventType.GAME_END, rawType = "FT", time = GameTime(FootballPeriods.SECOND_HALF, label = "FT"), description = "Full-time", sortOrder = Int.MAX_VALUE)
        return out
    }

    // ---- lineups -------------------------------------------------------------------------

    private fun ref(p: MtLineupPlayer): PlayerRef = PlayerRef(leagueId, p.id.toString(), name(p.name), p.number?.toIntOrNull(), if (p.isGoalkeeper) "GK" else null, p.profilePhoto)

    public fun lineups(gameId: String, l: MtLineup, home: TeamRef, away: TeamRef): List<Lineup> {
        fun side(team: TeamRef, t: MtLineupTeam?): Lineup? {
            if (t == null || t.players.isEmpty()) return null
            return Lineup(
                gameId, team,
                listOf(
                    LineupGroup(LineupGroupKind.STARTERS, "Starting XI", t.players.filter { it.startingLineup }.map(::ref)),
                    LineupGroup(LineupGroupKind.BENCH, "Bench", t.players.filter { !it.startingLineup }.map(::ref)),
                ),
                headCoach = t.coach,
            )
        }
        return listOfNotNull(side(home, l.homeTeam), side(away, l.awayTeam))
    }

    // ---- standings / players -------------------------------------------------------------

    public fun standings(s: MtStandings, seasonId: String?): StandingsTable {
        val groups = s.phases.flatMap { it.groups }.map { g ->
            StandingsGroup(
                label = g.competitionName ?: s.competitionTypeName ?: "League",
                rows = g.data.sortedBy { it.position }.map { r ->
                    StandingsRow(
                        team = teamRef(r.club), rank = r.position, played = r.matchesPlayed, wins = r.wins, losses = r.losses, draws = r.draws,
                        points = r.points, goalDifference = r.goalDifference,
                        extra = buildMap { if (r.club.isLive) put("live", "true") },
                    )
                },
            )
        }
        return StandingsTable(leagueId, seasonId, StageKind.REGULAR, groups, grouping = if (groups.size > 1) "group" else "league")
    }

    public fun squadPlayer(p: MtSquadPlayer, teamId: String): Player = Player(
        ref = PlayerRef(leagueId, p.id.toString(), name(p.name), p.number?.toIntOrNull(), if (p.isGoalKeeper == 1) "GK" else null, p.profilePhoto),
        teamId = teamId,
    )

    public fun player(p: MtPlayer): Player {
        fun detail(title: String) = p.details.firstOrNull { it.title == title }?.value?.takeIf { it.isNotBlank() }
        val first = detail("InternationalFirstName")
        val last = detail("InternationalLastName")
        val dob = detail("DateOfBirth")?.split('/')?.takeIf { it.size == 3 }?.let { (d, m, y) -> runCatching { LocalDate(y.toInt(), m.toInt(), d.toInt()) }.getOrNull() }
        return Player(
            ref = PlayerRef(leagueId, p.id.toString(), PlayerNames.fromParts(first, last, p.playerName), null, null, p.profilePhoto),
            firstName = first,
            lastName = last,
            birthDate = dob,
            birthPlace = detail("PlaceOfBirth"),
            nationality = detail("NationalityFIFA") ?: detail("Nationality"),
            teamId = p.team?.id?.toString(),
        )
    }
}
