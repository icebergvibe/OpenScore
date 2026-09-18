package org.openscore.providers.seriea

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
import kotlin.time.Instant

/** Pure functions from Deltatre SDP DTOs to the core model. */
public class SerieAMapper(private val leagueId: String) {

    /** Weeks ahead the feed knows only the day (`"2026-11-28Z"`, `isUnknownKickOffTime`): that day at midnight in Rome. */
    private fun startTime(m: SaMatch): Instant {
        val text = m.matchDateUtc ?: error("match ${m.matchId} has no date")
        if (text.contains('T')) return Instant.parse(text)
        return LocalDate.parse(text.removeSuffix("Z")).atStartOfDayIn(ROME)
    }

    public fun logo(path: String?): String? = path?.let { MEDIA + it }

    public fun teamRef(t: SaTeam?): TeamRef = TeamRef(
        leagueId = leagueId,
        id = t?.teamId ?: "",
        name = t?.officialName ?: t?.shortName ?: t?.teamId ?: "",
        abbreviation = t?.acronymName,
        logoUrl = logo(t?.imagery?.teamLogo),
    )

    public fun team(t: SaTeam): Team = Team(ref = teamRef(t), commonName = t.shortName, country = t.countryCode)

    public fun gameState(status: String?, phase: String?): GameState = when (status) {
        "UPCOMING", "UNKNOWN", "LINEUP", "TACTICAL" -> GameState.SCHEDULED
        "POSTPONED" -> GameState.POSTPONED
        "CANCELED", "CANCELLED" -> GameState.CANCELLED
        "ABANDONED" -> GameState.SUSPENDED
        "SUSPENDED" -> GameState.SUSPENDED
        "FINISHED" -> GameState.FINAL
        "LIVE" -> when (phase) {
            "HALF_TIME_BREAK", "END_SECOND_HALF", "EXTRA_TIME_HALF_TIME", "END_EXTRA_TIME_1" -> GameState.INTERMISSION
            else -> GameState.LIVE
        }
        null -> GameState.UNKNOWN
        else -> GameState.UNKNOWN
    }

    public fun currentPeriod(phase: String?): Period? = when (phase?.uppercase()?.replace("_", "")) {
        "FIRSTHALF", "HALFTIMEBREAK" -> FootballPeriods.FIRST_HALF
        "SECONDHALF", "ENDSECONDHALF", "FULLTIME" -> FootballPeriods.SECOND_HALF
        "EXTRATIME1", "EXTRATIMEFIRSTHALF", "EXTRATIMEHALFTIME", "ENDEXTRATIME1" -> FootballPeriods.EXTRA_FIRST
        "EXTRATIME2", "EXTRATIMESECONDHALF", "ENDEXTRATIME2" -> FootballPeriods.EXTRA_SECOND
        "PENALTIES", "PENALTYSHOOTOUT" -> FootballPeriods.PENALTIES
        else -> null
    }

    private fun int(e: JsonElement?): Int? = (e as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    public fun game(m: SaMatch, summary: SaSummary?, stats: SaTeamStats?): Game {
        val state = gameState(m.status, m.phase)
        val home = teamRef(m.home)
        val away = teamRef(m.away)
        val h = m.homeScorePush ?: m.providerHomeScore
        val a = m.awayScorePush ?: m.providerAwayScore
        val score = if (state != GameState.SCHEDULED && h != null && a != null) Score(h, a) else null
        val period = currentPeriod(m.phase)
        val minute = int(m.time)
        val clock = if (state.isLive && period != null) {
            Clock(
                if (minute != null) FootballPeriods.timeAtMinute(period, minute, int(m.additionalTime)?.takeIf { it > 0 }) else GameTime(period),
                running = state == GameState.LIVE,
            )
        } else null
        val events = summary?.let { events(it, home, away) }
        val shootout = m.providerPenaltyScoreHome != null || m.providerPenaltyScoreAway != null
        return Game(
            leagueId = leagueId,
            id = m.matchId ?: "",
            seasonId = m.seasonId,
            stage = StageKind.REGULAR,
            startTime = startTime(m),
            startTimeTbd = m.isUnknownKickOffTime || m.matchDateUtc?.contains('T') == false,
            venue = m.stadiumName,
            home = home,
            away = away,
            state = state,
            score = score,
            clock = clock,
            periodScores = periodScores(events, period, state, score, m),
            ending = if (state.isFinished) (if (shootout) GameEnding.SHOOTOUT else if (m.winReason?.contains("Extra", true) == true) GameEnding.OVERTIME else GameEnding.REGULATION) else null,
            events = events,
            stats = stats?.let(::stats).orEmpty(),
            rawState = listOfNotNull(m.status, m.phase).joinToString("/"),
        )
    }

    private fun periodScores(events: List<GameEvent>?, current: Period?, state: GameState, score: Score?, m: SaMatch): List<PeriodScore> {
        if (score == null) return emptyList()
        val last = current ?: if (state.isFinished) FootballPeriods.SECOND_HALF else return emptyList()
        if (events == null) return emptyList()
        val goals = events.filter { it.type.isGoal }
        return (1..last.number).map { n ->
            val p = when (n) { 1 -> FootballPeriods.FIRST_HALF; 2 -> FootballPeriods.SECOND_HALF; 3 -> FootballPeriods.EXTRA_FIRST; 4 -> FootballPeriods.EXTRA_SECOND; else -> FootballPeriods.PENALTIES }
            PeriodScore(p, goals.count { it.period.number == n && it.team?.id == m.home?.teamId }, goals.count { it.period.number == n && it.team?.id == m.away?.teamId })
        }
    }

    private fun stats(s: SaTeamStats): Map<String, StatPair> {
        fun v(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull
        val byId = s.stats.associateBy { it.statsId }
        fun pair(id: String): StatPair? {
            val st = byId[id] ?: return null
            val h = v(st.statsValueHome) ?: return null
            val a = v(st.statsValueAway) ?: return null
            return StatPair(h, a)
        }
        return listOfNotNull(
            pair("possession-perc")?.let { "possession" to it },
            pair("shots")?.let { "shots" to it },
            pair("shots-on-goal")?.let { "shotsOnTarget" to it },
            pair("expected-goals")?.let { "xg" to it },
            pair("corners")?.let { "corners" to it },
            pair("fouls")?.let { "fouls" to it },
            pair("offsides")?.let { "offsides" to it },
            pair("yellow-cards")?.let { "yellowCards" to it },
            pair("red-cards")?.let { "redCards" to it },
        ).toMap()
    }

    // ---- events --------------------------------------------------------------------------

    private fun ref(id: String?, name: String?, number: String? = null, role: Int? = null): PlayerRef? =
        if (id == null && name == null) null else PlayerRef(leagueId, id ?: name!!, name ?: id!!, number?.toIntOrNull(), roleCode(role))

    private fun roleCode(role: Int?): String? = when (role) { 1 -> "GK"; 2 -> "DF"; 3 -> "MF"; 4 -> "FW"; else -> null }

    public fun events(s: SaSummary, home: TeamRef, away: TeamRef): List<GameEvent> {
        val out = ArrayList<GameEvent>()
        val ordered = s.events.asReversed() // feed is newest first
        for ((i, e) in ordered.withIndex()) {
            val side = e.home ?: e.away
            val team = if (e.home != null) home else if (e.away != null) away else null
            val period = currentPeriod(side?.phase ?: e.phase) ?: FootballPeriods.periodForMinute(side?.time ?: 0)
            val time = side?.time?.let { FootballPeriods.timeAtMinute(period, it, side.additionalTime?.takeIf { a -> a > 0 }) } ?: GameTime(period)
            val p = side?.player
            val player = ref(p?.playerId, p?.shortName ?: p?.displayName, p?.bibNumber, p?.role)
            val id = e.eventId ?: "${e.type}-$i"
            val score = if (e.homeScorePush != null && e.awayScorePush != null) Score(e.homeScorePush, e.awayScorePush) else null
            val event: GameEvent? = when (e.type) {
                "first-half", "kick-off", "start" -> marker(id, FootballEventType.PERIOD_START, FootballPeriods.FIRST_HALF, e, "Kick-off", score)
                "half-time-break" -> marker(id, FootballEventType.PERIOD_END, FootballPeriods.FIRST_HALF, e, "Half-time", score)
                "second-half", "second-half-start" -> marker(id, FootballEventType.PERIOD_START, FootballPeriods.SECOND_HALF, e, "Second half", score)
                "end-second-half" -> marker(id, if (s.status == "FINISHED") FootballEventType.GAME_END else FootballEventType.PERIOD_END, FootballPeriods.SECOND_HALF, e, "Full-time", score)
                "goal", "own-goal", "penalty" -> {
                    val kind = when (e.type) { "own-goal" -> GoalKind.OWN_GOAL; "penalty" -> GoalKind.PENALTY; else -> GoalKind.OPEN_PLAY }
                    val type = when (kind) { GoalKind.OWN_GOAL -> FootballEventType.OWN_GOAL; GoalKind.PENALTY -> FootballEventType.PENALTY_GOAL; else -> FootballEventType.GOAL }
                    val assist = ref(p?.assistPlayerId, p?.assistShortName)
                    GameEvent(
                        id = id, type = type, rawType = e.type, time = time, team = team, players = listOfNotNull(player, assist), score = score,
                        description = e.description ?: ("Goal — " + (player?.name ?: "?")), details = FootballGoalDetails(player, assist, kind), sortOrder = i,
                    )
                }
                "penalty-missed" -> GameEvent(id = id, type = FootballEventType.PENALTY_MISSED, rawType = e.type, time = time, team = team, players = listOfNotNull(player), description = e.description, details = PenaltyMissDetails(player), sortOrder = i)
                "yellow-card", "red-card", "yellow-red-card" -> {
                    val (type, card) = when (e.type) {
                        "red-card" -> FootballEventType.RED_CARD to CardKind.RED
                        "yellow-red-card" -> FootballEventType.SECOND_YELLOW to CardKind.SECOND_YELLOW
                        else -> FootballEventType.YELLOW_CARD to CardKind.YELLOW
                    }
                    GameEvent(id = id, type = type, rawType = e.type, time = time, team = team, players = listOfNotNull(player), description = e.description ?: e.label, details = CardDetails(player, card), sortOrder = i)
                }
                "substitution" -> {
                    val off = ref(p?.relatedPlayerId, p?.relatedShortName)
                    GameEvent(id = id, type = FootballEventType.SUBSTITUTION, rawType = e.type, time = time, team = team, players = listOfNotNull(player, off), description = e.description ?: "Sub — ${player?.name ?: "?"} for ${off?.name ?: "?"}", details = SubstitutionDetails(player, off), sortOrder = i)
                }
                "var" -> GameEvent(id = id, type = FootballEventType.VAR, rawType = e.type, time = time, team = team, description = e.description ?: e.label, sortOrder = i)
                else -> GameEvent(id = id, type = FootballEventType.OTHER, rawType = e.type, time = time, team = team, players = listOfNotNull(player), description = e.description ?: e.label, sortOrder = i)
            }
            if (event != null) out += event
        }
        return out
    }

    private fun marker(id: String, type: FootballEventType, period: Period, e: SaEvent, text: String, score: Score?): GameEvent =
        GameEvent(id = id, type = type, rawType = e.type, time = GameTime(period, label = null), description = e.label ?: text, score = score, sortOrder = 0)

    // ---- lineups -------------------------------------------------------------------------

    private fun ref(p: SaPlayer): PlayerRef = PlayerRef(leagueId, p.playerId, p.shortName ?: p.displayName ?: p.playerId, p.bibNumber?.toIntOrNull(), roleCode(p.role))

    public fun lineups(gameId: String, l: SaLineups): List<Lineup> {
        fun side(t: SaTeam?): Lineup? {
            if (t == null || t.fielded.isEmpty()) return null
            return Lineup(
                gameId = gameId, team = teamRef(t),
                groups = listOf(
                    LineupGroup(LineupGroupKind.STARTERS, "Starting XI", t.fielded.map(::ref)),
                    LineupGroup(LineupGroupKind.BENCH, "Bench", t.benched.map(::ref)),
                ),
                headCoach = t.staff.firstOrNull { it.role == 101 }?.let { it.shortName ?: it.displayName },
                formation = footballFormation(t.tacticalFormation?.takeIf { it != "Unknown" }),
            )
        }
        return listOfNotNull(side(l.home), side(l.away))
    }

    // ---- standings / roster --------------------------------------------------------------

    public fun standings(s: SaStandings, seasonId: String?, label: String): StandingsTable {
        val rows = s.teams.map { t ->
            val byId = t.stats.associateBy { it.statsId }
            fun n(id: String) = (byId[id]?.statsValue as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            val form = (byId["form"]?.statsValue as? JsonArray)?.mapNotNull { ((it as? JsonObject)?.get("formType") as? JsonPrimitive)?.contentOrNull }?.filter { it != "-" }?.joinToString("")
            StandingsRow(
                team = teamRef(t), rank = n("rank"), played = n("matches-played"), wins = n("win"), losses = n("lose"), draws = n("draw"),
                points = n("points"), goalsFor = n("goals-for"), goalsAgainst = n("goals-against"), goalDifference = n("goal-difference"),
                extra = buildMap { if (!form.isNullOrEmpty()) put("form", form) },
            )
        }.sortedBy { it.rank }
        return StandingsTable(leagueId, seasonId, StageKind.REGULAR, listOf(StandingsGroup(label, rows)), grouping = "league")
    }

    public fun rosterPlayer(p: SaPlayer, teamId: String): Player = Player(
        ref = ref(p),
        firstName = p.mediaFirstName,
        lastName = p.mediaLastName,
        birthDate = Dates.localDateOrNull(p.dateOfBirth),
        nationality = p.nationalityIsoCode,
        heightCm = p.height?.toIntOrNull(),
        weightKg = p.weight?.toIntOrNull(),
        teamId = teamId,
    )

    public companion object {
        public const val MEDIA: String = "https://media-sdp.legaseriea.it/"
    }
}

private val ROME: TimeZone = TimeZone.of("Europe/Rome")
