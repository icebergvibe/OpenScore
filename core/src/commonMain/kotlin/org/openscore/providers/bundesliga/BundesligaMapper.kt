package org.openscore.providers.bundesliga

import org.openscore.model.Clock
import org.openscore.model.Game
import org.openscore.model.GameEnding
import org.openscore.model.GameEvent
import org.openscore.model.GameState
import org.openscore.model.Lineup
import org.openscore.model.LineupGroup
import org.openscore.model.LineupGroupKind
import org.openscore.model.Period
import org.openscore.model.PeriodScore
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
import org.openscore.model.football.SubstitutionDetails
import org.openscore.model.football.footballFormation
import org.openscore.provider.Dates
import org.openscore.providers.football.FootballPeriods
import kotlin.math.floor

/** Pure functions from the bundesliga.com Firebase nodes to the core model. */
public class BundesligaMapper(private val leagueId: String) {

    public fun teamRef(t: BlTeam): TeamRef = TeamRef(leagueId, t.dflDatalibraryClubId, t.nameFull, t.threeLetterCode, t.logoUrl)

    public fun teamRef(c: BlTableClub): TeamRef =
        TeamRef(leagueId, c.dflDatalibraryClubId ?: c.id ?: "", c.nameFull, c.threeLetterCode, c.logoUrl)

    public fun team(c: BlTableClub): Team = Team(ref = teamRef(c), commonName = c.nameShort, country = "GER")

    public fun gameState(status: String): GameState = when (status) {
        "PRE_MATCH" -> GameState.SCHEDULED
        "FIRST_HALF", "SECOND_HALF", "EXTRA_TIME_FIRST_HALF", "EXTRA_TIME_SECOND_HALF", "PENALTY_SHOOTOUT" -> GameState.LIVE
        "HALF", "EXTRA_TIME_HALF", "FULL_TIME" -> GameState.INTERMISSION
        "FINAL_WHISTLE" -> GameState.FINAL
        "" -> GameState.UNKNOWN
        else -> GameState.UNKNOWN
    }

    public fun currentPeriod(status: String): Period? = when (status) {
        "FIRST_HALF", "HALF" -> FootballPeriods.FIRST_HALF
        "SECOND_HALF", "FULL_TIME" -> FootballPeriods.SECOND_HALF
        "EXTRA_TIME_FIRST_HALF", "EXTRA_TIME_HALF" -> FootballPeriods.EXTRA_FIRST
        "EXTRA_TIME_SECOND_HALF" -> FootballPeriods.EXTRA_SECOND
        "PENALTY_SHOOTOUT" -> FootballPeriods.PENALTIES
        else -> null
    }

    public fun game(m: BlMatch, withEvents: Boolean, stats: BlStats? = null): Game {
        val state = gameState(m.matchStatus)
        val home = teamRef(m.teams.home)
        val away = teamRef(m.teams.away)
        val liveHome = m.score?.home?.live
        val liveAway = m.score?.away?.live
        val score = if (state != GameState.SCHEDULED && liveHome != null && liveAway != null) Score(liveHome, liveAway) else null
        val period = currentPeriod(m.matchStatus)
        val clock = if (state.isLive && period != null && m.minuteOfPlay != null) {
            Clock(FootballPeriods.timeAtMinute(period, m.minuteOfPlay.minute, m.minuteOfPlay.injuryTime.takeIf { it > 0 }), running = state == GameState.LIVE)
        } else null
        return Game(
            leagueId = leagueId,
            id = m.matchId,
            seasonId = m.dflDatalibrarySeasonId,
            stage = if (m.matchType == null) StageKind.REGULAR else StageKind.OTHER,
            startTime = Dates.instant(m.plannedKickOff),
            venue = m.stadiumName,
            home = home,
            away = away,
            state = state,
            score = score,
            clock = clock,
            periodScores = periodScores(m, state, score),
            ending = if (state.isFinished) GameEnding.REGULATION else null,
            events = if (withEvents) events(m, home, away) else null,
            stats = stats?.let(::stats).orEmpty(),
            rawState = m.matchStatus + (m.minuteOfPlay?.let { "/${it.minute}+${it.injuryTime}" } ?: ""),
        )
    }

    /** `halftime` is only final from HALF onwards, `fulltime` only at FINAL_WHISTLE — see the README. */
    private fun periodScores(m: BlMatch, state: GameState, score: Score?): List<PeriodScore> {
        if (score == null) return emptyList()
        val ht = m.score?.let { s -> if (s.home?.halftime != null && s.away?.halftime != null) Score(s.home.halftime, s.away.halftime) else null }
        return when (m.matchStatus) {
            "FIRST_HALF" -> listOf(PeriodScore(FootballPeriods.FIRST_HALF, score.home, score.away))
            "HALF" -> listOf(PeriodScore(FootballPeriods.FIRST_HALF, ht?.home ?: score.home, ht?.away ?: score.away))
            "SECOND_HALF", "FINAL_WHISTLE", "FULL_TIME" -> {
                val first = ht ?: Score(0, 0)
                listOf(
                    PeriodScore(FootballPeriods.FIRST_HALF, first.home, first.away),
                    PeriodScore(FootballPeriods.SECOND_HALF, score.home - first.home, score.away - first.away),
                )
            }
            else -> emptyList()
        }
    }

    private fun stats(s: BlStats): Map<String, StatPair> {
        fun fmt(v: Double) = if (v == floor(v)) v.toLong().toString() else v.toString()
        fun pair(st: BlStat?): StatPair? = st?.let { if (it.homeValue != null && it.awayValue != null) StatPair(fmt(it.homeValue), fmt(it.awayValue)) else null }
        val shots = if (s.shotsOnTarget?.homeValue != null && s.shotsOffTarget?.homeValue != null && s.shotsOnTarget.awayValue != null && s.shotsOffTarget.awayValue != null)
            StatPair(fmt(s.shotsOnTarget.homeValue + s.shotsOffTarget.homeValue), fmt(s.shotsOnTarget.awayValue + s.shotsOffTarget.awayValue)) else null
        return listOfNotNull(
            pair(s.ballPossessionRatio)?.let { "possession" to it },
            shots?.let { "shots" to it },
            pair(s.shotsOnTarget)?.let { "shotsOnTarget" to it },
            pair(s.XGoals)?.let { "xg" to it },
            pair(s.cornerKicks)?.let { "corners" to it },
            pair(s.fouls)?.let { "fouls" to it },
            pair(s.offsides)?.let { "offsides" to it },
            pair(s.passes)?.let { "passes" to it },
            pair(s.passAccuracy)?.let { "passAccuracy" to it },
            pair(s.distanceCovered)?.let { "distanceKm" to it },
            pair(s.sprints)?.let { "sprints" to it },
            pair(s.tacklesWon)?.let { "tacklesWon" to it },
        ).toMap()
    }

    // ---- events (ticker) -----------------------------------------------------------------

    private fun ref(p: BlPerson?): PlayerRef? = p?.let {
        PlayerRef(leagueId, it.dflDatalibraryObjectId ?: it.name, it.name, it.shirtNumber, it.position, it.imageUrl)
    }

    private fun sectionPeriod(section: String?, minute: Int): Period = when (section) {
        "FIRST_HALF", "HALF" -> FootballPeriods.FIRST_HALF
        "SECOND_HALF", "FINAL_WHISTLE" -> FootballPeriods.SECOND_HALF
        else -> FootballPeriods.periodForMinute(minute)
    }

    public fun events(m: BlMatch, home: TeamRef, away: TeamRef): List<GameEvent> {
        val out = ArrayList<GameEvent>()
        var lastScore = Score(0, 0)
        for ((id, e) in m.liveBlogEntries.entries.sortedBy { it.value.order }) {
            if (e.hidden) continue
            val minute = e.playtime?.minute ?: 0
            val injury = e.playtime?.injuryTime?.takeIf { it > 0 }
            val period = sectionPeriod(e.matchSection, minute)
            val time = FootballPeriods.timeAtMinute(period, minute, injury)
            val team = when (e.side) { "home" -> home; "away" -> away; else -> null }
            val d = e.detail
            val score = d?.score?.let { Score(it.home, it.away) }
            val event = when (e.entryType) {
                "goal" -> {
                    val scorer = ref(d?.scorer)
                    val assist = ref(d?.assist)
                    val kind = when {
                        d?.ownGoal == true -> GoalKind.OWN_GOAL
                        d?.penalty == true -> GoalKind.PENALTY
                        else -> GoalKind.OPEN_PLAY
                    }
                    val type = when (kind) { GoalKind.OWN_GOAL -> FootballEventType.OWN_GOAL; GoalKind.PENALTY -> FootballEventType.PENALTY_GOAL; else -> FootballEventType.GOAL }
                    // Which side an own-goal entry's `side` names is unobserved; the score after the goal
                    // says who it counted for, so the credited team comes from the score change.
                    val credited = when {
                        score == null -> if (kind == GoalKind.OWN_GOAL) when (team?.id) { home.id -> away; away.id -> home; else -> null } else team
                        score.home > lastScore.home -> home
                        score.away > lastScore.away -> away
                        else -> team
                    }
                    score?.let { lastScore = it }
                    GameEvent(
                        id = id, type = type, rawType = "goal", time = time, team = credited,
                        players = listOfNotNull(scorer, assist), score = score,
                        description = buildString {
                            append(if (kind == GoalKind.PENALTY) "Penalty — " else if (kind == GoalKind.OWN_GOAL) "Own goal — " else "Goal — ")
                            append(scorer?.name ?: "?"); assist?.let { append(" · ").append(it.name) }
                            d?.xG?.let { append(" (xG ").append(it).append(')') }
                        },
                        details = FootballGoalDetails(scorer, assist, kind), sortOrder = e.order.toInt(),
                    )
                }
                "yellowCard", "redCard", "yellowRedCard" -> {
                    val player = ref(d?.person)
                    val (type, card) = when (e.entryType) {
                        "redCard" -> FootballEventType.RED_CARD to CardKind.RED
                        "yellowRedCard" -> FootballEventType.SECOND_YELLOW to CardKind.SECOND_YELLOW
                        else -> FootballEventType.YELLOW_CARD to CardKind.YELLOW
                    }
                    GameEvent(
                        id = id, type = type, rawType = e.entryType, time = time, team = team, players = listOfNotNull(player), score = score,
                        description = "${card.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }} — ${player?.name ?: "?"}",
                        details = CardDetails(player, card), sortOrder = e.order.toInt(),
                    )
                }
                "sub" -> {
                    val on = ref(d?.`in`)
                    val off = ref(d?.out)
                    GameEvent(
                        id = id, type = FootballEventType.SUBSTITUTION, rawType = "sub", time = time, team = team, players = listOfNotNull(on, off), score = score,
                        description = "Sub — ${on?.name ?: "?"} for ${off?.name ?: "?"}", details = SubstitutionDetails(on, off), sortOrder = e.order.toInt(),
                    )
                }
                "videoAssistant" -> GameEvent(
                    id = id, type = FootballEventType.VAR, rawType = e.entryType, time = time, team = team, score = score,
                    description = listOfNotNull(d?.review, d?.situation, d?.decision?.let { "Decision: $it" }).joinToString(" — "), sortOrder = e.order.toInt(),
                )
                "start_firstHalf" -> marker(id, FootballEventType.PERIOD_START, FootballPeriods.FIRST_HALF, e, score, "Kick-off")
                "end_firstHalf" -> marker(id, FootballEventType.PERIOD_END, FootballPeriods.FIRST_HALF, e, score, "Half-time")
                "start_secondHalf" -> marker(id, FootballEventType.PERIOD_START, FootballPeriods.SECOND_HALF, e, score, "Second half")
                "end_secondHalf" -> marker(id, FootballEventType.PERIOD_END, FootballPeriods.SECOND_HALF, e, score, "End of second half")
                "finalWhistle" -> marker(id, FootballEventType.GAME_END, FootballPeriods.SECOND_HALF, e, score, "Full-time")
                else -> null // editorial: freetext, image, video, embed, stats, lineup, playerOfTheMatch
            }
            if (event != null) out += event
        }
        return out
    }

    private fun marker(id: String, type: FootballEventType, period: Period, e: BlEntry, score: Score?, text: String): GameEvent {
        val minute = e.playtime?.minute ?: FootballPeriods.offsetMinutes(period)
        val injury = e.playtime?.injuryTime?.takeIf { it > 0 }
        return GameEvent(
            id = id, type = type, rawType = e.entryType, time = FootballPeriods.timeAtMinute(period, minute, injury),
            score = score, description = text, sortOrder = e.order.toInt(),
        )
    }

    // ---- lineups ---------------------------------------------------------------------------

    private fun ref(p: BlLineupPerson): PlayerRef = PlayerRef(
        leagueId, p.dflDatalibraryObjectId ?: p.name, p.name, p.shirtNumber,
        when (p.role) { "GOALKEEPER" -> "GK"; "DEFENSE" -> "DF"; "MIDFIELD" -> "MF"; "ATTACK" -> "FW"; else -> p.role }, p.imageUrl,
    )

    public fun lineups(gameId: String, l: BlLineup, home: TeamRef, away: TeamRef): List<Lineup> {
        fun side(team: TeamRef, s: BlLineupSide?): Lineup? {
            if (s == null || s.startingEleven?.persons.isNullOrEmpty()) return null
            return Lineup(
                gameId = gameId, team = team,
                groups = listOf(
                    LineupGroup(LineupGroupKind.STARTERS, "Starting XI", s.startingEleven.persons.map(::ref)),
                    LineupGroup(LineupGroupKind.BENCH, "Bench", s.bench?.persons.orEmpty().map(::ref)),
                ),
                headCoach = s.coaches?.persons?.firstOrNull { it.role == "HEADCOACH" }?.name,
                formation = footballFormation(s.startingEleven.tacticalFormationName),
            )
        }
        return listOfNotNull(side(home, l.home), side(away, l.away))
    }

    // ---- table -----------------------------------------------------------------------------

    public fun standings(t: BlTable, label: String): StandingsTable {
        val rows = t.entries.sortedBy { it.rank }.map { e ->
            StandingsRow(
                team = teamRef(e.club),
                rank = e.rank,
                played = e.gamesPlayed,
                wins = e.wins,
                losses = e.losses,
                draws = e.draws,
                points = e.points,
                goalsFor = e.goalsScored,
                goalsAgainst = e.goalsAgainst,
                goalDifference = e.goalDifference,
                extra = buildMap {
                    e.qualification?.takeIf { it != "NONE" }?.let { put("qualification", it) }
                    e.tendency?.let { put("tendency", it) }
                },
            )
        }
        return StandingsTable(leagueId, t.season?.id, StageKind.REGULAR, listOf(StandingsGroup(label, rows)), grouping = "league")
    }
}
