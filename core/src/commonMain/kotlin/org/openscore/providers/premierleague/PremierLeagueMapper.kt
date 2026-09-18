package org.openscore.providers.premierleague

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
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
import org.openscore.model.football.DisallowedGoalDetails
import org.openscore.model.football.FootballEventType
import org.openscore.model.football.FootballGoalDetails
import org.openscore.model.football.GoalKind
import org.openscore.model.football.PenaltyMissDetails
import org.openscore.model.football.SubstitutionDetails
import org.openscore.model.football.footballFormation
import org.openscore.provider.Dates
import org.openscore.providers.football.FootballPeriods
import kotlin.math.floor
import kotlin.time.Instant

/** Pure functions from Pulselive SDP DTOs to the core model. */
public class PremierLeagueMapper(private val leagueId: String) {

    /** The score API omits crests; Premier League's public image host keys them by the same team id. */
    private fun logoUrl(teamId: String): String = "$BADGE_BASE/t$teamId.png"

    public fun teamRef(t: PlMatchTeam): TeamRef = TeamRef(leagueId, t.id, t.name, t.abbr, logoUrl(t.id))

    public fun team(t: PlTeam): Team = Team(
        ref = TeamRef(leagueId, t.id, t.name, t.abbr, logoUrl(t.id)),
        commonName = t.shortName,
        placeName = t.stadium?.city,
        arena = t.stadium?.name,
        country = "ENG",
    )

    /** `kickoff` is local time in `kickoffTimezoneString` (Europe/London). */
    public fun kickoff(m: PlMatch): Instant {
        val text = m.kickoff ?: error("match ${m.matchId} has no kickoff")
        val zone = TimeZone.of(m.kickoffTimezoneString ?: "Europe/London")
        return LocalDateTime.parse(text.replace(' ', 'T')).toInstant(zone)
    }

    public fun gameState(period: String, resultType: String?): GameState = when {
        resultType?.contains("Postponed", true) == true -> GameState.POSTPONED
        resultType?.contains("Abandoned", true) == true -> GameState.SUSPENDED
        period == "PreMatch" -> GameState.SCHEDULED
        period in setOf("FirstHalf", "SecondHalf", "ExtraFirstHalf", "ExtraSecondHalf", "ShootOut") -> GameState.LIVE
        period in setOf("HalfTime", "ExtraHalfTime", "FullTime90") -> GameState.INTERMISSION
        period == "FullTime" -> GameState.FINAL
        period.isEmpty() -> GameState.UNKNOWN
        else -> GameState.UNKNOWN
    }

    public fun currentPeriod(period: String): Period? = when (period) {
        "FirstHalf", "HalfTime" -> FootballPeriods.FIRST_HALF
        "SecondHalf", "FullTime90" -> FootballPeriods.SECOND_HALF
        "ExtraFirstHalf", "ExtraHalfTime" -> FootballPeriods.EXTRA_FIRST
        "ExtraSecondHalf" -> FootballPeriods.EXTRA_SECOND
        "ShootOut" -> FootballPeriods.PENALTIES
        else -> null
    }

    public fun game(m: PlMatch, withEvents: Boolean, timeline: List<PlTimelineEvent> = emptyList(), events: PlEvents? = null, lineups: PlLineups? = null, stats: List<PlTeamStats> = emptyList()): Game {
        val state = gameState(m.period, m.resultType)
        val home = teamRef(m.homeTeam)
        val away = teamRef(m.awayTeam)
        val score = if (state != GameState.SCHEDULED && m.homeTeam.score != null && m.awayTeam.score != null) Score(m.homeTeam.score, m.awayTeam.score) else null
        val period = currentPeriod(m.period)
        val minute = m.clock?.toIntOrNull()
        val clock = if (state.isLive && period != null) {
            Clock(if (minute != null) FootballPeriods.fromCumulative(period, minute) else GameTime(period), running = state == GameState.LIVE)
        } else null
        return Game(
            leagueId = leagueId,
            id = m.matchId,
            seasonId = m.seasonId ?: m.season,
            stage = StageKind.REGULAR,
            startTime = kickoff(m),
            venue = m.ground,
            home = home,
            away = away,
            state = state,
            score = score,
            clock = clock,
            periodScores = periodScores(m, state, score),
            ending = if (state.isFinished) GameEnding.REGULATION else null,
            events = if (withEvents) events(timeline, events, lineups, home, away) else null,
            stats = stats(stats, m.homeTeam.id),
            rawState = listOfNotNull(m.period, m.resultType, m.clock?.let { "$it'" }).joinToString("/"),
        )
    }

    private fun periodScores(m: PlMatch, state: GameState, score: Score?): List<PeriodScore> {
        if (score == null) return emptyList()
        val ht = if (m.homeTeam.halfTimeScore != null && m.awayTeam.halfTimeScore != null) Score(m.homeTeam.halfTimeScore, m.awayTeam.halfTimeScore) else null
        return when (m.period) {
            "FirstHalf" -> listOf(PeriodScore(FootballPeriods.FIRST_HALF, score.home, score.away))
            "HalfTime" -> listOf(PeriodScore(FootballPeriods.FIRST_HALF, ht?.home ?: score.home, ht?.away ?: score.away))
            "SecondHalf", "FullTime" -> {
                val first = ht ?: Score(0, 0)
                listOf(PeriodScore(FootballPeriods.FIRST_HALF, first.home, first.away), PeriodScore(FootballPeriods.SECOND_HALF, score.home - first.home, score.away - first.away))
            }
            else -> if (state.isFinished) listOf(PeriodScore(FootballPeriods.SECOND_HALF, score.home, score.away)) else emptyList()
        }
    }

    private fun stats(list: List<PlTeamStats>, homeId: String): Map<String, StatPair> {
        val home = list.firstOrNull { it.teamId == homeId || it.side == "Home" }?.stats ?: return emptyMap()
        val away = list.firstOrNull { it !== list.firstOrNull { h -> h.teamId == homeId || h.side == "Home" } }?.stats ?: return emptyMap()
        fun fmt(v: Double) = if (v == floor(v)) v.toLong().toString() else v.toString()
        fun num(e: kotlinx.serialization.json.JsonElement?): Double? = (e as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        fun pair(key: String): StatPair? {
            val h = num(home[key]) ?: return null
            val a = num(away[key]) ?: return null
            return StatPair(fmt(h), fmt(a))
        }
        return listOfNotNull(
            pair("possessionPercentage")?.let { "possession" to it },
            pair("totalScoringAtt")?.let { "shots" to it },
            pair("ontargetScoringAtt")?.let { "shotsOnTarget" to it },
            pair("expectedGoals")?.let { "xg" to it },
            pair("cornerTaken")?.let { "corners" to it },
            pair("fkFoulLost")?.let { "fouls" to it },
            pair("totalOffside")?.let { "offsides" to it },
            pair("totalPass")?.let { "passes" to it },
            pair("yellowCard")?.let { "yellowCards" to it },
            pair("totalRedCard")?.let { "redCards" to it },
            pair("saves")?.let { "saves" to it },
        ).toMap()
    }

    // ---- events --------------------------------------------------------------------------

    private fun playerIndex(l: PlLineups?): Map<String, PlayerRef> {
        if (l == null) return emptyMap()
        return (l.homeTeam?.players.orEmpty() + l.awayTeam?.players.orEmpty()).associate { it.id to playerRef(it) }
    }

    public fun playerRef(p: PlLineupPlayer): PlayerRef = PlayerRef(
        leagueId = leagueId, id = p.id,
        name = p.knownName ?: listOfNotNull(p.firstName, p.lastName).joinToString(" ").ifBlank { p.id },
        jerseyNumber = p.shirtNum?.toIntOrNull(),
        position = positionCode(p.subPosition ?: p.position),
    )

    private fun positionCode(position: String?): String? = when (position) {
        "Goalkeeper" -> "GK"; "Defender" -> "DF"; "Midfielder" -> "MF"; "Forward" -> "FW"; "Substitute" -> null; else -> position
    }

    private fun period(e: PlTimelineEvent): Period = when (e.periodId) {
        "1" -> FootballPeriods.FIRST_HALF
        "2" -> FootballPeriods.SECOND_HALF
        "3" -> FootballPeriods.EXTRA_FIRST
        "4" -> FootballPeriods.EXTRA_SECOND
        "5" -> FootballPeriods.PENALTIES
        else -> FootballPeriods.periodForMinute(e.minutes)
    }

    public fun events(timeline: List<PlTimelineEvent>, grouped: PlEvents?, lineups: PlLineups?, home: TeamRef, away: TeamRef): List<GameEvent> {
        val players = playerIndex(lineups)
        fun ref(id: String?): PlayerRef? = id?.let { players[it] ?: PlayerRef(leagueId, it, "#$it") }
        fun team(id: String?): TeamRef? = when (id) { home.id -> home; away.id -> away; else -> null }
        // `events` groups goals under the side credited with them (an own goal is listed under the
        // beneficiary, with `goalType: "Own"`) and stamps the conventional minute; the timeline
        // stamps the floor minute (1:17 → `minutes: 1`, events `time: "2"`) and attributes an own
        // goal to the scorer's team.
        val goalInfo = listOfNotNull(grouped?.homeTeam, grouped?.awayTeam).flatMap { side -> side.goals.map { (team(side.id) ?: home) to it } }
        fun opponent(t: TeamRef?): TeamRef? = when (t?.id) { home.id -> away; away.id -> home; else -> null }
        val out = ArrayList<GameEvent>()
        var h = 0
        var a = 0
        val pendingOff = ArrayList<PlTimelineEvent>()
        for ((i, e) in timeline.withIndex()) {
            val period = period(e)
            val time = FootballPeriods.fromCumulative(period, e.minutes, e.seconds, floorMinutes = true)
            val team = team(e.teamId)
            val player = ref(e.playerId)
            val id = "${e.eventType.lowercase()}-$i"
            val event: GameEvent? = when (e.eventType) {
                "FIRST_HALF_START" -> marker(id, FootballEventType.PERIOD_START, FootballPeriods.FIRST_HALF, e, "Kick-off")
                "FIRST_HALF_END" -> marker(id, FootballEventType.PERIOD_END, FootballPeriods.FIRST_HALF, e, "Half-time")
                "SECOND_HALF_START" -> marker(id, FootballEventType.PERIOD_START, FootballPeriods.SECOND_HALF, e, "Second half")
                "SECOND_HALF_END" -> marker(id, FootballEventType.GAME_END, FootballPeriods.SECOND_HALF, e, "Full-time")
                "GOAL", "PENALTY_GOAL", "OWN_GOAL" -> {
                    val (creditedByEvents, info) = goalInfo.firstOrNull { (_, g) ->
                        g.playerId == e.playerId && g.time?.toIntOrNull()?.let { it == e.minutes + 1 || it == e.minutes } == true
                    } ?: (null to null)
                    val kind = when {
                        e.eventType == "OWN_GOAL" || info?.goalType.equals("Own", true) -> GoalKind.OWN_GOAL
                        e.eventType == "PENALTY_GOAL" || info?.goalType.equals("Penalty", true) -> GoalKind.PENALTY
                        else -> GoalKind.OPEN_PLAY
                    }
                    val type = when (kind) { GoalKind.OWN_GOAL -> FootballEventType.OWN_GOAL; GoalKind.PENALTY -> FootballEventType.PENALTY_GOAL; else -> FootballEventType.GOAL }
                    val assist = ref(info?.assistPlayerId)
                    val credited = creditedByEvents ?: if (kind == GoalKind.OWN_GOAL) opponent(team) else team
                    if (credited?.id == home.id) h++ else if (credited?.id == away.id) a++
                    GameEvent(
                        id = id, type = type, rawType = e.eventType, time = time, team = credited,
                        players = listOfNotNull(player, assist), score = Score(h, a),
                        description = (if (kind == GoalKind.PENALTY) "Penalty — " else if (kind == GoalKind.OWN_GOAL) "Own goal — " else "Goal — ") + (player?.name ?: "?") + (assist?.let { " · ${it.name}" } ?: ""),
                        details = FootballGoalDetails(player, assist, kind), sortOrder = i,
                    )
                }
                "GOAL_DISALLOWED", "VAR_GOAL_DISALLOWED" -> GameEvent(
                    id = id, type = FootballEventType.GOAL_DISALLOWED, rawType = e.eventType, time = time, team = team, players = listOfNotNull(player),
                    description = "Goal disallowed — ${player?.name ?: "?"}" + if (e.eventType.startsWith("VAR")) " (VAR)" else "",
                    details = DisallowedGoalDetails(player, if (e.eventType.startsWith("VAR")) "VAR" else null), sortOrder = i,
                )
                "PENALTY_MISSED", "PENALTY_SAVED" -> GameEvent(
                    id = id, type = FootballEventType.PENALTY_MISSED, rawType = e.eventType, time = time, team = team, players = listOfNotNull(player),
                    description = "Penalty missed — ${player?.name ?: "?"}", details = PenaltyMissDetails(player, outcome = e.eventType.substringAfter('_').lowercase()), sortOrder = i,
                )
                "YELLOW_CARD", "RED_CARD", "SECOND_YELLOW" -> {
                    val (type, card) = when (e.eventType) {
                        "RED_CARD" -> FootballEventType.RED_CARD to CardKind.RED
                        "SECOND_YELLOW" -> FootballEventType.SECOND_YELLOW to CardKind.SECOND_YELLOW
                        else -> FootballEventType.YELLOW_CARD to CardKind.YELLOW
                    }
                    GameEvent(
                        id = id, type = type, rawType = e.eventType, time = time, team = team, players = listOfNotNull(player),
                        description = "${card.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }} — ${player?.name ?: "?"}",
                        details = CardDetails(player, card), sortOrder = i,
                    )
                }
                "PLAYER_SUBSTITUTE_OFF" -> { pendingOff += e; null }
                "PLAYER_SUBSTITUTE_ON" -> {
                    val off = pendingOff.firstOrNull { it.teamId == e.teamId && it.minutes == e.minutes && it.seconds == e.seconds } ?: pendingOff.firstOrNull { it.teamId == e.teamId }
                    off?.let(pendingOff::remove)
                    val on = player
                    val offRef = ref(off?.playerId)
                    GameEvent(
                        id = id, type = FootballEventType.SUBSTITUTION, rawType = "PLAYER_SUBSTITUTE", time = time, team = team, players = listOfNotNull(on, offRef),
                        description = "Sub — ${on?.name ?: "?"} for ${offRef?.name ?: "?"}", details = SubstitutionDetails(on, offRef), sortOrder = i,
                    )
                }
                else -> GameEvent(id = id, type = FootballEventType.OTHER, rawType = e.eventType, time = time, team = team, players = listOfNotNull(player), sortOrder = i)
            }
            if (event != null) out += event
        }
        return out
    }

    private fun marker(id: String, type: FootballEventType, period: Period, e: PlTimelineEvent, text: String): GameEvent =
        GameEvent(id = id, type = type, rawType = e.eventType, time = FootballPeriods.fromCumulative(period, e.minutes, e.seconds, floorMinutes = true), description = text, sortOrder = 0)

    // ---- lineups -------------------------------------------------------------------------

    public fun lineups(gameId: String, l: PlLineups, home: TeamRef, away: TeamRef): List<Lineup> {
        fun side(team: TeamRef, t: PlLineupTeam?): Lineup? {
            if (t == null || t.players.isEmpty()) return null
            val byId = t.players.associateBy { it.id }
            val starterIds = t.formation?.lineup?.flatten().orEmpty()
            val starters = if (starterIds.isNotEmpty()) starterIds.mapNotNull { byId[it] } else t.players.filter { it.position != "Substitute" }
            val bench = t.players.filter { it.position == "Substitute" }
            return Lineup(
                gameId = gameId, team = team,
                groups = listOf(
                    LineupGroup(LineupGroupKind.STARTERS, "Starting XI", starters.map(::playerRef)),
                    LineupGroup(LineupGroupKind.BENCH, "Bench", bench.map(::playerRef)),
                ),
                headCoach = t.managers.firstOrNull()?.let { listOfNotNull(it.firstName, it.lastName).joinToString(" ").ifBlank { null } },
                formation = footballFormation(t.formation?.formation),
            )
        }
        return listOfNotNull(side(home, l.homeTeam), side(away, l.awayTeam))
    }

    // ---- standings / squad / player -------------------------------------------------------

    public fun standings(s: PlStandings, label: String): StandingsTable {
        val rows = s.tables.firstOrNull()?.entries.orEmpty().sortedBy { it.overall.position }.map { e ->
            val o = e.overall
            StandingsRow(
                team = teamRef(e.team), rank = o.position, played = o.played, wins = o.won, losses = o.lost, draws = o.drawn,
                points = o.points, goalsFor = o.goalsFor, goalsAgainst = o.goalsAgainst, goalDifference = o.goalsFor - o.goalsAgainst,
                extra = buildMap { o.startingPosition?.let { put("startingPosition", it.toString()) }; if (s.live) put("live", "true") },
            )
        }
        return StandingsTable(leagueId, s.season?.id, StageKind.REGULAR, listOf(StandingsGroup(label, rows)), grouping = "league")
    }

    private fun playerId(id: kotlinx.serialization.json.JsonElement?): String? = when (id) {
        is JsonPrimitive -> id.contentOrNull
        is JsonObject -> (id["playerId"] as? JsonPrimitive)?.contentOrNull
        else -> null
    }

    public fun player(p: PlSquadPlayer, teamId: String?): Player {
        val id = playerId(p.id) ?: p.name?.display ?: "?"
        return Player(
            ref = PlayerRef(leagueId, id, p.name?.display ?: listOfNotNull(p.name?.first, p.name?.last).joinToString(" "), p.shirtNum, positionCode(p.position)),
            firstName = p.name?.first,
            lastName = p.name?.last,
            birthDate = Dates.localDateOrNull(p.dates?.birth),
            birthPlace = null,
            nationality = p.country?.isoCode,
            heightCm = p.height,
            weightKg = p.weight,
            handedness = p.preferredFoot?.let { if (it.startsWith("L", true)) "L" else if (it.startsWith("R", true)) "R" else null },
            teamId = teamId ?: p.currentTeam?.id,
        )
    }

    public companion object {
        public const val BADGE_BASE: String = "https://resources.premierleague.com/premierleague/badges/50"
    }
}
