package org.openscore.providers.sportomedia

import org.openscore.clubs.Clubs
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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Pure functions from gql.sportomedia.se DTOs to the core model. Team ids are the `abbrv`. */
public class SportomediaMapper(private val leagueId: String) {

    /** Team ids here are Sportomedia abbreviations, which the crosswalk keeps under [Clubs.SPORTOMEDIA] rather than a league id. */
    private fun teamRef(abbrv: String, name: String, logo: String?): TeamRef =
        TeamRef(leagueId, abbrv, name, abbrv, logo, clubId = Clubs.clubId(Clubs.SPORTOMEDIA, abbrv))

    public fun homeRef(m: SmMatch): TeamRef = teamRef(m.homeTeamAbbrv, m.homeTeamName, m.homeTeamLogo)
    public fun awayRef(m: SmMatch): TeamRef = teamRef(m.visitingTeamAbbrv, m.visitingTeamName, m.visitingTeamLogo)

    public fun teamRef(t: SmTeam): TeamRef = teamRef(t.abbrv, t.displayName ?: t.name ?: t.abbrv, t.logoImageUrl)

    public fun team(t: SmTeam): Team = Team(ref = teamRef(t), arena = t.arena?.name?.trim(), country = "SWE")

    public fun period(name: String?): Period? = when (name) {
        "PERIOD_FIRST_HALF" -> FootballPeriods.FIRST_HALF
        "PERIOD_SECOND_HALF" -> FootballPeriods.SECOND_HALF
        "PERIOD_FIRST_OVERTIME" -> FootballPeriods.EXTRA_FIRST
        "PERIOD_SECOND_OVERTIME" -> FootballPeriods.EXTRA_SECOND
        "PERIOD_PENALTIES" -> FootballPeriods.PENALTIES
        else -> null
    }

    /** `status` is the flag; an ONGOING match with no period whose latest event closed a period is in a break. */
    public fun gameState(m: SmMatch): GameState = when (m.status) {
        "UPCOMING" -> if (m.extendedStatus == "UPCOMING_STARTING") GameState.PRE_GAME else GameState.SCHEDULED
        "FINISHED" -> GameState.FINAL
        "POSTPONED" -> GameState.POSTPONED
        "INTERRUPTED" -> GameState.SUSPENDED
        "CANCELED", "CANCELLED" -> GameState.CANCELLED
        "ONGOING" -> if (m.period == null && m.matchEvents.firstOrNull()?.type == "PERIOD_RESULT") GameState.INTERMISSION else GameState.LIVE
        "" -> GameState.UNKNOWN
        else -> GameState.UNKNOWN
    }

    private fun clock(m: SmMatch, state: GameState): Clock? {
        if (!state.isLive) return null
        val period = period(m.period) ?: period(m.matchEvents.firstOrNull()?.period) ?: FootballPeriods.periodForMinute(m.matchMinute)
        val parsed = FootballPeriods.parseMinute(m.matchMinuteWithStoppageTime)
        val time = if (parsed != null) FootballPeriods.timeAtMinute(period, parsed.first, parsed.second)
            else if (m.matchMinute > 0) FootballPeriods.fromCumulative(period, m.matchMinute) else GameTime(period)
        return Clock(time, running = state == GameState.LIVE)
    }

    public fun game(m: SmMatch, withEvents: Boolean, stats: SmMatchStats? = null, players: Map<String, PlayerRef> = emptyMap()): Game {
        val state = gameState(m)
        val home = homeRef(m)
        val away = awayRef(m)
        val score = if (state == GameState.SCHEDULED || state == GameState.PRE_GAME) null else Score(m.homeTeamScore, m.visitingTeamScore)
        val events = if (withEvents) events(m, home, away, players) else null
        return Game(
            leagueId = leagueId,
            id = m.id.toString(),
            seasonId = Instant.parse(m.startDate).toString().take(4),
            stage = StageKind.REGULAR,
            startTime = Instant.parse(m.startDate),
            venue = m.arenaName,
            home = home,
            away = away,
            state = state,
            score = score,
            clock = clock(m, state),
            periodScores = periodScores(m, events, state, score, home, away),
            ending = if (state.isFinished) (if (m.matchEvents.any { it.period == "PERIOD_PENALTIES" }) GameEnding.SHOOTOUT else if (m.matchEvents.any { it.period?.contains("OVERTIME") == true }) GameEnding.OVERTIME else GameEnding.REGULATION) else null,
            events = events,
            stats = stats?.totalStats?.let(::stats).orEmpty(),
            rawState = listOfNotNull(m.status, m.extendedStatus, m.period, m.matchMinuteWithStoppageTime).joinToString("/"),
        )
    }

    private fun periodScores(m: SmMatch, events: List<GameEvent>?, state: GameState, score: Score?, home: TeamRef, away: TeamRef): List<PeriodScore> {
        if (score == null) return emptyList()
        val goals = events?.filter { it.type.isGoal }
        val current = period(m.period) ?: goals?.maxOfOrNull { it.period.number }?.let { n -> listOf(FootballPeriods.FIRST_HALF, FootballPeriods.SECOND_HALF, FootballPeriods.EXTRA_FIRST, FootballPeriods.EXTRA_SECOND, FootballPeriods.PENALTIES)[n - 1] }
            ?: if (state.isFinished) FootballPeriods.SECOND_HALF else return emptyList()
        if (goals == null) return emptyList()
        return (1..current.number).map { n ->
            val p = listOf(FootballPeriods.FIRST_HALF, FootballPeriods.SECOND_HALF, FootballPeriods.EXTRA_FIRST, FootballPeriods.EXTRA_SECOND, FootballPeriods.PENALTIES)[n - 1]
            PeriodScore(p, goals.count { it.period.number == n && it.team?.id == home.id }, goals.count { it.period.number == n && it.team?.id == away.id })
        }
    }

    private fun stats(s: SmPeriodStats): Map<String, StatPair> {
        fun pair(h: Int?, a: Int?) = if (h != null && a != null) StatPair(h.toString(), a.toString()) else null
        return listOfNotNull(
            pair(s.homeTeamPossesion, s.visitingTeamPossesion)?.let { "possession" to it },
            pair(s.homeTeamShots, s.visitingTeamShots)?.let { "shots" to it },
            pair(s.homeTeamShotsOnTarget, s.visitingTeamShotsOnTarget)?.let { "shotsOnTarget" to it },
            pair(s.homeTeamCorners, s.visitingTeamCorners)?.let { "corners" to it },
            pair(s.homeTeamOffsides, s.visitingTeamOffsides)?.let { "offsides" to it },
            pair(s.homeTeamYellowCards, s.visitingTeamYellowCards)?.let { "yellowCards" to it },
            pair(s.homeTeamRedCards, s.visitingTeamRedCards)?.let { "redCards" to it },
            pair(s.homeTeamDistance, s.visitingTeamDistance)?.let { "distanceM" to it },
        ).toMap()
    }

    // ---- events --------------------------------------------------------------------------

    /** Player ids only exist in lineups; events name players, so resolve through [players] (name → ref). */
    private fun ref(name: String?, id: Long? = null, players: Map<String, PlayerRef>): PlayerRef? {
        if (name == null && id == null) return null
        return players[name] ?: PlayerRef(leagueId, id?.toString() ?: name!!, name ?: "#$id")
    }

    public fun events(m: SmMatch, home: TeamRef, away: TeamRef, players: Map<String, PlayerRef>): List<GameEvent> {
        val out = ArrayList<GameEvent>()
        val ordered = m.matchEvents.asReversed()
        for ((i, e) in ordered.withIndex()) {
            val period = period(e.period) ?: FootballPeriods.periodForMinute(FootballPeriods.parseMinute(e.minuteWithStoppageTime)?.first ?: 0)
            val elapsed = e.gameTime?.let { (it - FootballPeriods.offsetMinutes(period) * 60).coerceAtLeast(0).seconds }
            val label = e.minuteWithStoppageTime?.let { FootballPeriods.parseMinute(it) }?.let { (min, stop) -> FootballPeriods.label(min, stop) }
            val time = GameTime(period, elapsed = elapsed, label = label ?: elapsed?.let { FootballPeriods.timeInPeriod(period, it).label })
            val team = when (e.byHomeTeam) { true -> home; false -> away; null -> null }
            val score = if (e.homeTeamScore != null && e.visitingTeamScore != null) Score(e.homeTeamScore, e.visitingTeamScore) else null
            val player = ref(e.playerName, null, players)
            val id = e.key ?: "${e.type}-$i"
            val event: GameEvent? = when (e.type) {
                "START" -> GameEvent(id = id, type = FootballEventType.PERIOD_START, rawType = e.type, time = GameTime(period, 0.seconds, label = FootballPeriods.label(FootballPeriods.offsetMinutes(period))), score = score, description = "Start of ${period.label}", sortOrder = i)
                "PERIOD_RESULT" -> {
                    val isEnd = m.status == "FINISHED" && ordered.drop(i + 1).none { it.type == "START" }
                    GameEvent(id = id, type = if (isEnd) FootballEventType.GAME_END else FootballEventType.PERIOD_END, rawType = e.type, time = time, score = score, description = e.description ?: "End of ${period.label}", sortOrder = i)
                }
                "GOAL", "PENALTY", "OWN_GOAL" -> {
                    val kind = when (e.type) { "PENALTY" -> GoalKind.PENALTY; "OWN_GOAL" -> GoalKind.OWN_GOAL; else -> GoalKind.OPEN_PLAY }
                    val type = when (kind) { GoalKind.PENALTY -> FootballEventType.PENALTY_GOAL; GoalKind.OWN_GOAL -> FootballEventType.OWN_GOAL; else -> FootballEventType.GOAL }
                    val assist = ref(e.assistPlayerName, e.assistPlayerId, players)
                    GameEvent(id = id, type = type, rawType = e.type, time = time, team = team, players = listOfNotNull(player, assist), score = score,
                        description = e.description ?: ("Goal — " + (player?.name ?: "?")), details = FootballGoalDetails(player, assist, kind), sortOrder = i)
                }
                "WARNING" -> GameEvent(id = id, type = FootballEventType.YELLOW_CARD, rawType = e.type, time = time, team = team, players = listOfNotNull(player), score = score, description = e.description ?: "Yellow — ${player?.name}", details = CardDetails(player, CardKind.YELLOW), sortOrder = i)
                "SECOND_WARNING" -> GameEvent(id = id, type = FootballEventType.SECOND_YELLOW, rawType = e.type, time = time, team = team, players = listOfNotNull(player), score = score, description = e.description, details = CardDetails(player, CardKind.SECOND_YELLOW), sortOrder = i)
                "RED_CARD" -> GameEvent(id = id, type = FootballEventType.RED_CARD, rawType = e.type, time = time, team = team, players = listOfNotNull(player), score = score, description = e.description, details = CardDetails(player, CardKind.RED), sortOrder = i)
                "SUBSTITUTION" -> {
                    val on = ref(e.inPlayerName, null, players)
                    val off = ref(e.outPlayerName, null, players)
                    GameEvent(id = id, type = FootballEventType.SUBSTITUTION, rawType = e.type, time = time, team = team, players = listOfNotNull(on, off), score = score, description = e.description ?: "Sub — ${on?.name} for ${off?.name}", details = SubstitutionDetails(on, off), sortOrder = i)
                }
                "VAR" -> GameEvent(id = id, type = FootballEventType.VAR, rawType = e.type, time = time, team = team, score = score, description = e.description, sortOrder = i)
                "PENALTY_MISSED", "MISSED_PENALTY" -> GameEvent(id = id, type = FootballEventType.PENALTY_MISSED, rawType = e.type, time = time, team = team, players = listOfNotNull(player), score = score, description = e.description, details = PenaltyMissDetails(player), sortOrder = i)
                else -> GameEvent(id = id, type = FootballEventType.OTHER, rawType = e.type, time = time, team = team, players = listOfNotNull(player), score = score, description = e.description ?: e.typeString, sortOrder = i)
            }
            if (event != null) out += event
        }
        return out
    }

    // ---- lineups -------------------------------------------------------------------------

    private fun positionCode(text: String?): String? = when {
        text == null -> null
        text.equals("Goalkeeper", true) -> "GK"
        text.contains("Back", true) -> "DF"
        text.contains("Midfield", true) -> "MF"
        text.contains("Forward", true) -> "FW"
        else -> text
    }

    public fun playerRef(p: SmLineupPlayer): PlayerRef = PlayerRef(
        leagueId, p.id?.toString() ?: p.displayName.orEmpty(),
        p.displayName ?: listOfNotNull(p.givenName, p.surName).joinToString(" "), p.shirtNumber, positionCode(p.positionText), p.image,
    )

    /** Name → ref index for resolving event players. */
    public fun playerIndex(l: SmLineups?): Map<String, PlayerRef> {
        if (l == null) return emptyMap()
        return (l.homeTeam?.starting.orEmpty() + l.homeTeam?.substitutes.orEmpty() + l.visitingTeam?.starting.orEmpty() + l.visitingTeam?.substitutes.orEmpty())
            .map(::playerRef).associateBy { it.name }
    }

    public fun lineups(gameId: String, l: SmLineups, home: TeamRef, away: TeamRef): List<Lineup> {
        fun side(team: TeamRef, t: SmLineupTeam?): Lineup? {
            if (t == null || t.starting.isEmpty()) return null
            return Lineup(gameId, team, listOf(
                LineupGroup(LineupGroupKind.STARTERS, "Starting XI", t.starting.sortedBy { it.position?.toIntOrNull() ?: 99 }.map(::playerRef)),
                LineupGroup(LineupGroupKind.BENCH, "Bench", t.substitutes.map(::playerRef)),
            ), formation = footballFormation(t.formation?.takeIf { it != "fallback" }))
        }
        return listOfNotNull(side(home, l.homeTeam), side(away, l.visitingTeam))
    }

    // ---- standings / squad / player -------------------------------------------------------

    /** [logos] by abbreviation fills the crests the `total` table omits (they are only on the home/away tables). */
    public fun standings(s: SmStandings, seasonId: String, label: String, logos: Map<String, String?> = emptyMap()): StandingsTable {
        val rows = s.standings.sortedBy { it.position }.map { r ->
            val v = r.stats.associate { it.name to (it.value?.toIntOrNull() ?: 0) }
            StandingsRow(
                team = teamRef(r.teamAbbrv, r.teamName, r.logoImageUrl ?: logos[r.teamAbbrv]), rank = r.position,
                played = v["gp"] ?: 0, wins = v["w"] ?: 0, losses = v["l"] ?: 0, draws = v["t"] ?: 0, points = v["pts"] ?: 0,
                goalsFor = v["gf"], goalsAgainst = v["ga"], goalDifference = v["d"],
                extra = buildMap {
                    val form = r.form.mapNotNull { it.matchResult }.joinToString("")
                    if (form.isNotEmpty()) put("form", form)
                    r.previousPosition?.let { put("previousPosition", it.toString()) }
                },
            )
        }
        return StandingsTable(leagueId, seasonId, StageKind.REGULAR, listOf(StandingsGroup(label, rows)), grouping = "league")
    }

    public fun player(p: SmPlayer, teamId: String?): Player = Player(
        ref = PlayerRef(leagueId, (p.id ?: p.fogisId)?.toString() ?: p.displayName.orEmpty(), p.displayName ?: listOfNotNull(p.givenName, p.surName).joinToString(" "), p.shirtNumber, positionCode(p.position), p.image),
        firstName = p.givenName,
        lastName = p.surName,
        birthDate = Dates.localDateOrNull(p.birthDate),
        nationality = p.nationality,
        heightCm = p.height,
        weightKg = p.weight,
        teamId = teamId ?: p.teamAbbrv,
    )
}
