package org.openscore.providers.efl

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
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
import org.openscore.model.PlayerNames
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
import org.openscore.model.football.footballFormation
import org.openscore.providers.football.FootballPeriods
import kotlin.math.floor
import kotlin.time.Instant

/**
 * Pure functions from the Gamechanger DTOs to the core model. One mapper serves both the
 * Championship and the Carabao Cup; [competitionId] only decides how a round is labelled
 * and whether a match is a play-off tie.
 */
public class EflMapper(private val leagueId: String, private val competitionId: Int) {

    // ---- teams ---------------------------------------------------------------------------

    /** The list rows carry no team id, but every crest is `…/t{id}.png` - the id the rest of the API uses. */
    public fun teamIdFromCrest(crest: String?): String? = crest?.let { CREST_ID.find(it)?.groupValues?.get(1) }

    public fun teamRef(t: EflRowTeam): TeamRef {
        val name = t.name ?: t.officialName ?: "?"
        return TeamRef(leagueId, teamIdFromCrest(t.crest) ?: name, name, t.initials?.takeIf { it.isNotBlank() }, t.crest)
    }

    public fun teamRef(t: EflTeam): TeamRef {
        val name = t.teamName ?: t.teamOfficialName ?: "?"
        return TeamRef(leagueId, t.teamID ?: teamIdFromCrest(t.crestURL) ?: name, name, t.teamNameInitials?.takeIf { it.isNotBlank() }, t.crestURL)
    }

    public fun team(t: EflTeam): Team = Team(
        ref = teamRef(t),
        commonName = t.teamShortName,
        arena = t.stadiumName,
        country = "ENG",
    )

    // ---- state ---------------------------------------------------------------------------

    /** `2026-09-17 18:30:00` is UTC without a marker. */
    public fun kickoff(text: String?): Instant {
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: error("match has no kick-off time")
        return LocalDateTime.parse(t.take(19).replace(' ', 'T')).toInstant(TimeZone.UTC)
    }

    /**
     * The site's own state machine over `period`: `PreMatch` upcoming, `FullTime` complete,
     * everything between active, `Postponed` in either `period` or `resultType`. A period the
     * table does not know but a running minute alongside it still means the match is on.
     */
    public fun gameState(period: String?, resultType: String?, minute: Int?): GameState = when {
        resultType.equals("Postponed", true) || period.equals("Postponed", true) -> GameState.POSTPONED
        resultType.equals("Abandoned", true) || period.equals("Abandoned", true) -> GameState.SUSPENDED
        resultType.equals("Cancelled", true) || period.equals("Cancelled", true) -> GameState.CANCELLED
        period == null || period == "PreMatch" || period == "Pre-Match" -> GameState.SCHEDULED
        period in LIVE_PERIODS -> GameState.LIVE
        period in BREAK_PERIODS -> GameState.INTERMISSION
        period == "FullTime" -> GameState.FINAL
        minute != null -> GameState.LIVE
        else -> GameState.UNKNOWN
    }

    /**
     * The period a `period` value belongs to. `FullTimePens` is the pause before a shoot-out,
     * which the Carabao Cup reaches straight after 90 minutes and a semi-final after extra time.
     */
    public fun currentPeriod(period: String?, extraTime: Boolean = false): Period? = when (period) {
        "FirstHalf", "HalfTime" -> FootballPeriods.FIRST_HALF
        "SecondHalf", "FullTime90", "FullTime" -> FootballPeriods.SECOND_HALF
        "ExtraFirstHalf", "ExtraHalfTime" -> FootballPeriods.EXTRA_FIRST
        "ExtraSecondHalf" -> FootballPeriods.EXTRA_SECOND
        "FullTimePens" -> if (extraTime) FootballPeriods.EXTRA_SECOND else FootballPeriods.SECOND_HALF
        "ShootOut" -> FootballPeriods.PENALTIES
        else -> null
    }

    private fun clock(state: GameState, period: String?, minute: Int?, extraTime: Boolean = false): Clock? {
        if (!state.isLive) return null
        val p = currentPeriod(period, extraTime) ?: return null
        val time = if (minute != null) FootballPeriods.fromCumulative(p, minute) else GameTime(p)
        return Clock(time, running = state == GameState.LIVE)
    }

    private fun ending(resultType: String?, penalties: Boolean, extraTime: Boolean): GameEnding = when {
        resultType == "PenaltyShootout" || penalties -> GameEnding.SHOOTOUT
        extraTime -> GameEnding.OVERTIME
        else -> GameEnding.REGULATION
    }

    // ---- games ---------------------------------------------------------------------------

    /** A list row: names, crests, running score and period, no ids beyond the crest and no events. */
    public fun game(row: EflRow<EflMatchRow>): Game {
        val m = row.attributes
        val state = gameState(m.matchPeriod, m.resultType, m.matchMinutes)
        val home = teamRef(m.homeTeam)
        val away = teamRef(m.awayTeam)
        val score = if (state.hasStarted && m.homeTeam.score != null && m.awayTeam.score != null) Score(m.homeTeam.score, m.awayTeam.score) else null
        val pens = pair(m.homeTeam.penaltyScore, m.awayTeam.penaltyScore)
        return Game(
            leagueId = leagueId,
            id = row.id,
            seasonId = m.kickOffDateUTC?.let { seasonOf(kickoff(it)) },
            stage = if (competitionId == CARABAO_CUP) StageKind.OTHER else StageKind.REGULAR,
            startTime = kickoff(m.kickOffDateUTC),
            home = home,
            away = away,
            state = state,
            score = score,
            clock = clock(state, m.matchPeriod, m.matchMinutes),
            periodScores = listScores(m.matchPeriod, score, pair(m.homeTeam.halfScore, m.awayTeam.halfScore), pens),
            // The row has no extra-time score; a match past the 105th minute went to extra time.
            ending = if (state.isFinished) ending(m.resultType, pens != null, extraTime = (m.matchMinutes ?: 0) > 105) else null,
            rawState = listOfNotNull(m.matchPeriod, m.resultType, m.matchMinutes?.let { "$it'" }, m.postponementReason).joinToString("/"),
            startTimeTbd = isTbc(m.TBC),
        )
    }

    /** The match document, with the stats rows when the match has started. */
    public fun game(doc: EflRow<EflMatch>, stats: List<EflRow<EflMatchStats>> = emptyList()): Game {
        val m = doc.attributes
        val period = m.matchDetails?.period ?: m.period
        val minute = m.matchDetails?.matchTime
        val resultType = m.matchDetails?.resultType
        val state = gameState(period, resultType, minute)
        val homeSide = side(m, m.homeTeamID)
        val awaySide = side(m, m.awayTeamID)
        val home = (m.homeTeam ?: homeSide?.team)?.let(::teamRef) ?: TeamRef(leagueId, m.homeTeamID ?: "?", m.homeTeamID ?: "?")
        val away = (m.awayTeam ?: awaySide?.team)?.let(::teamRef) ?: TeamRef(leagueId, m.awayTeamID ?: "?", m.awayTeamID ?: "?")
        val score = if (state.hasStarted) pair(homeSide?.score, awaySide?.score) else null
        val pens = pair(homeSide?.penaltyScore, awaySide?.penaltyScore)
        val extra = pair(homeSide?.extraScore, awaySide?.extraScore)
        val events = events(m, home, away)
        return Game(
            leagueId = leagueId,
            id = doc.id,
            seasonId = m.seasonID?.toString(),
            stage = stage(m),
            competition = round(m),
            startTime = kickoff(m.kickOffUTC),
            venue = listOfNotNull(m.venue, m.venueCity).distinct().joinToString(", ").ifEmpty { null },
            home = home,
            away = away,
            state = state,
            score = score,
            clock = clock(state, period, minute, extraTime = extra != null),
            periodScores = detailScores(state, period, score, pair(homeSide?.halfScore, awaySide?.halfScore), extra, pens, events, home, away),
            ending = if (state.isFinished) ending(resultType, pens != null, extra != null) else null,
            events = events,
            stats = stats(stats, home.id, away.id),
            rawState = listOfNotNull(period, resultType, minute?.let { "$it'" }, m.postponementReason).joinToString("/"),
            startTimeTbd = isTbc(m.TBC),
        )
    }

    /** The two sides of the tie; a play-off final document also carries the semi-final placeholders. */
    private fun side(m: EflMatch, teamId: String?): EflMatchTeam? = teamId?.let { id -> m.matchTeams.firstOrNull { it.teamID == id } }

    private fun pair(home: Int?, away: Int?): Score? = if (home != null && away != null) Score(home, away) else null

    /** Seasons are keyed by their start year; a new one begins in July. */
    private fun seasonOf(kickoff: Instant): String {
        val date = kickoff.toLocalDateTime(TimeZone.UTC).date
        return (if (date.month >= Month.JULY) date.year else date.year - 1).toString()
    }

    /** The Championship play-offs live inside the league's own competition as rounds 47-49. */
    private fun stage(m: EflMatch): StageKind = when {
        competitionId == CARABAO_CUP -> StageKind.OTHER
        isPlayOff(m) -> StageKind.PLAYOFF
        else -> StageKind.REGULAR
    }

    private fun isPlayOff(m: EflMatch): Boolean = (m.matchDay?.toIntOrNull() ?: 0) >= PLAYOFF_FIRST_ROUND || m.matchType == "2nd Leg" || m.matchType == "Cup"

    /** A round label worth showing under the league name: cup rounds and the play-offs, not the 46 league rounds. */
    private fun round(m: EflMatch): String? {
        val day = m.matchDay?.toIntOrNull()
        return when {
            competitionId == CARABAO_CUP -> day?.let { "Round $it" } ?: m.matchDay
            !isPlayOff(m) -> null
            day == PLAYOFF_FINAL_ROUND || m.matchType == "Cup" -> "Play-off final"
            else -> "Play-off semi-final" + when (m.matchType) { "2nd Leg" -> ", 2nd leg"; "1st Leg" -> ", 1st leg"; else -> "" }
        }
    }

    // ---- period scores -------------------------------------------------------------------

    /** The list knows the half-time and final scores: halves only, plus the shoot-out tally. */
    private fun listScores(period: String?, score: Score?, half: Score?, pens: Score?): List<PeriodScore> {
        if (score == null) return emptyList()
        val out = ArrayList<PeriodScore>(3)
        when (period) {
            "FirstHalf" -> out += PeriodScore(FootballPeriods.FIRST_HALF, score.home, score.away)
            "HalfTime" -> out += PeriodScore(FootballPeriods.FIRST_HALF, half?.home ?: score.home, half?.away ?: score.away)
            else -> {
                val first = half ?: Score(0, 0)
                out += PeriodScore(FootballPeriods.FIRST_HALF, first.home, first.away)
                out += PeriodScore(FootballPeriods.SECOND_HALF, score.home - first.home, score.away - first.away)
            }
        }
        if (pens != null) out += PeriodScore(FootballPeriods.PENALTIES, pens.home, pens.away)
        return out
    }

    /**
     * The document files every goal under the side it counts for, with its period, so halves
     * and extra time are counted from the events. Until the first goal event the totals are
     * all there is, and [listScores] reads them the way the list does.
     */
    private fun detailScores(state: GameState, period: String?, score: Score?, half: Score?, extra: Score?, pens: Score?, events: List<GameEvent>, home: TeamRef, away: TeamRef): List<PeriodScore> {
        if (score == null) return emptyList()
        val goals = events.filter { it.type.isGoal }
        if (goals.isEmpty()) return listScores(period, score, half, pens)
        val current = currentPeriod(period, extra != null)?.number ?: if (state.isFinished) 2 else 1
        val played = maxOf(current.coerceAtMost(4), if (extra != null) 4 else 0, goals.maxOfOrNull { it.period.number } ?: 0).coerceAtMost(4)
        val out = (1..played).map { n ->
            val p = periodByNumber(n)
            PeriodScore(p, goals.count { it.period.number == n && it.team?.id == home.id }, goals.count { it.period.number == n && it.team?.id == away.id })
        }.toMutableList()
        if (pens != null) out += PeriodScore(FootballPeriods.PENALTIES, pens.home, pens.away)
        return out
    }

    private fun periodByNumber(n: Int): Period = when (n) {
        1 -> FootballPeriods.FIRST_HALF
        2 -> FootballPeriods.SECOND_HALF
        3 -> FootballPeriods.EXTRA_FIRST
        4 -> FootballPeriods.EXTRA_SECOND
        else -> FootballPeriods.PENALTIES
    }

    private fun stats(rows: List<EflRow<EflMatchStats>>, homeId: String, awayId: String): Map<String, StatPair> {
        // Two rows, home first; matched by id where the ids are known.
        val h = rows.firstOrNull { it.attributes.teamID == homeId }?.attributes ?: rows.getOrNull(0)?.attributes ?: return emptyMap()
        val a = rows.firstOrNull { it.attributes.teamID == awayId }?.attributes ?: rows.getOrNull(1)?.attributes ?: return emptyMap()
        fun fmt(v: Double) = if (v == floor(v)) v.toLong().toString() else v.toString()
        fun pair(x: Number?, y: Number?): StatPair? = if (x != null && y != null) StatPair(fmt(x.toDouble()), fmt(y.toDouble())) else null
        return listOfNotNull(
            pair(h.possession, a.possession)?.let { "possession" to it },
            pair(h.shots, a.shots)?.let { "shots" to it },
            // A side with no shot on target has `null` there, not 0.
            pair(h.shots?.let { h.shotsOnTarget ?: 0 }, a.shots?.let { a.shotsOnTarget ?: 0 })?.let { "shotsOnTarget" to it },
            pair(h.corners, a.corners)?.let { "corners" to it },
            pair(h.fouls, a.fouls)?.let { "fouls" to it },
        ).toMap()
    }

    // ---- events --------------------------------------------------------------------------

    private fun playerIndex(m: EflMatch): Map<String, PlayerRef> = buildMap {
        for (side in m.matchTeams) for (p in side.players?.start.orEmpty() + side.players?.sub.orEmpty()) {
            val id = p.playerID ?: continue
            put(id, playerRef(p))
        }
    }

    public fun playerRef(p: EflMatchPlayer): PlayerRef = PlayerRef(
        leagueId = leagueId,
        id = p.playerID ?: "?",
        name = p.playerName?.let { n -> n.customKnownName ?: n.knownName ?: PlayerNames.fromParts(n.firstName, n.lastName) }?.ifBlank { null } ?: p.playerID ?: "?",
        jerseyNumber = p.shirtNumber,
        position = positionCode(p.playerSubPosition ?: p.playerPosition),
    )

    private fun positionCode(position: String?): String? = when (position) {
        "Goalkeeper" -> "GK"; "Defender" -> "DF"; "Midfielder" -> "MF"; "Forward", "Striker" -> "FW"; "Substitute", null -> null; else -> position
    }

    /** Goals and cards name their period; substitutions carry Opta's number for it; a shoot-out kick has none. */
    private fun period(e: EflEvent): Period = when (e.eventPeriod) {
        "FirstHalf", "1" -> FootballPeriods.FIRST_HALF
        "SecondHalf", "2" -> FootballPeriods.SECOND_HALF
        "ExtraFirstHalf", "3" -> FootballPeriods.EXTRA_FIRST
        "ExtraSecondHalf", "4" -> FootballPeriods.EXTRA_SECOND
        "ShootOut", "5" -> FootballPeriods.PENALTIES
        else -> if (e.shootoutEvents != null) FootballPeriods.PENALTIES else FootballPeriods.periodForMinute(e.eventTime ?: 0)
    }

    private fun time(e: EflEvent, period: Period): GameTime {
        if (period == FootballPeriods.PENALTIES) return GameTime(period)
        // `eventMinute`/`eventSecond` are the floor of the clock (28:17), `eventTime` the conventional minute (29).
        val floor = e.eventMinute
        return if (floor != null) FootballPeriods.fromCumulative(period, floor, e.eventSecond ?: 0, floorMinutes = true)
        else FootballPeriods.fromCumulative(period, e.eventTime ?: FootballPeriods.offsetMinutes(period))
    }

    /**
     * Every event of both sides in time order. Goals are filed under the side they count for
     * (an own goal under the beneficiary, `goalType: "Own"`), which is what
     * [FootballEventType.OWN_GOAL] wants; the scorer is resolved through the lineups, never
     * through the event's stale nested `player`.
     */
    public fun events(m: EflMatch, home: TeamRef, away: TeamRef): List<GameEvent> {
        val players = playerIndex(m)
        fun ref(id: String?): PlayerRef? = id?.let { players[it] ?: PlayerRef(leagueId, it, "#${it.removePrefix("p")}") }
        fun team(id: String?): TeamRef? = when (id) { home.id -> home; away.id -> away; else -> null }
        val all = m.matchTeams.flatMap { side ->
            val ev = side.events ?: return@flatMap emptyList()
            (ev.goals + ev.bookings + ev.subs + ev.shootout + ev.`var`).map { (team(side.teamID) ?: team(it.teamID)) to it }
        }.sortedWith(compareBy({ it.second.eventTimestamp ?: "" }, { it.second.eventID?.toLongOrNull() ?: Long.MAX_VALUE }))
        var h = 0
        var a = 0
        return all.mapIndexed { i, (credited, e) ->
            val period = period(e)
            val time = time(e, period)
            val id = e.eventID ?: "event-$i"
            val goal = e.goalEvents
            val card = e.bookingEvents
            val sub = e.substitutionEvents
            val kick = e.shootoutEvents
            when {
                goal != null -> {
                    val kind = when (goal.goalType) { "Own" -> GoalKind.OWN_GOAL; "Penalty" -> GoalKind.PENALTY; else -> GoalKind.OPEN_PLAY }
                    val type = when (kind) { GoalKind.OWN_GOAL -> FootballEventType.OWN_GOAL; GoalKind.PENALTY -> FootballEventType.PENALTY_GOAL; else -> FootballEventType.GOAL }
                    val scorer = ref(goal.playerID)
                    if (credited?.id == home.id) h++ else if (credited?.id == away.id) a++
                    GameEvent(
                        id = id, type = type, rawType = "goal:${goal.goalType ?: "?"}", time = time, team = credited,
                        players = listOfNotNull(scorer), score = Score(h, a),
                        description = (when (kind) { GoalKind.PENALTY -> "Penalty - "; GoalKind.OWN_GOAL -> "Own goal - "; else -> "Goal - " }) + (scorer?.name ?: "?"),
                        details = FootballGoalDetails(scorer, null, kind), sortOrder = i,
                    )
                }
                card != null -> {
                    val (type, kindOfCard) = when {
                        card.cardType == "SecondYellow" -> FootballEventType.SECOND_YELLOW to CardKind.SECOND_YELLOW
                        card.card == "Red" || card.cardType == "Red" -> FootballEventType.RED_CARD to CardKind.RED
                        else -> FootballEventType.YELLOW_CARD to CardKind.YELLOW
                    }
                    val player = ref(card.playerID)
                    GameEvent(
                        id = id, type = type, rawType = "booking:${card.cardType ?: card.card ?: "?"}", time = time, team = credited, players = listOfNotNull(player),
                        description = "${kindOfCard.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }} - ${player?.name ?: "?"}" + (card.reason?.let { " ($it)" } ?: ""),
                        details = CardDetails(player, kindOfCard, card.reason), sortOrder = i,
                    )
                }
                sub != null -> {
                    val on = ref(sub.subOnID)
                    val off = ref(sub.subOffID)
                    GameEvent(
                        id = id, type = FootballEventType.SUBSTITUTION, rawType = "substitution", time = time, team = credited, players = listOfNotNull(on, off),
                        description = "Sub - ${on?.name ?: "?"} for ${off?.name ?: "?"}", details = SubstitutionDetails(on, off, sub.reason?.lowercase()), sortOrder = i,
                    )
                }
                kick != null -> {
                    val shooter = ref(kick.playerID)
                    val scored = kick.outcome == "Scored"
                    GameEvent(
                        id = id, type = FootballEventType.SHOOTOUT_ATTEMPT, rawType = "shootout:${kick.outcome ?: "?"}", time = time, team = credited, players = listOfNotNull(shooter),
                        description = (if (scored) "Penalty scored - " else "Penalty ${kick.outcome?.lowercase() ?: "missed"} - ") + (shooter?.name ?: "?"),
                        details = ShootoutAttemptDetails(shooter, null, scored, kick.outcome?.lowercase()), sortOrder = i,
                    )
                }
                else -> GameEvent(id = id, type = FootballEventType.VAR, rawType = "var", time = time, team = credited, description = "VAR check", sortOrder = i)
            }
        }
    }

    // ---- lineups -------------------------------------------------------------------------

    public fun lineups(gameId: String, m: EflMatch, home: TeamRef, away: TeamRef): List<Lineup> {
        fun lineup(team: TeamRef, t: EflMatchTeam?): Lineup? {
            val players = t?.players ?: return null
            if (players.start.isEmpty() && players.sub.isEmpty()) return null
            return Lineup(
                gameId = gameId, team = team,
                groups = listOf(
                    LineupGroup(LineupGroupKind.STARTERS, "Starting XI", players.start.sortedBy { it.formationPlace ?: 99 }.map(::playerRef)),
                    LineupGroup(LineupGroupKind.BENCH, "Bench", players.sub.map(::playerRef)),
                ),
                formation = footballFormation(t.formation),
            )
        }
        return listOfNotNull(lineup(home, side(m, home.id)), lineup(away, side(m, away.id)))
    }

    // ---- standings -----------------------------------------------------------------------

    public fun standings(rows: List<EflRow<EflTableRow>>, seasonId: String, label: String): StandingsTable {
        val table = rows.sortedBy { it.attributes.position }.map { r ->
            val t = r.attributes
            StandingsRow(
                team = TeamRef(leagueId, r.id, t.teamName ?: r.id, t.teamNameInitials?.takeIf { it.isNotBlank() }, t.crestURL),
                rank = t.position, played = t.played, wins = t.won, losses = t.lost, draws = t.drawn,
                points = t.points, goalsFor = t.goalsFor, goalsAgainst = t.goalsAgainst, goalDifference = t.goalDifference,
                extra = buildMap {
                    t.startDayPosition?.let { put("startingPosition", it.toString()) }
                    // Newest first upstream; oldest first here, the way a form strip reads.
                    t.form?.takeIf { it.isNotBlank() }?.let { put("form", it.split(',').reversed().joinToString("")) }
                },
            )
        }
        return StandingsTable(leagueId, seasonId, StageKind.REGULAR, listOf(StandingsGroup(label, table)), grouping = "league")
    }

    public companion object {
        public const val CHAMPIONSHIP: Int = 10
        public const val CARABAO_CUP: Int = 2
        /** `matchDay` of the first play-off round (the semi-final first legs); 49 is the final. */
        public const val PLAYOFF_FIRST_ROUND: Int = 47
        public const val PLAYOFF_FINAL_ROUND: Int = 49

        private val CREST_ID = Regex("""/(t\d+)\.png(?:\?.*)?$""")
        private val LIVE_PERIODS = setOf("FirstHalf", "SecondHalf", "ExtraFirstHalf", "ExtraSecondHalf", "ShootOut")
        private val BREAK_PERIODS = setOf("HalfTime", "ExtraHalfTime", "FullTime90", "FullTimePens")
    }
}
