package org.openscore.providers.uefa

import org.openscore.model.Clock
import org.openscore.model.Coordinates
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
import org.openscore.model.football.FootballEventType
import org.openscore.model.football.FootballGoalDetails
import org.openscore.model.football.GoalKind
import org.openscore.model.football.SubstitutionDetails
import org.openscore.provider.Dates
import org.openscore.providers.football.FootballPeriods
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Pure functions from the uefa.com DTOs to the core model. */
public class UefaMapper(private val leagueId: String) {

    // ---- teams & people ------------------------------------------------------------------

    public fun teamRef(t: UefaTeam): TeamRef = TeamRef(
        leagueId = leagueId,
        id = t.id,
        name = t.translations?.displayName?.EN ?: t.internationalName ?: t.id,
        abbreviation = t.teamCode,
        logoUrl = t.mediumLogoUrl ?: t.logoUrl,
    )

    public fun team(t: UefaTeam): Team = Team(
        ref = teamRef(t),
        commonName = t.translations?.shortName?.EN ?: t.internationalName,
        logoDarkUrl = t.bigLogoUrl,
        country = t.countryCode,
    )

    private fun positionCode(fieldPosition: String?): String? = when (fieldPosition) {
        "GOALKEEPER" -> "GK"
        "DEFENDER" -> "DF"
        "MIDFIELDER" -> "MF"
        "FORWARD" -> "FW"
        else -> null
    }

    /** [actorType] is the event actor's `type` (`PLAYER`, `COACH`), which marks booked staff. */
    public fun playerRef(raw: UefaPerson, jerseyNumber: Int? = null, actorType: String? = null): PlayerRef {
        val p = raw.flat
        val id = p.id ?: "?"
        return PlayerRef(
            leagueId = leagueId,
            id = id,
            name = p.translations?.name?.EN ?: p.internationalName
                ?: listOfNotNull(p.translations?.firstName?.EN, p.translations?.lastName?.EN).joinToString(" ").ifBlank { id },
            jerseyNumber = jerseyNumber ?: p.clubJerseyNumber?.toIntOrNull(),
            position = positionCode(p.fieldPosition) ?: if (actorType == "COACH" || p.role == "COACH") "COACH" else null,
            headshotUrl = p.imageUrl,
        )
    }

    public fun player(p: UefaPerson): Player = Player(
        ref = playerRef(p),
        firstName = p.translations?.firstName?.EN,
        lastName = p.translations?.lastName?.EN,
        birthDate = Dates.localDateOrNull(p.birthDate),
        nationality = p.countryCode,
        heightCm = p.height,
        weightKg = p.weight,
        teamId = p.clubId,
    )

    // ---- phases & state ----------------------------------------------------------------

    /** Period a `phase` string plays in; breaks map to the period that just ended. */
    public fun period(phase: String?): Period? = when (phase) {
        "FIRST_HALF", "HALF_TIME_BREAK", "HALF_TIME" -> FootballPeriods.FIRST_HALF
        "SECOND_HALF", "EXTRA_TIME_BREAK", "FULL_TIME_BREAK" -> FootballPeriods.SECOND_HALF
        "EXTRA_TIME_FIRST_HALF", "EXTRA_TIME_HALF_TIME_BREAK" -> FootballPeriods.EXTRA_FIRST
        "EXTRA_TIME_SECOND_HALF", "PENALTY_BREAK" -> FootballPeriods.EXTRA_SECOND
        "PENALTY", "PENALTIES", "PENALTY_SHOOTOUT" -> FootballPeriods.PENALTIES
        else -> null
    }

    private fun isBreak(phase: String?): Boolean = phase != null && phase.contains("BREAK")

    public fun gameState(m: UefaMatch, events: List<UefaEvent>? = null): GameState = when (m.status) {
        "UPCOMING" -> if (m.lineupStatus != null && m.lineupStatus != "NOT_AVAILABLE") GameState.PRE_GAME else GameState.SCHEDULED
        "LIVE", "CURRENT" -> if (isBreak(m.phase) || betweenPhases(m.phase, events)) GameState.INTERMISSION else GameState.LIVE
        "FINISHED" -> GameState.FINAL
        "ABANDONED", "SUSPENDED" -> GameState.SUSPENDED
        "CANCELED", "CANCELLED" -> GameState.CANCELLED
        "POSTPONED" -> GameState.POSTPONED
        else -> GameState.UNKNOWN
    }

    /**
     * Between `END_PHASE` and the next `START_PHASE` the match object may still carry the
     * phase that just ended; the events say the whistle has gone.
     */
    private fun betweenPhases(phase: String?, events: List<UefaEvent>?): Boolean {
        if (phase == null || events == null) return false
        val ended = events.lastOrNull { it.type == "END_PHASE" && it.phase == phase }?.timestamp ?: return false
        if (events.any { it.type == "FULL_TIME" }) return false
        return events.none { it.type == "START_PHASE" && it.phase != phase && (it.timestamp ?: "") > ended }
    }

    private fun stage(m: UefaMatch): StageKind? = when {
        m.competitionPhase == "QUALIFYING" || m.round?.phase == "QUALIFYING" -> StageKind.OTHER
        m.round?.mode == "GROUP" -> StageKind.REGULAR
        m.round?.mode == "KNOCK_OUT" || m.round?.mode == "FINAL" -> StageKind.PLAYOFF
        else -> null
    }

    private fun score(m: UefaMatch, state: GameState): Score? {
        if (state == GameState.SCHEDULED || state == GameState.PRE_GAME) return null
        val s = m.score?.total ?: m.score?.regular ?: return null
        return Score(s.home, s.away)
    }

    private fun ending(m: UefaMatch, events: List<UefaEvent>?): GameEnding = when {
        m.score?.penalty != null || m.winner?.match?.reason == "WIN_ON_PENALTIES" -> GameEnding.SHOOTOUT
        m.score?.total != null && m.score.regular != null && m.score.total != m.score.regular -> GameEnding.OVERTIME
        m.winner?.match?.reason == "WIN_ON_EXTRA_TIME" || m.winner?.aggregate?.reason == "WIN_ON_EXTRA_TIME" -> GameEnding.OVERTIME
        events?.any { it.phase?.startsWith("EXTRA_TIME") == true } == true -> GameEnding.OVERTIME
        else -> GameEnding.REGULATION
    }

    // ---- game ----------------------------------------------------------------------------

    /**
     * Match object (+ optional MAIN events and team statistics) → [Game]. [now] is needed for
     * the running clock; the events give the second-precision period timestamps, the match
     * object alone only `minute`.
     */
    public fun game(m: UefaMatch, events: List<UefaEvent>? = null, stats: List<UefaTeamStatistics>? = null, now: Instant? = null): Game {
        val state = gameState(m, events)
        val home = teamRef(m.homeTeam)
        val away = teamRef(m.awayTeam)
        val score = score(m, state)
        val mapped = events?.let { events(m, it) }
        return Game(
            leagueId = leagueId,
            id = m.id,
            seasonId = m.seasonYear,
            stage = stage(m),
            competition = m.round?.metaData?.name,
            startTime = Instant.parse(m.kickOffTime.dateTime),
            venue = m.stadium?.translations?.name?.EN ?: m.stadium?.translations?.officialName?.EN,
            home = home,
            away = away,
            state = state,
            score = score,
            clock = clock(m, state, events, now),
            periodScores = if (score == null) emptyList() else periodScores(m, mapped, home, away),
            ending = if (state.isFinished) ending(m, events) else null,
            events = mapped,
            stats = stats?.let { stats(it, home.id, away.id) } ?: emptyMap(),
            rawState = listOfNotNull(m.status, m.phase, m.minute?.let { "${it.normal}+${it.injury ?: 0}" }).joinToString("/"),
        )
    }

    /** Elapsed time in the current period from `START_PHASE`/`END_PHASE`, else from `minute`. */
    private fun clock(m: UefaMatch, state: GameState, events: List<UefaEvent>?, now: Instant?): Clock? {
        if (!state.isLive) return null
        val period = period(m.phase) ?: events?.let { lastPhasePeriod(it) } ?: return null
        val phaseEvents = events?.filter { period(it.phase) == period }.orEmpty()
        val start = phaseEvents.firstOrNull { it.type == "START_PHASE" }?.timestamp?.let(Instant::parse)
        if (start != null && now != null) {
            val end = phaseEvents.firstOrNull { it.type == "END_PHASE" }?.timestamp?.let(Instant::parse)
            val elapsed = ((end ?: now) - start).let { if (it.isNegative()) Duration.ZERO else it }
            return Clock(FootballPeriods.timeInPeriod(period, elapsed), running = end == null && state == GameState.LIVE)
        }
        val minute = m.minute?.normal ?: return Clock(GameTime(period), running = if (state == GameState.INTERMISSION) false else null)
        return Clock(FootballPeriods.timeAtMinute(period, minute, m.minute.injury?.takeIf { it > 0 }), running = state == GameState.LIVE)
    }

    private fun lastPhasePeriod(events: List<UefaEvent>): Period? =
        events.lastOrNull { it.type == "START_PHASE" }?.let { period(it.phase) }

    private fun periodScores(m: UefaMatch, events: List<GameEvent>?, home: TeamRef, away: TeamRef): List<PeriodScore> {
        // Goals from the timeline when it has them, else from the match object's own scorer list.
        val fromEvents = events?.filter { it.type.isGoal }?.map { it.period to (it.team?.id == home.id) }.orEmpty()
        val goals: List<Pair<Period, Boolean>> = fromEvents.ifEmpty {
            m.playerEvents?.scorers.orEmpty().mapNotNull { s ->
                val p = period(s.phase) ?: return@mapNotNull null
                val scorerHome = s.teamId == home.id
                p to (if (s.goalType == "OWN") !scorerHome else scorerHome)
            }
        }
        val extraTime = m.score?.total != null && m.score.regular != null && m.score.total != m.score.regular
        val started = events?.filter { it.type == FootballEventType.PERIOD_START }?.maxOfOrNull { it.period.number }
        val current = started ?: period(m.phase)?.number
            ?: if (m.status == "FINISHED") (if (extraTime || m.score?.penalty != null) 4 else 2) else return emptyList()
        val last = maxOf(current, goals.maxOfOrNull { it.first.number } ?: 0).coerceAtMost(4)
        return (1..last).map { n ->
            val p = periodByNumber(n)
            PeriodScore(p, goals.count { it.first.number == n && it.second }, goals.count { it.first.number == n && !it.second })
        }
    }

    private fun periodByNumber(n: Int): Period = when (n) {
        1 -> FootballPeriods.FIRST_HALF
        2 -> FootballPeriods.SECOND_HALF
        3 -> FootballPeriods.EXTRA_FIRST
        4 -> FootballPeriods.EXTRA_SECOND
        else -> FootballPeriods.PENALTIES
    }

    private fun stats(list: List<UefaTeamStatistics>, homeId: String, awayId: String): Map<String, StatPair> {
        val h = list.firstOrNull { it.teamId == homeId }?.statistics?.associate { it.name to it.value } ?: return emptyMap()
        val a = list.firstOrNull { it.teamId == awayId }?.statistics?.associate { it.name to it.value } ?: return emptyMap()
        fun pair(name: String): StatPair? {
            val x = h[name] ?: return null
            val y = a[name] ?: return null
            return StatPair(x, y)
        }
        return STAT_NAMES.mapNotNull { (key, name) -> pair(name)?.let { key to it } }.toMap()
    }

    // ---- events --------------------------------------------------------------------------

    /**
     * Game time of an event: `minute` is the minute in progress, `second` the second within
     * it, `injuryMinute` the stoppage minute — so 14:31 means 13 min 31 s after kick-off.
     */
    public fun eventTime(period: Period, t: UefaEventTime?): GameTime {
        if (t?.minute == null) return GameTime(period)
        val base = FootballPeriods.offsetMinutes(period)
        val second = t.second ?: 0
        val elapsed = if (t.injuryMinute != null && t.injuryMinute > 0) {
            ((t.minute - base) * 60 + (t.injuryMinute - 1) * 60 + second).seconds
        } else {
            ((t.minute - 1 - base).coerceAtLeast(0) * 60 + second).seconds
        }
        return GameTime(period, elapsed = elapsed, label = FootballPeriods.label(t.minute, t.injuryMinute))
    }

    public fun events(m: UefaMatch, raw: List<UefaEvent>): List<GameEvent> {
        val home = teamRef(m.homeTeam)
        val away = teamRef(m.awayTeam)
        fun teamOf(actor: UefaActor?): TeamRef? = actor?.team?.let { if (it.id == home.id) home else if (it.id == away.id) away else teamRef(it) }
        fun opponent(team: TeamRef?): TeamRef? = when (team?.id) { home.id -> away; away.id -> home; else -> null }
        val lastPeriod = raw.lastOrNull { it.type == "START_PHASE" }?.let { period(it.phase) } ?: FootballPeriods.SECOND_HALF

        val out = ArrayList<GameEvent>(raw.size)
        var h = 0
        var a = 0
        raw.forEachIndexed { index, e ->
            val period = period(e.phase) ?: lastPeriod
            val time = if (e.type == "FULL_TIME") GameTime(period, elapsed = null) else eventTime(period, e.time)
            val actor = e.primaryActor
            val actorTeam = teamOf(actor)
            val primary = actor?.person?.let { playerRef(it, actorType = actor.type) }
            val secondary = e.secondaryActor?.person?.let { playerRef(it, actorType = e.secondaryActor.type) }
            val coordinates = e.fieldPosition?.coordinate?.let { c -> if (c.x != null && c.y != null) Coordinates(c.x, c.y) else null }
            val event: GameEvent = when (e.type) {
                "GOAL" -> {
                    val own = e.subType == "OWN" || e.subType == "OWN_GOAL"
                    val team = if (own) opponent(actorTeam) ?: actorTeam else actorTeam
                    if (team?.id == home.id) h++ else a++
                    val (type, kind) = when {
                        own -> FootballEventType.OWN_GOAL to GoalKind.OWN_GOAL
                        e.subType == "PENALTY" -> FootballEventType.PENALTY_GOAL to GoalKind.PENALTY
                        e.bodyPart == "HEAD" -> FootballEventType.GOAL to GoalKind.HEADER
                        else -> FootballEventType.GOAL to GoalKind.OPEN_PLAY
                    }
                    GameEvent(
                        id = e.id, type = type, rawType = "GOAL" + (e.subType?.let { ":$it" } ?: ""), time = time, team = team,
                        players = listOfNotNull(primary), score = Score(h, a), coordinates = coordinates,
                        description = (if (own) "Own goal — " else if (kind == GoalKind.PENALTY) "Penalty — " else "Goal — ") + (primary?.name ?: "?"),
                        details = FootballGoalDetails(scorer = primary, assist = null, kind = kind),
                        sortOrder = index,
                    )
                }
                "YELLOW_CARD", "RED_CARD" -> {
                    val (type, card) = when {
                        e.type == "YELLOW_CARD" -> FootballEventType.YELLOW_CARD to CardKind.YELLOW
                        e.subType?.contains("YELLOW") == true -> FootballEventType.SECOND_YELLOW to CardKind.SECOND_YELLOW
                        else -> FootballEventType.RED_CARD to CardKind.RED
                    }
                    GameEvent(
                        id = e.id, type = type, rawType = e.type + (e.subType?.let { ":$it" } ?: ""), time = time, team = actorTeam,
                        players = listOfNotNull(primary), coordinates = coordinates,
                        description = listOfNotNull(card.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }, primary?.name).joinToString(" — "),
                        details = CardDetails(primary, card, e.detail), sortOrder = index,
                    )
                }
                "SUBSTITUTION" -> GameEvent(
                    id = e.id, type = FootballEventType.SUBSTITUTION, rawType = "SUBSTITUTION" + (e.detail?.let { ":$it" } ?: ""), time = time, team = actorTeam,
                    players = listOfNotNull(secondary, primary),
                    description = "Sub — ${secondary?.name ?: "?"} for ${primary?.name ?: "?"}",
                    details = SubstitutionDetails(playerOn = secondary, playerOff = primary, reason = e.detail?.lowercase()), sortOrder = index,
                )
                "PENALTY" -> {
                    val scored = e.subType == "SCORED"
                    GameEvent(
                        id = e.id, type = FootballEventType.SHOOTOUT_ATTEMPT, rawType = "PENALTY:${e.subType ?: "?"}", time = GameTime(FootballPeriods.PENALTIES),
                        team = actorTeam, players = listOfNotNull(primary),
                        description = (if (scored) "Penalty scored — " else "Penalty missed — ") + (primary?.name ?: "?") + (e.detail?.let { " ($it)" } ?: ""),
                        details = ShootoutAttemptDetails(shooter = primary, goalie = secondary, scored = scored, shotType = e.detail?.lowercase()), sortOrder = index,
                    )
                }
                "START_PHASE" -> GameEvent(e.id, FootballEventType.PERIOD_START, "START_PHASE", time.copy(elapsed = Duration.ZERO), description = "Start of ${period.label}", sortOrder = index)
                "END_PHASE" -> GameEvent(e.id, FootballEventType.PERIOD_END, "END_PHASE", time, description = "End of ${period.label}", sortOrder = index)
                "FULL_TIME" -> GameEvent(e.id, FootballEventType.GAME_END, "FULL_TIME", time, description = "Full time", sortOrder = index)
                "VAR" -> GameEvent(e.id, FootballEventType.VAR, "VAR", time, team = actorTeam, players = listOfNotNull(primary), description = "VAR check", sortOrder = index)
                "INJURY_TIME" -> GameEvent(
                    e.id, FootballEventType.OTHER, "INJURY_TIME", time,
                    description = e.injuryTimeMinutesAdded?.let { "+$it min added" } ?: "Injury time", sortOrder = index,
                )
                else -> GameEvent(
                    e.id, FootballEventType.OTHER, e.type + (e.subType?.let { ":$it" } ?: ""), time, team = actorTeam,
                    players = listOfNotNull(primary), coordinates = coordinates,
                    description = e.type.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() } + (actorTeam?.let { " — ${it.name}" } ?: ""),
                    sortOrder = index,
                )
            }
            out += event
        }
        return out.sortedWith(compareBy({ it.period.number }, { it.time.elapsed?.inWholeSeconds ?: Long.MAX_VALUE }, { it.sortOrder ?: 0 }))
    }

    // ---- lineups -------------------------------------------------------------------------

    public fun lineups(l: UefaLineups, m: UefaMatch): List<Lineup> {
        fun side(s: UefaLineupSide?, fallback: UefaTeam): Lineup? {
            if (s == null || (s.field.isEmpty() && s.bench.isEmpty())) return null
            val coach = s.coaches.firstOrNull { it.role == "COACH" }?.person ?: s.coaches.firstOrNull()?.person
            return Lineup(
                gameId = m.id,
                team = teamRef(s.team ?: fallback),
                groups = listOf(
                    LineupGroup(LineupGroupKind.STARTERS, "Starting XI", s.field.map { playerRef(it.player, it.jerseyNumber) }),
                    LineupGroup(LineupGroupKind.BENCH, "Bench", s.bench.map { playerRef(it.player, it.jerseyNumber) }),
                ),
                headCoach = coach?.let { it.translations?.name?.EN ?: it.internationalName },
                formation = null,
            )
        }
        return listOfNotNull(side(l.homeTeam, m.homeTeam), side(l.awayTeam, m.awayTeam))
    }

    // ---- standings -----------------------------------------------------------------------

    public fun standings(list: List<UefaStandings>, seasonId: String, leagueName: String): StandingsTable {
        val groups = list.sortedBy { it.group?.order ?: 0 }.map { s ->
            // The single league-phase table is group "League" inside round "League Phase"; the round names it better.
            // The Nations League has 14 groups over four tiers and only the tier says which one a table is.
            val group = s.group?.metaData?.groupName?.takeIf { it != "League" }
            val tier = s.group?.league?.metaData?.leagueName
            val label = when {
                group != null && tier != null -> "$tier · $group"
                group != null -> group
                else -> s.round?.metaData?.name ?: leagueName
            }
            val rows = s.items.sortedBy { it.rank }.map { i ->
                val team = i.team?.let { teamRef(it) } ?: TeamRef(leagueId, i.teamId ?: "?", i.teamId ?: "?")
                StandingsRow(
                    team = team, rank = i.rank, played = i.played, wins = i.won, losses = i.lost, draws = i.drawn, points = i.points,
                    goalsFor = i.goalsFor, goalsAgainst = i.goalsAgainst, goalDifference = i.goalDifference,
                    extra = buildMap {
                        i.rankingCoefficient?.let { put("coefficient", it.toString()) }
                        if (i.isLive) put("live", "true")
                        if (i.isTied) put("tied", "true")
                    },
                )
            }
            StandingsGroup(label, rows)
        }
        return StandingsTable(leagueId, seasonId, StageKind.REGULAR, groups, grouping = if (groups.size <= 1) "league" else "group")
    }

    public companion object {
        /** Core stat key → `team-statistics` name. */
        public val STAT_NAMES: List<Pair<String, String>> = listOf(
            "possession" to "ball_possession",
            "shots" to "attempts",
            "shotsOnTarget" to "attempts_on_target",
            "shotsOffTarget" to "attempts_off_target",
            "shotsBlocked" to "attempts_blocked",
            "corners" to "corners",
            "fouls" to "fouls_committed",
            "offsides" to "offsides",
            "yellowCards" to "yellow_cards",
            "redCards" to "red_cards",
            "saves" to "saves",
            "passes" to "passes_attempted",
            "passesCompleted" to "passes_completed",
            "passAccuracy" to "passes_accuracy",
            "distanceKm" to "distance_covered",
        )
    }
}
