package org.openscore.providers.chl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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
import org.openscore.model.hockey.Strength
import org.openscore.provider.Dates
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Pure functions from CHL (Corebine) DTOs to the core model. */
public object ChlMapper {

    private const val LEAGUE_ID = "chl"

    /** Verified pattern for team logos; keyed by the team's numeric `externalId`. */
    public fun teamLogo(externalId: String?): String? =
        externalId?.let { "https://res.cloudinary.com/chl-production/image/upload/chl-prod/assets/teams/$it.png" }

    public fun teamRef(t: ChlTeamRef): TeamRef =
        TeamRef(LEAGUE_ID, t.entityId, t.name, t.shortName, teamLogo(t.externalId))

    public fun teamRef(t: ChlLineupTeam): TeamRef =
        TeamRef(LEAGUE_ID, t.entityId, t.name, t.shortName, teamLogo(t.externalId))

    /** 3-letter lowercase (`fin`) → upper-case alpha-3; apps can map further. */
    private fun country(c: ChlCountry?): String? = c?.code?.uppercase()

    public fun team(t: ChlTeamRef): Team = Team(ref = teamRef(t), country = country(t.country))

    // ---- periods -------------------------------------------------------------------------

    public fun period(name: String): Period = when {
        name.startsWith("1st") -> Period(1, PeriodType.REGULATION, "1")
        name.startsWith("2nd") -> Period(2, PeriodType.REGULATION, "2")
        name.startsWith("3rd") -> Period(3, PeriodType.REGULATION, "3")
        name.startsWith("Overtime", ignoreCase = true) || name == "OT" -> Period(4, PeriodType.OVERTIME, "OT")
        name.startsWith("Shootout", ignoreCase = true) || name == "SO" -> Period(5, PeriodType.SHOOTOUT, "SO")
        else -> Period(0, PeriodType.UNKNOWN, name)
    }

    private fun periodStart(p: Period): Int = when (p.type) {
        PeriodType.REGULATION -> (p.number - 1) * 1200
        PeriodType.OVERTIME -> 3600
        else -> 0
    }

    private fun periodLength(p: Period, stage: StageKind?): Duration? = when (p.type) {
        PeriodType.REGULATION -> 20.minutes
        PeriodType.OVERTIME -> if (stage == StageKind.REGULAR) 5.minutes else null
        else -> null
    }

    public fun stage(m: ChlMatch): StageKind = when (m.stage?.group?.name) {
        null, "Regular Season" -> StageKind.REGULAR
        else -> StageKind.PLAYOFF
    }

    public fun ending(state: ChlNamed?): GameEnding? = when (state?.shortName) {
        "F" -> GameEnding.REGULATION
        "F/OT" -> GameEnding.OVERTIME
        "F/SO" -> GameEnding.SHOOTOUT
        else -> null
    }

    /**
     * `status` is the flag; live values are not captured yet, so anything that is neither
     * `not-started` nor `finished` is treated as live. During a live game, a listed period
     * that has finished while the next has not started reads as an intermission.
     */
    public fun gameState(m: ChlMatch): GameState = when (m.status) {
        "not-started" -> GameState.SCHEDULED
        "finished" -> GameState.FINAL
        "" -> GameState.UNKNOWN
        else -> {
            val periods = m.results?.periods.orEmpty()
            val anyInProgress = periods.any { it.status != "finished" && it.status != "not-started" }
            val someFinished = periods.any { it.status == "finished" }
            if (!anyInProgress && someFinished) GameState.INTERMISSION else GameState.LIVE
        }
    }

    // ---- games ---------------------------------------------------------------------------

    public fun game(m: ChlMatch, withEvents: Boolean): Game {
        val state = gameState(m)
        val stage = stage(m)
        val periods = m.results?.periods.orEmpty()
            .filter { it.status != "not-started" }
            .map { period(it.name) to it }
            .sortedBy { it.first.number }
        val score = m.results?.scores?.takeIf { state != GameState.SCHEDULED }?.let { Score(it.home, it.away) }
        return Game(
            leagueId = LEAGUE_ID,
            id = m.entityId,
            seasonId = null,
            stage = stage,
            startTime = Instant.parse(m.startDate),
            venue = m.venue?.name,
            home = teamRef(m.teams.home),
            away = teamRef(m.teams.away),
            state = state,
            score = score,
            clock = null, // CHL publishes no clock
            periodScores = periods.mapNotNull { (p, raw) -> raw.scores?.let { PeriodScore(p, it.home, it.away) } },
            ending = if (state.isFinished) ending(m.state) else null,
            events = if (withEvents) events(m, stage) else null,
            rawState = listOfNotNull(m.status, m.state?.shortName).joinToString("/"),
        )
    }

    // ---- events --------------------------------------------------------------------------

    public fun playerRef(p: ChlPlayer): PlayerRef = PlayerRef(
        leagueId = LEAGUE_ID,
        id = p.entityId,
        name = "${p.firstName} ${p.lastName}".trim(),
        jerseyNumber = p.number,
        position = p.position?.shortName,
    )

    public fun events(m: ChlMatch, stage: StageKind): List<GameEvent> {
        val home = teamRef(m.teams.home)
        val away = teamRef(m.teams.away)
        val out = ArrayList<GameEvent>()
        val periods = m.results?.periods.orEmpty().map { period(it.name) to it }.sortedBy { it.first.number }
        var seq = 0
        for ((period, raw) in periods) {
            val start = periodStart(period)
            val len = periodLength(period, stage)
            val inShootout = period.type == PeriodType.SHOOTOUT
            for (a in chronological(raw.actions)) {
                seq++
                val team = when (a.teamType) { "home" -> home; "away" -> away; else -> null }
                val players = a.players.map(::playerRef)
                val elapsed = a.time?.let { (it.regularTime - start).coerceAtLeast(0).seconds }
                val time = GameTime(period, elapsed = elapsed, remaining = if (elapsed != null && len != null && elapsed <= len) len - elapsed else null)
                val id = "${period.number}-${a.externalId ?: a.actionType}-$seq"
                val title = a.message?.title.orEmpty()
                val event: GameEvent? = when (a.actionType) {
                    "goal", "goal-own" -> if (inShootout) null else {
                        val scorer = players.firstOrNull()
                        val assists = players.drop(1)
                        val strength = strength(title)
                        GameEvent(
                            id = id, type = HockeyEventType.GOAL, rawType = a.actionType, time = time, team = team,
                            players = players, score = scoreFromTitle(title),
                            description = buildString {
                                append("Goal — ").append(scorer?.name ?: "?")
                                if (assists.isNotEmpty()) append(" · ").append(assists.joinToString { it.name })
                                strength?.takeIf { it != Strength.EV }?.let { append(" [").append(it).append(']') }
                            },
                            details = GoalDetails(scorer = scorer, assists = assists, strength = strength, emptyNet = strength == Strength.EN),
                            sortOrder = seq,
                        )
                    }
                    "goal-penalty", "shot-penalty-miss" -> if (!inShootout) {
                        // Outside the shootout the same goal is also reported as `goal`; a miss is not an event we model.
                        null
                    } else {
                        val scored = a.actionType == "goal-penalty"
                        GameEvent(
                            id = id, type = HockeyEventType.SHOOTOUT_ATTEMPT, rawType = a.actionType, time = GameTime(period), team = team,
                            players = players, description = (if (scored) "Shootout goal — " else "Shootout miss — ") + (players.firstOrNull()?.name ?: "?"),
                            details = ShootoutAttemptDetails(players.firstOrNull(), null, scored = scored), sortOrder = seq,
                        )
                    }
                    "penalty" -> GameEvent(
                        id = id, type = HockeyEventType.PENALTY, rawType = "penalty", time = time, team = team, players = players,
                        description = listOfNotNull(players.firstOrNull()?.name ?: team?.name, title.takeIf { it != "Penalty" }).joinToString(" — "),
                        details = PenaltyDetails(player = players.firstOrNull()), // infraction/minutes only exist as ticker text
                        sortOrder = seq,
                    )
                    "goalkeeper-change" -> GameEvent(
                        id = id, type = HockeyEventType.GOALIE_CHANGE, rawType = a.actionType, time = time, team = team, players = players,
                        description = (players.firstOrNull()?.name ?: "Goalie") + if (title.contains("Out", true)) " out" else " in", sortOrder = seq,
                    )
                    "timeout" -> GameEvent(id = id, type = HockeyEventType.OTHER, rawType = "timeout", time = time, team = team, description = title, sortOrder = seq)
                    // The first period has `event-start` ("Start of Game") instead of `period-start`.
                    "period-start", "event-start" -> GameEvent(id = id, type = HockeyEventType.PERIOD_START, rawType = a.actionType, time = GameTime(period, Duration.ZERO, len), description = title, sortOrder = seq)
                    "period-end" -> GameEvent(id = id, type = HockeyEventType.PERIOD_END, rawType = a.actionType, time = GameTime(period, len, if (len != null) Duration.ZERO else null), description = title, sortOrder = seq)
                    "event-end" -> GameEvent(id = id, type = HockeyEventType.GAME_END, rawType = a.actionType, time = GameTime(period, len, if (len != null) Duration.ZERO else null), description = title, sortOrder = seq)
                    else -> GameEvent(id = id, type = HockeyEventType.OTHER, rawType = a.actionType, time = time, team = team, players = players, description = title, sortOrder = seq)
                }
                if (event != null) out += event
            }
        }
        return out.sortedWith(compareBy({ it.period.number }, { it.time.elapsed?.inWholeSeconds ?: 0L }, { it.sortOrder ?: 0 }))
    }

    /** Finished games list actions newest-first; put them back in game order. */
    private fun chronological(actions: List<ChlAction>): List<ChlAction> {
        val first = actions.firstOrNull() ?: return actions
        val descending = first.actionType == "period-end" || first.actionType == "event-end" ||
            (actions.size > 1 && (first.time?.regularTime ?: 0) > (actions.last().time?.regularTime ?: 0))
        return if (descending) actions.reversed() else actions
    }

    /** `"Goal, 2:1, PP"` → strength from the trailing qualifier. */
    public fun strength(title: String): Strength? {
        val qualifier = title.split(',').drop(2).joinToString(",").trim()
        return when {
            qualifier.contains("Penalty Shot", true) -> Strength.PS
            qualifier.contains("EN", false) || qualifier.contains("Empty", true) -> Strength.EN
            qualifier.contains("PP") -> Strength.PP
            qualifier.contains("SH") -> Strength.SH
            else -> Strength.EV
        }
    }

    /** `"Goal, 2:1"` → home 2, away 1. */
    public fun scoreFromTitle(title: String): Score? {
        val part = title.split(',').getOrNull(1)?.trim() ?: return null
        val (h, a) = part.split(':').takeIf { it.size == 2 } ?: return null
        val home = h.trim().toIntOrNull() ?: return null
        val away = a.trim().toIntOrNull() ?: return null
        return Score(home, away)
    }

    // ---- lineups -------------------------------------------------------------------------

    public fun lineups(l: ChlLineups): List<Lineup> {
        fun side(t: ChlLineupTeam): Lineup {
            val groups = ArrayList<LineupGroup>()
            val goalies = t.athletes.filter { it.position?.shortName == "GK" }.sortedBy { it.position?.index ?: 99 }
            groups += LineupGroup(LineupGroupKind.GOALIES, "Goalies", goalies.map(::playerRef))
            val skaters = t.athletes.filter { it.position?.shortName != "GK" }
            for (line in skaters.mapNotNull { it.position?.categoryIndex }.distinct().sorted()) {
                val onLine = skaters.filter { it.position?.categoryIndex == line }.sortedBy { it.position?.index ?: 99 }
                val fw = onLine.filter { it.position?.shortName == "FW" }
                val de = onLine.filter { it.position?.shortName == "DE" }
                if (fw.isNotEmpty()) groups += LineupGroup(LineupGroupKind.LINE, "Line $line", fw.map(::playerRef))
                if (de.isNotEmpty()) groups += LineupGroup(LineupGroupKind.PAIRING, "Pairing $line", de.map(::playerRef))
            }
            val rest = skaters.filter { it.position?.categoryIndex == null }
            if (rest.isNotEmpty()) groups += LineupGroup(LineupGroupKind.OTHER, "Other", rest.map(::playerRef))
            return Lineup(
                gameId = l.entityId, team = teamRef(t), groups = groups,
                headCoach = t.staff.firstOrNull { it.position?.name == "Head Coach" }?.let { "${it.firstName} ${it.lastName}".trim() },
            )
        }
        return listOf(side(l.teams.home), side(l.teams.away))
    }

    // ---- standings -----------------------------------------------------------------------

    public fun standings(series: List<ChlSeries>, seasonId: String): StandingsTable {
        val groups = series.map { s ->
            StandingsGroup(
                label = s.stage?.group?.name ?: "League",
                rows = s.teams.sortedBy { it.stats.place }.map { t ->
                    val won = t.stats.matches?.won
                    val lost = t.stats.matches?.lost
                    val otherLosses = (lost?.overtimes ?: 0) + (lost?.shootouts ?: 0)
                    StandingsRow(
                        team = TeamRef(LEAGUE_ID, t.entityId, t.name, t.shortName, teamLogo(t.externalId)),
                        rank = t.stats.place,
                        played = t.stats.matches?.played?.total ?: 0,
                        wins = won?.total ?: 0,
                        losses = (lost?.total ?: 0) - otherLosses,
                        draws = t.stats.matches?.drawn?.takeIf { it > 0 },
                        otherLosses = otherLosses,
                        points = t.stats.points,
                        goalsFor = t.stats.goals?.scored?.total,
                        goalsAgainst = t.stats.goals?.conceded?.total,
                        goalDifference = (t.stats.goals?.scored?.total ?: 0) - (t.stats.goals?.conceded?.total ?: 0),
                        extra = buildMap {
                            won?.let { put("regulationWins", (it.total - it.overtimes - it.shootouts).toString()) }
                            if (t.stats.isLive) put("live", "true")
                        },
                    )
                },
            )
        }
        val playoff = series.firstOrNull()?.stage?.group?.name?.let { it != "Regular Season" } == true
        return StandingsTable(LEAGUE_ID, seasonId, if (playoff) StageKind.PLAYOFF else StageKind.REGULAR, groups, grouping = "group")
    }

    // ---- players -------------------------------------------------------------------------

    public fun player(p: ChlPlayer, teamId: String? = p.team?.entityId): Player {
        val props = p.info?.properties.orEmpty()
        fun prop(name: String) = props.firstOrNull { it.name == name }
        fun int(name: String) = (prop(name)?.value as? JsonPrimitive)?.intOrNull
        fun str(name: String) = (prop(name)?.value as? JsonPrimitive)?.contentOrNull
        val nationality = p.nationality ?: (prop("Nationality")?.value as? JsonObject)?.let { obj ->
            ChlCountry(code = (obj["code"] as? JsonPrimitive)?.contentOrNull, name = (obj["name"] as? JsonPrimitive)?.contentOrNull)
        }
        return Player(
            ref = playerRef(p),
            firstName = p.firstName,
            lastName = p.lastName,
            birthDate = Dates.localDateOrNull(str("Birthday")),
            nationality = country(nationality),
            heightCm = int("Height"),
            weightKg = int("Weight"),
            handedness = prop("Shoots")?.shortValue ?: str("Shoots")?.let { if (it.startsWith("l", true)) "L" else if (it.startsWith("r", true)) "R" else null },
            teamId = teamId,
        )
    }
}
