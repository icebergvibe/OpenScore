package org.openscore.providers.ligue1

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
import org.openscore.model.ShootoutAttemptDetails
import org.openscore.model.StageKind
import org.openscore.model.StandingsGroup
import org.openscore.model.StandingsRow
import org.openscore.model.StandingsTable
import org.openscore.model.StatPair
import org.openscore.model.Team
import org.openscore.model.TeamRef
import org.openscore.model.football.CardDetails
import org.openscore.model.football.CardKind
import org.openscore.model.football.DisallowedGoalDetails
import org.openscore.model.football.FootballEventType
import org.openscore.model.football.FootballGoalDetails
import org.openscore.model.football.GoalKind
import org.openscore.model.football.PenaltyMissDetails
import org.openscore.model.football.SubstitutionDetails
import org.openscore.model.football.VarDecision
import org.openscore.model.football.footballFormation
import org.openscore.provider.Dates
import org.openscore.providers.football.FootballPeriods
import kotlin.time.Duration
import kotlin.time.Instant

/** Pure functions from ma-api.ligue1.fr DTOs to the core model. */
public class Ligue1Mapper(private val leagueId: String) {

    public fun teamRef(id: String, identity: L1ClubIdentity?): TeamRef = TeamRef(
        leagueId = leagueId,
        id = id,
        name = identity?.name?.ifBlank { null } ?: identity?.shortName ?: id,
        abbreviation = identity?.trigram,
        logoUrl = identity?.assets?.logo?.medium ?: identity?.assets?.logo?.small,
    )

    public fun team(identity: L1ClubIdentity, club: L1Club?): Team = Team(
        ref = teamRef(identity.id, identity),
        commonName = identity.shortName,
        placeName = null,
        arena = null,
        country = if (identity.isInFrenchChampionship == true) "FRA" else null,
    ).let { t -> if (club?.websiteLink != null) t else t }

    // ---- state ---------------------------------------------------------------------------

    public fun gameState(period: String): GameState = when (period) {
        "preMatch" -> GameState.SCHEDULED
        "preMatchWithPlayers" -> GameState.PRE_GAME
        "firstHalf", "secondHalf", "extraFirstHalf", "extraSecondHalf", "fullTimePens", "ShootOut", "shootOut" -> GameState.LIVE
        "halfTime", "extraHalfTime", "fullTime90" -> GameState.INTERMISSION
        "fullTime" -> GameState.FINAL
        "" -> GameState.UNKNOWN
        else -> GameState.UNKNOWN
    }

    /** Period a `period` string belongs to (the one that just ended, during breaks). */
    public fun currentPeriod(period: String): Period? = when (period) {
        "firstHalf", "halfTime" -> FootballPeriods.FIRST_HALF
        "secondHalf", "fullTime90" -> FootballPeriods.SECOND_HALF
        "extraFirstHalf", "extraHalfTime" -> FootballPeriods.EXTRA_FIRST
        "extraSecondHalf" -> FootballPeriods.EXTRA_SECOND
        "fullTimePens", "ShootOut", "shootOut" -> FootballPeriods.PENALTIES
        else -> null
    }

    private fun ending(periodsDates: L1PeriodDates?, shootOut: Boolean): GameEnding = when {
        shootOut -> GameEnding.SHOOTOUT
        periodsDates?.extraFirstHalfStartedAt != null -> GameEnding.OVERTIME
        else -> GameEnding.REGULATION
    }

    // ---- summaries -----------------------------------------------------------------------

    public fun game(s: L1MatchSummary): Game {
        val state = gameState(s.period)
        val shootOut = s.home.shootOutScore != null || s.away.shootOutScore != null
        return Game(
            leagueId = leagueId,
            id = s.matchId,
            seasonId = null,
            stage = StageKind.REGULAR,
            startTime = Instant.parse(s.date),
            home = teamRef(s.home.clubId, s.home.clubIdentity),
            away = teamRef(s.away.clubId, s.away.clubIdentity),
            state = state,
            score = if (s.home.score != null && s.away.score != null && state != GameState.SCHEDULED && state != GameState.PRE_GAME) Score(s.home.score, s.away.score) else null,
            clock = clockFromMatchTime(state, s.period, s.matchTime),
            ending = if (state.isFinished) ending(null, shootOut) else null,
            rawState = s.period,
        )
    }

    /** The summary feed only has `matchTime` (floor minutes); good enough for a scoreboard. */
    private fun clockFromMatchTime(state: GameState, period: String, matchTime: String?): Clock? {
        if (!state.isLive) return null
        val p = currentPeriod(period) ?: return null
        val (minute, stoppage) = FootballPeriods.parseMinute(matchTime) ?: return Clock(GameTime(p), running = null)
        return Clock(FootballPeriods.timeAtMinute(p, minute, stoppage), running = state == GameState.LIVE)
    }

    // ---- match resource ------------------------------------------------------------------

    public fun game(m: L1Match, now: Instant): Game {
        val state = gameState(m.period)
        val home = teamRef(m.home.clubId, m.home.clubIdentity)
        val away = teamRef(m.away.clubId, m.away.clubIdentity)
        val shootOut = m.home.shootOutScore != null || m.away.shootOutScore != null
        val score = if (m.home.score != null && m.away.score != null && state != GameState.SCHEDULED && state != GameState.PRE_GAME) Score(m.home.score, m.away.score) else null
        val events = events(m)
        return Game(
            leagueId = leagueId,
            id = m.id,
            seasonId = m.season?.toString(),
            stage = StageKind.REGULAR,
            startTime = Instant.parse(m.date),
            venue = m.stadium?.name,
            home = home,
            away = away,
            state = state,
            score = score,
            clock = clock(m, state, now),
            periodScores = periodScores(m, events, score),
            ending = if (state.isFinished) ending(m.periodsDates, shootOut) else null,
            events = events,
            stats = stats(m.home.stats, m.away.stats),
            rawState = m.period + (m.matchTime?.let { "/$it" } ?: ""),
        )
    }

    /** Clock from the second-precision period timestamps; frozen at the period end during breaks. */
    private fun clock(m: L1Match, state: GameState, now: Instant): Clock? {
        if (!state.isLive) return null
        val p = currentPeriod(m.period) ?: return null
        val d = m.periodsDates ?: return Clock(GameTime(p), running = null)
        val (start, end) = when (p.number) {
            1 -> d.firstHalfStartedAt to d.firstHalfEndedAt
            2 -> d.secondHalfStartedAt to d.secondHalfEndedAt
            3 -> d.extraFirstHalfStartedAt to d.extraFirstHalfEndedAt
            4 -> d.extraSecondHalfStartedAt to d.extraSecondHalfEndedAt
            else -> null to null
        }
        if (start == null) return Clock(GameTime(p), running = null)
        val until = end?.let(Instant::parse) ?: now
        val elapsed = (until - Instant.parse(start)).let { if (it.isNegative()) Duration.ZERO else it }
        return Clock(FootballPeriods.timeInPeriod(p, elapsed), running = end == null)
    }

    private fun periodScores(m: L1Match, events: List<GameEvent>, score: Score?): List<PeriodScore> {
        if (score == null) return emptyList()
        val current = currentPeriod(m.period) ?: if (m.period == "fullTime") lastPlayedPeriod(m) else return emptyList()
        val goals = events.filter { it.type.isGoal }
        return (1..current.number).map { n ->
            val p = periodByNumber(n)
            PeriodScore(
                p,
                goals.count { it.period.number == n && it.team?.id == m.home.clubId },
                goals.count { it.period.number == n && it.team?.id == m.away.clubId },
            )
        }
    }

    private fun lastPlayedPeriod(m: L1Match): Period = when {
        m.home.penaltyShots.isNotEmpty() -> FootballPeriods.PENALTIES
        m.periodsDates?.extraSecondHalfStartedAt != null -> FootballPeriods.EXTRA_SECOND
        m.periodsDates?.extraFirstHalfStartedAt != null -> FootballPeriods.EXTRA_FIRST
        else -> FootballPeriods.SECOND_HALF
    }

    private fun periodByNumber(n: Int): Period = when (n) {
        1 -> FootballPeriods.FIRST_HALF
        2 -> FootballPeriods.SECOND_HALF
        3 -> FootballPeriods.EXTRA_FIRST
        4 -> FootballPeriods.EXTRA_SECOND
        else -> FootballPeriods.PENALTIES
    }

    private fun stats(h: L1TeamStats?, a: L1TeamStats?): Map<String, StatPair> {
        if (h == null || a == null) return emptyMap()
        fun fmt(v: Any): String = if (v is Double && v == kotlin.math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
        fun pair(x: Any?, y: Any?): StatPair? = if (x == null || y == null) null else StatPair(fmt(x), fmt(y))
        return listOfNotNull(
            pair(h.possessionPercentage, a.possessionPercentage)?.let { "possession" to it },
            pair(h.scoringAttempts, a.scoringAttempts)?.let { "shots" to it },
            pair(h.ontargetScoringAttempts, a.ontargetScoringAttempts)?.let { "shotsOnTarget" to it },
            pair(h.blockedScoringAttempts, a.blockedScoringAttempts)?.let { "shotsBlocked" to it },
            pair(h.expectedGoals, a.expectedGoals)?.let { "xg" to it },
            pair(h.corners, a.corners)?.let { "corners" to it },
            pair(h.fouls, a.fouls)?.let { "fouls" to it },
            pair(h.offsides, a.offsides)?.let { "offsides" to it },
            pair(h.passes, a.passes)?.let { "passes" to it },
            pair(h.bigChances, a.bigChances)?.let { "bigChances" to it },
            pair(h.bookingsData?.yellow, a.bookingsData?.yellow)?.let { "yellowCards" to it },
            pair(h.bookingsData?.red, a.bookingsData?.red)?.let { "redCards" to it },
        ).toMap()
    }

    // ---- events --------------------------------------------------------------------------

    private fun positionCode(position: Int?): String? = when (position) { 1 -> "GK"; 2 -> "DF"; 3 -> "MF"; 4 -> "FW"; else -> null }

    public fun playerRef(p: L1MatchPlayer): PlayerRef {
        val i = p.playerIdentity
        return PlayerRef(
            leagueId = leagueId,
            id = p.id,
            name = listOfNotNull(i?.firstName, i?.lastName).joinToString(" ").ifBlank { p.id },
            jerseyNumber = p.shirtNumber ?: i?.jerseyNumber,
            position = positionCode(p.position),
            headshotUrl = i?.assets?.facePictures?.medium ?: i?.assets?.facePictures?.small,
        )
    }

    private fun periodAt(m: L1Match, timestamp: Long?, minute: Int?): Period {
        val d = m.periodsDates
        val t = timestamp?.let(Instant::fromEpochMilliseconds)
        fun after(s: String?) = s != null && t != null && t >= Instant.parse(s)
        return when {
            after(d?.extraSecondHalfStartedAt) -> FootballPeriods.EXTRA_SECOND
            after(d?.extraFirstHalfStartedAt) -> FootballPeriods.EXTRA_FIRST
            after(d?.secondHalfStartedAt) -> FootballPeriods.SECOND_HALF
            after(d?.firstHalfStartedAt) -> FootballPeriods.FIRST_HALF
            minute != null -> FootballPeriods.periodForMinute(minute, d?.extraFirstHalfStartedAt != null)
            else -> FootballPeriods.FIRST_HALF
        }
    }

    private fun eventTime(m: L1Match, time: String?, timestamp: Long?): GameTime {
        val parsed = FootballPeriods.parseMinute(time)
        val period = periodAt(m, timestamp, parsed?.first)
        return if (parsed != null) FootballPeriods.timeAtMinute(period, parsed.first, parsed.second) else GameTime(period)
    }

    public fun events(m: L1Match): List<GameEvent> {
        val players = (m.home.players + m.away.players).mapValues { playerRef(it.value) }
        fun ref(id: String?): PlayerRef? = id?.let { players[it] ?: PlayerRef(leagueId, it, it.substringAfterLast('_')) }
        val home = teamRef(m.home.clubId, m.home.clubIdentity)
        val away = teamRef(m.away.clubId, m.away.clubIdentity)
        val out = ArrayList<GameEvent>()
        var seq = 0
        val settled = gameState(m.period).isFinished

        for ((team, side) in listOf(home to m.home, away to m.away)) {
            for (g in side.goals) {
                seq++
                val scorer = ref(g.scorerId ?: g.playerId)
                val assist = ref(g.assistProviderId)
                val (type, kind) = when (g.type) {
                    "own" -> FootballEventType.OWN_GOAL to GoalKind.OWN_GOAL
                    "penalty" -> FootballEventType.PENALTY_GOAL to GoalKind.PENALTY
                    else -> FootballEventType.GOAL to GoalKind.OPEN_PLAY
                }
                // `varDecision: 1` marks a goal VAR is involved with, not one VAR has allowed:
                // in the 2026-09-13 capture a 2nd-minute goal went 0 then 1 and, on the very next
                // poll, moved into `canceledGoals` with 2, while a finished document carries a 1
                // on a goal that stood. The value only settles when the match does, so a
                // confirmation is claimed only at full time - otherwise the reader is told VAR
                // allowed a goal one poll before it is disallowed.
                val varDecision = when {
                    g.varDecision == 2 -> VarDecision.OVERTURNED
                    g.varDecision == 1 && settled -> VarDecision.CONFIRMED
                    else -> null
                }
                out += GameEvent(
                    id = g.eventId ?: "goal-$seq", type = type, rawType = "goal:${g.type ?: "goal"}",
                    time = eventTime(m, g.time, g.timestamp), team = team,
                    players = listOfNotNull(scorer, assist),
                    description = buildString {
                        append(if (kind == GoalKind.OWN_GOAL) "Own goal — " else if (kind == GoalKind.PENALTY) "Penalty — " else "Goal — ")
                        append(scorer?.name ?: "?")
                        assist?.let { append(" · ").append(it.name) }
                    },
                    details = FootballGoalDetails(scorer, assist, kind, varDecision),
                    sortOrder = g.timestamp?.toInt() ?: seq,
                )
            }
            for (g in side.canceledGoals) {
                seq++
                val player = ref(g.scorerId ?: g.playerId)
                out += GameEvent(
                    id = g.eventId ?: "canceled-$seq", type = FootballEventType.GOAL_DISALLOWED, rawType = "canceledGoal",
                    time = eventTime(m, g.time, g.timestamp), team = team, players = listOfNotNull(player),
                    description = "Goal disallowed — ${player?.name ?: "?"}", details = DisallowedGoalDetails(player, g.reason),
                    sortOrder = g.timestamp?.toInt() ?: seq,
                )
            }
            for (g in side.missedPenalties) {
                seq++
                val player = ref(g.scorerId ?: g.playerId)
                out += GameEvent(
                    id = g.eventId ?: "missed-$seq", type = FootballEventType.PENALTY_MISSED, rawType = "missedPenalty",
                    time = eventTime(m, g.time, g.timestamp), team = team, players = listOfNotNull(player),
                    description = "Penalty missed — ${player?.name ?: "?"}", details = PenaltyMissDetails(player),
                    sortOrder = g.timestamp?.toInt() ?: seq,
                )
            }
            for (b in side.bookings) {
                seq++
                val player = ref(b.playerId)
                val (type, card) = when (b.type) {
                    "secondYellow" -> FootballEventType.SECOND_YELLOW to CardKind.SECOND_YELLOW
                    "straightRed", "red" -> FootballEventType.RED_CARD to CardKind.RED
                    else -> FootballEventType.YELLOW_CARD to CardKind.YELLOW
                }
                out += GameEvent(
                    id = b.eventId ?: "booking-$seq", type = type, rawType = "booking:${b.type ?: "yellow"}",
                    time = eventTime(m, b.time, b.timestamp), team = team, players = listOfNotNull(player),
                    description = listOfNotNull(card.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }, player?.name, b.reason).joinToString(" — "),
                    details = CardDetails(player, card, b.reason),
                    sortOrder = b.timestamp?.toInt() ?: seq,
                )
            }
            for (s in side.substitutions) {
                seq++
                val on = ref(s.subOnId)
                val off = ref(s.subOffId)
                out += GameEvent(
                    id = s.eventId ?: "sub-$seq", type = FootballEventType.SUBSTITUTION, rawType = "substitution" + (s.reason?.let { ":$it" } ?: ""),
                    time = eventTime(m, s.time, s.timestamp), team = team, players = listOfNotNull(on, off),
                    description = "Sub — ${on?.name ?: "?"} for ${off?.name ?: "?"}",
                    details = SubstitutionDetails(on, off, s.reason),
                    sortOrder = s.timestamp?.toInt() ?: seq,
                )
            }
            for ((i, ps) in side.penaltyShots.withIndex()) {
                seq++
                val player = ref(ps.playerId ?: ps.scorerId)
                val scored = ps.scored ?: (ps.type == "goal" || ps.type == "scored")
                out += GameEvent(
                    id = ps.eventId ?: "pen-${team.id}-$i", type = FootballEventType.SHOOTOUT_ATTEMPT, rawType = "penaltyShot",
                    time = GameTime(FootballPeriods.PENALTIES), team = team, players = listOfNotNull(player),
                    description = (if (scored) "Penalty scored — " else "Penalty missed — ") + (player?.name ?: "?"),
                    details = ShootoutAttemptDetails(player, null, scored),
                    sortOrder = ps.timestamp?.toInt() ?: (1_000_000 + i),
                )
            }
        }

        // Running score after each goal.
        var h = 0
        var a = 0
        val withScore = out.sortedWith(compareBy({ it.period.number }, { it.time.elapsed?.inWholeSeconds ?: 0L }, { it.sortOrder ?: 0 })).map { e ->
            if (e.type.isGoal) {
                if (e.team?.id == home.id) h++ else a++
                e.copy(score = Score(h, a))
            } else e
        }.toMutableList()

        // Period markers from the second-precision timestamps.
        val d = m.periodsDates
        fun marker(period: Period, startedAt: String?, endedAt: String?) {
            if (startedAt != null) withScore += GameEvent(
                id = "${period.label}-start", type = FootballEventType.PERIOD_START, rawType = "period",
                time = GameTime(period, Duration.ZERO, label = FootballPeriods.label(FootballPeriods.offsetMinutes(period))), sortOrder = -1,
                description = "Start of ${period.label}",
            )
            if (endedAt != null) withScore += GameEvent(
                id = "${period.label}-end", type = if (period.number == 2 && m.period == "fullTime" && d?.extraFirstHalfStartedAt == null) FootballEventType.GAME_END else FootballEventType.PERIOD_END,
                rawType = "period",
                time = GameTime(period, Instant.parse(endedAt) - Instant.parse(startedAt!!), label = null), sortOrder = Int.MAX_VALUE,
                description = "End of ${period.label}",
            )
        }
        marker(FootballPeriods.FIRST_HALF, d?.firstHalfStartedAt, d?.firstHalfEndedAt)
        marker(FootballPeriods.SECOND_HALF, d?.secondHalfStartedAt, d?.secondHalfEndedAt)
        marker(FootballPeriods.EXTRA_FIRST, d?.extraFirstHalfStartedAt, d?.extraFirstHalfEndedAt)
        marker(FootballPeriods.EXTRA_SECOND, d?.extraSecondHalfStartedAt, d?.extraSecondHalfEndedAt)

        return withScore.sortedWith(compareBy({ it.period.number }, { it.time.elapsed?.inWholeSeconds ?: 0L }, { it.sortOrder ?: 0 }))
    }

    // ---- lineups -------------------------------------------------------------------------

    public fun lineups(m: L1Match): List<Lineup> {
        if (m.period == "preMatch") return emptyList()
        fun side(s: L1MatchSide): Lineup {
            val starters = s.playersIds.mapNotNull { s.players[it] }.filter { it.startedMatch == true }.sortedBy { it.formationPlace ?: 99 }
            val bench = s.players.values.filter { it.startedMatch != true && (it.sub == 1 || it.playedMatch == true) }.sortedBy { it.shirtNumber ?: 99 }
            return Lineup(
                gameId = m.id,
                team = teamRef(s.clubId, s.clubIdentity),
                groups = listOf(
                    LineupGroup(LineupGroupKind.STARTERS, "Starting XI", starters.map(::playerRef)),
                    LineupGroup(LineupGroupKind.BENCH, "Bench", bench.map(::playerRef)),
                ),
                headCoach = s.manager?.let { listOfNotNull(it.knownName ?: it.firstName, it.lastName.takeIf { _ -> it.knownName == null }).joinToString(" ").ifBlank { null } },
                formation = footballFormation(s.formation),
            )
        }
        return listOf(side(m.home), side(m.away))
    }

    // ---- standings / roster / player ---------------------------------------------------

    public fun standings(s: L1Standings, label: String): StandingsTable {
        val rows = s.standings.values.sortedBy { it.rank }.map { r ->
            StandingsRow(
                team = teamRef(r.clubId, r.clubIdentity),
                rank = r.rank,
                played = r.played,
                wins = r.wins,
                losses = r.losses,
                draws = r.draws,
                points = r.points,
                goalsFor = r.forGoals,
                goalsAgainst = r.againstGoals,
                goalDifference = r.goalsDifference,
                extra = buildMap {
                    r.rankDelta?.let { put("rankDelta", it.toString()) }
                    val form = r.seasonResults.mapNotNull { it.resultLetter?.uppercase() }.takeLast(5).joinToString("")
                    if (form.isNotEmpty()) put("form", form)
                },
            )
        }
        return StandingsTable(leagueId, s.season?.toString(), StageKind.REGULAR, listOf(StandingsGroup(label, rows)), grouping = "league")
    }

    private fun squadPosition(squad: L1Squad, id: String): String? = when (id) {
        in squad.goalkeepersIds -> "GK"
        in squad.defendersIds -> "DF"
        in squad.midfieldersIds -> "MF"
        in squad.strikersIds -> "FW"
        else -> null
    }

    public fun roster(summary: L1ClubSummary, teamId: String): List<Player> {
        val squad = summary.squad ?: return emptyList()
        val order = squad.goalkeepersIds + squad.defendersIds + squad.midfieldersIds + squad.strikersIds
        return order.mapNotNull { id -> squad.players[id]?.let { identity(it, squadPosition(squad, id), teamId) } }
    }

    private fun identity(i: L1PlayerIdentity, position: String?, teamId: String?): Player = Player(
        ref = PlayerRef(
            leagueId = leagueId, id = i.id,
            name = listOfNotNull(i.firstName, i.lastName).joinToString(" ").ifBlank { i.id },
            jerseyNumber = i.jerseyNumber, position = position,
            headshotUrl = i.assets?.facePictures?.medium ?: i.assets?.facePictures?.small,
        ),
        firstName = i.firstName,
        lastName = i.lastName,
        birthDate = Dates.localDateOrNull(i.birthDate),
        nationality = i.countryShortCode,
        handedness = i.preferredFoot?.let { if (it.startsWith("l", true)) "L" else if (it.startsWith("r", true)) "R" else null },
        teamId = teamId,
    )

    public fun player(p: L1Player, championshipId: Int): Player {
        val reg = p.championships[championshipId.toString()] ?: p.championships.values.firstOrNull()
        return Player(
            ref = PlayerRef(
                leagueId = leagueId, id = p.id,
                name = listOfNotNull(p.firstName, p.lastName).joinToString(" ").ifBlank { p.id },
                jerseyNumber = reg?.jerseyNumber, position = positionCode(reg?.position),
                headshotUrl = reg?.assets?.facePictures?.medium ?: reg?.assets?.facePictures?.small,
            ),
            firstName = p.firstName,
            lastName = p.lastName,
            birthDate = Dates.localDateOrNull(p.birthDate),
            nationality = p.countryShortCode,
            handedness = p.preferredFoot?.let { if (it.startsWith("l", true)) "L" else if (it.startsWith("r", true)) "R" else null },
            teamId = reg?.championshipClubId,
            active = reg?.active?.let { it == 1 },
        )
    }
}
