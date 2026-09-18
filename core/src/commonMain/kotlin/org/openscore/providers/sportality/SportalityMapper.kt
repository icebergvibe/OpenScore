package org.openscore.providers.sportality

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
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
import org.openscore.model.PeriodType
import org.openscore.model.Player
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
import org.openscore.model.hockey.ShotDetails
import org.openscore.model.hockey.ShotOutcome
import org.openscore.model.hockey.Strength
import org.openscore.provider.Dates
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Pure functions from Sportality DTOs to the core model; parameterised by league id. */
public class SportalityMapper(private val leagueId: String) {

    // ---- periods / time ------------------------------------------------------------------

    /** 1–3 regulation, 4+ overtime, 99 shootout (mapped to number 5 so it sorts after OT). */
    public fun period(raw: Int): Period = when {
        raw == 99 -> Period(5, PeriodType.SHOOTOUT, "SO")
        raw >= 4 -> Period(raw, PeriodType.OVERTIME, if (raw == 4) "OT" else "${raw - 3}OT")
        else -> Period(raw, PeriodType.REGULATION, raw.toString())
    }

    public fun clockDuration(text: String?): Duration? {
        val parts = text?.split(':') ?: return null
        if (parts.size != 2) return null
        val m = parts[0].toIntOrNull() ?: return null
        val s = parts[1].toIntOrNull() ?: return null
        return m.minutes + s.seconds
    }

    private fun periodLength(period: Period): Duration? = when (period.type) {
        PeriodType.REGULATION -> 20.minutes
        PeriodType.OVERTIME -> 5.minutes
        else -> null
    }

    private fun time(rawPeriod: Int, elapsedText: String?): GameTime {
        val period = period(rawPeriod)
        val elapsed = clockDuration(elapsedText)
        val len = periodLength(period)
        val remaining = if (elapsed != null && len != null && elapsed <= len) len - elapsed else null
        return GameTime(period, elapsed = elapsed, remaining = remaining)
    }

    public fun overviewState(state: String?): GameState? = when (state) {
        "NotStarted" -> GameState.SCHEDULED
        "Ongoing" -> GameState.LIVE
        "PeriodBreak" -> GameState.INTERMISSION
        "GameEnded" -> GameState.FINAL
        else -> null
    }

    public fun scheduleState(state: String?): GameState = when (state) {
        "pre-game", "pre_game" -> GameState.SCHEDULED
        "live" -> GameState.LIVE
        "post-game", "post_game" -> GameState.FINAL
        "canceled", "cancelled" -> GameState.CANCELLED
        else -> GameState.UNKNOWN
    }

    private fun ending(overtime: Boolean, shootout: Boolean): GameEnding = when {
        shootout -> GameEnding.SHOOTOUT
        overtime -> GameEnding.OVERTIME
        else -> GameEnding.REGULATION
    }

    // ---- teams ---------------------------------------------------------------------------

    public fun teamRef(t: SptTeam): TeamRef = TeamRef(
        leagueId = leagueId,
        id = t.uuid,
        name = t.teamNames.long ?: t.teamNames.short ?: t.teamNames.code ?: t.uuid,
        abbreviation = t.teamNames.code ?: t.teamCode,
        logoUrl = t.logo ?: t.icon,
    )

    public fun teamRef(t: SptGameInfoTeam): TeamRef = TeamRef(
        leagueId = leagueId,
        id = t.uuid,
        name = t.names.long ?: t.names.short ?: t.names.code ?: t.uuid,
        abbreviation = t.names.code,
        logoUrl = t.icon,
    )

    public fun teamRef(t: SptScheduleTeam): TeamRef = TeamRef(
        leagueId = leagueId,
        id = t.uuid,
        name = t.names?.long ?: t.names?.short ?: t.code ?: t.uuid,
        abbreviation = t.names?.code ?: t.code,
        logoUrl = t.icon,
    )

    /** `gameheader` teams carry only a display code; [teamsByCode] (from `all-teams`) supplies the UUID. */
    public fun teamRef(t: SptHeaderTeam, teamsByCode: Map<String, SptTeam>): TeamRef =
        teamsByCode[t.code]?.let { teamRef(it).copy(logoUrl = it.logo ?: t.logo) }
            ?: TeamRef(leagueId, id = t.code, name = t.name, abbreviation = t.code, logoUrl = t.logo)

    public fun team(t: SptTeam): Team = Team(
        ref = teamRef(t),
        commonName = t.teamNames.short,
    )

    // ---- games ---------------------------------------------------------------------------

    /** Scoreboard entry from `gameheader`, optionally refined by a `game-overview` fetched for live games. */
    public fun game(h: SptHeaderGame, teamsByCode: Map<String, SptTeam>, overview: SptOverview?): Game {
        val state = overview?.let { overviewState(it.state) } ?: if (h.played) GameState.FINAL else GameState.SCHEDULED
        val score = when {
            overview != null && state != GameState.SCHEDULED -> Score(overview.homeGoals, overview.awayGoals)
            h.played && h.homeTeam.result != null && h.awayTeam.result != null -> Score(h.homeTeam.result, h.awayTeam.result)
            else -> null
        }
        return Game(
            leagueId = leagueId,
            id = h.uuid,
            seasonId = h.ssgtUuid,
            stage = StageKind.REGULAR,
            startTime = Instant.parse(h.startDateTime),
            venue = h.venue,
            home = teamRef(h.homeTeam, teamsByCode),
            away = teamRef(h.awayTeam, teamsByCode),
            state = state,
            score = score,
            clock = overview?.let { clock(it, state) },
            ending = if (state.isFinished) ending(h.overtime, h.shootout) else null,
            rawState = overview?.state ?: "played=${h.played}",
        )
    }

    /** Schedule entry (used for dates outside the `gameheader` window). */
    public fun game(s: SptScheduleGame): Game {
        val state = scheduleState(s.state)
        val home = (s.homeTeamInfo.score as? JsonPrimitive)?.intOrNull
        val away = (s.awayTeamInfo.score as? JsonPrimitive)?.intOrNull
        return Game(
            leagueId = leagueId,
            id = s.uuid,
            seasonId = s.ssgtUuid,
            stage = StageKind.REGULAR,
            startTime = Instant.parse(s.rawStartDateTime),
            venue = s.venueInfo?.name,
            home = teamRef(s.homeTeamInfo),
            away = teamRef(s.awayTeamInfo),
            state = state,
            score = if (state != GameState.SCHEDULED && home != null && away != null) Score(home, away) else null,
            ending = if (state.isFinished) ending(s.overtime, s.shootout) else null,
            rawState = s.state,
        )
    }

    /** Full game: `game-info` header + `game-overview` (null before the game) + `play-by-play` (empty before). */
    public fun game(info: SptGameInfoResponse, overview: SptOverview?, events: List<SptEvent>): Game {
        val gi = info.gameInfo
        val state = overview?.let { overviewState(it.state) } ?: scheduleState(gi.state)
        val home = teamRef(info.homeTeam)
        val away = teamRef(info.awayTeam)
        val infoHome = (info.homeTeam.score as? JsonPrimitive)?.intOrNull
        val infoAway = (info.awayTeam.score as? JsonPrimitive)?.intOrNull
        val score = when {
            overview != null && state != GameState.SCHEDULED -> Score(overview.homeGoals, overview.awayGoals)
            state != GameState.SCHEDULED && infoHome != null && infoAway != null -> Score(infoHome, infoAway)
            else -> null
        }
        val mapped = events(events, home, away)
        val ending = if (state.isFinished) ending(gi.overtime, gi.shootout) else null
        return Game(
            leagueId = leagueId,
            id = gi.gameUuid,
            seasonId = info.ssgtUuid,
            stage = StageKind.REGULAR,
            startTime = Instant.parse(gi.startDateTime),
            venue = gi.arenaName,
            home = home,
            away = away,
            state = state,
            score = score,
            clock = overview?.let { clock(it, state) },
            periodScores = periodScores(mapped, overview?.time?.period ?: events.maxOfOrNull { it.period } ?: 0, score, ending),
            ending = ending,
            events = mapped,
            rawState = listOfNotNull(gi.state, overview?.state).joinToString("/"),
        )
    }

    private fun clock(o: SptOverview, state: GameState): Clock? {
        if (!state.isLive) return null
        val t = o.time ?: return null
        return Clock(time(t.period, t.periodTime), running = null)
    }

    private fun periodScores(events: List<GameEvent>, currentRaw: Int, score: Score?, ending: GameEnding?): List<PeriodScore> {
        if (currentRaw == 0 || score == null) return emptyList()
        val current = period(currentRaw)
        val tallies = LinkedHashMap<Int, PeriodScore>()
        var prevHome = 0
        var prevAway = 0
        for (g in events.filter { it.type == HockeyEventType.GOAL && it.score != null }) {
            val after = g.score!!
            val existing = tallies[g.period.number] ?: PeriodScore(g.period, 0, 0)
            tallies[g.period.number] = existing.copy(home = existing.home + (after.home - prevHome), away = existing.away + (after.away - prevAway))
            prevHome = after.home
            prevAway = after.away
        }
        val lastPlayed = if (current.type == PeriodType.SHOOTOUT) 4 else current.number
        val result = (1..lastPlayed).map { n -> tallies[n] ?: PeriodScore(period(n), 0, 0) }.toMutableList()
        if (current.type == PeriodType.SHOOTOUT && ending == GameEnding.SHOOTOUT) {
            val homeWon = score.home > score.away
            result += PeriodScore(current, if (homeWon) 1 else 0, if (homeWon) 0 else 1)
        }
        return result
    }

    // ---- events --------------------------------------------------------------------------

    public fun playerRef(p: SptEventPlayer): PlayerRef = PlayerRef(
        leagueId = leagueId,
        id = p.playerId,
        name = "${p.firstName} ${p.familyName}".trim(),
        jerseyNumber = p.jerseyToday?.toIntOrNull(),
    )

    public fun events(raw: List<SptEvent>, home: TeamRef, away: TeamRef): List<GameEvent> {
        val out = ArrayList<GameEvent>()
        for (e in raw) {
            val team = when (e.eventTeam?.place) { "home" -> home; "away" -> away; else -> null }
            val coords = if (e.locationX != null && e.locationY != null) Coordinates(e.locationX, e.locationY) else null
            val player = e.player?.let(::playerRef)
            when (e.type) {
                "period" -> {
                    val p = period(e.period)
                    val len = periodLength(p)
                    if (e.started == true) out += GameEvent(
                        id = "period-${e.period}-start", type = HockeyEventType.PERIOD_START, rawType = "period",
                        time = GameTime(p, elapsed = Duration.ZERO, remaining = len), sortOrder = -1,
                    )
                    if (e.finished == true) out += GameEvent(
                        id = "period-${e.period}-end", type = HockeyEventType.PERIOD_END, rawType = "period",
                        time = GameTime(p, elapsed = len, remaining = if (len != null) Duration.ZERO else null), sortOrder = Int.MAX_VALUE,
                    )
                }
                "goal" -> {
                    if (e.period == 99) continue // the deciding shootout goal is also reported as a goal; attempts cover it
                    val assists = listOfNotNull(e.assists["first"], e.assists["second"]).map(::playerRef)
                    val strength = strength(e.goalStatus, e.isPenaltyShot)
                    out += GameEvent(
                        id = e.id(), type = HockeyEventType.GOAL, rawType = "goal" + (e.goalStatus?.let { ":$it" } ?: ""),
                        time = time(e.period, e.time), team = team,
                        players = listOfNotNull(player) + assists,
                        score = if (e.homeGoals != null && e.awayGoals != null) Score(e.homeGoals, e.awayGoals) else null,
                        coordinates = coords,
                        description = buildString {
                            append("Goal — ").append(player?.name ?: "?")
                            e.player?.statistics?.firstOrNull { it.key == "G" }?.value?.let { append(" (").append(it).append(')') }
                            if (assists.isNotEmpty()) append(" · ").append(assists.joinToString { it.name })
                            strength?.takeIf { it != Strength.EV }?.let { append(" [").append(it).append(']') }
                            if (e.isEmptyNetGoal) append(" [EN]")
                        },
                        details = GoalDetails(
                            scorer = player, assists = assists, strength = strength, emptyNet = e.isEmptyNetGoal,
                            scorerSeasonTotal = e.player?.statistics?.firstOrNull { it.key == "G" }?.value?.toIntOrNull(),
                        ),
                        sortOrder = e.eventId,
                    )
                }
                "shot" -> out += GameEvent(
                    id = e.id(), type = HockeyEventType.SHOT, rawType = "shot",
                    time = time(e.period, e.time), team = team, players = listOfNotNull(player), coordinates = coords,
                    details = ShotDetails(shooter = player, outcome = ShotOutcome.ON_GOAL),
                    sortOrder = e.eventId,
                )
                "penalty" -> {
                    val minutes = e.variant?.description?.substringBefore(" min")?.trim()?.toIntOrNull()
                        ?: e.variant?.minorTime?.toIntOrNull()?.takeIf { it > 0 }
                    out += GameEvent(
                        id = e.id(), type = HockeyEventType.PENALTY, rawType = "penalty" + (e.offence?.let { ":$it" } ?: ""),
                        time = time(e.period, e.time), team = team, players = listOfNotNull(player),
                        description = listOfNotNull(player?.name ?: team?.name, minutes?.let { "$it min" }, e.offence).joinToString(" — "),
                        details = PenaltyDetails(player = player, minutes = minutes, infraction = e.offence, severity = e.variant?.shortName),
                        sortOrder = e.eventId,
                    )
                }
                "goalkeeper" -> out += GameEvent(
                    id = e.id(), type = HockeyEventType.GOALIE_CHANGE, rawType = "goalkeeper",
                    time = time(e.period, e.time), team = team, players = listOfNotNull(player),
                    description = (player?.name ?: "Goalie") + if (e.isEntering == true) " in" else " out",
                    sortOrder = e.eventId,
                )
                "timeout" -> out += GameEvent(
                    id = e.id(), type = HockeyEventType.OTHER, rawType = "timeout",
                    time = time(e.period, e.time), team = team, description = "Timeout — ${team?.name ?: ""}".trim(),
                    sortOrder = e.eventId,
                )
                "shootout-penalty-shot" -> out += GameEvent(
                    id = e.id(), type = HockeyEventType.SHOOTOUT_ATTEMPT, rawType = e.type,
                    time = GameTime(period(99)), team = team, players = listOfNotNull(player), coordinates = coords,
                    score = e.shootoutScore?.let { Score(it.homeGoals, it.awayGoals) },
                    description = (if (e.isGoal == true) "Shootout goal — " else "Shootout miss — ") + (player?.name ?: "?"),
                    details = ShootoutAttemptDetails(shooter = player, goalie = null, scored = e.isGoal == true),
                    sortOrder = e.shootoutIndex ?: e.eventId,
                )
                else -> out += GameEvent(
                    id = e.id(), type = HockeyEventType.OTHER, rawType = e.type,
                    time = time(e.period, e.time), team = team, players = listOfNotNull(player), sortOrder = e.eventId,
                )
            }
        }
        return out.sortedWith(compareBy({ it.period.number }, { it.time.elapsed?.inWholeSeconds ?: 0L }, { it.sortOrder ?: 0 }))
    }

    private fun SptEvent.id(): String = eventId?.toString() ?: "$type-$period-$time"

    public fun strength(goalStatus: String?, penaltyShot: Boolean): Strength? = when {
        penaltyShot || goalStatus == "PS" -> Strength.PS
        goalStatus == null -> null
        goalStatus.startsWith("PP") -> Strength.PP
        goalStatus.startsWith("SH") -> Strength.SH
        goalStatus == "EN" -> Strength.EN
        else -> Strength.EV
    }

    // ---- lineups -------------------------------------------------------------------------

    public fun lineups(gameId: String, box: SptBoxscore, home: TeamRef, away: TeamRef): List<Lineup> {
        fun side(team: TeamRef, players: Map<String, SptBoxPlayer>?, goalies: Map<String, SptBoxPlayer>?, skaters: List<SptBoxRow>?, gks: List<SptBoxRow>?): Lineup {
            fun ref(row: SptBoxRow): PlayerRef {
                val id = row.info.playerId.toString()
                val p = players?.get(id) ?: goalies?.get(id)
                return PlayerRef(leagueId, id, p?.fullName?.ifBlank { null } ?: "#$id", row.nr, row.pos)
            }
            val groups = ArrayList<LineupGroup>()
            groups += LineupGroup(LineupGroupKind.GOALIES, "Goalies", gks.orEmpty().sortedBy { it.line ?: 99 }.map(::ref))
            for (line in skaters.orEmpty().mapNotNull { it.line }.distinct().sorted()) {
                val onLine = skaters.orEmpty().filter { it.line == line }
                val forwards = onLine.filter { it.pos in FORWARD_POS }
                val defense = onLine.filter { it.pos in DEFENSE_POS }
                if (forwards.isNotEmpty()) groups += LineupGroup(LineupGroupKind.LINE, "Line $line", forwards.map(::ref))
                if (defense.isNotEmpty()) groups += LineupGroup(LineupGroupKind.PAIRING, "Pairing $line", defense.map(::ref))
            }
            val rest = skaters.orEmpty().filter { it.line == null || it.pos !in FORWARD_POS + DEFENSE_POS }
            if (rest.isNotEmpty()) groups += LineupGroup(LineupGroupKind.OTHER, "Other", rest.map(::ref))
            return Lineup(gameId, team, groups)
        }
        return listOf(
            side(home, box.players.homeTeamValue, box.goalkeepers.homeTeamValue, box.stats.homeTeamValue, box.gkStats.homeTeamValue),
            side(away, box.players.awayTeamValue, box.goalkeepers.awayTeamValue, box.stats.awayTeamValue, box.gkStats.awayTeamValue),
        )
    }

    // ---- standings / roster / player -----------------------------------------------------

    public fun standings(s: SptStandings, seasonId: String): StandingsTable {
        val rows = s.leagueStandings.sortedBy { it.rank }.map { r ->
            val t = r.info.teamInfo
            StandingsRow(
                team = TeamRef(leagueId, t.teamUuid, t.teamNames.long ?: t.teamNames.short ?: t.teamUuid, t.teamNames.code, t.teamMedia),
                rank = r.rank,
                played = r.gp,
                wins = r.w + r.otw,
                losses = r.l,
                otherLosses = r.otl,
                points = r.points,
                goalsFor = r.g,
                goalsAgainst = r.ga,
                goalDifference = r.diff,
                extra = buildMap {
                    put("regulationWins", r.w.toString())
                    put("overtimeWins", r.otw.toString())
                    s.groupings.firstOrNull { r.rank in it.first..it.last }?.let { put("group", it.description) }
                },
            )
        }
        return StandingsTable(leagueId, seasonId, StageKind.REGULAR, listOf(StandingsGroup("League", rows)), grouping = "league")
    }

    public fun roster(groups: List<SptAthleteGroup>, teamId: String): List<Player> = groups.flatMap { g ->
        g.players.map { a ->
            Player(
                ref = PlayerRef(
                    leagueId = leagueId,
                    id = a.uuid,
                    name = a.fullName?.ifBlank { null } ?: "${a.firstName} ${a.lastName}".trim(),
                    jerseyNumber = a.jerseyNumber,
                    position = g.positionCode,
                    headshotUrl = a.renderedLatestPortrait?.url,
                ),
                firstName = a.firstName,
                lastName = a.lastName,
                nationality = a.nationality?.ifBlank { null },
                teamId = teamId,
            )
        }
    }

    public fun player(p: SptProfilePage): Player = Player(
        ref = PlayerRef(
            leagueId = leagueId,
            id = p.uuid,
            name = p.fullName?.ifBlank { null } ?: "${p.firstName} ${p.lastName}".trim(),
            jerseyNumber = p.jerseyNumber,
            position = p.positionCode,
            headshotUrl = p.media?.url,
        ),
        firstName = p.firstName,
        lastName = p.lastName,
        birthDate = Dates.localDateOrNull(p.birthDate),
        nationality = p.nationality,
        heightCm = p.height?.value?.toInt(),
        weightKg = p.weight?.value?.toInt(),
        handedness = p.shoots?.takeIf { it == "L" || it == "R" },
        teamId = p.team?.uuid,
        active = p.isInSquad,
    )

    private companion object {
        val FORWARD_POS = setOf("LW", "CE", "RW", "C", "F")
        val DEFENSE_POS = setOf("LD", "RD", "D")
    }
}
